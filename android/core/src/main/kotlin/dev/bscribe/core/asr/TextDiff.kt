package dev.bscribe.core.asr

/**
 * Turns a freely-edited transcript back into correction pairs.
 *
 * Editing a whole transcript is far easier than selecting spans and fixing
 * them one at a time, but training needs the opposite shape: what the model
 * said here, and what was actually said. So the pairing is recovered
 * afterwards by aligning the edited words against the original ones.
 *
 * Word-level rather than character-level, because the timestamps that locate
 * audio are per word. A change is only useful if we can say which seconds it
 * refers to.
 */
object TextDiff {

    /**
     * A stretch where the edit differs from the model's output.
     *
     * [firstWordIdx]..[lastWordIdx] index the original words. An insertion has
     * no original words to point at, so it attaches to the preceding word and
     * reports an empty [original].
     */
    data class Change(
        val firstWordIdx: Int,
        val lastWordIdx: Int,
        val original: String,
        val corrected: String,
    )

    /** Splits on whitespace; punctuation stays attached, as whisper emits it. */
    fun tokenize(text: String): List<String> =
        text.split(WHITESPACE).filter { it.isNotBlank() }

    /**
     * @param original the model's words, in order.
     * @param editedText what the user left in the field.
     * @return only the stretches that actually differ.
     */
    fun changes(original: List<String>, editedText: String): List<Change> {
        val edited = tokenize(editedText)
        if (original.isEmpty() && edited.isEmpty()) return emptyList()

        val keep = longestCommonSubsequence(original, edited)

        val changes = mutableListOf<Change>()
        var oi = 0
        var ei = 0
        var pair = 0

        fun flush(oStart: Int, oEnd: Int, eStart: Int, eEnd: Int) {
            if (oStart == oEnd && eStart == eEnd) return
            changes += Change(
                // An insertion attaches to the word before it; at the very
                // start there is none, so it attaches to the first word.
                firstWordIdx = if (oStart < oEnd) oStart else (oStart - 1).coerceAtLeast(0),
                lastWordIdx = if (oStart < oEnd) oEnd - 1 else (oStart - 1).coerceAtLeast(0),
                original = original.subList(oStart, oEnd).joinToString(" "),
                corrected = edited.subList(eStart, eEnd).joinToString(" "),
            )
        }

        while (pair < keep.size) {
            val (ko, ke) = keep[pair]
            flush(oi, ko, ei, ke)
            oi = ko + 1
            ei = ke + 1
            pair++
        }
        flush(oi, original.size, ei, edited.size)

        return changes
    }

    /** True when the edit says something the model did not. */
    fun differs(original: List<String>, editedText: String): Boolean =
        original != tokenize(editedText)

    /**
     * Indices of the matching words, as (originalIndex, editedIndex) pairs.
     *
     * Plain LCS: quadratic, which is fine for a transcript. Anything smarter
     * would be harder to reason about for no gain at this size.
     */
    private fun longestCommonSubsequence(
        a: List<String>,
        b: List<String>,
    ): List<Pair<Int, Int>> {
        val n = a.size
        val m = b.size
        if (n == 0 || m == 0) return emptyList()

        val table = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                table[i][j] = if (a[i] == b[j]) {
                    table[i + 1][j + 1] + 1
                } else {
                    maxOf(table[i + 1][j], table[i][j + 1])
                }
            }
        }

        val out = mutableListOf<Pair<Int, Int>>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { out += i to j; i++; j++ }
                table[i + 1][j] >= table[i][j + 1] -> i++
                else -> j++
            }
        }
        return out
    }

    private val WHITESPACE = Regex("\\s+")
}
