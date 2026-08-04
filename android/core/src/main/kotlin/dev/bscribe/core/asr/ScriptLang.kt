package dev.bscribe.core.asr

/** Language of a single word, as far as its script can tell us. */
enum class Lang {
    EN,
    FA,

    /** No letters to judge by — digits, punctuation, symbols. */
    UND,
    ;

    val code: String get() = when (this) {
        EN -> "en"
        FA -> "fa"
        UND -> "und"
    }
}

/**
 * Per-word language classification by Unicode script.
 *
 * Whisper has no per-word language head — it predicts one language token per
 * decode and conditions the whole pass on it. For Farsi and English that
 * limitation doesn't matter, because the two languages don't share a script:
 * anything whisper emits in Arabic script is Farsi, anything in Latin is
 * English. That makes classification exact and free rather than probabilistic.
 *
 * The one thing this cannot see through is a decode that was *forced* to the
 * wrong language: whisper told to transcribe Farsi audio as English will
 * transliterate into Latin, and the script signal is destroyed. That is why
 * [dev.bscribe.core.asr.DecodeArbiter] picks the decode language per window
 * instead of forcing one for the whole file.
 */
object ScriptLang {

    /**
     * Classifies a token by counting its letters. Ties and letterless tokens
     * are [Lang.UND]; the caller resolves those to whatever language its
     * window decoded as.
     */
    fun ofWord(text: String): Lang {
        var fa = 0
        var en = 0
        for (ch in text) {
            when {
                isArabicScript(ch) -> fa++
                isLatinLetter(ch) -> en++
            }
        }
        return when {
            fa > en -> Lang.FA
            en > fa -> Lang.EN
            else -> Lang.UND
        }
    }

    /**
     * Summarises a whole session for `language_hint` in the export bundle
     * (see docs/dataset-format.md), which accepts en | fa | mixed | und.
     *
     * [Lang.UND] words are ignored rather than counted as evidence — a note
     * that is all numerals shouldn't read as "mixed".
     */
    fun ofSession(langs: List<Lang>): String {
        val fa = langs.count { it == Lang.FA }
        val en = langs.count { it == Lang.EN }
        val total = fa + en
        if (total == 0) return "und"
        val faShare = fa.toDouble() / total
        return when {
            faShare >= MAJORITY -> "fa"
            faShare <= 1 - MAJORITY -> "en"
            else -> "mixed"
        }
    }

    /**
     * Persian uses the Arabic script plus four letters of its own (پ چ ژ گ),
     * and its own digit forms. Includes the presentation-form blocks because
     * some tokenizers emit those rather than the canonical letters.
     */
    private fun isArabicScript(ch: Char): Boolean = when (ch.code) {
        in 0x0600..0x06FF -> true // Arabic (incl. Persian letters and digits)
        in 0x0750..0x077F -> true // Arabic Supplement
        in 0x08A0..0x08FF -> true // Arabic Extended-A
        in 0xFB50..0xFDFF -> true // Arabic Presentation Forms-A
        in 0xFE70..0xFEFF -> true // Arabic Presentation Forms-B
        else -> false
    }

    private fun isLatinLetter(ch: Char): Boolean =
        ch.isLetter() && ch.code < 0x0250 // Basic Latin through Latin Extended-B

    /** Above this share of one language, the session counts as that language. */
    private const val MAJORITY = 0.9
}
