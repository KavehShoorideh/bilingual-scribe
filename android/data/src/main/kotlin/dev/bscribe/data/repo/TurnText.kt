package dev.bscribe.data.repo

import dev.bscribe.data.db.TranscriptWordEntity

/**
 * Joins words into editable text, separating speaking turns with a blank line.
 *
 * A blank line rather than a written marker: the text is diffed word by word
 * to recover corrections, and any literal "new voice" text would be compared
 * as though someone had said it. Whitespace carries the information without
 * entering the transcript.
 */
fun buildTurnText(words: List<TranscriptWordEntity>): String {
    if (words.isEmpty()) return ""
    val out = StringBuilder()
    var turn = words.first().turnIdx
    for ((i, w) in words.withIndex()) {
        if (i > 0) out.append(if (w.turnIdx != turn) "\n\n" else " ")
        turn = w.turnIdx
        out.append(w.text)
    }
    return out.toString()
}

/** Splits editable text back into its turns, on blank lines. */
fun splitTurns(text: String): List<String> =
    text.split(Regex("\\n\\s*\\n")).map { it.trim() }

/** Rejoins turns into the stored form. */
fun joinTurns(turns: List<String>): String = turns.joinToString("\n\n")
