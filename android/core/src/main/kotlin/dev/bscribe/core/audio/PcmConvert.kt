package dev.bscribe.core.audio

/**
 * PCM16 → float32, the format whisper's encoder wants.
 *
 * [WavReader] and the capture path both deal in [ShortArray]; whisper takes
 * normalised floats in [-1, 1].
 */
object PcmConvert {

    /**
     * Divides by 32768 rather than 32767 so the conversion is an exact power
     * of two and [Short.MIN_VALUE] maps to exactly -1.0 instead of overshooting
     * it. Full-scale positive samples land a hair under +1.0, which is
     * standard and inaudible.
     */
    fun toFloatMono(
        samples: ShortArray,
        offset: Int = 0,
        length: Int = samples.size - offset,
    ): FloatArray {
        require(offset >= 0 && length >= 0 && offset + length <= samples.size) {
            "range $offset..${offset + length} outside ShortArray of ${samples.size}"
        }
        val out = FloatArray(length)
        for (i in 0 until length) {
            out[i] = samples[offset + i] / 32768f
        }
        return out
    }
}
