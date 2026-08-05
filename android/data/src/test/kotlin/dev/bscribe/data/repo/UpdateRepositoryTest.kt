package dev.bscribe.data.repo

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric only because [UpdateInfo.parse] uses android's org.json; the
 * logic under test is otherwise pure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    /** Serves local files, so the download path is exercised without a server. */
    private val fromDisk: (java.net.URL) -> UpdateRepository.Body = { url ->
        val f = File(url.toURI())
        UpdateRepository.Body(f.inputStream(), f.length())
    }

    private val sampleUpdateJson = """
        {
          "versionCode": 12,
          "versionName": "0.2.0-m1a",
          "tag": "v0.2.0-m1a",
          "apkUrl": "https://example.invalid/app-release.apk",
          "sha256": "ABCDEF0123456789"
        }
    """.trimIndent()

    @Test
    fun `parses release metadata and normalises the hash`() {
        val info = UpdateInfo.parse(sampleUpdateJson)
        assertEquals(12, info.versionCode)
        assertEquals("0.2.0-m1a", info.versionName)
        assertEquals("v0.2.0-m1a", info.tag)
        // Hashes are compared case-insensitively; parse lowercases so equality
        // works even if a future workflow emits uppercase.
        assertEquals("abcdef0123456789", info.sha256)
    }

    @Test
    fun `finds the update asset among other release assets`() {
        val release = """
            { "assets": [
              { "name": "app-release.apk",
                "browser_download_url": "https://example.invalid/app.apk" },
              { "name": "update.json",
                "browser_download_url": "https://example.invalid/update.json" }
            ] }
        """.trimIndent()
        assertEquals(
            "https://example.invalid/update.json",
            UpdateRepository.findUpdateJsonUrl(release),
        )
    }

    @Test
    fun `returns null when a release predates in-app updates`() {
        val release = """
            { "assets": [
              { "name": "app-release.apk",
                "browser_download_url": "https://example.invalid/app.apk" }
            ] }
        """.trimIndent()
        assertNull(UpdateRepository.findUpdateJsonUrl(release))
    }

    @Test
    fun `returns null when a release has no assets at all`() {
        assertNull(UpdateRepository.findUpdateJsonUrl("""{ "tag_name": "v1" }"""))
    }

    @Test
    fun `sha256 matches a known vector`() {
        val f = tmp.newFile("payload.bin")
        f.writeText("abc")
        // Well-known SHA-256 of "abc".
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            UpdateRepository.sha256(f),
        )
    }

    @Test
    fun `sha256 differs for a truncated file`() {
        val full = tmp.newFile("full.bin").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        val cut = tmp.newFile("cut.bin").apply { writeBytes(ByteArray(4000) { it.toByte() }) }
        assertTrue(UpdateRepository.sha256(full) != UpdateRepository.sha256(cut))
    }

    @Test
    fun `an unreachable endpoint fails instead of throwing`() = runTest {
        val repo = UpdateRepository(
            cacheDir = tmp.newFolder("cache"),
            currentVersionCode = 8,
            // Reserved TLD, guaranteed not to resolve.
            latestReleaseUrl = "https://bilingual-scribe.invalid/releases/latest",
        )
        repo.check()
        assertTrue(
            repo.state.value is UpdateState.Failed,
            "expected Failed but was ${repo.state.value}",
        )
    }

    @Test
    fun `a corrupt download is rejected and deleted`() = runTest {
        val cache = tmp.newFolder("cache")
        val served = tmp.newFile("served.apk").apply { writeText("pretend apk") }
        val repo = UpdateRepository(cache, currentVersionCode = 1, fetch = fromDisk)

        val info = UpdateInfo(
            versionCode = 2,
            versionName = "0.2.0",
            tag = "v0.2.0",
            apkUrl = served.toURI().toURL().toString(),
            // Deliberately wrong.
            sha256 = "0".repeat(64),
        )
        repo.download(info)

        assertTrue(
            repo.state.value is UpdateState.Failed,
            "expected Failed but was ${repo.state.value}",
        )
        assertTrue(
            File(cache, "update-2.apk").let { !it.exists() },
            "a failed download must not leave a partial APK behind",
        )
    }

    @Test
    fun `a finished download survives a failed check`() = runTest {
        // Losing a completed download because the network blipped would be the
        // wrong trade on a phone connection.
        val cache = tmp.newFolder("cache")
        val served = tmp.newFile("served.apk").apply { writeText("payload") }
        val repo = UpdateRepository(
            cache,
            currentVersionCode = 1,
            latestReleaseUrl = "https://bilingual-scribe.invalid/releases/latest",
            fetch = { url ->
                // The release check must fail; the APK download must not.
                if (url.protocol == "file") fromDisk(url) else error("no network")
            },
        )

        val info = UpdateInfo(
            versionCode = 2,
            versionName = "0.2.0",
            tag = "v0.2.0",
            apkUrl = served.toURI().toURL().toString(),
            sha256 = UpdateRepository.sha256(served),
        )
        repo.download(info)
        assertTrue(repo.ready.value != null, "download should be ready")

        repo.check()
        assertTrue(repo.state.value is UpdateState.Failed, "the check itself failed")
        assertTrue(repo.ready.value != null, "the downloaded build must still be installable")
    }

    @Test
    fun `discarding a download deletes its file`() = runTest {
        val cache = tmp.newFolder("cache")
        val served = tmp.newFile("served.apk").apply { writeText("payload") }
        val repo = UpdateRepository(cache, currentVersionCode = 1, fetch = fromDisk)
        repo.download(
            UpdateInfo(
                versionCode = 2,
                versionName = "0.2.0",
                tag = "v0.2.0",
                apkUrl = served.toURI().toURL().toString(),
                sha256 = UpdateRepository.sha256(served),
            ),
        )
        val apk = repo.ready.value?.apk
        assertTrue(apk != null && apk.exists())

        repo.discardReady()
        assertNull(repo.ready.value)
        assertTrue(!apk!!.exists(), "discarding must not leave the file behind")
    }
}
