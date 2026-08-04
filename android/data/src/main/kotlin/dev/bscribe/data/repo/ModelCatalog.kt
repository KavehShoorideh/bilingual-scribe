package dev.bscribe.data.repo

/**
 * A downloadable ggml model.
 *
 * [sha256] is not optional book-keeping: a truncated ggml file does not fail
 * cleanly when whisper loads it, it crashes the process inside native code.
 * Verification before the file is ever handed to the engine turns an obscure
 * SIGSEGV into a retry.
 */
data class ModelSpec(
    val fileName: String,
    val label: String,
    val description: String,
    val sizeBytes: Long,
    val sha256: String,
    val url: String,
) {
    val sizeMb: Int get() = (sizeBytes / 1_000_000).toInt()
}

/**
 * The stock multilingual models. `.en` variants are deliberately absent —
 * their tokenizer cannot represent Farsi at all, and the fine-tuning path in
 * M2 needs the multilingual vocabulary.
 *
 * Hashes are the HuggingFace LFS digests, verified against a real download.
 */
object ModelCatalog {

    private const val BASE_URL =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

    val TINY = ModelSpec(
        fileName = "ggml-tiny-q5_1.bin",
        label = "Tiny",
        description = "Fastest, roughest. Intended for the live transcript (M1b).",
        sizeBytes = 32_152_673,
        sha256 = "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7",
        url = "$BASE_URL/ggml-tiny-q5_1.bin",
    )

    val BASE = ModelSpec(
        fileName = "ggml-base-q5_1.bin",
        label = "Base",
        description = "Default for the final pass — good quality at close to real time.",
        sizeBytes = 59_707_625,
        sha256 = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898",
        url = "$BASE_URL/ggml-base-q5_1.bin",
    )

    val SMALL = ModelSpec(
        fileName = "ggml-small-q5_1.bin",
        label = "Small",
        description = "Best accuracy, noticeably slower and hotter. Better on Farsi.",
        sizeBytes = 190_085_487,
        sha256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
        url = "$BASE_URL/ggml-small-q5_1.bin",
    )

    val all: List<ModelSpec> = listOf(TINY, BASE, SMALL)

    /** What the final pass uses unless settings say otherwise. */
    val defaultFinal: ModelSpec = BASE

    fun byFileName(name: String): ModelSpec? = all.firstOrNull { it.fileName == name }
}
