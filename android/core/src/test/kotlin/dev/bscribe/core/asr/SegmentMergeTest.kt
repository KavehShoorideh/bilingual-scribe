package dev.bscribe.core.asr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SegmentMergeTest {

    private fun word(text: String, t0: Long, t1: Long) =
        Word(text, t0, t1, 0.9f, 0, ScriptLang.ofWord(text))

    private fun seg(
        lang: String,
        t0: Long,
        t1: Long,
        avgLogProb: Float,
        vararg texts: String,
    ): DecodedSegment {
        val step = ((t1 - t0) / texts.size.coerceAtLeast(1)).coerceAtLeast(1)
        val words = texts.mapIndexed { i, t -> word(t, t0 + i * step, t0 + (i + 1) * step) }
        return DecodedSegment(lang, t0, t1, words, avgLogProb)
    }

    @Test
    fun `each utterance keeps the language that read it better`() {
        // The case the app exists for: one sentence, English then Farsi.
        // Choosing a single language for the whole window threw half of it away.
        val english = listOf(
            seg("en", 0, 2000, -0.20f, "hello", "there"),
            seg("en", 2000, 4000, -1.80f, "beroon", "az", "khoone"),
        )
        val farsi = listOf(
            seg("fa", 0, 2000, -1.90f, "هلو", "دیر"),
            seg("fa", 2000, 4000, -0.25f, "بیرون", "از", "خونه"),
        )

        val merged = SegmentMerge.merge(english, farsi)
        assertEquals(listOf("en", "fa"), merged.map { it.lang })
        assertEquals(
            listOf("hello", "there", "بیرون", "از", "خونه"),
            SegmentMerge.words(merged).map { it.text },
        )
    }

    @Test
    fun `overlapping segments do not both survive`() {
        // Both languages describe the same seconds; keeping both would print
        // the sentence twice.
        val a = listOf(seg("en", 0, 3000, -0.3f, "one", "two"))
        val b = listOf(seg("fa", 0, 3000, -0.9f, "یک", "دو"))
        val merged = SegmentMerge.merge(a, b)
        assertEquals(1, merged.size)
        assertEquals("en", merged[0].lang)
    }

    @Test
    fun `empty segments are discarded`() {
        val a = listOf(DecodedSegment("en", 0, 1000, emptyList(), -0.01f))
        val b = listOf(seg("fa", 0, 1000, -0.9f, "سلام"))
        val merged = SegmentMerge.merge(a, b)
        assertEquals(1, merged.size)
        assertEquals("fa", merged[0].lang)
    }

    @Test
    fun `merging with nothing keeps the original`() {
        val a = listOf(seg("en", 0, 1000, -0.4f, "hello"))
        assertEquals(a, SegmentMerge.merge(a, emptyList()))
        assertEquals(a, SegmentMerge.merge(emptyList(), a))
        assertTrue(SegmentMerge.merge(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `non-overlapping segments are all kept in time order`() {
        val a = listOf(seg("en", 4000, 6000, -0.4f, "later"))
        val b = listOf(seg("fa", 0, 2000, -0.5f, "زودتر"))
        val merged = SegmentMerge.merge(a, b)
        assertEquals(listOf(0L, 4000L), merged.map { it.t0Ms })
    }

    @Test
    fun `words inherit their segment language only when script is ambiguous`() {
        val merged = listOf(seg("fa", 0, 2000, -0.3f, "۱۲۳", "سلام", "42"))
        val words = SegmentMerge.words(merged)
        // Persian digits are Farsi by script; ASCII digits are ambiguous and
        // take the segment's language.
        assertEquals(Lang.FA, words[0].lang)
        assertEquals(Lang.FA, words[1].lang)
        assertEquals(Lang.FA, words[2].lang)
    }

    @Test
    fun `english segment lends its language to bare numbers`() {
        val merged = listOf(seg("en", 0, 1000, -0.3f, "42"))
        assertEquals(Lang.EN, SegmentMerge.words(merged)[0].lang)
    }

    // ---- repetition collapse ----

    @Test
    fun `a repeated phrase at the end is dropped`() {
        // Exactly the failure seen on a 7s clip: whisper padded to 30s and
        // repeated the utterance into the silence.
        val words = listOf("که", "اینجای", "که", "اینجای").mapIndexed { i, t ->
            word(t, i * 100L, i * 100L + 100)
        }
        assertEquals(
            listOf("که", "اینجای"),
            SegmentMerge.collapseRepeats(words).map { it.text },
        )
    }

    @Test
    fun `a phrase repeated three times collapses to one`() {
        val words = listOf("a", "b", "a", "b", "a", "b").mapIndexed { i, t ->
            word(t, i * 100L, i * 100L + 100)
        }
        assertEquals(listOf("a", "b"), SegmentMerge.collapseRepeats(words).map { it.text })
    }

    @Test
    fun `a doubled single word is left alone`() {
        // "very very" is usually real speech, so single-word repeats are not
        // treated as hallucination.
        val words = listOf("very", "very", "slow").mapIndexed { i, t ->
            word(t, i * 100L, i * 100L + 100)
        }
        assertEquals(3, SegmentMerge.collapseRepeats(words).size)
    }

    @Test
    fun `distinct speech is untouched`() {
        val words = listOf("the", "cat", "sat", "on", "the", "mat").mapIndexed { i, t ->
            word(t, i * 100L, i * 100L + 100)
        }
        assertEquals(6, SegmentMerge.collapseRepeats(words).size)
    }

    @Test
    fun `empty and single-word input survive collapse`() {
        assertTrue(SegmentMerge.collapseRepeats(emptyList()).isEmpty())
        assertEquals(1, SegmentMerge.collapseRepeats(listOf(word("hi", 0, 100))).size)
    }
}
