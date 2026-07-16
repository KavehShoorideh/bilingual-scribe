package dev.bscribe.core.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class WavReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val spec = WavSpec.SCRIBE_DEFAULT

    @Test
    fun `info reads spec and duration`() {
        val file = tmp.newFile("a.wav")
        WavFileWriter(file, spec).use { it.append(ShortArray(spec.sampleRate * 2)) }

        val info = WavReader.info(file)
        assertEquals(spec, info.spec)
        assertEquals(2000L, info.durationMs)
    }

    @Test
    fun `readRange returns exact samples`() {
        val file = tmp.newFile("b.wav")
        // 1 s of audio where sample i has value i (mod Short range).
        val samples = ShortArray(spec.sampleRate) { it.toShort() }
        WavFileWriter(file, spec).use { it.append(samples) }

        // 100 ms window starting at 500 ms → samples 8000..9599
        val range = WavReader.readRange(file, startMs = 500, endMs = 600)
        assertEquals(1600, range.size)
        assertContentEquals(ShortArray(1600) { (8000 + it).toShort() }, range)
    }

    @Test
    fun `readRange clamps beyond end and handles inverted ranges`() {
        val file = tmp.newFile("c.wav")
        WavFileWriter(file, spec).use { it.append(ShortArray(160)) } // 10 ms

        assertEquals(0, WavReader.readRange(file, 500, 600).size)
        assertEquals(0, WavReader.readRange(file, 8, 8).size)
        assertEquals(160, WavReader.readRange(file, -100, 100).size)
    }
}
