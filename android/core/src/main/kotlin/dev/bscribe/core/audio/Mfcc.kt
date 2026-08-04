package dev.bscribe.core.audio

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Mel-frequency cepstral coefficients — a compact description of the shape of
 * the vocal tract, which is roughly what makes one voice sound different from
 * another.
 *
 * Used here only to tell *that* the speaker changed, not who is speaking.
 * Identifying people would need a trained embedding model; this needs no model
 * at all, which keeps it honest about what it can deliver.
 */
class Mfcc(
    private val sampleRate: Int = 16_000,
    private val frameSize: Int = 400, // 25 ms
    private val hopSize: Int = 160, // 10 ms
    private val fftSize: Int = 512,
    private val melBands: Int = 26,
    /** Coefficients kept per frame, starting at 1. */
    val coefficients: Int = 12,
) {

    private val window = DoubleArray(frameSize) {
        // Hamming.
        0.54 - 0.46 * cos(2.0 * Math.PI * it / (frameSize - 1))
    }

    private val filterBank: Array<DoubleArray> = buildMelFilterBank()

    /**
     * @return one vector of [coefficients] values per frame.
     */
    fun analyse(samples: ShortArray): List<DoubleArray> {
        if (samples.size < frameSize) return emptyList()
        val frames = mutableListOf<DoubleArray>()

        var start = 0
        while (start + frameSize <= samples.size) {
            val frame = DoubleArray(fftSize)
            // Pre-emphasis lifts the high frequencies, where much of the
            // speaker-specific detail lives.
            var previous = if (start > 0) samples[start - 1].toDouble() else 0.0
            for (i in 0 until frameSize) {
                val x = samples[start + i].toDouble()
                frame[i] = (x - PRE_EMPHASIS * previous) * window[i]
                previous = x
            }

            val power = Fft.powerSpectrum(frame)
            val melEnergies = DoubleArray(melBands) { band ->
                var sum = 0.0
                val filter = filterBank[band]
                for (bin in filter.indices) {
                    if (filter[bin] != 0.0) sum += power[bin] * filter[bin]
                }
                // Floor before the log so silence gives a finite value rather
                // than -inf, which would poison every downstream mean.
                ln(sum.coerceAtLeast(1e-10))
            }

            frames += dct(melEnergies)
            start += hopSize
        }
        return frames
    }

    /** DCT-II, dropping coefficient 0 — it is loudness, not voice quality. */
    private fun dct(input: DoubleArray): DoubleArray {
        val out = DoubleArray(coefficients)
        val n = input.size
        val scale = sqrt(2.0 / n)
        for (k in 1..coefficients) {
            var sum = 0.0
            for (i in 0 until n) {
                sum += input[i] * cos(Math.PI * k * (i + 0.5) / n)
            }
            out[k - 1] = sum * scale
        }
        return out
    }

    private fun buildMelFilterBank(): Array<DoubleArray> {
        val bins = fftSize / 2 + 1
        val lowMel = hzToMel(LOW_HZ)
        val highMel = hzToMel(minOf(HIGH_HZ, sampleRate / 2.0))
        val points = DoubleArray(melBands + 2) { i ->
            melToHz(lowMel + (highMel - lowMel) * i / (melBands + 1))
        }
        val binOf = points.map { hz ->
            ((fftSize + 1) * hz / sampleRate).toInt().coerceIn(0, bins - 1)
        }

        return Array(melBands) { band ->
            val filter = DoubleArray(bins)
            val left = binOf[band]
            val centre = binOf[band + 1]
            val right = binOf[band + 2]
            for (bin in left until centre) {
                if (centre > left) filter[bin] = (bin - left).toDouble() / (centre - left)
            }
            for (bin in centre until right) {
                if (right > centre) filter[bin] = (right - bin).toDouble() / (right - centre)
            }
            if (centre in filter.indices) filter[centre] = 1.0
            filter
        }
    }

    /** Frame index covering a moment in the signal. */
    fun frameAt(timeMs: Long): Int =
        ((timeMs * sampleRate / 1000) / hopSize).toInt()

    private companion object {
        const val PRE_EMPHASIS = 0.97
        const val LOW_HZ = 80.0 // below the lowest speaking fundamental
        const val HIGH_HZ = 8000.0

        fun hzToMel(hz: Double) = 2595.0 * log10(1.0 + hz / 700.0)
        fun melToHz(mel: Double) = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)
    }
}
