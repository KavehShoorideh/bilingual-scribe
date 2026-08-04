package dev.bscribe.data.repo

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

sealed interface ModelStatus {
    data object Absent : ModelStatus
    data class Downloading(val percent: Int) : ModelStatus
    data class Installed(val file: File) : ModelStatus
    data class Failed(val reason: String) : ModelStatus
}

/**
 * Downloads and verifies whisper models into `<filesDir>/models`.
 *
 * Downloads resume: a partial file is kept as `<name>.part` and continued with
 * a Range request. A 190 MB model over a phone connection is long enough that
 * starting from zero after every interruption is not acceptable.
 */
class ModelRepository(private val filesDir: File) {

    private val _statuses = MutableStateFlow<Map<String, ModelStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, ModelStatus>> = _statuses.asStateFlow()

    fun modelsDir(): File = File(filesDir, "models").apply { mkdirs() }

    fun fileFor(spec: ModelSpec): File = File(modelsDir(), spec.fileName)

    fun isInstalled(spec: ModelSpec): Boolean =
        fileFor(spec).let { it.isFile && it.length() == spec.sizeBytes }

    /** Refreshes every model's status from what is actually on disk. */
    fun refresh() {
        _statuses.value = ModelCatalog.all.associate { spec ->
            spec.fileName to if (isInstalled(spec)) {
                ModelStatus.Installed(fileFor(spec))
            } else {
                ModelStatus.Absent
            }
        }
    }

    fun installedModels(): List<ModelSpec> = ModelCatalog.all.filter { isInstalled(it) }

    suspend fun delete(spec: ModelSpec) = withContext(Dispatchers.IO) {
        fileFor(spec).delete()
        partFor(spec).delete()
        setStatus(spec, ModelStatus.Absent)
    }

    suspend fun download(spec: ModelSpec) = withContext(Dispatchers.IO) {
        if (isInstalled(spec)) {
            setStatus(spec, ModelStatus.Installed(fileFor(spec)))
            return@withContext
        }

        val part = partFor(spec)
        val target = fileFor(spec)
        try {
            var have = if (part.isFile) part.length() else 0L
            if (have > spec.sizeBytes) {
                // Corrupt or from a different revision — start over.
                part.delete()
                have = 0L
            }

            setStatus(spec, ModelStatus.Downloading(percentOf(have, spec.sizeBytes)))

            val conn = (URL(spec.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "bilingual-scribe")
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }

            val code = conn.responseCode
            // 206 honours the range; 200 means the server ignored it and is
            // sending the whole file, so any partial data must be discarded.
            val resuming = code == HttpURLConnection.HTTP_PARTIAL
            if (!resuming && code != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                setStatus(spec, ModelStatus.Failed("Server returned HTTP $code"))
                return@withContext
            }
            if (!resuming) have = 0L

            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, resuming).use { output ->
                    val buf = ByteArray(1 shl 16)
                    var written = have
                    var lastPct = -1
                    while (true) {
                        if (!currentCoroutineContext().isActive) throw CancellationException()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        written += n
                        val pct = percentOf(written, spec.sizeBytes)
                        if (pct != lastPct) {
                            lastPct = pct
                            setStatus(spec, ModelStatus.Downloading(pct))
                        }
                    }
                }
            }
            conn.disconnect()

            if (part.length() != spec.sizeBytes) {
                part.delete()
                setStatus(spec, ModelStatus.Failed("Download was truncated. Try again."))
                return@withContext
            }

            val actual = sha256(part)
            if (!actual.equals(spec.sha256, ignoreCase = true)) {
                part.delete()
                setStatus(
                    spec,
                    ModelStatus.Failed("Checksum mismatch — the file is corrupt."),
                )
                return@withContext
            }

            if (!part.renameTo(target)) {
                part.delete()
                setStatus(spec, ModelStatus.Failed("Could not move the model into place."))
                return@withContext
            }
            setStatus(spec, ModelStatus.Installed(target))
        } catch (e: CancellationException) {
            // Leave the .part file: the next attempt resumes from it.
            setStatus(spec, ModelStatus.Absent)
            throw e
        } catch (e: Exception) {
            setStatus(spec, ModelStatus.Failed(e.message ?: e.javaClass.simpleName))
        }
    }

    /**
     * Installs a model the user obtained elsewhere — a browser download, a
     * file transfer, or a personal fine-tune from the trainer.
     *
     * This is the escape hatch when the app itself cannot reach the network,
     * and on a privacy-first app it is arguably the more natural path anyway:
     * the phone never has to talk to HuggingFace at all.
     *
     * @param open supplies the bytes; the caller owns the URI and its lifetime.
     */
    suspend fun importFrom(
        spec: ModelSpec,
        open: () -> java.io.InputStream?,
    ): Unit = withContext(Dispatchers.IO) {
        val part = partFor(spec)
        val target = fileFor(spec)
        try {
            setStatus(spec, ModelStatus.Downloading(0))
            part.delete()

            val input = open()
            if (input == null) {
                setStatus(spec, ModelStatus.Failed("Could not read that file."))
                return@withContext
            }

            input.use { source ->
                part.outputStream().use { output ->
                    val buf = ByteArray(1 shl 16)
                    var written = 0L
                    var lastPct = -1
                    while (true) {
                        if (!currentCoroutineContext().isActive) throw CancellationException()
                        val n = source.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        written += n
                        // Size is known from the catalog, so progress is real
                        // even though the source stream may not report length.
                        val pct = percentOf(written, spec.sizeBytes)
                        if (pct != lastPct) {
                            lastPct = pct
                            setStatus(spec, ModelStatus.Downloading(pct))
                        }
                    }
                }
            }

            if (part.length() != spec.sizeBytes) {
                val got = part.length()
                part.delete()
                setStatus(
                    spec,
                    ModelStatus.Failed(
                        "That file is ${got / 1_000_000} MB but ${spec.label} should be " +
                            "${spec.sizeMb} MB. Wrong file, or the download was incomplete.",
                    ),
                )
                return@withContext
            }

            val actual = sha256(part)
            if (!actual.equals(spec.sha256, ignoreCase = true)) {
                part.delete()
                setStatus(
                    spec,
                    ModelStatus.Failed(
                        "That file is the right size but its contents don't match " +
                            "${spec.label}. Not installing it.",
                    ),
                )
                return@withContext
            }

            if (!part.renameTo(target)) {
                part.delete()
                setStatus(spec, ModelStatus.Failed("Could not move the model into place."))
                return@withContext
            }
            setStatus(spec, ModelStatus.Installed(target))
        } catch (e: CancellationException) {
            part.delete()
            setStatus(spec, ModelStatus.Absent)
            throw e
        } catch (e: Exception) {
            part.delete()
            setStatus(spec, ModelStatus.Failed(e.message ?: e.javaClass.simpleName))
        }
    }

    private fun partFor(spec: ModelSpec) = File(modelsDir(), spec.fileName + ".part")

    private fun setStatus(spec: ModelSpec, status: ModelStatus) {
        _statuses.value = _statuses.value + (spec.fileName to status)
    }

    companion object {
        internal fun percentOf(done: Long, total: Long): Int =
            if (total <= 0) 0 else ((done * 100) / total).toInt().coerceIn(0, 100)

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
