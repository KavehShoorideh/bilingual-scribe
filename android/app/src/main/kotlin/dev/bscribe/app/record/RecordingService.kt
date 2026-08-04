package dev.bscribe.app.record

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.bscribe.app.R
import dev.bscribe.app.appContainer
import dev.bscribe.app.ui.MainActivity
import dev.bscribe.core.asr.LiveTranscriber
import dev.bscribe.core.asr.NoopLiveTranscriber
import dev.bscribe.core.audio.PcmLevels
import dev.bscribe.core.audio.WavFileWriter
import dev.bscribe.core.audio.WavSpec
import dev.bscribe.app.util.formatClock
import dev.bscribe.core.model.SessionState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Foreground microphone service. Owns the only [AudioRecord] and the only
 * [WavFileWriter] in the app.
 *
 * Capture contract (requirement #1 — never lose an idea):
 *  - PCM goes to disk synchronously on the capture thread, before anything
 *    else sees it. Live transcription (M1b) taps the same buffer strictly
 *    non-blocking, downstream of the write.
 *  - The WAV header self-heals via [dev.bscribe.core.audio.WavRepair] at next
 *    app start if this process dies uncleanly.
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commandMutex = Mutex()

    private val container by lazy { applicationContext.appContainer }
    private val repo by lazy { container.sessionRepository }
    private val state by lazy { container.recorderState }
    private val runner by lazy { container.transcriptionRunner }

    /** M0: a no-op; M1b swaps in the whisper chunked engine. */
    private val liveTranscriber: LiveTranscriber = NoopLiveTranscriber()

    private var capture: CaptureRun? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** The in-flight final pass, so Cancel has something to cancel. */
    @Volatile private var transcribeJob: Job? = null

    /** One pause/resume segment currently on disk. */
    private inner class CaptureRun(
        val recordingId: String,
        val writer: WavFileWriter,
        val baseElapsedMs: Long,
    ) {
        @Volatile var running = true
        lateinit var thread: Thread

        fun start() {
            thread = Thread({ loop() }, "audio-capture").also { it.start() }
        }

        private fun loop() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val spec = WavSpec.SCRIBE_DEFAULT
            val minBuf = AudioRecord.getMinBufferSize(
                spec.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    spec.sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    (minBuf * 4).coerceAtLeast(spec.byteRate), // ≥1 s of headroom
                )
            } catch (e: SecurityException) {
                Log.e(TAG, "mic permission lost", e)
                running = false
                return
            }

            try {
                record.startRecording()
                val buf = ShortArray(spec.sampleRate * 320 / 1000) // 320 ms
                while (running) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0) {
                        // Disk first, always.
                        writer.append(buf, 0, n)
                        // Live engine taps a copy, never blocks the loop.
                        liveTranscriber.feed(buf, 0, n)
                        state.onProgress(
                            elapsedMs = baseElapsedMs + writer.durationMs,
                            vuMeter = PcmLevels.dbToMeter(PcmLevels.rmsDb(buf, 0, n)),
                        )
                    } else if (n < 0) {
                        Log.e(TAG, "AudioRecord.read failed: $n")
                        break
                    }
                }
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }

        /** Stops the loop and returns the final duration; writer is closed. */
        fun finish(): Long {
            running = false
            thread.join(3_000)
            val duration = writer.durationMs
            writer.close()
            return duration
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Must enter the foreground promptly with the microphone type.
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(Phase.Recording),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
                scope.launch { commandMutex.withLock { handleStart() } }
            }
            ACTION_PAUSE -> scope.launch { commandMutex.withLock { handlePause() } }
            ACTION_RESUME -> scope.launch { commandMutex.withLock { handleResume() } }
            ACTION_STOP -> scope.launch { commandMutex.withLock { handleStop() } }
            ACTION_TRANSCRIBE -> {
                // Enter the foreground immediately: a service started from the
                // background has a few seconds to do so or the system kills it.
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(Phase.Transcribing(0, 0)),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
                val i = intent
                scope.launch { commandMutex.withLock { handleTranscribe(i) } }
            }
            // Deliberately not behind the command mutex: the mutex is held for
            // the duration of a pass, so a cancel routed through it could only
            // run once the thing it is cancelling had already finished.
            ACTION_CANCEL_TRANSCRIBE -> {
                Log.i(TAG, "cancelling transcription")
                transcribeJob?.cancel()
            }
            else -> Log.w(TAG, "unknown action ${intent?.action}")
        }
        return START_NOT_STICKY
    }

    private suspend fun handleStart() {
        if (capture != null) return // already recording
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "started without RECORD_AUDIO permission")
            stopSelfCleanly()
            return
        }

        val session = repo.createSession()
        state.onStarting(session.id)
        val (recording, file) = repo.startSegment(session.id)
        beginCapture(recording.id, file, baseElapsedMs = 0L)
        state.onRecording()
        liveTranscriber.start()
    }

    private suspend fun handlePause() {
        val run = capture ?: return
        val sessionId = state.sessionId.value ?: return
        capture = null
        val duration = run.finish()
        repo.finalizeSegment(run.recordingId, duration)
        repo.setState(sessionId, SessionState.PAUSED)
        state.onPaused()
        releaseWakeLock()
        notify(buildNotification(Phase.Paused))
    }

    private suspend fun handleResume() {
        if (capture != null) return
        val sessionId = state.sessionId.value ?: return
        val previous = repo.recordingsOf(sessionId).sumOf { it.durationMs }
        val (recording, file) = repo.startSegment(sessionId)
        beginCapture(recording.id, file, baseElapsedMs = previous)
        state.onRecording()
        notify(buildNotification(Phase.Recording))
    }

    private suspend fun handleStop() {
        val sessionId = state.sessionId.value
        state.onStopping()
        liveTranscriber.stop()
        capture?.let { run ->
            capture = null
            val duration = run.finish()
            repo.finalizeSegment(run.recordingId, duration)
        }
        if (sessionId != null) {
            repo.setState(sessionId, SessionState.STOPPED)
            releaseWakeLock()
            if (container.settingsRepository.autoTranscribe.first()) {
                runTranscription(sessionId)
            }
        }
        stopSelfCleanly()
    }

    /**
     * Runs the accurate pass with the service still in the foreground.
     *
     * The microphone is already released by this point, so the service
     * re-declares itself as dataSync only — holding a microphone foreground
     * type without recording would show a mic indicator for no reason.
     */
    private suspend fun runTranscription(sessionId: String) {
        startForeground(
            NOTIFICATION_ID,
            buildNotification(Phase.Transcribing(0, 0)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        // Transcription is CPU-bound and long; without this the CPU can sleep
        // mid-pass and a 10-minute note takes far longer than it should.
        acquireWakeLock()
        try {
            // Tracked separately so Cancel can reach it — the command mutex is
            // deliberately not held here, or a cancel could never be processed.
            val job = scope.launch {
                val outcome = runner.run(sessionId) { progress ->
                    notify(
                        buildNotification(
                            Phase.Transcribing(progress.doneMs, progress.totalMs),
                        ),
                    )
                }
                if (outcome is TranscribeOutcome.NoModel) {
                    Log.i(TAG, "skipped transcription: ${outcome.wanted} is not downloaded")
                }
            }
            transcribeJob = job
            job.join()
        } finally {
            transcribeJob = null
            releaseWakeLock()
        }
    }

    private suspend fun handleTranscribe(intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        if (capture != null) {
            // Never contend with live capture for CPU or for the foreground
            // service type; the audio comes first.
            Log.w(TAG, "refusing to transcribe while recording")
            return
        }
        runTranscription(sessionId)
        stopSelfCleanly()
    }

    private fun beginCapture(recordingId: String, file: File, baseElapsedMs: Long) {
        acquireWakeLock()
        capture = CaptureRun(recordingId, WavFileWriter(file), baseElapsedMs).also { it.start() }
    }

    private fun stopSelfCleanly() {
        state.onIdle()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // System teardown while capturing (rare; e.g. task removed + swipe).
        // Close the writer so the header is consistent; the DB row stays
        // unfinalized and startup recovery reconciles it.
        capture?.let { runCatching { it.finish() } }
        capture = null
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    // ---- notification ----

    /** What the service is doing, which determines the notification it shows. */
    private sealed interface Phase {
        data object Recording : Phase
        data object Paused : Phase
        data class Transcribing(val doneMs: Long, val totalMs: Long) : Phase {
            val percent: Int
                get() = if (totalMs <= 0) 0 else ((doneMs * 100) / totalMs).toInt()
        }
    }

    private fun buildNotification(phase: Phase): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shown while Bilingual Scribe is capturing or transcribing" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        fun action(name: String, action: String): NotificationCompat.Action {
            val intent = Intent(this, RecordingService::class.java).setAction(action)
            val pi = PendingIntent.getService(
                this, action.hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Action.Builder(0, name, pi).build()
        }

        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentText("Bilingual Scribe")
            .setOngoing(true)
            .setContentIntent(contentIntent)

        return when (phase) {
            is Phase.Recording -> builder
                .setContentTitle("Recording")
                .addAction(action("Pause", ACTION_PAUSE))
                .addAction(action("Stop", ACTION_STOP))
                .build()

            is Phase.Paused -> builder
                .setContentTitle("Paused")
                .addAction(action("Resume", ACTION_RESUME))
                .addAction(action("Stop", ACTION_STOP))
                .build()

            is Phase.Transcribing -> builder
                .setContentTitle("Transcribing…")
                .apply {
                    if (phase.totalMs > 0) {
                        // Progress in audio time, not segments: a one-segment
                        // recording otherwise shows a spinner that never moves,
                        // which is indistinguishable from a hang.
                        setContentText(
                            "${formatClock(phase.doneMs)} of ${formatClock(phase.totalMs)}",
                        )
                        setProgress(100, phase.percent, false)
                    } else {
                        setProgress(0, 0, true)
                    }
                }
                // Transcription can take minutes; leaving no way out but a
                // force-stop is worse than a partial transcript. Words are
                // saved per segment, so cancelling keeps what finished.
                .addAction(action("Cancel", ACTION_CANCEL_TRANSCRIBE))
                .build()
        }
    }

    private fun notify(notification: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    // ---- wakelock ----

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bscribe:record")
            .also { it.acquire(MAX_WAKELOCK_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        /** Safety valve: a "note" longer than 4 h is a forgotten recorder. */
        private const val MAX_WAKELOCK_MS = 4 * 60 * 60 * 1000L

        const val ACTION_START = "dev.bscribe.app.action.START"
        const val ACTION_PAUSE = "dev.bscribe.app.action.PAUSE"
        const val ACTION_RESUME = "dev.bscribe.app.action.RESUME"
        const val ACTION_STOP = "dev.bscribe.app.action.STOP"

        /** Re-run the final pass over an existing session. */
        const val ACTION_TRANSCRIBE = "dev.bscribe.app.action.TRANSCRIBE"
        const val ACTION_CANCEL_TRANSCRIBE = "dev.bscribe.app.action.CANCEL_TRANSCRIBE"
        const val EXTRA_SESSION_ID = "sessionId"

        fun intent(context: android.content.Context, action: String): Intent =
            Intent(context, RecordingService::class.java).setAction(action)

        fun transcribeIntent(context: android.content.Context, sessionId: String): Intent =
            intent(context, ACTION_TRANSCRIBE).putExtra(EXTRA_SESSION_ID, sessionId)
    }
}
