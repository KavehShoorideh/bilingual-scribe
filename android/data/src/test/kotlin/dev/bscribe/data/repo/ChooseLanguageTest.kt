package dev.bscribe.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.bscribe.data.db.ScribeDatabase
import dev.bscribe.data.db.TranscriptWordEntity
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Choosing a language for a stretch of audio used to scramble the transcript:
 * `chosen` was flipped between the canonical rows and the per-language
 * variants, whose wordIdx is numbered independently, and the view ordered by
 * wordIdx. These pin the sentence staying a sentence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChooseLanguageTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: ScribeDatabase
    private lateinit var repo: SessionRepository
    private lateinit var recordingId: String

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ScribeDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = SessionRepository(db, tmp.newFolder("audio")) { 1_000L }

        val session = repo.createSession()
        val (recording, _) = repo.startSegment(session.id)
        recordingId = recording.id
        repo.finalizeSegment(recordingId, 6_000)

        // Canonical: five English words across six seconds.
        val canonical = listOf("one", "two", "three", "four", "five")
            .mapIndexed { i, t -> word(t, i, i * 1000L, i * 1000L + 900, variant = "", chosen = true) }
        // The Farsi lane numbers its own words from zero — the collision that
        // caused the scrambling.
        val farsi = listOf("یک", "دو")
            .mapIndexed { i, t ->
                word(t, i, 2000L + i * 1000L, 2000L + i * 1000L + 900, variant = "fa", chosen = false)
            }
        db.transcriptDao().insertAll(canonical + farsi)
    }

    @After
    fun tearDown() = db.close()

    private fun word(
        text: String,
        idx: Int,
        t0: Long,
        t1: Long,
        variant: String,
        chosen: Boolean,
    ) = TranscriptWordEntity(
        recordingId = recordingId,
        wordIdx = idx,
        text = text,
        t0Ms = t0,
        t1Ms = t1,
        prob = 0.9f,
        segmentIdx = idx,
        modelId = "test",
        lang = if (variant == "fa") "fa" else "en",
        variant = variant,
        chosen = chosen,
    )

    @Test
    fun `transcript stays in order after choosing a language`() = runTest {
        repo.chooseLanguage(recordingId, t0Ms = 2000, t1Ms = 4000, lang = "fa")

        val text = repo.observeTranscript(recordingId).first().map { it.text }
        // Words 3 and 4 covered 2000-3900ms and are replaced by the Farsi
        // reading of the same seconds; everything else keeps its place.
        assertEquals(listOf("one", "two", "یک", "دو", "five"), text)
    }

    @Test
    fun `word indices are contiguous after a swap`() = runTest {
        repo.chooseLanguage(recordingId, t0Ms = 2000, t1Ms = 4000, lang = "fa")
        val words = repo.observeTranscript(recordingId).first()
        assertEquals(words.indices.toList(), words.map { it.wordIdx })
    }

    @Test
    fun `words outside the span are untouched`() = runTest {
        repo.chooseLanguage(recordingId, t0Ms = 2000, t1Ms = 4000, lang = "fa")
        val words = repo.observeTranscript(recordingId).first()
        assertEquals("one", words.first().text)
        assertEquals("five", words.last().text)
    }

    @Test
    fun `choosing a language with no reading leaves the transcript alone`() = runTest {
        // Blanking the span would silently delete words the user can still see.
        repo.chooseLanguage(recordingId, t0Ms = 5000, t1Ms = 6000, lang = "fa")
        val text = repo.observeTranscript(recordingId).first().map { it.text }
        assertEquals(listOf("one", "two", "three", "four", "five"), text)
    }

    @Test
    fun `corrections over the replaced span are archived`() = runTest {
        repo.applyCorrection(
            recordingId = recordingId,
            firstWordIdx = 2,
            lastWordIdx = 3,
            t0Ms = 2000,
            t1Ms = 3900,
            originalText = "three four",
            correctedText = "corrected",
        )
        assertEquals(1, repo.observeCorrections(recordingId).first().size)

        repo.chooseLanguage(recordingId, t0Ms = 2000, t1Ms = 4000, lang = "fa")

        assertTrue(
            repo.observeCorrections(recordingId).first().isEmpty(),
            "a correction pointing at words that were replaced must not survive",
        )
    }

    @Test
    fun `a correction elsewhere survives`() = runTest {
        repo.applyCorrection(
            recordingId = recordingId,
            firstWordIdx = 0,
            lastWordIdx = 0,
            t0Ms = 0,
            t1Ms = 900,
            originalText = "one",
            correctedText = "won",
        )
        repo.chooseLanguage(recordingId, t0Ms = 2000, t1Ms = 4000, lang = "fa")
        assertEquals(1, repo.observeCorrections(recordingId).first().size)
    }

    @Test
    fun `the verdict is recorded as a label`() = runTest {
        repo.chooseLanguage(recordingId, t0Ms = 2000, t1Ms = 4000, lang = "fa")
        val feedback = repo.observeLanguageFeedback(recordingId).first()
        assertEquals(1, feedback.size)
        assertEquals("fa", feedback[0].chosenLang)
    }

    @Test
    fun `the variant lanes still show which reading is current`() = runTest {
        repo.chooseLanguage(recordingId, t0Ms = 2000, t1Ms = 4000, lang = "fa")
        val farsiLane = repo.observeAllVariants(recordingId).first().filter { it.variant == "fa" }
        assertTrue(farsiLane.isNotEmpty())
        assertTrue(farsiLane.all { it.chosen }, "the picked lane should read as current")
    }
}
