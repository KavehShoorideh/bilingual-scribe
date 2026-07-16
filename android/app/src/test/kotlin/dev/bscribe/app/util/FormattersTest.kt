package dev.bscribe.app.util

import kotlin.test.Test
import kotlin.test.assertEquals

class FormattersTest {

    @Test
    fun `clock formats minutes and seconds`() {
        assertEquals("0:00", formatClock(0))
        assertEquals("0:59", formatClock(59_999))
        assertEquals("1:05", formatClock(65_000))
        assertEquals("59:59", formatClock(3_599_000))
    }

    @Test
    fun `clock rolls into hours`() {
        assertEquals("1:00:00", formatClock(3_600_000))
        assertEquals("1:02:05", formatClock(3_725_000))
        assertEquals("11:22:33", formatClock(((11 * 3600 + 22 * 60 + 33) * 1000).toLong()))
    }
}
