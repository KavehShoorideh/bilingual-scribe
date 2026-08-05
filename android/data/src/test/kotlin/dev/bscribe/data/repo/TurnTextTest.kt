package dev.bscribe.data.repo

import dev.bscribe.core.asr.TextDiff
import dev.bscribe.data.db.TranscriptWordEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TurnTextTest {

    private fun word(text: String, idx: Int, turn: Int) = TranscriptWordEntity(
        recordingId = "r",
        wordIdx = idx,
        text = text,
        t0Ms = idx * 100L,
        t1Ms = idx * 100L + 90,
        prob = 0.9f,
        segmentIdx = idx,
        modelId = "m",
        turnIdx = turn,
    )

    @Test
    fun `a turn change becomes a blank line`() {
        val words = listOf(
            word("hello", 0, 0), word("there", 1, 0),
            word("salam", 2, 1), word("chetori", 3, 1),
        )
        assertEquals("hello there\n\nsalam chetori", buildTurnText(words))
    }

    @Test
    fun `one speaker is one paragraph`() {
        val words = listOf(word("a", 0, 0), word("b", 1, 0), word("c", 2, 0))
        assertEquals("a b c", buildTurnText(words))
    }

    @Test
    fun `no words is empty text`() {
        assertEquals("", buildTurnText(emptyList()))
    }

    @Test
    fun `turn markers are invisible to the diff`() {
        // The whole reason turns are blank lines rather than written markers:
        // a literal "new voice" would be compared as though someone said it.
        val words = listOf(
            word("hello", 0, 0), word("there", 1, 0),
            word("salam", 2, 1),
        )
        val text = buildTurnText(words)
        val modelWords = words.map { it.text }
        assertTrue(
            TextDiff.changes(modelWords, text).isEmpty(),
            "re-serialising the model's own words must read as no edit",
        )
    }

    @Test
    fun `splitting and rejoining round-trips`() {
        val text = "first turn\n\nsecond turn\n\nthird"
        assertEquals(text, joinTurns(splitTurns(text)))
    }

    @Test
    fun `splitting tolerates extra blank lines`() {
        assertEquals(listOf("one", "two"), splitTurns("one\n\n\n  \n\ntwo"))
    }

    @Test
    fun `a single turn splits to one entry`() {
        assertEquals(listOf("just this"), splitTurns("just this"))
    }
}
