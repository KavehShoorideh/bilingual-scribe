package dev.bscribe.core.asr

/** One language's attempt at a window of audio. */
data class DecodeResult(
    /** ISO 639-1 code the decode was forced to. */
    val lang: String,
    val words: List<Word>,
    /**
     * Mean per-token log probability, as whisper reports it: always negative,
     * closer to zero is more confident.
     */
    val avgLogProb: Float,
)

/**
 * Chooses between an English and a Farsi decode of the same audio.
 *
 * Whisper forced to the wrong language does not fail quietly — it produces
 * fluent, confident-looking text. Average log probability is the only signal
 * whisper gives us to tell a real transcription from a plausible hallucination,
 * and it is imperfect, so the rules here are deliberately conservative.
 */
object DecodeArbiter {

    /**
     * @return the better of the two, or null if neither produced anything.
     *   Ties resolve to [a] so the outcome is deterministic — callers pass the
     *   detector's preferred language first.
     */
    fun pick(a: DecodeResult?, b: DecodeResult?): DecodeResult? {
        val aOk = a != null && a.words.isNotEmpty()
        val bOk = b != null && b.words.isNotEmpty()

        // An empty decode always loses, however confident it claims to be:
        // whisper reports high confidence for producing nothing on silence,
        // which would otherwise beat a correct transcription outright.
        if (aOk && !bOk) return a
        if (bOk && !aOk) return b
        if (!aOk && !bOk) return null

        return if (b!!.avgLogProb > a!!.avgLogProb) b else a
    }
}
