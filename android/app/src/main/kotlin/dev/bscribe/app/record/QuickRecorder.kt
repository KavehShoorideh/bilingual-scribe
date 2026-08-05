package dev.bscribe.app.record

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import dev.bscribe.core.audio.WavFileWriter
import dev.bscribe.core.audio.WavSpec
import java.io.File

/**
 * A short throwaway recording, for dictating a correction.
 *
 * Deliberately separate from [RecordingService]: that service exists to make
 * sure a thought is never lost — foreground, wake-locked, crash-recovering —
 * and none of that applies to a two-second phrase you can simply say again.
 * Routing dictation through it would also mean a correction appeared in the
 * session list as a recording of its own.
 *
 * Only safe to use while the recorder is idle; the microphone is exclusive.
 */
class QuickRecorder(private val file: File) {

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "dictation").also { it.start() }
    }

    /** Stops and returns the captured audio, or null if nothing usable. */
    fun stop(): File? {
        running = false
        thread?.join(3_000)
        thread = null
        return if (file.isFile && file.length() > WavSpec.HEADER_SIZE) file else null
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
                (minBuf * 2).coerceAtLeast(spec.byteRate),
            )
        } catch (e: SecurityException) {
            running = false
            return
        }

        WavFileWriter(file).use { writer ->
            try {
                record.startRecording()
                val buf = ShortArray(spec.sampleRate / 10) // 100 ms
                while (running) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0) writer.append(buf, 0, n) else if (n < 0) break
                }
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }
    }
}
