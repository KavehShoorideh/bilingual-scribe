package dev.bscribe.asr

import dev.bscribe.core.asr.BatchTranscriber
import dev.bscribe.core.asr.DecodeArbiter
import dev.bscribe.core.asr.DecodeParams
import dev.bscribe.core.asr.DecodeResult
import dev.bscribe.core.asr.Lang
import dev.bscribe.core.asr.ScriptLang
import dev.bscribe.core.asr.Word
import dev.bscribe.core.audio.PcmConvert
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Which languages the final pass should try. Disabling one halves the work.
 */
data class LanguagePlan(val codes: List<String>) {
    val isDual: Boolean get() = codes.size > 1

    companion object {
        val DUAL = LanguagePlan(listOf("en", "fa"))
        val ENGLISH_ONLY = LanguagePlan(listOf("en"))
        val FARSI_ONLY = LanguagePlan(listOf("fa"))
    }
}

/**
 * Whole-file transcription over whisper.cpp.
 *
 * Bilingual strategy: whisper decides a language once per decode and commits
 * to it, so a note that switches languages cannot be handled by a single pass.
 * Instead each window is decoded in *both* languages on separate states that
 * share one set of model weights, and [DecodeArbiter] keeps the more confident
 * result. Per-word language then falls out of the script (see [ScriptLang]).
 *
 * Thread safety: a whisper state is not reentrant, so each has its own mutex.
 * The context is shared read-only across states once loaded.
 */
class WhisperEngine(
    modelFile: File,
    private val plan: LanguagePlan = LanguagePlan.DUAL,
    private val totalThreads: Int = DEFAULT_THREADS,
) : BatchTranscriber, Closeable {

    private val ctx: Long
    private val slots: List<Slot>

    /** One decode state plus the lock guarding it. */
    private class Slot(val lang: String, val handle: Long) {
        val mutex = Mutex()
    }

    init {
        require(modelFile.isFile) { "model not found: ${modelFile.absolutePath}" }
        val preset = DtwPreset.forModelFile(modelFile.name)
        ctx = WhisperNative.nativeInitContext(modelFile.absolutePath, preset.ordinalValue)
        check(ctx != 0L) { "whisper failed to load ${modelFile.name}" }

        slots = plan.codes.map { code ->
            val state = WhisperNative.nativeInitState(ctx)
            if (state == 0L) {
                // Roll back anything already allocated; a half-built engine
                // would leak native memory for the life of the process.
                WhisperNative.nativeFreeContext(ctx)
                error("whisper failed to allocate a decode state for $code")
            }
            Slot(code, state)
        }
    }

    val engineVersion: String get() = WhisperNative.nativeVersion()

    /**
     * Threads per concurrent decode.
     *
     * Two decodes each asking for every core do not run twice as fast; they
     * contend for the same cores and memory bandwidth and both get slower. So
     * the budget is split, never duplicated.
     */
    private val threadsPerDecode: Int
        get() = (totalThreads / slots.size).coerceAtLeast(1)

    override suspend fun transcribe(pcm: ShortArray, params: DecodeParams): List<Word> {
        val durationMs = (pcm.size * 1000L) / SAMPLE_RATE
        return transcribeWindowed(durationMs, params) { startMs, endMs ->
            val from = ((startMs * SAMPLE_RATE) / 1000).toInt().coerceIn(0, pcm.size)
            val to = ((endMs * SAMPLE_RATE) / 1000).toInt().coerceIn(from, pcm.size)
            pcm.copyOfRange(from, to)
        }
    }

    /**
     * Transcribes by pulling one window at a time from [readWindow].
     *
     * Recordings are read in 30 s slices rather than all at once: the capture
     * service allows sessions up to four hours, and a four-hour ShortArray is
     * roughly 460 MB, which would OOM long before whisper ever saw it.
     *
     * @param readWindow returns 16 kHz mono PCM16 for the requested range.
     */
    suspend fun transcribeWindowed(
        durationMs: Long,
        params: DecodeParams,
        readWindow: (startMs: Long, endMs: Long) -> ShortArray,
    ): List<Word> = withContext(Dispatchers.Default) {
        val words = mutableListOf<Word>()
        var startMs = 0L

        while (startMs < durationMs) {
            val endMs = minOf(startMs + WINDOW_MS, durationMs)
            // Trailing scraps are noise, not speech; whisper hallucinates
            // rather than reporting silence on them.
            if (endMs - startMs < MIN_WINDOW_MS) break

            val chunk = readWindow(startMs, endMs)
            if (chunk.isEmpty()) break

            val float = PcmConvert.toFloatMono(chunk)
            val winner = decodeWindow(float, params)
            if (winner != null) {
                val windowLang = if (winner.lang == "fa") Lang.FA else Lang.EN
                winner.words.forEach { w ->
                    words += w.copy(
                        t0Ms = w.t0Ms + startMs,
                        t1Ms = w.t1Ms + startMs,
                        // Script is authoritative where it exists; tokens with
                        // no letters inherit the window's language.
                        lang = if (w.lang == Lang.UND) windowLang else w.lang,
                    )
                }
            }
            startMs = endMs
        }

        words.mapIndexed { i, w -> w.copy(segmentIdx = i) }
    }

    /** Decodes one window in every enabled language and returns the winner. */
    private suspend fun decodeWindow(
        float: FloatArray,
        params: DecodeParams,
    ): DecodeResult? = coroutineScope {
        if (slots.size == 1) return@coroutineScope runDecode(slots[0], float, params)

        val results = slots.map { slot ->
            async { runDecode(slot, float, params) }
        }.map { it.await() }

        results.reduce { acc, next -> DecodeArbiter.pick(acc, next) }
    }

    private suspend fun runDecode(
        slot: Slot,
        float: FloatArray,
        params: DecodeParams,
    ): DecodeResult? = slot.mutex.withLock {
        val ok = WhisperNative.nativeFull(
            ctx = ctx,
            state = slot.handle,
            pcm = float,
            language = slot.lang,
            nThreads = threadsPerDecode,
            beamSize = params.beamSize,
            // 1 word per segment is what gives the UI per-word tap targets.
            maxLen = params.maxLen,
            noContext = params.noContext,
            tokenTimestamps = params.tokenTimestamps,
        )
        if (!ok) return@withLock null

        val n = WhisperNative.nativeWordCount(slot.handle)
        val words = ArrayList<Word>(n)
        for (i in 0 until n) {
            val packed = WhisperNative.nativeWordAt(slot.handle, i) ?: continue
            parseWord(packed)?.let { words += it }
        }
        DecodeResult(
            lang = slot.lang,
            words = words,
            avgLogProb = WhisperNative.nativeAvgLogProb(slot.handle),
        )
    }

    override fun close() {
        slots.forEach { WhisperNative.nativeFreeState(it.handle) }
        WhisperNative.nativeFreeContext(ctx)
    }

    companion object {
        private const val SAMPLE_RATE = 16_000

        /** whisper's native context length. */
        private const val WINDOW_MS = 30_000L

        /** Below ~1s whisper invents text rather than admitting silence. */
        private const val MIN_WINDOW_MS = 1_000L

        /**
         * The FP4 has 2 performance + 6 efficiency cores. Asking for all 8
         * schedules work onto the little cores where it runs slower than not
         * scheduling it at all.
         */
        const val DEFAULT_THREADS = 4

        /**
         * Parses "text\tt0\tt1\tprob\tsegIdx" from the JNI layer. Returns null
         * for empty text, which whisper emits for pure-silence segments.
         */
        internal fun parseWord(packed: String): Word? {
            val parts = packed.split('\t')
            if (parts.size < 5) return null
            val text = parts[0]
            if (text.isBlank()) return null
            return Word(
                text = text,
                t0Ms = parts[1].toLongOrNull() ?: return null,
                t1Ms = parts[2].toLongOrNull() ?: return null,
                prob = parts[3].toFloatOrNull() ?: 0f,
                segmentIdx = parts[4].toIntOrNull() ?: 0,
                lang = ScriptLang.ofWord(text),
            )
        }
    }
}
