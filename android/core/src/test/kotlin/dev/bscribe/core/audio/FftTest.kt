package dev.bscribe.core.audio

import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FftTest {

    @Test
    fun `dc signal has all energy in bin zero`() {
        val power = Fft.powerSpectrum(DoubleArray(64) { 1.0 })
        assertTrue(power[0] > 0.0)
        for (i in 1 until power.size) {
            assertTrue(power[i] < 1e-9, "bin $i should be empty but was ${power[i]}")
        }
    }

    @Test
    fun `a sine lands in its own bin`() {
        val n = 64
        val k = 8 // cycles across the window
        val signal = DoubleArray(n) { sin(2.0 * Math.PI * k * it / n) }
        val power = Fft.powerSpectrum(signal)
        val peak = power.indices.maxByOrNull { power[it] }
        assertEquals(k, peak)
    }

    @Test
    fun `two tones produce two peaks`() {
        val n = 128
        val signal = DoubleArray(n) {
            sin(2.0 * Math.PI * 8 * it / n) + sin(2.0 * Math.PI * 20 * it / n)
        }
        val power = Fft.powerSpectrum(signal)
        val ranked = power.indices.sortedByDescending { power[it] }
        assertTrue(setOf(8, 20) == ranked.take(2).toSet(), "peaks were ${ranked.take(2)}")
    }

    @Test
    fun `silence stays silent`() {
        val power = Fft.powerSpectrum(DoubleArray(32))
        assertTrue(power.all { abs(it) < 1e-12 })
    }

    @Test
    fun `spectrum length is n over two plus one`() {
        assertEquals(33, Fft.powerSpectrum(DoubleArray(64)).size)
    }

    @Test
    fun `non power of two is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Fft.transform(DoubleArray(10), DoubleArray(10))
        }
    }

    @Test
    fun `mismatched arrays are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Fft.transform(DoubleArray(8), DoubleArray(16))
        }
    }
}
