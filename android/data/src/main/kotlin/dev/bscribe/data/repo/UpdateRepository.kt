package dev.bscribe.data.repo

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * What a published release says about itself.
 *
 * The GitHub API exposes a tag name but not a versionCode, so releases carry
 * an `update.json` asset produced by the release workflow. [sha256] is the
 * whole point of the file: it lets the app reject a truncated download before
 * handing it to the package installer, where the failure would be opaque.
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val tag: String,
    val apkUrl: String,
    val sha256: String,
) {
    companion object {
        fun parse(json: String): UpdateInfo {
            val o = JSONObject(json)
            return UpdateInfo(
                versionCode = o.getInt("versionCode"),
                versionName = o.getString("versionName"),
                tag = o.optString("tag", ""),
                apkUrl = o.getString("apkUrl"),
                sha256 = o.getString("sha256").lowercase(),
            )
        }
    }
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val currentVersionCode: Int) : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateState
    data class ReadyToInstall(val info: UpdateInfo, val apk: File) : UpdateState
    data class Failed(val reason: String) : UpdateState
}

/**
 * Checks GitHub Releases for a newer build, downloads it, and verifies it.
 *
 * Deliberately stops at a verified file on disk — installing is the caller's
 * job, because the decision to restart this process belongs to whoever knows
 * whether a recording is in progress (requirement #1: never lose an idea).
 *
 * Uses [HttpURLConnection] rather than pulling in an HTTP client: this is two
 * requests in the whole app, and a dependency would cost more than it saves.
 */
class UpdateRepository(
    private val cacheDir: File,
    private val currentVersionCode: Int,
    private val latestReleaseUrl: String = DEFAULT_LATEST_RELEASE_URL,
) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    fun reset() { _state.value = UpdateState.Idle }

    suspend fun check() = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Checking
        try {
            val assetUrl = findUpdateJsonUrl(readText(URL(latestReleaseUrl)))
                ?: return@withContext fail(
                    "This release has no update.json — it predates in-app updates.",
                )
            val info = UpdateInfo.parse(readText(URL(assetUrl)))
            _state.value = if (info.versionCode > currentVersionCode) {
                UpdateState.Available(info)
            } else {
                UpdateState.UpToDate(currentVersionCode)
            }
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun download(info: UpdateInfo) = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Downloading(info, 0)
        val target = File(cacheDir, "update-${info.versionCode}.apk")
        try {
            target.delete()
            val conn = open(URL(info.apkUrl))
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(1 shl 16)
                    var read = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = ((read * 100) / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                _state.value = UpdateState.Downloading(info, pct)
                            }
                        }
                    }
                }
            }

            val actual = sha256(target)
            if (!actual.equals(info.sha256, ignoreCase = true)) {
                target.delete()
                return@withContext fail(
                    "Download is corrupt (checksum mismatch). Not installing.",
                )
            }
            _state.value = UpdateState.ReadyToInstall(info, target)
        } catch (e: Exception) {
            target.delete()
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun fail(reason: String) {
        _state.value = UpdateState.Failed(reason)
    }

    // ---- plumbing ----

    private fun open(url: URL): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "bilingual-scribe")
            if (responseCode !in 200..299) {
                val code = responseCode
                disconnect()
                throw IllegalStateException("HTTP $code from $url")
            }
        }

    private fun readText(url: URL): String {
        val conn = open(url)
        return try {
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val DEFAULT_LATEST_RELEASE_URL =
            "https://api.github.com/repos/KavehShoorideh/bilingual-scribe/releases/latest"

        /** Pulls the browser_download_url of the `update.json` asset, if present. */
        fun findUpdateJsonUrl(releaseJson: String): String? {
            val assets = JSONObject(releaseJson).optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name") == "update.json") {
                    return asset.optString("browser_download_url").ifEmpty { null }
                }
            }
            return null
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
