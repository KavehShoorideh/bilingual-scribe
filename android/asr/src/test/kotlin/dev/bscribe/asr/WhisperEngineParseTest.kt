package dev.bscribe.asr

import dev.bscribe.core.asr.Lang
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The JNI packing format and the token→word reassembly are a contract between
 * whisper_jni.cpp and Kotlin. These pin it without a model, a device, or the
 * native library.
 */
class WhisperEngineParseTest {

    private fun tok(text: String, t0: Long, t1: Long, p: Float = 0.9f) =
        WhisperEngine.Token(text, t0, t1, p)

    @Test
    fun `parses a token`() {
        val t = WhisperEngine.parseToken(" hello\t1200\t1450\t0.9312")
        assertEquals(" hello", t?.text)
        assertEquals(1200L, t?.t0Ms)
        assertEquals(1450L, t?.t1Ms)
        assertEquals(0.9312f, t?.prob)
    }

    @Test
    fun `keeps the leading space that marks a word start`() {
        // Trimming here would destroy the only word-boundary signal whisper
        // gives us.
        assertEquals(" world", WhisperEngine.parseToken(" world\t0\t100\t0.5")?.text)
    }

    @Test
    fun `malformed rows are dropped rather than crashing the pass`() {
        assertNull(WhisperEngine.parseToken("hello"))
        assertNull(WhisperEngine.parseToken("hello\t0"))
        assertNull(WhisperEngine.parseToken(""))
        assertNull(WhisperEngine.parseToken("hello\tnope\t100\t0.5"))
    }

    @Test
    fun `joins subword tokens into one english word`() {
        val words = WhisperEngine.wordsFromTokens(
            listOf(tok(" trans", 0, 100), tok("crip", 100, 200), tok("tion", 200, 300)),
        )
        assertEquals(1, words.size)
        assertEquals("transcription", words[0].text)
        // The word spans first token start to last token end.
        assertEquals(0L, words[0].t0Ms)
        assertEquals(300L, words[0].t1Ms)
        assertEquals(Lang.EN, words[0].lang)
    }

    @Test
    fun `joins per-character farsi tokens into whole words`() {
        // This is the bug that made Farsi render as loose isolated letters:
        // Persian tokenizes to roughly one character per token.
        val words = WhisperEngine.wordsFromTokens(
            listOf(
                tok(" س", 0, 50), tok("ل", 50, 100), tok("ا", 100, 150), tok("م", 150, 200),
                tok(" خ", 250, 300), tok("و", 300, 350), tok("ب", 350, 400),
            ),
        )
        assertEquals(2, words.size)
        assertEquals("سلام", words[0].text)
        assertEquals("خوب", words[1].text)
        assertEquals(Lang.FA, words[0].lang)
        assertEquals(0L, words[0].t0Ms)
        assertEquals(200L, words[0].t1Ms)
        assertEquals(250L, words[1].t0Ms)
    }

    @Test
    fun `splits words on leading whitespace`() {
        val words = WhisperEngine.wordsFromTokens(
            listOf(tok(" one", 0, 100), tok(" two", 100, 200), tok(" three", 200, 300)),
        )
        assertEquals(listOf("one", "two", "three"), words.map { it.text })
    }

    @Test
    fun `word probability is the mean of its tokens`() {
        val words = WhisperEngine.wordsFromTokens(
            listOf(tok(" a", 0, 50, 1.0f), tok("b", 50, 100, 0.0f)),
        )
        assertEquals(1, words.size)
        assertTrue(kotlin.math.abs(words[0].prob - 0.5f) < 1e-6)
    }

    @Test
    fun `whitespace-only tokens produce no words`() {
        assertEquals(0, WhisperEngine.wordsFromTokens(listOf(tok("  ", 0, 100))).size)
        assertEquals(0, WhisperEngine.wordsFromTokens(emptyList()).size)
    }

    @Test
    fun `word indices are sequential`() {
        val words = WhisperEngine.wordsFromTokens(
            listOf(tok(" a", 0, 10), tok(" b", 10, 20), tok(" c", 20, 30)),
        )
        assertEquals(listOf(0, 1, 2), words.map { it.segmentIdx })
    }

    @Test
    fun `a mixed-language utterance keeps per-word languages`() {
        val words = WhisperEngine.wordsFromTokens(
            listOf(tok(" hello", 0, 100), tok(" سلام", 100, 200)),
        )
        assertEquals(Lang.EN, words[0].lang)
        assertEquals(Lang.FA, words[1].lang)
    }

    @Test
    fun `dtw preset is chosen from the model filename`() {
        assertEquals(DtwPreset.TINY, DtwPreset.forModelFile("ggml-tiny-q5_1.bin"))
        assertEquals(DtwPreset.BASE, DtwPreset.forModelFile("ggml-base-q5_1.bin"))
        assertEquals(DtwPreset.SMALL, DtwPreset.forModelFile("ggml-small-q5_1.bin"))
    }

    @Test
    fun `an unknown model disables dtw rather than guessing a head layout`() {
        assertEquals(DtwPreset.NONE, DtwPreset.forModelFile("my-finetune.bin"))
    }

    @Test
    fun `language plan knows when it is dual`() {
        assertEquals(true, LanguagePlan.DUAL.isDual)
        assertEquals(false, LanguagePlan.ENGLISH_ONLY.isDual)
    }
}
