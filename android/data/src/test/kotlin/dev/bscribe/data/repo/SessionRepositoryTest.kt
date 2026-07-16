package dev.bscribe.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.bscribe.core.audio.WavFileWriter
import dev.bscribe.core.audio.WavSpec
import dev.bscribe.core.model.SessionState
import dev.bscribe.data.db.ScribeDatabase
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end repository behavior on a real (in-memory) Room DB via Robolectric,
 * including the crash-recovery path over real files.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Robolectric 4.14 does not support API 36 yet
class SessionRepositoryTest {

    private lateinit var db: ScribeDatabase
    private lateinit var audioRoot: File
    private lateinit var repo: SessionRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ScribeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        audioRoot = File.createTempFile("audio", "").let { f -> f.delete(); f.mkdirs(); f }
        repo = SessionRepository(db, audioRoot, clock = { 1_000_000L })
    }

    @After
    fun tearDown() {
        db.close()
        audioRoot.deleteRecursively()
    }

    @Test
    fun `pause-resume creates sequential segments with distinct files`() = runTest {
        val session = repo.createSession()
        val (seg0, file0) = repo.startSegment(session.id)
        repo.finalizeSegment(seg0.id, durationMs = 1500)
        repo.setState(session.id, SessionState.PAUSED)

        val (seg1, file1) = repo.startSegment(session.id)
        repo.finalizeSegment(seg1.id, durationMs = 800)

        assertEquals(0, seg0.idx)
        assertEquals(1, seg1.idx)
        assertTrue(file0.path != file1.path)
        assertTrue(file0.path.contains(session.id))

        val recs = repo.recordingsOf(session.id)
        assertEquals(listOf(0, 1), recs.map { it.idx })
        assertEquals(listOf(1500L, 800L), recs.map { it.durationMs })
        assertTrue(recs.all { it.finalized })
    }

    @Test
    fun `recoverInterrupted repairs crashed wav and parks session`() = runTest {
        val session = repo.createSession()
        val (seg, file) = repo.startSegment(session.id)

        // Write 2 s of audio and "crash" without finalizing: stale header.
        val writer = WavFileWriter(file, WavSpec.SCRIBE_DEFAULT)
        writer.append(ShortArray(32_000) { 3 })
        val crashedBytes = file.readBytes()
        writer.close()
        file.writeBytes(crashedBytes) // restore the stale-header state

        val report = repo.recoverInterrupted()

        assertEquals(1, report.repairedSegments)
        assertEquals(1, report.recoveredSessions)
        val recovered = repo.recordingsOf(session.id).single()
        assertTrue(recovered.finalized)
        assertEquals(2000L, recovered.durationMs)
        assertEquals(SessionState.STOPPED, repo.session(session.id)!!.state)
    }

    @Test
    fun `recoverInterrupted tolerates segment that never hit disk`() = runTest {
        val session = repo.createSession()
        repo.startSegment(session.id) // file never created

        val report = repo.recoverInterrupted()

        assertEquals(0, report.repairedSegments)
        assertEquals(1, report.emptySegments)
        val rec = repo.recordingsOf(session.id).single()
        assertTrue(rec.finalized)
        assertEquals(0L, rec.durationMs)
    }

    @Test
    fun `deleteSession removes rows and audio files`() = runTest {
        val session = repo.createSession()
        val (_, file) = repo.startSegment(session.id)
        WavFileWriter(file).use { it.append(ShortArray(160)) }
        assertTrue(file.exists())

        repo.deleteSession(session.id)

        assertFalse(file.exists())
        assertEquals(null, repo.session(session.id))
        assertTrue(repo.observeSessions().first().isEmpty())
    }

    @Test
    fun `observeSessions orders newest first`() = runTest {
        var now = 1_000L
        val repo2 = SessionRepository(db, audioRoot, clock = { now })
        val a = repo2.createSession()
        now = 2_000L
        val b = repo2.createSession()

        val list = repo2.observeSessions().first()
        assertEquals(listOf(b.id, a.id), list.map { it.session.id })
    }
}
