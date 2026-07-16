package dev.bscribe.core.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class WavFileWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val spec = WavSpec.SCRIBE_DEFAULT

    private fun header(file: File): ByteBuffer =
        ByteBuffer.wrap(file.readBytes().copyOf(WavSpec.HEADER_SIZE)).order(ByteOrder.LITTLE_ENDIAN)

    @Test
    fun `closed file has byte-exact canonical header`() {
        val file = tmp.newFile("a.wav")
        val samples = ShortArray(1600) { (it % 128).toShort() } // 100 ms
        WavFileWriter(file, spec).use { it.append(samples) }

        val h = header(file)
        assertEquals("RIFF", magic(h, 0))
        assertEquals("WAVE", magic(h, 8))
        assertEquals("fmt ", magic(h, 12))
        assertEquals("data", magic(h, 36))
        assertEquals(16, h.getInt(16))                    // fmt chunk size
        assertEquals(1, h.getShort(20).toInt())           // PCM
        assertEquals(1, h.getShort(22).toInt())           // mono
        assertEquals(16_000, h.getInt(24))                // sample rate
        assertEquals(32_000, h.getInt(28))                // byte rate
        assertEquals(2, h.getShort(32).toInt())           // block align
        assertEquals(16, h.getShort(34).toInt())          // bits
        assertEquals(3200, h.getInt(40))                  // data bytes
        assertEquals(36 + 3200, h.getInt(4))              // RIFF size
        assertEquals(WavSpec.HEADER_SIZE + 3200L, file.length())
    }

    @Test
    fun `payload is little-endian PCM in order`() {
        val file = tmp.newFile("b.wav")
        val samples = shortArrayOf(0x1234, -0x1234, 0, Short.MAX_VALUE, Short.MIN_VALUE)
        WavFileWriter(file, spec).use { it.append(samples) }

        val bytes = file.readBytes()
        val payload = ByteBuffer.wrap(bytes, WavSpec.HEADER_SIZE, bytes.size - WavSpec.HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) assertEquals(s, payload.short)
    }

    @Test
    fun `append respects offset and length`() {
        val file = tmp.newFile("c.wav")
        val samples = shortArrayOf(1, 2, 3, 4, 5)
        WavFileWriter(file, spec).use { it.append(samples, offset = 1, length = 3) }

        val bytes = file.readBytes()
        val payload = ByteBuffer.wrap(bytes, WavSpec.HEADER_SIZE, bytes.size - WavSpec.HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(3 * 2, bytes.size - WavSpec.HEADER_SIZE)
        assertEquals(2, payload.short.toInt())
        assertEquals(3, payload.short.toInt())
        assertEquals(4, payload.short.toInt())
    }

    @Test
    fun `header is patched periodically without close`() {
        val file = tmp.newFile("d.wav")
        // Patch interval of 1 second of audio.
        val writer = WavFileWriter(file, spec, headerPatchIntervalBytes = spec.byteRate)
        try {
            writer.append(ShortArray(spec.sampleRate)) // exactly 1 s → triggers patch
            val h = header(file)
            assertEquals(spec.sampleRate * 2, h.getInt(40))
        } finally {
            writer.close()
        }
    }

    @Test
    fun `header sizes are stale between patches by design`() {
        val file = tmp.newFile("e.wav")
        val writer = WavFileWriter(file, spec) // default 5 s interval
        try {
            writer.append(ShortArray(spec.sampleRate)) // 1 s — below interval
            val h = header(file)
            // Stale header (0) is expected here; WavRepair recovers from it.
            assertEquals(0, h.getInt(40))
            assertTrue(file.length() > WavSpec.HEADER_SIZE)
        } finally {
            writer.close()
        }
        // After close it must be consistent.
        assertEquals(spec.sampleRate * 2, header(file).getInt(40))
    }

    @Test
    fun `durationMs tracks appended audio`() {
        val file = tmp.newFile("f.wav")
        WavFileWriter(file, spec).use { w ->
            w.append(ShortArray(spec.sampleRate * 2))       // 2 s
            w.append(ShortArray(spec.sampleRate / 2))       // 0.5 s
            assertEquals(2500L, w.durationMs)
        }
    }

    private fun magic(b: ByteBuffer, offset: Int): String {
        val arr = ByteArray(4)
        for (i in 0..3) arr[i] = b.get(offset + i)
        return String(arr, Charsets.US_ASCII)
    }
}
