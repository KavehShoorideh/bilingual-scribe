package dev.bscribe.core.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * Minimal reader for the canonical WAVs this app writes. Used for clip
 * extraction at export time and for feeding the final transcription pass.
 */
object WavReader {

    data class Info(val spec: WavSpec, val dataBytes: Long, val durationMs: Long)

    fun info(file: File): Info {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(WavSpec.HEADER_SIZE)
            raf.readFully(header)
            require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF") { "not a WAV: $file" }
            require(String(header, 8, 4, Charsets.US_ASCII) == "WAVE") { "not a WAV: $file" }
            val spec = WavSpec(
                sampleRate = leInt(header, 24),
                channels = leShort(header, 22),
                bitsPerSample = leShort(header, 34),
            )
            val dataBytes = file.length() - WavSpec.HEADER_SIZE
            return Info(spec, dataBytes, spec.dataBytesToMs(dataBytes))
        }
    }

    /**
     * Reads samples in [startMs, endMs) clamped to the file's bounds.
     * Returns an empty array when the range lies outside the audio.
     */
    fun readRange(file: File, startMs: Long, endMs: Long): ShortArray {
        val info = info(file)
        val spec = info.spec
        val totalSamples = info.dataBytes / spec.blockAlign
        val fromSample = spec.msToSamples(startMs.coerceAtLeast(0)).coerceAtMost(totalSamples)
        val toSample = spec.msToSamples(endMs.coerceAtLeast(0)).coerceAtMost(totalSamples)
        if (toSample <= fromSample) return ShortArray(0)

        val count = (toSample - fromSample).toInt()
        val bytes = ByteArray(count * spec.bytesPerSample)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(WavSpec.HEADER_SIZE + fromSample * spec.blockAlign)
            raf.readFully(bytes)
        }
        val out = ShortArray(count)
        for (i in 0 until count) {
            out[i] = ((bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
        }
        return out
    }

    private fun leInt(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun leShort(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
}
