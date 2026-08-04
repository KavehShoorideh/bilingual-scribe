package dev.bscribe.core.audio

import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PcmLevelsTest {

    @Test
    fun `silence is floor`() {
        assertEquals(PcmLevels.SILENCE_DB, PcmLevels.rmsDb(ShortArray(1600)), 0.0)
        assertEquals(PcmLevels.SILENCE_DB, PcmLevels.rmsDb(ShortArray(0)), 0.0)
    }

    @Test
    fun `full-scale sine is about -3 dBFS RMS`() {
        val n = 16_000
        val sine = ShortArray(n) { (sin(2.0 * Math.PI * 440.0 * it / 16_000.0) * 32767).toInt().toShort() }
        val db = PcmLevels.rmsDb(sine)
        assertTrue(abs(db - (-3.01)) < 0.1, "expected ~-3.01 dB, got $db")
    }

    @Test
    fun `meter maps floor to 0 and full scale to 1`() {
        assertEquals(0f, PcmLevels.dbToMeter(-60.0), 1e-6f)
        assertEquals(1f, PcmLevels.dbToMeter(0.0), 1e-6f)
        assertTrue(PcmLevels.dbToMeter(-30.0) in 0.45f..0.55f)
    }

    @Test
    fun `peak tracks a short transient that rms averages away`() {
        // A single loud sample in an otherwise quiet block: this is exactly
        // the syllable onset the meter must respond to.
        val block = ShortArray(320) { if (it == 10) (Short.MAX_VALUE / 2).toShort() else 100 }
        val peak = PcmLevels.peakDb(block)
        val rms = PcmLevels.rmsDb(block)
        assertTrue(peak > rms + 20, "peak $peak should far exceed rms $rms on a transient")
    }

    @Test
    fun `peak of silence is the floor`() {
        assertEquals(PcmLevels.SILENCE_DB, PcmLevels.peakDb(ShortArray(64)))
        assertEquals(PcmLevels.SILENCE_DB, PcmLevels.peakDb(ShortArray(0)))
    }

    @Test
    fun `peak of full scale is near zero dB`() {
        val db = PcmLevels.peakDb(shortArrayOf(Short.MAX_VALUE))
        assertTrue(db > -0.1 && db <= 0.0, "expected ~0 dBFS but was $db")
    }

    @Test
    fun `most negative sample does not overflow`() {
        // -Short.MIN_VALUE does not fit in a Short; negating without widening
        // would wrap to a negative magnitude and report silence.
        val db = PcmLevels.peakDb(shortArrayOf(Short.MIN_VALUE))
        assertTrue(db > -0.1, "expected full scale but was $db")
    }

    @Test
    fun `peak honours offset and length`() {
        val block = ShortArray(100) { if (it < 50) Short.MAX_VALUE else 0 }
        assertEquals(PcmLevels.SILENCE_DB, PcmLevels.peakDb(block, offset = 50, length = 50))
    }
}
