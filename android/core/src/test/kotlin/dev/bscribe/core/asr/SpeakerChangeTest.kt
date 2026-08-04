package dev.bscribe.core.asr

import dev.bscribe.core.audio.Mfcc
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeakerChangeTest {

    // ---- candidate boundaries ----

    @Test
    fun `finds the gap between two utterances`() {
        val words = listOf(
            0L to 400L,
            420L to 800L,
            // 600 ms of silence — long enough for someone else to start.
            1400L to 1800L,
        )
        val candidates = SpeakerChange.candidates(words)
        assertEquals(1, candidates.size)
        assertEquals(2, candidates[0].wordIndex)
        // Probed at the middle of the silence, away from either voice.
        assertEquals(1100L, candidates[0].timeMs)
    }

    @Test
    fun `ignores the ordinary gaps between words`() {
        val words = (0 until 10).map { i -> (i * 400L) to (i * 400L + 350L) }
        assertTrue(SpeakerChange.candidates(words).isEmpty())
    }

    @Test
    fun `no words means no candidates`() {
        assertTrue(SpeakerChange.candidates(emptyList()).isEmpty())
        assertTrue(SpeakerChange.candidates(listOf(0L to 100L)).isEmpty())
    }

    // ---- turn assignment ----

    @Test
    fun `a single speaker stays one turn`() {
        val frames = List(400) { doubleArrayOf(1.0, 2.0, 3.0) }
        val turns = SpeakerChange.assignTurns(
            wordCount = 5,
            candidates = listOf(SpeakerChange.Candidate(3, 1000)),
            frames = frames,
            frameOf = { (it / 10).toInt() },
        )
        assertTrue(turns.all { it == 0 }, "identical audio must not split turns")
    }

    @Test
    fun `a different voice starts a new turn`() {
        // Two clearly different mean vectors either side of frame 100.
        val frames = List(200) { i ->
            if (i < 100) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(0.0, 1.0, 0.0)
        }
        val turns = SpeakerChange.assignTurns(
            wordCount = 4,
            candidates = listOf(SpeakerChange.Candidate(2, 1000)),
            frames = frames,
            frameOf = { 100 },
            contextFrames = 100,
        )
        assertEquals(listOf(0, 0, 1, 1), turns.toList())
    }

    @Test
    fun `a boundary with too little audio on one side is ignored`() {
        // Only 5 frames before the boundary — not enough to characterise a
        // voice, so guessing would be worse than leaving it alone.
        val frames = List(200) { i ->
            if (i < 5) doubleArrayOf(1.0, 0.0) else doubleArrayOf(0.0, 1.0)
        }
        val turns = SpeakerChange.assignTurns(
            wordCount = 3,
            candidates = listOf(SpeakerChange.Candidate(1, 50)),
            frames = frames,
            frameOf = { 5 },
        )
        assertTrue(turns.all { it == 0 })
    }

    @Test
    fun `no frames yields a single turn`() {
        val turns = SpeakerChange.assignTurns(
            wordCount = 3,
            candidates = listOf(SpeakerChange.Candidate(1, 100)),
            frames = emptyList(),
            frameOf = { 0 },
        )
        assertEquals(listOf(0, 0, 0), turns.toList())
    }

    @Test
    fun `turns increment across several changes`() {
        val frames = List(400) { i ->
            when {
                i < 100 -> doubleArrayOf(1.0, 0.0, 0.0)
                i < 200 -> doubleArrayOf(0.0, 1.0, 0.0)
                else -> doubleArrayOf(0.0, 0.0, 1.0)
            }
        }
        val turns = SpeakerChange.assignTurns(
            wordCount = 6,
            candidates = listOf(
                SpeakerChange.Candidate(2, 1000),
                SpeakerChange.Candidate(4, 2000),
            ),
            frames = frames,
            frameOf = { ms -> (ms / 10).toInt() },
            contextFrames = 100,
        )
        assertEquals(listOf(0, 0, 1, 1, 2, 2), turns.toList())
    }

    // ---- distance ----

    @Test
    fun `identical vectors have zero distance`() {
        val v = doubleArrayOf(1.0, 2.0, 3.0)
        assertTrue(SpeakerChange.cosineDistance(v, v) < 1e-12)
    }

    @Test
    fun `loudness alone does not register as a different speaker`() {
        // Cosine distance ignores magnitude, which is why it is used here:
        // someone leaning closer to the mic is not a new person.
        val quiet = doubleArrayOf(1.0, 2.0, 3.0)
        val loud = doubleArrayOf(10.0, 20.0, 30.0)
        assertTrue(SpeakerChange.cosineDistance(quiet, loud) < 1e-12)
    }

    @Test
    fun `orthogonal vectors are maximally distant`() {
        val d = SpeakerChange.cosineDistance(doubleArrayOf(1.0, 0.0), doubleArrayOf(0.0, 1.0))
        assertTrue(d > 0.99, "expected ~1.0 but was $d")
    }

    // ---- end to end on synthesised voices ----

    /**
     * Two synthetic "voices" with different formant-like spectra. Not real
     * speech, but enough to prove the MFCC → distance path separates timbres
     * rather than merely amplitudes.
     */
    private fun voice(freqs: List<Double>, ms: Int, amplitude: Double = 0.3): ShortArray {
        val n = 16_000 * ms / 1000
        return ShortArray(n) { i ->
            val t = i.toDouble() / 16_000
            val v = freqs.sumOf { f -> sin(2.0 * PI * f * t) } / freqs.size
            (v * amplitude * Short.MAX_VALUE).toInt().toShort()
        }
    }

    @Test
    fun `mfcc distinguishes two different timbres`() {
        val mfcc = Mfcc()
        val a = mfcc.analyse(voice(listOf(220.0, 700.0, 1400.0), ms = 1500))
        val b = mfcc.analyse(voice(listOf(140.0, 400.0, 2600.0), ms = 1500))
        assertTrue(a.isNotEmpty() && b.isNotEmpty())

        fun mean(frames: List<DoubleArray>): DoubleArray {
            val out = DoubleArray(frames[0].size)
            frames.forEach { f -> f.indices.forEach { out[it] += f[it] } }
            return DoubleArray(out.size) { out[it] / frames.size }
        }

        val across = SpeakerChange.cosineDistance(mean(a), mean(b))
        val within = SpeakerChange.cosineDistance(
            mean(a.take(a.size / 2)),
            mean(a.drop(a.size / 2)),
        )
        assertTrue(
            across > within,
            "different timbres ($across) should differ more than one timbre with itself ($within)",
        )
        assertTrue(across > SpeakerChange.THRESHOLD, "across-voice distance $across below threshold")
    }

    @Test
    fun `mfcc is stable for the same voice`() {
        val mfcc = Mfcc()
        val frames = mfcc.analyse(voice(listOf(220.0, 700.0, 1400.0), ms = 2000))
        fun mean(f: List<DoubleArray>): DoubleArray {
            val out = DoubleArray(f[0].size)
            f.forEach { v -> v.indices.forEach { out[it] += v[it] } }
            return DoubleArray(out.size) { out[it] / f.size }
        }
        val d = SpeakerChange.cosineDistance(
            mean(frames.take(frames.size / 2)),
            mean(frames.drop(frames.size / 2)),
        )
        assertTrue(d < SpeakerChange.THRESHOLD, "same voice split into two halves scored $d")
    }

    @Test
    fun `mfcc handles audio shorter than one frame`() {
        assertTrue(Mfcc().analyse(ShortArray(100)).isEmpty())
        assertTrue(Mfcc().analyse(ShortArray(0)).isEmpty())
    }

    @Test
    fun `mfcc of silence is finite`() {
        // Log of zero energy would be -inf and poison every mean downstream.
        val frames = Mfcc().analyse(ShortArray(16_000))
        assertTrue(frames.isNotEmpty())
        assertTrue(frames.all { f -> f.all { it.isFinite() } })
    }
}
