package dev.bscribe.asr

import java.io.File
import java.security.MessageDigest

/**
 * Naming and identity for on-device whisper models.
 *
 * Models live in `<filesDir>/models/` as `.bin` files (ggml format). Note the
 * path is spelled out rather than globbed: Kotlin block comments nest, so a
 * literal slash-star inside this KDoc would swallow the rest of the file.
 * A model's identity
 * string — recorded on every transcript word and export — is
 * `<filename>:sha256:<first 12 hex chars>` so a transcript can always be
 * traced to the exact weights that produced it, including personal fine-tunes
 * imported from the trainer.
 */
object ModelFiles {

    const val DIR_NAME = "models"

    /** Stock models the app can fetch on first run (M1a) or the user sideloads. */
    val STOCK_LIVE = "ggml-tiny-q5_1.bin"
    val STOCK_FINAL_DEFAULT = "ggml-base-q5_1.bin"
    val STOCK_FINAL_QUALITY = "ggml-small-q5_1.bin"

    fun modelsDir(filesDir: File): File = File(filesDir, DIR_NAME).apply { mkdirs() }

    fun listModels(filesDir: File): List<File> =
        modelsDir(filesDir).listFiles { f -> f.isFile && f.extension == "bin" }
            ?.sortedBy { it.name } ?: emptyList()

    fun modelId(file: File): String = "${file.name}:sha256:${sha256Prefix(file)}"

    private fun sha256Prefix(file: File, hexChars: Int = 12): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(hexChars)
    }
}
