package dev.bscribe.core.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streams PCM16 samples to a WAV file so that the file is recoverable at any
 * moment, even if the process is killed mid-write.
 *
 * Strategy: write a canonical 44-byte header up front with zeroed size fields,
 * append samples, and patch the RIFF/data size fields in place every
 * [headerPatchIntervalBytes] of audio and on [close]. If the process dies
 * between patches, [WavRepair] reconstructs the sizes from the file length —
 * PCM data itself is always intact because samples are appended sequentially.
 *
 * Not thread-safe by design: the audio capture thread is the only writer.
 */
class WavFileWriter(
    file: File,
    private val spec: WavSpec = WavSpec.SCRIBE_DEFAULT,
    /** Default: patch the header every ~5 seconds of audio. */
    private val headerPatchIntervalBytes: Int = spec.byteRate * 5,
) : Closeable {

    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes: Long = 0
    private var bytesSinceLastPatch: Long = 0
    private var closed = false

    /** Little-endian scratch buffer, reused across appends. */
    private var scratch: ByteBuffer = newScratch(DEFAULT_SCRATCH_SAMPLES)

    init {
        raf.setLength(0)
        raf.write(buildHeader(spec, dataBytes = 0))
    }

    val bytesWritten: Long get() = dataBytes
    val durationMs: Long get() = spec.dataBytesToMs(dataBytes)

    /** Appends [length] samples from [samples] starting at [offset]. */
    fun append(samples: ShortArray, offset: Int = 0, length: Int = samples.size - offset) {
        check(!closed) { "writer is closed" }
        require(offset >= 0 && length >= 0 && offset + length <= samples.size) {
            "bad range: offset=$offset length=$length size=${samples.size}"
        }
        if (length == 0) return

        if (scratch.capacity() < length * 2) scratch = newScratch(length)
        scratch.clear()
        for (i in offset until offset + length) scratch.putShort(samples[i])

        raf.write(scratch.array(), 0, length * 2)
        dataBytes += length * 2L
        bytesSinceLastPatch += length * 2L

        if (bytesSinceLastPatch >= headerPatchIntervalBytes) {
            patchHeader()
            bytesSinceLastPatch = 0
        }
    }

    /** Forces the size fields on disk to match what has been written so far. */
    fun sync() {
        check(!closed) { "writer is closed" }
        patchHeader()
        raf.fd.sync()
        bytesSinceLastPatch = 0
    }

    override fun close() {
        if (closed) return
        patchHeader()
        raf.fd.sync()
        raf.close()
        closed = true
    }

    private fun patchHeader() {
        val end = raf.filePointer
        raf.seek(4)
        raf.write(intLe((WavSpec.HEADER_SIZE - 8 + dataBytes).toInt()))
        raf.seek(40)
        raf.write(intLe(dataBytes.toInt()))
        raf.seek(end)
    }

    companion object {
        private const val DEFAULT_SCRATCH_SAMPLES = 8192

        private fun newScratch(samples: Int): ByteBuffer =
            ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)

        internal fun intLe(v: Int): ByteArray =
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

        internal fun shortLe(v: Int): ByteArray =
            ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()

        /** The canonical 44-byte PCM WAV header this app writes and repairs. */
        fun buildHeader(spec: WavSpec, dataBytes: Long): ByteArray {
            val b = ByteBuffer.allocate(WavSpec.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray(Charsets.US_ASCII))
            b.putInt((WavSpec.HEADER_SIZE - 8 + dataBytes).toInt())
            b.put("WAVE".toByteArray(Charsets.US_ASCII))
            b.put("fmt ".toByteArray(Charsets.US_ASCII))
            b.putInt(16)                        // fmt chunk size
            b.putShort(1)                       // audio format: PCM
            b.putShort(spec.channels.toShort())
            b.putInt(spec.sampleRate)
            b.putInt(spec.byteRate)
            b.putShort(spec.blockAlign.toShort())
            b.putShort(spec.bitsPerSample.toShort())
            b.put("data".toByteArray(Charsets.US_ASCII))
            b.putInt(dataBytes.toInt())
            return b.array()
        }
    }
}
