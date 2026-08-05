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
    /**
     * How bytes are fetched. Injectable so the download path — verification,
     * cleanup, what survives a failure — can be tested without a network or a
     * stub server. The default is the only implementation that ships.
     */
    private val fetch: (URL) -> Body = ::httpBody,
) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /**
     * A verified build waiting to be installed.
     *
     * Kept apart from [state] so checking again does not throw away a download
     * you already have: releases can land while an earlier one is sitting
     * downloaded, and losing the finished file to look for a newer one would
     * be the wrong trade on a phone connection.
     */
    private val _ready = MutableStateFlow<UpdateState.ReadyToInstall?>(null)
    val ready: StateFlow<UpdateState.ReadyToInstall?> = _ready.asStateFlow()

    fun reset() { _state.value = UpdateState.Idle }

    /** Discards a downloaded build, deleting its file. */
    fun discardReady() {
        _ready.value?.apk?.delete()
        _ready.value = null
    }

    suspend fun check() = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Checking
        try {
            val assetUrl = findUpdateJsonUrl(readText(URL(latestReleaseUrl)))
                ?: return@withContext fail(
                    "This release has no update.json — it predates in-app updates.",
                )
            val info = UpdateInfo.parse(readText(URL(assetUrl)))
            val alreadyDownloaded = _ready.value

            _state.value = when {
                info.versionCode <= currentVersionCode -> UpdateState.UpToDate(currentVersionCode)

                // Newer than the build sitting downloaded: that file is now
                // stale, so drop it rather than offering to install a version
                // that is no longer the latest.
                alreadyDownloaded != null && info.versionCode > alreadyDownloaded.info.versionCode -> {
                    discardReady()
                    UpdateState.Available(info)
                }

                alreadyDownloaded != null -> UpdateState.UpToDate(currentVersionCode)

                else -> UpdateState.Available(info)
            }
        } catch (e: Exception) {
            // A failed check must not cost a finished download; [ready] is
            // untouched, so Install stays available while this reports why the
            // check could not run.
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun download(info: UpdateInfo) = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Downloading(info, 0)
        val target = File(cacheDir, "update-${info.versionCode}.apk")
        try {
            target.delete()
            val body = fetch(URL(info.apkUrl))
            val total = body.lengthBytes
            body.stream.use { input ->
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
            val readyState = UpdateState.ReadyToInstall(info, target)
            _ready.value = readyState
            _state.value = readyState
        } catch (e: Exception) {
            target.delete()
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun fail(reason: String) {
        _state.value = UpdateState.Failed(reason)
    }

    // ---- plumbing ----

    private fun readText(url: URL): String =
        fetch(url).stream.use { it.bufferedReader().readText() }

    /** A response body and its length, or -1 when the server does not say. */
    data class Body(val stream: java.io.InputStream, val lengthBytes: Long)

    companion object {
        /** The shipping fetcher: plain HTTP, no caching. */
        fun httpBody(url: URL): Body {
            val conn = open(url)
            return Body(conn.inputStream, conn.contentLengthLong)
        }

        private fun open(url: URL): HttpURLConnection =
            (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "bilingual-scribe")
                // Ask for the newest release, not a cached view of it: GitHub
                // and any intermediary will happily serve a minutes-old
                // answer, which is long enough to miss a release entirely.
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Pragma", "no-cache")
                useCaches = false
                if (responseCode !in 200..299) {
                    val code = responseCode
                    disconnect()
                    throw IllegalStateException("HTTP $code from $url")
                }
            }

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
