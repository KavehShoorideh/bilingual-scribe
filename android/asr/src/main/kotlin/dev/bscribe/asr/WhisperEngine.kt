package dev.bscribe.asr

import dev.bscribe.core.asr.BatchTranscriber
import dev.bscribe.core.asr.DecodeArbiter
import dev.bscribe.core.asr.DecodeParams
import dev.bscribe.core.asr.DecodeResult
import dev.bscribe.core.asr.Lang
import dev.bscribe.core.asr.ScriptLang
import dev.bscribe.core.asr.Word
import dev.bscribe.core.audio.PcmConvert
import android.util.Log
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
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

    /** One decoder token, before words are reassembled from them. */
    internal data class Token(
        val text: String,
        val t0Ms: Long,
        val t1Ms: Long,
        val prob: Float,
    )

    init {
        require(modelFile.isFile) { "model not found: ${modelFile.absolutePath}" }
        // DTW is off deliberately. whisper computes t_dtw per token but never
        // uses it to set token or segment t0/t1 — the timestamps this engine
        // reads come from the heuristic path either way. Enabling it added
        // cross-attention tensors to the graph and a 128 MB ggml arena
        // allocated on every call, for output nothing consumed.
        ctx = WhisperNative.nativeInitContext(
            modelFile.absolutePath,
            DtwPreset.NONE.ordinalValue,
        )
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
        // Capped at 4 per decode: whisper scales sub-linearly with threads, and
        // the FP4 has only 2 performance cores behind 6 efficiency ones, so
        // piling more threads onto one decode buys little. Dual decode gets
        // 4+4 and actually uses the whole chip.
        get() = (totalThreads / slots.size).coerceIn(1, MAX_THREADS_PER_DECODE)

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
        onWindow: (doneMs: Long, totalMs: Long) -> Unit = { _, _ -> },
        readWindow: (startMs: Long, endMs: Long) -> ShortArray,
    ): List<Word> = withContext(Dispatchers.Default) {
        val words = mutableListOf<Word>()
        var startMs = 0L

        while (startMs < durationMs) {
            // Cooperative cancellation: without this a long pass ignores the
            // user cancelling and keeps burning battery to the end.
            ensureActive()

            val endMs = minOf(startMs + WINDOW_MS, durationMs)
            // Trailing scraps are noise, not speech; whisper hallucinates
            // rather than reporting silence on them.
            if (endMs - startMs < MIN_WINDOW_MS) break

            val chunk = readWindow(startMs, endMs)
            if (chunk.isEmpty()) break

            val float = PcmConvert.toFloatMono(chunk)
            val startedAt = System.nanoTime()
            val winner = decodeWindow(float, params)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            // Realtime factor per window: the number that says whether live
            // transcription (M1b) is even possible on this hardware.
            val audioMs = endMs - startMs
            Log.i(
                TAG,
                "window ${startMs}..${endMs}ms decoded in ${elapsedMs}ms " +
                    "(RTF ${"%.2f".format(elapsedMs.toDouble() / audioMs)}, " +
                    "${slots.size} lang x $threadsPerDecode threads)",
            )

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
            onWindow(startMs, durationMs)
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

        val tokens = mutableListOf<Token>()
        val nSeg = WhisperNative.nativeSegmentCount(slot.handle)
        for (seg in 0 until nSeg) {
            val nTok = WhisperNative.nativeTokenCount(slot.handle, seg)
            for (i in 0 until nTok) {
                val packed = WhisperNative.nativeTokenAt(ctx, slot.handle, seg, i) ?: continue
                parseToken(packed)?.let { tokens += it }
            }
        }
        val words = wordsFromTokens(tokens)

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
        private const val TAG = "WhisperEngine"
        private const val SAMPLE_RATE = 16_000

        /** whisper's native context length. */
        private const val WINDOW_MS = 30_000L

        /** Below ~1s whisper invents text rather than admitting silence. */
        private const val MIN_WINDOW_MS = 1_000L

        /** Beyond this, extra threads on one decode stop paying for themselves. */
        const val MAX_THREADS_PER_DECODE = 4

        /**
         * Total threads across all concurrent decodes.
         *
         * Was 4, which dual decode split into 2+2 — half the chip idle while
         * the user waited. Using every core lets dual run 4+4.
         */
        val DEFAULT_THREADS: Int =
            Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

        /** Parses "text\tt0\tt1\tprob" from the JNI layer. */
        internal fun parseToken(packed: String): Token? {
            // Split from the right: token text can itself contain a tab.
            val parts = packed.split('\t')
            if (parts.size < 4) return null
            val text = parts.subList(0, parts.size - 3).joinToString("\t")
            return Token(
                text = text,
                t0Ms = parts[parts.size - 3].toLongOrNull() ?: return null,
                t1Ms = parts[parts.size - 2].toLongOrNull() ?: return null,
                prob = parts[parts.size - 1].toFloatOrNull() ?: 0f,
            )
        }

        /**
         * Reassembles words from decoder tokens.
         *
         * A token is not a word. Persian tokenizes to roughly one character per
         * token, so treating tokens as words split every word into isolated
         * letters — which also broke Arabic cursive shaping, because the
         * letters were then laid out as separate runs.
         *
         * Whisper marks a word start with a leading space on the token, so a
         * new word begins at any token whose text starts with whitespace. The
         * word spans from its first token's start to its last token's end, and
         * takes the mean probability of its tokens.
         */
        internal fun wordsFromTokens(tokens: List<Token>): List<Word> {
            val words = mutableListOf<Word>()
            val current = StringBuilder()
            var t0 = 0L
            var t1 = 0L
            var probSum = 0f
            var count = 0

            fun flush() {
                val text = current.toString().trim()
                if (text.isNotEmpty()) {
                    words += Word(
                        text = text,
                        t0Ms = t0,
                        t1Ms = t1,
                        prob = if (count > 0) probSum / count else 0f,
                        segmentIdx = words.size,
                        lang = ScriptLang.ofWord(text),
                    )
                }
                current.setLength(0)
                probSum = 0f
                count = 0
            }

            for (token in tokens) {
                if (token.text.isEmpty()) continue
                val startsWord = token.text.first().isWhitespace()
                if (startsWord && current.isNotEmpty()) flush()
                if (current.isEmpty()) t0 = token.t0Ms
                current.append(token.text)
                t1 = token.t1Ms
                probSum += token.prob
                count++
            }
            flush()
            return words
        }
    }
}
