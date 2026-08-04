package dev.bscribe.core.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PcmConvertTest {

    @Test
    fun `silence maps to zero`() {
        val out = PcmConvert.toFloatMono(ShortArray(8))
        assertTrue(out.all { it == 0f })
    }

    @Test
    fun `full-scale negative maps to exactly minus one`() {
        // The reason for dividing by 32768 rather than 32767: Short.MIN_VALUE
        // lands exactly on -1.0 instead of overshooting past it.
        val out = PcmConvert.toFloatMono(shortArrayOf(Short.MIN_VALUE))
        assertEquals(-1.0f, out[0])
    }

    @Test
    fun `full-scale positive stays just inside plus one`() {
        val out = PcmConvert.toFloatMono(shortArrayOf(Short.MAX_VALUE))
        assertTrue(out[0] < 1.0f, "must not exceed 1.0")
        assertTrue(out[0] > 0.9999f, "should be within a LSB of 1.0")
    }

    @Test
    fun `sign is preserved`() {
        val out = PcmConvert.toFloatMono(shortArrayOf(1000, -1000))
        assertTrue(out[0] > 0f)
        assertTrue(out[1] < 0f)
        assertEquals(out[0], -out[1])
    }

    @Test
    fun `honours offset and length`() {
        val samples = shortArrayOf(0, 0, 16384, 16384, 0)
        val out = PcmConvert.toFloatMono(samples, offset = 2, length = 2)
        assertEquals(2, out.size)
        assertEquals(0.5f, out[0])
        assertEquals(0.5f, out[1])
    }

    @Test
    fun `empty range yields empty array`() {
        assertEquals(0, PcmConvert.toFloatMono(ShortArray(4), 2, 0).size)
    }

    @Test
    fun `out of bounds range fails loudly`() {
        val samples = ShortArray(4)
        assertFailsWith<IllegalArgumentException> { PcmConvert.toFloatMono(samples, 0, 5) }
        assertFailsWith<IllegalArgumentException> { PcmConvert.toFloatMono(samples, 3, 2) }
        assertFailsWith<IllegalArgumentException> { PcmConvert.toFloatMono(samples, -1, 2) }
    }
}
