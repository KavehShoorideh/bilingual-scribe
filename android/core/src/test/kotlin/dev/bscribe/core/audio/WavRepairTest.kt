package dev.bscribe.core.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class WavRepairTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val spec = WavSpec.SCRIBE_DEFAULT

    /** Writes audio and "crashes" (no close): header stays zeroed. */
    private fun crashedWav(file: File, samples: Int): File {
        val writer = WavFileWriter(file, spec)
        writer.append(ShortArray(samples) { 7 })
        // Simulate SIGKILL: copy current bytes, discard the writer without close.
        val snapshot = tmp.newFile(file.name + ".crash")
        snapshot.writeBytes(file.readBytes())
        writer.close()
        return snapshot
    }

    @Test
    fun `repairs stale header after simulated crash`() {
        val crashed = crashedWav(tmp.newFile("a.wav"), samples = spec.sampleRate * 3) // 3 s
        val result = WavRepair.repair(crashed, spec)

        val repaired = assertIs<WavRepair.Result.Repaired>(result)
        assertEquals(spec.sampleRate * 3 * 2L, repaired.dataBytes)
        assertEquals(3000L, repaired.durationMs)

        val h = ByteBuffer.wrap(crashed.readBytes().copyOf(WavSpec.HEADER_SIZE))
            .order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(spec.sampleRate * 3 * 2, h.getInt(40))
        assertEquals(36 + spec.sampleRate * 3 * 2, h.getInt(4))

        // Second pass: nothing left to fix.
        assertIs<WavRepair.Result.Consistent>(WavRepair.repair(crashed, spec))
    }

    @Test
    fun `drops trailing partial sample frame`() {
        val crashed = crashedWav(tmp.newFile("b.wav"), samples = 1600)
        // Kill happened mid-sample: file ends on an odd byte.
        java.io.RandomAccessFile(crashed, "rw").use { it.setLength(it.length() + 1) }

        val repaired = assertIs<WavRepair.Result.Repaired>(WavRepair.repair(crashed, spec))
        assertEquals(1600 * 2L, repaired.dataBytes)
        assertEquals(WavSpec.HEADER_SIZE + 1600 * 2L, crashed.length())
    }

    @Test
    fun `cleanly closed file is reported consistent`() {
        val file = tmp.newFile("c.wav")
        WavFileWriter(file, spec).use { it.append(ShortArray(800)) }
        val result = assertIs<WavRepair.Result.Consistent>(WavRepair.repair(file, spec))
        assertEquals(50L, result.durationMs)
    }

    @Test
    fun `rejects files shorter than a header`() {
        val file = tmp.newFile("d.wav")
        file.writeBytes(ByteArray(10))
        assertIs<WavRepair.Result.Rejected>(WavRepair.repair(file, spec))
    }

    @Test
    fun `rejects non-wav content of header size`() {
        val file = tmp.newFile("e.bin")
        file.writeBytes(ByteArray(100) { 0x42 })
        assertIs<WavRepair.Result.Rejected>(WavRepair.repair(file, spec))
    }

    @Test
    fun `rejects wav with foreign sample rate`() {
        val file = tmp.newFile("f.wav")
        val foreign = WavSpec(sampleRate = 44_100, channels = 1, bitsPerSample = 16)
        file.writeBytes(WavFileWriter.buildHeader(foreign, dataBytes = 0))
        assertIs<WavRepair.Result.Rejected>(WavRepair.repair(file, spec))
    }

    @Test
    fun `zero-sample crash yields empty but valid file`() {
        val crashed = crashedWav(tmp.newFile("g.wav"), samples = 0)
        // A 44-byte file with zeroed sizes is already consistent (0 data bytes).
        val result = WavRepair.repair(crashed, spec)
        assertIs<WavRepair.Result.Consistent>(result)
    }
}
