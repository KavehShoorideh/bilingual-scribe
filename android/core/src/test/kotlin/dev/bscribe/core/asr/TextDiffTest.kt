package dev.bscribe.core.asr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextDiffTest {

    private fun words(vararg w: String) = w.toList()

    @Test
    fun `an unedited transcript yields nothing`() {
        val original = words("remind", "me", "to", "call", "mum")
        assertTrue(TextDiff.changes(original, "remind me to call mum").isEmpty())
        assertTrue(!TextDiff.differs(original, "remind me to call mum"))
    }

    @Test
    fun `whitespace changes are not edits`() {
        val original = words("hello", "there")
        assertTrue(TextDiff.changes(original, "  hello\n  there  ").isEmpty())
    }

    @Test
    fun `a single replaced word points at that word`() {
        val original = words("remind", "me", "to", "call", "ma", "mon")
        val changes = TextDiff.changes(original, "remind me to call مامان")
        assertEquals(1, changes.size)
        assertEquals("ma mon", changes[0].original)
        assertEquals("مامان", changes[0].corrected)
        assertEquals(4, changes[0].firstWordIdx)
        assertEquals(5, changes[0].lastWordIdx)
    }

    @Test
    fun `two separate edits produce two changes`() {
        val original = words("the", "kat", "sat", "on", "the", "mit")
        val changes = TextDiff.changes(original, "the cat sat on the mat")
        assertEquals(2, changes.size)
        assertEquals("kat" to "cat", changes[0].original to changes[0].corrected)
        assertEquals("mit" to "mat", changes[1].original to changes[1].corrected)
    }

    @Test
    fun `a deletion reports empty corrected text`() {
        val original = words("um", "so", "anyway")
        val changes = TextDiff.changes(original, "so anyway")
        assertEquals(1, changes.size)
        assertEquals("um", changes[0].original)
        assertEquals("", changes[0].corrected)
        assertEquals(0, changes[0].firstWordIdx)
    }

    @Test
    fun `an insertion attaches to the preceding word`() {
        // Nothing in the original corresponds to an inserted word, but the
        // change still has to name the audio it belongs to.
        val original = words("call", "tomorrow")
        val changes = TextDiff.changes(original, "call her tomorrow")
        assertEquals(1, changes.size)
        assertEquals("", changes[0].original)
        assertEquals("her", changes[0].corrected)
        assertEquals(0, changes[0].firstWordIdx)
    }

    @Test
    fun `an insertion at the very start attaches to the first word`() {
        val original = words("tomorrow")
        val changes = TextDiff.changes(original, "call tomorrow")
        assertEquals(1, changes.size)
        assertEquals(0, changes[0].firstWordIdx)
        assertTrue(changes[0].firstWordIdx >= 0, "index must stay valid")
    }

    @Test
    fun `rewriting everything is one change`() {
        val original = words("complete", "nonsense", "here")
        val changes = TextDiff.changes(original, "این جمله فارسی است")
        assertEquals(1, changes.size)
        assertEquals("complete nonsense here", changes[0].original)
        assertEquals("این جمله فارسی است", changes[0].corrected)
    }

    @Test
    fun `clearing the field marks the whole thing as not speech`() {
        val original = words("noise", "noise")
        val changes = TextDiff.changes(original, "")
        assertEquals(1, changes.size)
        assertEquals("", changes[0].corrected)
    }

    @Test
    fun `text added to an empty transcript is one insertion`() {
        val changes = TextDiff.changes(emptyList(), "hello there")
        assertEquals(1, changes.size)
        assertEquals("hello there", changes[0].corrected)
    }

    @Test
    fun `both empty yields nothing`() {
        assertTrue(TextDiff.changes(emptyList(), "").isEmpty())
    }

    @Test
    fun `repeated words are matched positionally`() {
        // LCS must not collapse the two "the"s into one match and mis-locate
        // the edit.
        val original = words("the", "cat", "and", "the", "dog")
        val changes = TextDiff.changes(original, "the cat and the fox")
        assertEquals(1, changes.size)
        assertEquals("dog", changes[0].original)
        assertEquals(4, changes[0].firstWordIdx)
    }

    @Test
    fun `a farsi correction inside english is located correctly`() {
        val original = words("i", "told", "her", "salam", "yesterday")
        val changes = TextDiff.changes(original, "i told her سلام yesterday")
        assertEquals(1, changes.size)
        assertEquals(3, changes[0].firstWordIdx)
        assertEquals(3, changes[0].lastWordIdx)
        assertEquals("سلام", changes[0].corrected)
    }

    @Test
    fun `tokenize keeps punctuation attached as whisper emits it`() {
        assertEquals(listOf("hello,", "there."), TextDiff.tokenize("hello, there."))
    }
}
