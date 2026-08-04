package dev.bscribe.asr

/**
 * Raw JNI surface. Everything here is unsafe: handles are native pointers and
 * a stale one crashes the process rather than throwing. Use [WhisperEngine],
 * which owns lifetimes and serialises access.
 */
internal object WhisperNative {

    init {
        System.loadLibrary("bscribe_asr")
    }

    external fun nativeVersion(): String

    /** @param dtwPreset a [DtwPreset] ordinal; 0 disables DTW alignment. */
    external fun nativeInitContext(modelPath: String, dtwPreset: Int): Long
    external fun nativeFreeContext(ctx: Long)

    external fun nativeInitState(ctx: Long): Long
    external fun nativeFreeState(state: Long)

    external fun nativeDetectLanguage(
        ctx: Long,
        state: Long,
        pcm: FloatArray,
        offsetMs: Int,
        nThreads: Int,
    ): FloatArray?

    external fun nativeLangId(code: String): Int

    external fun nativeFull(
        ctx: Long,
        state: Long,
        pcm: FloatArray,
        language: String?,
        nThreads: Int,
        beamSize: Int,
        maxLen: Int,
        noContext: Boolean,
        tokenTimestamps: Boolean,
    ): Boolean

    external fun nativeSegmentCount(state: Long): Int

    external fun nativeTokenCount(state: Long, segment: Int): Int

    /**
     * "text\tt0Ms\tt1Ms\tprob", or null for special tokens.
     *
     * The text keeps its leading space where whisper emitted one — that is the
     * word-boundary marker [WhisperEngine] reassembles words from.
     */
    external fun nativeTokenAt(ctx: Long, state: Long, segment: Int, index: Int): String?

    external fun nativeAvgLogProb(state: Long): Float
}

/**
 * whisper's `whisper_alignment_heads_preset` ordinals. DTW alignment needs a
 * preset matching the model that was loaded — using the wrong one silently
 * degrades timestamps rather than failing.
 */
internal enum class DtwPreset(val ordinalValue: Int) {
    NONE(0),
    TINY(4),
    BASE(6),
    SMALL(8),
    ;

    companion object {
        /**
         * Picks the preset from a ggml filename, e.g. `ggml-base-q5_1.bin`.
         * Unknown models get [NONE]: heuristic timestamps are worse than DTW
         * but far better than DTW aligned against the wrong head layout.
         */
        fun forModelFile(name: String): DtwPreset {
            val n = name.lowercase()
            return when {
                n.contains("tiny") -> TINY
                n.contains("base") -> BASE
                n.contains("small") -> SMALL
                else -> NONE
            }
        }
    }
}
