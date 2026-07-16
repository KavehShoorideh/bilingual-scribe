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
}
