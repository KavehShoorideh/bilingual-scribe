package dev.bscribe.core.asr

import kotlin.test.Test
import kotlin.test.assertEquals

class ScriptLangTest {

    @Test
    fun `latin words are english`() {
        assertEquals(Lang.EN, ScriptLang.ofWord("hello"))
        assertEquals(Lang.EN, ScriptLang.ofWord("Bilingual"))
    }

    @Test
    fun `arabic-script words are farsi`() {
        assertEquals(Lang.FA, ScriptLang.ofWord("سلام"))
        assertEquals(Lang.FA, ScriptLang.ofWord("خوبی"))
    }

    @Test
    fun `persian-specific letters are farsi`() {
        // پ چ ژ گ — the four letters Persian adds to the Arabic script.
        assertEquals(Lang.FA, ScriptLang.ofWord("پژوهش"))
        assertEquals(Lang.FA, ScriptLang.ofWord("گچ"))
    }

    @Test
    fun `letterless tokens are undetermined`() {
        assertEquals(Lang.UND, ScriptLang.ofWord("123"))
        assertEquals(Lang.UND, ScriptLang.ofWord("—"))
        assertEquals(Lang.UND, ScriptLang.ofWord("..."))
        assertEquals(Lang.UND, ScriptLang.ofWord(""))
        assertEquals(Lang.UND, ScriptLang.ofWord(" "))
    }

    @Test
    fun `persian digits count as farsi`() {
        // U+06F0..U+06F9 live in the Arabic block and only appear in Persian
        // text, so they are evidence of language in a way ASCII digits are not.
        assertEquals(Lang.FA, ScriptLang.ofWord("۱۲۳"))
    }

    @Test
    fun `punctuation attached to a word does not change its language`() {
        assertEquals(Lang.EN, ScriptLang.ofWord("hello,"))
        assertEquals(Lang.FA, ScriptLang.ofWord("سلام،"))
        assertEquals(Lang.EN, ScriptLang.ofWord("\"quoted\""))
    }

    @Test
    fun `a mixed token resolves to its majority script`() {
        assertEquals(Lang.FA, ScriptLang.ofWord("سلامhi"))   // 4 fa vs 2 en
        assertEquals(Lang.EN, ScriptLang.ofWord("helloسل"))  // 5 en vs 2 fa
    }

    @Test
    fun `an evenly split token is undetermined rather than guessed`() {
        assertEquals(Lang.UND, ScriptLang.ofWord("abسل"))
    }

    @Test
    fun `session of only english is english`() {
        assertEquals("en", ScriptLang.ofSession(List(20) { Lang.EN }))
    }

    @Test
    fun `session of only farsi is farsi`() {
        assertEquals("fa", ScriptLang.ofSession(List(20) { Lang.FA }))
    }

    @Test
    fun `session with both languages is mixed`() {
        val langs = List(10) { Lang.EN } + List(10) { Lang.FA }
        assertEquals("mixed", ScriptLang.ofSession(langs))
    }

    @Test
    fun `a few stray words do not make a session mixed`() {
        // 19 english, 1 farsi — below the 10% threshold. A single misrecognised
        // word shouldn't relabel the whole note in the training export.
        val langs = List(19) { Lang.EN } + listOf(Lang.FA)
        assertEquals("en", ScriptLang.ofSession(langs))
    }

    @Test
    fun `undetermined words are ignored not counted as evidence`() {
        // A note that is mostly numerals but clearly English should read "en",
        // not "mixed" or "und".
        val langs = List(30) { Lang.UND } + List(5) { Lang.EN }
        assertEquals("en", ScriptLang.ofSession(langs))
    }

    @Test
    fun `a session with no letters at all is undetermined`() {
        assertEquals("und", ScriptLang.ofSession(List(5) { Lang.UND }))
        assertEquals("und", ScriptLang.ofSession(emptyList()))
    }

    @Test
    fun `lang codes match the export schema`() {
        // docs/dataset-format.md accepts exactly en | fa | mixed | und.
        assertEquals("en", Lang.EN.code)
        assertEquals("fa", Lang.FA.code)
        assertEquals("und", Lang.UND.code)
    }
}
