package dev.bscribe.core.asr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class DecodeArbiterTest {

    private fun word(text: String) = Word(text, 0, 100, 0.9f, 0)

    private fun result(lang: String, avgLogProb: Float, vararg words: String) =
        DecodeResult(lang, words.map(::word), avgLogProb)

    @Test
    fun `higher average log probability wins`() {
        val en = result("en", -0.30f, "hello", "there")
        val fa = result("fa", -1.20f, "سلام")
        assertSame(en, DecodeArbiter.pick(en, fa))
    }

    @Test
    fun `farsi wins when it is the more confident decode`() {
        val en = result("en", -1.50f, "salaam", "khoobi")
        val fa = result("fa", -0.25f, "سلام", "خوبی")
        assertSame(fa, DecodeArbiter.pick(en, fa))
    }

    @Test
    fun `an empty decode loses even when more confident`() {
        // Whisper reports high confidence for emitting nothing on near-silence.
        // Left unhandled that would beat a correct transcription outright.
        val empty = result("fa", -0.01f)
        val real = result("en", -0.80f, "something", "was", "said")
        assertSame(real, DecodeArbiter.pick(real, empty))
        assertSame(real, DecodeArbiter.pick(empty, real))
    }

    @Test
    fun `null side is handled`() {
        val en = result("en", -0.5f, "hello")
        assertSame(en, DecodeArbiter.pick(en, null))
        assertSame(en, DecodeArbiter.pick(null, en))
    }

    @Test
    fun `both empty yields null`() {
        assertNull(DecodeArbiter.pick(result("en", -0.1f), result("fa", -0.1f)))
    }

    @Test
    fun `both null yields null`() {
        assertNull(DecodeArbiter.pick(null, null))
    }

    @Test
    fun `ties resolve to the first argument deterministically`() {
        val a = result("en", -0.5f, "one")
        val b = result("fa", -0.5f, "یک")
        assertSame(a, DecodeArbiter.pick(a, b))
        // Order is the caller's signal — the detector's preferred language
        // goes first, so a tie keeps the detector's opinion.
        assertSame(b, DecodeArbiter.pick(b, a))
    }

    @Test
    fun `the winning language is carried through`() {
        val winner = DecodeArbiter.pick(
            result("en", -2.0f, "gibberish"),
            result("fa", -0.2f, "سلام"),
        )
        assertEquals("fa", winner?.lang)
    }
}
