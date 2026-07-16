package dev.bscribe.core.audio

/**
 * PCM format for everything Bilingual Scribe records and exports.
 * 16 kHz mono PCM16 is what Whisper consumes natively; recording in the target
 * format means clips can be exported without resampling.
 */
data class WavSpec(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
) {
    val bytesPerSample: Int get() = bitsPerSample / 8
    val blockAlign: Int get() = channels * bytesPerSample
    val byteRate: Int get() = sampleRate * blockAlign

    fun samplesToMs(sampleCount: Long): Long = sampleCount * 1000L / sampleRate
    fun msToSamples(ms: Long): Long = ms * sampleRate / 1000L
    fun dataBytesToMs(dataBytes: Long): Long = dataBytes * 1000L / byteRate

    companion object {
        val SCRIBE_DEFAULT = WavSpec(sampleRate = 16_000, channels = 1, bitsPerSample = 16)

        /** Canonical header this app writes: 44-byte PCM WAV, no extra chunks. */
        const val HEADER_SIZE = 44
    }
}
