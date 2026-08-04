package dev.bscribe.asr

import dev.bscribe.core.asr.Lang
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The JNI packing format is a contract between whisper_jni.cpp and Kotlin.
 * These tests pin it without needing a model, a device, or the native library.
 */
class WhisperEngineParseTest {

    @Test
    fun `parses a well-formed english word`() {
        val w = WhisperEngine.parseWord("hello\t1200\t1450\t0.9312\t3")
        assertEquals("hello", w?.text)
        assertEquals(1200L, w?.t0Ms)
        assertEquals(1450L, w?.t1Ms)
        assertEquals(0.9312f, w?.prob)
        assertEquals(3, w?.segmentIdx)
        assertEquals(Lang.EN, w?.lang)
    }

    @Test
    fun `tags farsi words from their script`() {
        val w = WhisperEngine.parseWord("سلام\t0\t500\t0.88\t0")
        assertEquals(Lang.FA, w?.lang)
    }

    @Test
    fun `blank text is dropped`() {
        // whisper emits empty segments for pure silence.
        assertNull(WhisperEngine.parseWord("\t0\t100\t0.5\t0"))
        assertNull(WhisperEngine.parseWord("   \t0\t100\t0.5\t0"))
    }

    @Test
    fun `malformed rows are dropped rather than crashing the pass`() {
        assertNull(WhisperEngine.parseWord("hello"))
        assertNull(WhisperEngine.parseWord("hello\t0"))
        assertNull(WhisperEngine.parseWord(""))
        assertNull(WhisperEngine.parseWord("hello\tnotanumber\t100\t0.5\t0"))
    }

    @Test
    fun `unparseable probability degrades to zero rather than dropping the word`() {
        // Losing a word because its confidence was odd would be worse than
        // keeping it with prob 0 — the text is the valuable part.
        val w = WhisperEngine.parseWord("hello\t0\t100\tNaNish\t0")
        assertEquals("hello", w?.text)
        assertEquals(0f, w?.prob)
    }

    @Test
    fun `text containing spaces survives`() {
        val w = WhisperEngine.parseWord("New York\t0\t100\t0.5\t0")
        assertEquals("New York", w?.text)
    }

    @Test
    fun `dtw preset is chosen from the model filename`() {
        assertEquals(DtwPreset.TINY, DtwPreset.forModelFile("ggml-tiny-q5_1.bin"))
        assertEquals(DtwPreset.BASE, DtwPreset.forModelFile("ggml-base-q5_1.bin"))
        assertEquals(DtwPreset.SMALL, DtwPreset.forModelFile("ggml-small-q5_1.bin"))
    }

    @Test
    fun `an unknown model disables dtw rather than guessing a head layout`() {
        // Aligning against the wrong alignment heads produces confidently
        // wrong timestamps; falling back to heuristic ones is the safer error.
        assertEquals(DtwPreset.NONE, DtwPreset.forModelFile("my-finetune.bin"))
        assertEquals(DtwPreset.NONE, DtwPreset.forModelFile("ggml-large-v3.bin"))
    }

    @Test
    fun `language plan knows when it is dual`() {
        assertEquals(true, LanguagePlan.DUAL.isDual)
        assertEquals(false, LanguagePlan.ENGLISH_ONLY.isDual)
        assertEquals(false, LanguagePlan.FARSI_ONLY.isDual)
    }
}
