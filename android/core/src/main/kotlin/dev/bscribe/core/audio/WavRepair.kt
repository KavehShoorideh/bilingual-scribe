package dev.bscribe.core.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Recovers WAV files whose header size fields went stale because the process
 * died between [WavFileWriter] header patches. Only files with this app's
 * canonical 44-byte header layout are touched; anything else is rejected.
 */
object WavRepair {

    sealed interface Result {
        /** Header matched file length already; nothing changed. */
        data class Consistent(val dataBytes: Long, val durationMs: Long) : Result

        /** Size fields were stale and have been fixed from the file length. */
        data class Repaired(val dataBytes: Long, val durationMs: Long) : Result

        /** Not a file this app wrote (or truncated below a full header). */
        data class Rejected(val reason: String) : Result
    }

    fun repair(file: File, spec: WavSpec = WavSpec.SCRIBE_DEFAULT): Result {
        if (!file.exists()) return Result.Rejected("file does not exist")
        val length = file.length()
        if (length < WavSpec.HEADER_SIZE) {
            return Result.Rejected("file shorter than a WAV header ($length bytes)")
        }

        RandomAccessFile(file, "rw").use { raf ->
            val header = ByteArray(WavSpec.HEADER_SIZE)
            raf.readFully(header)
            val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

            if (!matches(b, 0, "RIFF") || !matches(b, 8, "WAVE") ||
                !matches(b, 12, "fmt ") || !matches(b, 36, "data")
            ) {
                return Result.Rejected("not a canonical scribe WAV header")
            }
            if (b.getShort(20).toInt() != 1) return Result.Rejected("not PCM")
            if (b.getInt(24) != spec.sampleRate) {
                return Result.Rejected("unexpected sample rate ${b.getInt(24)}")
            }
            if (b.getShort(22).toInt() != spec.channels) {
                return Result.Rejected("unexpected channel count ${b.getShort(22)}")
            }
            if (b.getShort(34).toInt() != spec.bitsPerSample) {
                return Result.Rejected("unexpected bits/sample ${b.getShort(34)}")
            }

            // Truth is the file length; sizes in the header may be stale.
            // Drop a trailing partial sample frame if the process died mid-write.
            val rawDataBytes = length - WavSpec.HEADER_SIZE
            val dataBytes = rawDataBytes - (rawDataBytes % spec.blockAlign)
            val durationMs = spec.dataBytesToMs(dataBytes)

            val headerRiff = b.getInt(4).toLong() and 0xFFFFFFFFL
            val headerData = b.getInt(40).toLong() and 0xFFFFFFFFL
            val consistent =
                headerData == dataBytes && headerRiff == WavSpec.HEADER_SIZE - 8 + dataBytes &&
                    rawDataBytes == dataBytes

            if (consistent) return Result.Consistent(dataBytes, durationMs)

            if (rawDataBytes != dataBytes) {
                raf.setLength(WavSpec.HEADER_SIZE + dataBytes)
            }
            raf.seek(4)
            raf.write(WavFileWriter.intLe((WavSpec.HEADER_SIZE - 8 + dataBytes).toInt()))
            raf.seek(40)
            raf.write(WavFileWriter.intLe(dataBytes.toInt()))
            raf.fd.sync()
            return Result.Repaired(dataBytes, durationMs)
        }
    }

    private fun matches(b: ByteBuffer, offset: Int, magic: String): Boolean {
        for (i in magic.indices) {
            if (b.get(offset + i) != magic[i].code.toByte()) return false
        }
        return true
    }
}
