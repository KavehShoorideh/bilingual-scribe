package dev.bscribe.core.asr

import kotlin.math.sqrt

/**
 * Detects *where the speaker changes*, without identifying who is speaking.
 *
 * This is a deliberate scope choice. Telling two people apart by name needs a
 * trained speaker-embedding model and a clustering step; noticing that the
 * voice changed needs neither, and is most of the practical value — a
 * transcript that starts a new paragraph when someone else speaks is readable,
 * whereas one that merges two people is not.
 *
 * What it cannot do: overlapping speech. When two people talk at once there is
 * no boundary to find, and the recogniser will have merged them anyway.
 */
object SpeakerChange {

    /**
     * A candidate boundary: speakers change during silence, not mid-word, so
     * only gaps between words are considered. That removes the vast majority
     * of false positives for free.
     */
    data class Candidate(val wordIndex: Int, val timeMs: Long)

    /**
     * Finds the gaps between words long enough to plausibly hide a speaker
     * change.
     *
     * @param wordTimes (startMs, endMs) per word, in order.
     */
    fun candidates(
        wordTimes: List<Pair<Long, Long>>,
        minGapMs: Long = MIN_GAP_MS,
    ): List<Candidate> {
        val out = mutableListOf<Candidate>()
        for (i in 1 until wordTimes.size) {
            val gap = wordTimes[i].first - wordTimes[i - 1].second
            if (gap >= minGapMs) {
                // Probe at the midpoint of the silence, which is as far as
                // possible from either speaker's audio.
                out += Candidate(
                    wordIndex = i,
                    timeMs = wordTimes[i - 1].second + gap / 2,
                )
            }
        }
        return out
    }

    /**
     * Assigns a turn number to each word, incrementing wherever the voice
     * either side of a candidate gap differs enough.
     *
     * @param frames MFCC vectors, evenly spaced.
     * @param frameOf maps a time in ms to an index into [frames].
     * @return one turn index per word; all zeros when nothing changed.
     */
    fun assignTurns(
        wordCount: Int,
        candidates: List<Candidate>,
        frames: List<DoubleArray>,
        frameOf: (Long) -> Int,
        contextFrames: Int = CONTEXT_FRAMES,
        threshold: Double = THRESHOLD,
        /**
         * Language of each word, if known.
         *
         * One person switching between English and Persian changes their
         * phonemes and prosody enough to look like a different speaker, so a
         * boundary that is also a language change is attributed to the
         * language rather than reported as a new voice. Bilingual speech is
         * the normal case for this app, so without this nearly every
         * code-switch produced a spurious "new voice".
         */
        languageOf: ((Int) -> Lang)? = null,
    ): IntArray {
        val turns = IntArray(wordCount)
        if (wordCount == 0 || frames.isEmpty()) return turns

        // Which words begin a new turn.
        val changesAt = HashSet<Int>()
        for (candidate in candidates) {
            if (languageOf != null && crossesLanguage(candidate.wordIndex, wordCount, languageOf)) {
                continue
            }
            val at = frameOf(candidate.timeMs)
            val before = meanOf(frames, at - contextFrames, at)
            val after = meanOf(frames, at, at + contextFrames)
            // Not enough audio on one side to judge; leave the turn alone
            // rather than guessing from a fragment.
            if (before == null || after == null) continue
            if (cosineDistance(before, after) >= threshold) changesAt += candidate.wordIndex
        }

        var turn = 0
        for (i in 0 until wordCount) {
            if (i in changesAt) turn++
            turns[i] = turn
        }
        return turns
    }

    /**
     * Whether the words either side of a boundary are in different languages.
     *
     * Script-neutral words are skipped over rather than treated as a language
     * of their own, so a number sitting on the boundary doesn't hide a genuine
     * change or invent one.
     */
    private fun crossesLanguage(
        boundary: Int,
        wordCount: Int,
        languageOf: (Int) -> Lang,
    ): Boolean {
        var before: Lang? = null
        var i = boundary - 1
        while (i >= 0) {
            val l = languageOf(i)
            if (l != Lang.UND) { before = l; break }
            i--
        }
        var after: Lang? = null
        var j = boundary
        while (j < wordCount) {
            val l = languageOf(j)
            if (l != Lang.UND) { after = l; break }
            j++
        }
        return before != null && after != null && before != after
    }

    /** Mean vector over a half-open frame range, or null if too short. */
    private fun meanOf(frames: List<DoubleArray>, from: Int, to: Int): DoubleArray? {
        val lo = from.coerceAtLeast(0)
        val hi = to.coerceAtMost(frames.size)
        if (hi - lo < MIN_FRAMES) return null
        val dims = frames[lo].size
        val sum = DoubleArray(dims)
        for (i in lo until hi) {
            val f = frames[i]
            for (d in 0 until dims) sum[d] += f[d]
        }
        val n = (hi - lo).toDouble()
        for (d in 0 until dims) sum[d] /= n
        return sum
    }

    /**
     * 1 - cosine similarity. Cosine rather than Euclidean because it ignores
     * overall magnitude, which tracks loudness and microphone distance rather
     * than who is talking.
     */
    internal fun cosineDistance(a: DoubleArray, b: DoubleArray): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0.0
        return 1.0 - dot / (sqrt(na) * sqrt(nb))
    }

    /** A pause shorter than this is a breath, not a handover. */
    const val MIN_GAP_MS = 350L

    /** ~1 s either side at a 10 ms hop. */
    const val CONTEXT_FRAMES = 100

    /** Below ~0.3 s of audio there is not enough to characterise a voice. */
    const val MIN_FRAMES = 30

    /**
     * Tuned conservatively: a missed change leaves two speakers in one
     * paragraph, which is what happens today anyway, while a false change
     * chops up a single person's monologue and looks broken.
     */
    const val THRESHOLD = 0.35
}
