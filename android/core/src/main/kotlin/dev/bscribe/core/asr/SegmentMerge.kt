package dev.bscribe.core.asr

/**
 * The result of a transcription pass.
 *
 * Both single-language readings are kept, not just the winner. The rejected
 * one is not waste: showing the two side by side lets the user say which was
 * actually right, and that judgement is ground truth for language
 * identification that no amount of decoding confidence can supply.
 */
data class TranscriptPass(
    /** What the merge chose, utterance by utterance. */
    val chosen: List<Word>,
    /** Every language's full reading of the same audio, keyed by ISO code. */
    val alternatives: Map<String, List<Word>>,
)

/** One decoder segment — an utterance — from a single-language decode. */
data class DecodedSegment(
    /** ISO 639-1 code the decode was forced to. */
    val lang: String,
    val t0Ms: Long,
    val t1Ms: Long,
    val words: List<Word>,
    /** Mean per-token log probability; always negative, nearer zero is better. */
    val avgLogProb: Float,
)

/**
 * Chooses, utterance by utterance, between the English and Farsi decodes of
 * the same audio.
 *
 * Picking one language for a whole 30 s window is wrong for the case this app
 * exists to serve: a sentence that starts in English and finishes in Farsi is
 * one window, so one language won and the other half was transcribed as
 * gibberish in the wrong script. Whisper already breaks its output into
 * utterances, and those are short enough to be individually monolingual, so
 * the choice belongs at that level.
 */
object SegmentMerge {

    /**
     * Merges two single-language decodes of the same audio into one timeline.
     *
     * Both decodes describe the same seconds, so their segments overlap; where
     * they do, the more confident one wins and the other is dropped rather
     * than both being kept and the text duplicated.
     */
    fun merge(a: List<DecodedSegment>, b: List<DecodedSegment>): List<DecodedSegment> {
        val candidates = (a + b)
            .filter { it.words.isNotEmpty() }
            // Earliest first; on a tie prefer the more confident, so the
            // outcome does not depend on which language was decoded first.
            .sortedWith(compareBy({ it.t0Ms }, { -it.avgLogProb }))

        val kept = mutableListOf<DecodedSegment>()
        for (segment in candidates) {
            val last = kept.lastOrNull()
            if (last == null || segment.t0Ms >= last.t1Ms - OVERLAP_TOLERANCE_MS) {
                kept += segment
            } else if (segment.avgLogProb > last.avgLogProb) {
                // Same stretch of audio, better reading of it.
                kept[kept.lastIndex] = segment
            }
        }
        return kept
    }

    /**
     * Flattens merged segments to words, tagging any word whose script is
     * ambiguous with the language its segment was decoded in.
     */
    fun words(segments: List<DecodedSegment>): List<Word> {
        val out = mutableListOf<Word>()
        for (segment in segments) {
            val fallback = if (segment.lang == "fa") Lang.FA else Lang.EN
            for (word in segment.words) {
                out += word.copy(lang = if (word.lang == Lang.UND) fallback else word.lang)
            }
        }
        return out.sortedBy { it.t0Ms }.mapIndexed { i, w -> w.copy(segmentIdx = i) }
    }

    /**
     * Drops a tail that merely repeats what came just before it.
     *
     * Whisper pads short audio out to its 30 s window and often fills the
     * silence by repeating the last thing it heard, so a seven-second note
     * comes back with every phrase twice. Only runs of two or more words are
     * collapsed: a doubled single word ("very very") is usually real speech.
     */
    fun collapseRepeats(words: List<Word>): List<Word> {
        var result = words
        var changed = true
        while (changed) {
            changed = false
            val n = result.size
            var k = n / 2
            while (k >= MIN_REPEAT_WORDS) {
                val tail = result.subList(n - k, n)
                val before = result.subList(n - 2 * k, n - k)
                if (tail.map { it.text } == before.map { it.text }) {
                    result = result.subList(0, n - k)
                    changed = true
                    break
                }
                k--
            }
        }
        return result
    }

    /** Segments closer than this are treated as covering the same audio. */
    const val OVERLAP_TOLERANCE_MS = 200L

    /** Shorter repeats are more likely to be genuine speech than hallucination. */
    const val MIN_REPEAT_WORDS = 2
}
