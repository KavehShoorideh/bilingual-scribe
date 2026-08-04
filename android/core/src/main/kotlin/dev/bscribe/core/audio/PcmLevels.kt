package dev.bscribe.core.audio

import kotlin.math.log10
import kotlin.math.sqrt

/** Level metering for the recording screen's VU display. */
object PcmLevels {

    const val SILENCE_DB = -90.0

    /** RMS level of the buffer in dBFS (0 dB = full-scale sine). */
    fun rmsDb(samples: ShortArray, offset: Int = 0, length: Int = samples.size - offset): Double {
        if (length <= 0) return SILENCE_DB
        var sumSquares = 0.0
        for (i in offset until offset + length) {
            val s = samples[i] / 32768.0
            sumSquares += s * s
        }
        val rms = sqrt(sumSquares / length)
        if (rms <= 0.0) return SILENCE_DB
        return (20.0 * log10(rms)).coerceAtLeast(SILENCE_DB)
    }

    /**
     * Peak level of the buffer in dBFS.
     *
     * Peak rather than RMS is what makes a meter feel attached to the voice:
     * RMS is an average, so a short loud syllable is diluted by the quiet
     * either side of it and the needle lags behind what you just heard.
     */
    fun peakDb(samples: ShortArray, offset: Int = 0, length: Int = samples.size - offset): Double {
        if (length <= 0) return SILENCE_DB
        var peak = 0
        for (i in offset until offset + length) {
            val v = samples[i].toInt()
            // Negate carefully: -Short.MIN_VALUE overflows a Short.
            val magnitude = if (v < 0) -v else v
            if (magnitude > peak) peak = magnitude
        }
        if (peak == 0) return SILENCE_DB
        return (20.0 * log10(peak / 32768.0)).coerceAtLeast(SILENCE_DB)
    }

    /** Maps dBFS to a 0..1 meter position over a practical speech range. */
    fun dbToMeter(db: Double, floorDb: Double = -60.0): Float =
        (((db - floorDb) / -floorDb).coerceIn(0.0, 1.0)).toFloat()
}
