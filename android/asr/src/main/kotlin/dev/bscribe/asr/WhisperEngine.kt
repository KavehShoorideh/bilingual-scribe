package dev.bscribe.asr

import dev.bscribe.core.asr.BatchTranscriber
import dev.bscribe.core.asr.DecodeParams
import dev.bscribe.core.asr.DecodedSegment
import dev.bscribe.core.asr.LanguagePrior
import dev.bscribe.core.asr.ScriptLang
import dev.bscribe.core.asr.SegmentMerge
import dev.bscribe.core.asr.TranscriptPass
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
 * share one set of model weights, and [SegmentMerge] chooses between them one
 * utterance at a time. Per-word language then falls out of the script (see
 * [ScriptLang]).
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
    // Capped at 4 per decode: whisper scales sub-linearly with threads, and the
    // FP4 has only 2 performance cores behind 6 efficiency ones, so piling more
    // onto one decode buys little. Two concurrent decodes get 4+4 and use the
    // whole chip; a single decode is not made faster by asking for all eight.
    private fun threadsFor(activeDecodes: Int): Int =
        (totalThreads / activeDecodes.coerceAtLeast(1)).coerceIn(1, MAX_THREADS_PER_DECODE)

    private val threadsPerDecode: Int get() = threadsFor(slots.size)

    override suspend fun transcribe(pcm: ShortArray, params: DecodeParams): List<Word> =
        transcribePass(pcm, params).chosen

    suspend fun transcribePass(pcm: ShortArray, params: DecodeParams): TranscriptPass {
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
    ): TranscriptPass = withContext(Dispatchers.Default) {
        val words = mutableListOf<Word>()
        // Every language's own reading, kept so the UI can show both and the
        // user can say which was right.
        val perLanguage = slots.associate { it.lang to mutableListOf<Word>() }
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
            // What language the audio *is*, asked before anything is decoded.
            // Decode confidence cannot answer this: whisper is better at
            // English than Persian, so a fluent English translation of Persian
            // speech outscores a correct Persian transcription of it.
            val prior = detectLanguages(float)
            // Decoding a language the audio plainly is not costs a full decode
            // to produce something that will be discarded — and, worse, that
            // whisper is fluent enough to make look plausible. Where the
            // detector is confident, decode only that language.
            val active = slotsToRun(prior)
            val decoded = decodeWindow(float, params, active)
            val merged = SegmentMerge.merge(
                decoded[slots[0].lang].orEmpty(),
                decoded.getOrElse(slots.getOrNull(1)?.lang ?: "") { emptyList() },
                prior,
            )
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            // Realtime factor per window: the number that says whether live
            // transcription (M1b) is even possible on this hardware.
            val audioMs = endMs - startMs
            Log.i(
                TAG,
                "window ${startMs}..${endMs}ms decoded in ${elapsedMs}ms " +
                    "(RTF ${"%.2f".format(elapsedMs.toDouble() / audioMs)}, " +
                    "${active.joinToString("+") { it.lang }} x ${threadsFor(active.size)} threads)",
            )

            SegmentMerge.words(merged).forEach { w ->
                words += w.copy(t0Ms = w.t0Ms + startMs, t1Ms = w.t1Ms + startMs)
            }
            for ((lang, segments) in decoded) {
                // Only what this language plausibly contributes. A language
                // decisively beaten over a stretch of audio was translating
                // rather than transcribing, and showing that alongside the
                // winner reads as a translation pair rather than two readings
                // of the same sound.
                val rivals = decoded.filterKeys { it != lang }.values.flatten()
                val kept = SegmentMerge.competitive(segments, rivals, prior = prior)
                SegmentMerge.words(kept).forEach { w ->
                    perLanguage[lang]?.add(
                        w.copy(t0Ms = w.t0Ms + startMs, t1Ms = w.t1Ms + startMs),
                    )
                }
            }
            startMs = endMs
            onWindow(startMs, durationMs)
        }

        // Whisper pads short audio to its 30 s window and often fills the
        // silence by repeating the last thing it heard.
        fun tidy(list: List<Word>) =
            SegmentMerge.collapseRepeats(list).mapIndexed { i, w -> w.copy(segmentIdx = i) }

        TranscriptPass(
            chosen = tidy(words),
            alternatives = perLanguage.mapValues { (_, v) -> tidy(v) },
        )
    }

    /**
     * Decodes one window in every enabled language and merges them utterance
     * by utterance.
     *
     * Merging per utterance rather than per window is the whole point: a
     * sentence that switches language mid-way is a single window, so choosing
     * one language for the window threw away half the sentence.
     */
    /**
     * Asks the encoder which language this window is, for each language we
     * decode in.
     *
     * Encoder-only, so it costs a fraction of a decode. Detection runs before
     * the decodes because it shares a state with one of them and whisper_full
     * overwrites the mel spectrogram it works from.
     *
     * A failure here is not fatal: an empty prior simply means the merge falls
     * back to comparing decode confidence, which is where it started.
     */
    private suspend fun detectLanguages(float: FloatArray): LanguagePrior {
        if (slots.size < 2) return LanguagePrior.NONE
        val slot = slots[0]
        val probs = slot.mutex.withLock {
            WhisperNative.nativeDetectLanguage(ctx, slot.handle, float, 0, threadsPerDecode)
        } ?: return LanguagePrior.NONE

        val byLang = slots.mapNotNull { s ->
            val id = WhisperNative.nativeLangId(s.lang)
            if (id < 0 || id >= probs.size) null else s.lang to probs[id]
        }.toMap()

        Log.i(TAG, "language detector: $byLang")
        return LanguagePrior(byLang)
    }

    /**
     * Which languages are worth decoding for this window.
     *
     * Ambiguity is kept — a window the detector cannot settle is exactly where
     * both readings are worth having, and where the user's verdict matters.
     */
    private fun slotsToRun(prior: LanguagePrior): List<Slot> {
        if (slots.size < 2) return slots
        val ranked = slots.sortedByDescending { prior.probabilities[it.lang] ?: 0f }
        val top = prior.probabilities[ranked[0].lang] ?: 0f
        val next = prior.probabilities[ranked[1].lang] ?: 0f
        return if (top >= CONFIDENT_LANGUAGE && next <= AMBIGUOUS_LANGUAGE) {
            listOf(ranked[0])
        } else {
            slots
        }
    }

    private suspend fun decodeWindow(
        float: FloatArray,
        params: DecodeParams,
        active: List<Slot>,
    ): Map<String, List<DecodedSegment>> = coroutineScope {
        val threads = threadsFor(active.size)
        val jobs = active.map { slot ->
            slot.lang to async { runDecode(slot, float, params, threads) }
        }
        jobs.associate { (lang, job) -> lang to job.await() }
    }

    private suspend fun runDecode(
        slot: Slot,
        float: FloatArray,
        params: DecodeParams,
        threads: Int = threadsPerDecode,
    ): List<DecodedSegment> = slot.mutex.withLock {
        val ok = WhisperNative.nativeFull(
            ctx = ctx,
            state = slot.handle,
            pcm = float,
            language = slot.lang,
            nThreads = threads,
            beamSize = params.beamSize,
            maxLen = params.maxLen,
            noContext = params.noContext,
            tokenTimestamps = params.tokenTimestamps,
        )
        if (!ok) return@withLock emptyList()

        val segments = mutableListOf<DecodedSegment>()
        val nSeg = WhisperNative.nativeSegmentCount(slot.handle)
        for (seg in 0 until nSeg) {
            val tokens = mutableListOf<Token>()
            val nTok = WhisperNative.nativeTokenCount(slot.handle, seg)
            for (i in 0 until nTok) {
                val packed = WhisperNative.nativeTokenAt(ctx, slot.handle, seg, i) ?: continue
                parseToken(packed)?.let { tokens += it }
            }
            val words = wordsFromTokens(tokens)
            if (words.isEmpty()) continue
            segments += DecodedSegment(
                lang = slot.lang,
                t0Ms = words.first().t0Ms,
                t1Ms = words.last().t1Ms,
                words = words,
                avgLogProb = meanLogProb(tokens),
            )
        }
        segments
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
         * Detector confidence above which the other language is not decoded at
         * all. Deliberately high: skipping wrongly costs a whole language, and
         * the saving is only speed.
         */
        const val CONFIDENT_LANGUAGE = 0.85f

        /** …and the runner-up must be this unlikely for the skip to be safe. */
        const val AMBIGUOUS_LANGUAGE = 0.10f

        /**
         * Total threads across all concurrent decodes.
         *
         * Was 4, which dual decode split into 2+2 — half the chip idle while
         * the user waited. Using every core lets dual run 4+4.
         */
        val DEFAULT_THREADS: Int =
            Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

        /**
         * Mean log probability over a segment's tokens — the confidence used
         * to choose between the English and Farsi reading of the same audio.
         */
        internal fun meanLogProb(tokens: List<Token>): Float {
            if (tokens.isEmpty()) return -1e9f
            var sum = 0.0
            for (t in tokens) sum += kotlin.math.ln(t.prob.coerceAtLeast(1e-9f).toDouble())
            return (sum / tokens.size).toFloat()
        }

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
