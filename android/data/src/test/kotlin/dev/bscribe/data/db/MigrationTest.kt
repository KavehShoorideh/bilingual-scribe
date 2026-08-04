package dev.bscribe.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The v1 → v2 migration runs against a database that already holds real
 * recordings. Losing those would break the app's one promise, so this asserts
 * survival of the data, not just that the schema changed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ScribeDatabase::class.java,
    )

    private val dbName = "migration-test.db"

    @Test
    fun `v1 to v2 preserves sessions recordings and transcript words`() {
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL(
                "INSERT INTO sessions (id, title, createdAt, state, languageHint, reviewedAt) " +
                    "VALUES ('s1', 'A note', 1000, 'STOPPED', 'en', NULL)",
            )
            db.execSQL(
                "INSERT INTO recordings " +
                    "(id, sessionId, idx, wavPath, startedWallClock, durationMs, " +
                    "sampleRate, finalized) " +
                    "VALUES ('r1', 's1', 0, 's1/seg000.wav', 1000, 5000, 16000, 1)",
            )
            db.execSQL(
                "INSERT INTO transcript_words " +
                    "(recordingId, wordIdx, text, t0Ms, t1Ms, prob, segmentIdx, modelId) " +
                    "VALUES ('r1', 0, 'hello', 0, 500, 0.9, 0, 'ggml-base:sha256:abc')",
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 2, true, ScribeDatabase.MIGRATION_1_2)

        db.query("SELECT id, title FROM sessions").use { c ->
            assertTrue(c.moveToFirst(), "the session must survive the migration")
            assertEquals("s1", c.getString(0))
            assertEquals("A note", c.getString(1))
        }
        db.query("SELECT wavPath, durationMs FROM recordings").use { c ->
            assertTrue(c.moveToFirst(), "the recording row must survive")
            assertEquals("s1/seg000.wav", c.getString(0))
            assertEquals(5000L, c.getLong(1))
        }
        db.query("SELECT text, lang, avgLogProb FROM transcript_words").use { c ->
            assertTrue(c.moveToFirst(), "existing transcript words must survive")
            assertEquals("hello", c.getString(0))
            // Words written before M1a have no language information; they must
            // read as undetermined rather than being silently labelled English.
            assertEquals("und", c.getString(1))
            assertEquals(0.0f, c.getFloat(2))
        }
    }

    @Test
    fun `v2 accepts language and confidence on new words`() {
        helper.createDatabase(dbName, 1).close()
        val db = helper.runMigrationsAndValidate(dbName, 2, true, ScribeDatabase.MIGRATION_1_2)

        db.execSQL(
            "INSERT INTO sessions (id, title, createdAt, state, languageHint, reviewedAt) " +
                "VALUES ('s1', NULL, 1, 'STOPPED', NULL, NULL)",
        )
        db.execSQL(
            "INSERT INTO recordings " +
                "(id, sessionId, idx, wavPath, startedWallClock, durationMs, " +
                "sampleRate, finalized) VALUES ('r1', 's1', 0, 'p.wav', 1, 1, 16000, 1)",
        )
        db.execSQL(
            "INSERT INTO transcript_words " +
                "(recordingId, wordIdx, text, t0Ms, t1Ms, prob, segmentIdx, modelId, " +
                "lang, avgLogProb) " +
                "VALUES ('r1', 0, 'سلام', 0, 400, 0.88, 0, 'm', 'fa', -0.31)",
        )

        db.query("SELECT lang, avgLogProb FROM transcript_words").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("fa", c.getString(0))
            assertEquals(-0.31f, c.getFloat(1), absoluteTolerance = 0.0001f)
        }
    }

    @Test
    fun `an empty v1 database migrates cleanly`() {
        helper.createDatabase(dbName, 1).close()
        helper.runMigrationsAndValidate(dbName, 2, true, ScribeDatabase.MIGRATION_1_2)
        // runMigrationsAndValidate throws if the resulting schema does not
        // match the exported v2 JSON, so reaching here is the assertion.
    }

    @Test
    fun `v1 through v3 keeps notes and leaves them out of the trash`() {
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL(
                "INSERT INTO sessions (id, title, createdAt, state, languageHint, reviewedAt) " +
                    "VALUES ('s1', 'Old note', 1000, 'STOPPED', 'en', NULL)",
            )
        }

        val db = helper.runMigrationsAndValidate(
            dbName, 6, true,
            ScribeDatabase.MIGRATION_1_2,
            ScribeDatabase.MIGRATION_2_3,
            ScribeDatabase.MIGRATION_3_4,
            ScribeDatabase.MIGRATION_4_5,
            ScribeDatabase.MIGRATION_5_6,
        )

        db.query("SELECT title, deletedAt FROM sessions").use { c ->
            assertTrue(c.moveToFirst(), "the note must survive two migrations")
            assertEquals("Old note", c.getString(0))
            // Existing notes are live, not trashed — a migration must never
            // sweep someone's recordings into a bin they didn't ask for.
            assertTrue(c.isNull(1), "deletedAt must default to NULL")
        }
    }

    @Test
    fun `v1 through v4 preserves a note and its transcript`() {
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL(
                "INSERT INTO sessions (id, title, createdAt, state, languageHint, reviewedAt) " +
                    "VALUES ('s1', 'Kept', 1000, 'TRANSCRIBED', 'mixed', NULL)",
            )
            db.execSQL(
                "INSERT INTO recordings " +
                    "(id, sessionId, idx, wavPath, startedWallClock, durationMs, " +
                    "sampleRate, finalized) " +
                    "VALUES ('r1', 's1', 0, 's1/seg000.wav', 1000, 5000, 16000, 1)",
            )
            db.execSQL(
                "INSERT INTO transcript_words " +
                    "(recordingId, wordIdx, text, t0Ms, t1Ms, prob, segmentIdx, modelId) " +
                    "VALUES ('r1', 0, 'سلام', 0, 500, 0.9, 0, 'm')",
            )
        }

        val db = helper.runMigrationsAndValidate(
            dbName, 6, true,
            ScribeDatabase.MIGRATION_1_2,
            ScribeDatabase.MIGRATION_2_3,
            ScribeDatabase.MIGRATION_3_4,
            ScribeDatabase.MIGRATION_4_5,
            ScribeDatabase.MIGRATION_5_6,
        )

        db.query("SELECT title, deletedAt, transcribeWallMs FROM sessions").use { c ->
            assertTrue(c.moveToFirst(), "the note must survive three migrations")
            assertEquals("Kept", c.getString(0))
            assertTrue(c.isNull(1), "must not be trashed by a migration")
            // Notes transcribed before timing existed have no timing to report,
            // which must read as unknown rather than as an instantaneous pass.
            assertTrue(c.isNull(2), "timing must be NULL, not 0")
        }
        db.query("SELECT text FROM transcript_words").use { c ->
            assertTrue(c.moveToFirst(), "transcript words must survive")
            assertEquals("سلام", c.getString(0))
        }
    }

    @Test
    fun `v3 round-trips a trashed and restored note`() {
        helper.createDatabase(dbName, 1).close()
        val db = helper.runMigrationsAndValidate(
            dbName, 6, true,
            ScribeDatabase.MIGRATION_1_2,
            ScribeDatabase.MIGRATION_2_3,
            ScribeDatabase.MIGRATION_3_4,
            ScribeDatabase.MIGRATION_4_5,
            ScribeDatabase.MIGRATION_5_6,
        )

        db.execSQL(
            "INSERT INTO sessions (id, title, createdAt, state, languageHint, " +
                "reviewedAt, deletedAt) VALUES ('s1', NULL, 1, 'STOPPED', NULL, NULL, 9999)",
        )
        db.query("SELECT deletedAt FROM sessions WHERE id = 's1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(9999L, c.getLong(0))
        }

        db.execSQL("UPDATE sessions SET deletedAt = NULL WHERE id = 's1'")
        db.query("SELECT deletedAt FROM sessions WHERE id = 's1'").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0), "restoring must clear deletedAt")
        }
    }
}
