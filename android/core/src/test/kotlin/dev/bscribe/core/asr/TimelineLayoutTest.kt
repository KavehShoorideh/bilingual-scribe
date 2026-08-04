package dev.bscribe.core.asr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimelineLayoutTest {

    private val scale = 0.09f

    private fun item(t0: Long, t1: Long, width: Float) = TimelineLayout.Item(t0, t1, width)

    // ---- the property the view exists for ----

    @Test
    fun `the same instant lands at the same place in both lanes`() {
        // A sparse English lane against a dense Farsi one — the case that
        // broke it: resolving collisions per lane pushed the dense lane far
        // right of the sparse one, so the marker showed two different moments.
        val english = listOf(item(0, 900, 90f), item(4000, 4900, 90f))
        // 100 ms apart so one of them starts at exactly 4000, the instant the
        // English lane's second word also starts.
        val farsi = (0 until 50).map { i -> item(i * 100L, i * 100L + 90L, 85f) }

        val layout = TimelineLayout.place(listOf(english, farsi), scale)

        // Both lanes have a word starting at 0 and at 4000 ms (farsi index 33).
        assertEquals(layout.lanes[0][0].xPx, layout.mapping.xOf(0))
        assertEquals(layout.lanes[1][0].xPx, layout.mapping.xOf(0))

        val farsiAt4000 = farsi.indexOfFirst { it.t0Ms == 4000L }
        assertTrue(farsiAt4000 >= 0)
        assertEquals(
            layout.lanes[0][1].xPx,
            layout.lanes[1][farsiAt4000].xPx,
            "the same instant must be at the same x in both lanes",
        )
    }

    @Test
    fun `a dense lane cannot push a sparse lane out of alignment`() {
        val sparse = listOf(item(0, 500, 60f), item(9000, 9500, 60f))
        val dense = (0 until 90).map { i -> item(i * 100L, i * 100L + 90L, 100f) }
        val layout = TimelineLayout.place(listOf(sparse, dense), scale)

        // Whatever the crowding, position is a function of time alone.
        for (t in listOf(0L, 100L, 3000L, 9000L)) {
            val x = layout.mapping.xOf(t)
            dense.forEachIndexed { i, d ->
                if (d.t0Ms == t) assertEquals(x, layout.lanes[1][i].xPx)
            }
            sparse.forEachIndexed { i, sItem ->
                if (sItem.t0Ms == t) assertEquals(x, layout.lanes[0][i].xPx)
            }
        }
    }

    // ---- no overlap ----

    @Test
    fun `words never overlap however fast the speech`() {
        val fast = (0 until 30).map { i -> item(i * 250L, i * 250L + 200L, 90f) }
        val layout = TimelineLayout.place(listOf(fast), scale)
        val placed = layout.lanes[0]
        for (i in 1 until placed.size) {
            val previousEnd = placed[i - 1].xPx + placed[i - 1].widthPx
            assertTrue(
                placed[i].xPx >= previousEnd,
                "word $i at ${placed[i].xPx} overlaps ${i - 1} ending at $previousEnd",
            )
        }
    }

    @Test
    fun `neither lane overlaps when both are dense`() {
        val a = (0 until 40).map { i -> item(i * 200L, i * 200L + 150L, 120f) }
        val b = (0 until 40).map { i -> item(i * 200L + 90L, i * 200L + 240L, 120f) }
        val layout = TimelineLayout.place(listOf(a, b), scale)
        for (lane in layout.lanes) {
            for (i in 1 until lane.size) {
                assertTrue(lane[i].xPx >= lane[i - 1].xPx + lane[i - 1].widthPx)
            }
        }
    }

    @Test
    fun `a gap is left between neighbours`() {
        val lane = listOf(item(0, 100, 50f), item(120, 220, 50f))
        val layout = TimelineLayout.place(listOf(lane), scale, gapPx = 12f)
        val placed = layout.lanes[0]
        assertTrue(placed[1].xPx - (placed[0].xPx + 50f) >= 12f)
    }

    // ---- the mapping ----

    @Test
    fun `position never goes backwards as time advances`() {
        val lane = (0 until 25).map { i -> item(i * 130L, i * 130L + 110L, 95f) }
        val layout = TimelineLayout.place(listOf(lane), scale)
        var previous = Float.NEGATIVE_INFINITY
        for (t in 0L..4_000L step 25L) {
            val x = layout.mapping.xOf(t)
            assertTrue(x >= previous, "went backwards at ${t}ms: $x < $previous")
            previous = x
        }
    }

    @Test
    fun `sparse speech keeps true time scale`() {
        val lane = listOf(item(0, 500, 40f), item(10_000, 10_500, 40f))
        val layout = TimelineLayout.place(listOf(lane), scale)
        assertEquals(0f, layout.lanes[0][0].xPx)
        assertEquals(10_000 * scale, layout.lanes[0][1].xPx)
    }

    @Test
    fun `time before and after the words still maps somewhere sensible`() {
        val lane = listOf(item(5_000, 5_500, 60f))
        val layout = TimelineLayout.place(listOf(lane), scale)
        assertTrue(layout.mapping.xOf(0) < layout.lanes[0][0].xPx)
        assertTrue(layout.mapping.xOf(9_000) > layout.lanes[0][0].xPx)
        assertTrue(layout.mapping.xOf(4_000) > layout.mapping.xOf(0))
    }

    @Test
    fun `interpolates within an interval`() {
        val lane = listOf(item(0, 100, 10f), item(1000, 1100, 10f))
        val layout = TimelineLayout.place(listOf(lane), scale)
        val mid = layout.mapping.xOf(500)
        assertTrue(mid > layout.mapping.xOf(0) && mid < layout.mapping.xOf(1000))
    }

    @Test
    fun `empty input is handled`() {
        val layout = TimelineLayout.place(listOf(emptyList(), emptyList()), scale)
        assertTrue(layout.lanes.all { it.isEmpty() })
        assertEquals(1_000 * scale, layout.mapping.xOf(1_000))
    }

    @Test
    fun `one empty lane does not disturb the other`() {
        val lane = listOf(item(0, 500, 60f), item(2000, 2500, 60f))
        val layout = TimelineLayout.place(listOf(lane, emptyList()), scale)
        assertEquals(2, layout.lanes[0].size)
        assertTrue(layout.lanes[1].isEmpty())
        assertEquals(layout.lanes[0][1].xPx, layout.mapping.xOf(2000))
    }

    @Test
    fun `words starting at the same instant in both lanes share a position`() {
        val a = listOf(item(1000, 1500, 200f))
        val b = listOf(item(1000, 1400, 40f))
        val layout = TimelineLayout.place(listOf(a, b), scale)
        assertEquals(layout.lanes[0][0].xPx, layout.lanes[1][0].xPx)
    }
}
