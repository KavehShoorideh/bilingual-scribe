package dev.bscribe.core.asr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimelineLayoutTest {

    private val scale = 0.06f // 60 px per second

    private fun item(t0: Long, t1: Long, width: Float) = TimelineLayout.Item(t0, t1, width)

    @Test
    fun `words never overlap however fast the speech`() {
        // Four words a second, each needing 80 px: laying out by time alone
        // would give each 15 px and pile them on top of each other.
        val items = (0 until 20).map { i -> item(i * 250L, i * 250L + 200L, 80f) }
        val placed = TimelineLayout.place(items, scale)

        for (i in 1 until placed.size) {
            val previousEnd = placed[i - 1].xPx + placed[i - 1].widthPx
            assertTrue(
                placed[i].xPx >= previousEnd,
                "word $i starts at ${placed[i].xPx} but ${i - 1} ends at $previousEnd",
            )
        }
    }

    @Test
    fun `a gap is left between neighbours`() {
        val items = listOf(item(0, 100, 50f), item(120, 220, 50f))
        val placed = TimelineLayout.place(items, scale, gapPx = 12f)
        assertTrue(placed[1].xPx - (placed[0].xPx + 50f) >= 12f)
    }

    @Test
    fun `sparse speech keeps true time positions`() {
        // Words a long way apart need no pushing, so they should land exactly
        // where their timestamps say.
        val items = listOf(item(0, 500, 40f), item(10_000, 10_500, 40f))
        val placed = TimelineLayout.place(items, scale)
        assertEquals(0f, placed[0].xPx)
        assertEquals(10_000 * scale, placed[1].xPx)
    }

    @Test
    fun `a pause pulls the lane back to true time after a dense run`() {
        // Dense words drift right; the long silence afterwards must let the
        // next word return to where its timestamp actually is.
        val dense = (0 until 10).map { i -> item(i * 100L, i * 100L + 90L, 100f) }
        val afterPause = item(60_000, 60_500, 100f)
        val placed = TimelineLayout.place(dense + afterPause, scale)
        assertEquals(60_000 * scale, placed.last().xPx, "drift must not persist past a pause")
    }

    @Test
    fun `empty input places nothing`() {
        assertTrue(TimelineLayout.place(emptyList(), scale).isEmpty())
    }

    // ---- playhead ----

    @Test
    fun `playhead sits inside the word being spoken`() {
        val items = listOf(item(0, 1000, 60f), item(2000, 3000, 60f))
        val placed = TimelineLayout.place(items, scale)
        val x = TimelineLayout.playheadX(items, placed, nowMs = 500, pxPerMs = scale)
        assertTrue(
            x >= placed[0].xPx && x <= placed[0].xPx + placed[0].widthPx,
            "playhead $x outside the first word",
        )
    }

    @Test
    fun `playhead moves through the gap between words`() {
        val items = listOf(item(0, 1000, 60f), item(3000, 4000, 60f))
        val placed = TimelineLayout.place(items, scale)
        val early = TimelineLayout.playheadX(items, placed, 1500, scale)
        val late = TimelineLayout.playheadX(items, placed, 2500, scale)
        assertTrue(late > early, "playhead must advance across silence")
        assertTrue(late <= placed[1].xPx)
    }

    @Test
    fun `playhead advances monotonically across a dense run`() {
        // The property that matters while listening: the lane only ever moves
        // forwards, even where collision resolution has shifted words.
        val items = (0 until 30).map { i -> item(i * 120L, i * 120L + 100L, 90f) }
        val placed = TimelineLayout.place(items, scale)
        var previous = Float.NEGATIVE_INFINITY
        for (nowMs in 0L..3_600L step 40L) {
            val x = TimelineLayout.playheadX(items, placed, nowMs, scale)
            assertTrue(x >= previous, "playhead went backwards at ${nowMs}ms: $x < $previous")
            previous = x
        }
    }

    @Test
    fun `playhead before the first word still tracks time`() {
        val items = listOf(item(5_000, 5_500, 60f))
        val placed = TimelineLayout.place(items, scale)
        val atZero = TimelineLayout.playheadX(items, placed, 0, scale)
        val atFour = TimelineLayout.playheadX(items, placed, 4_000, scale)
        assertTrue(atFour > atZero)
        assertTrue(atZero < placed[0].xPx)
    }

    @Test
    fun `playhead past the last word keeps moving`() {
        val items = listOf(item(0, 1000, 60f))
        val placed = TimelineLayout.place(items, scale)
        val justAfter = TimelineLayout.playheadX(items, placed, 1_200, scale)
        val muchLater = TimelineLayout.playheadX(items, placed, 9_000, scale)
        assertTrue(muchLater > justAfter)
    }

    @Test
    fun `no words falls back to plain time`() {
        assertEquals(
            1_000 * scale,
            TimelineLayout.playheadX(emptyList(), emptyList(), 1_000, scale),
        )
    }
}
