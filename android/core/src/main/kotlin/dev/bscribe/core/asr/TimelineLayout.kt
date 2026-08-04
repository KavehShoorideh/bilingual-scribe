package dev.bscribe.core.asr

/**
 * Places words along a time axis without letting them overlap.
 *
 * Position by time alone does not work: speech runs at three or four words a
 * second, so a word occupies a few tens of milliseconds of the timeline but
 * needs far more width than that to be legible. Laying out purely by start
 * time therefore piles words on top of each other exactly when there is most
 * to read.
 *
 * So time sets where a word *wants* to be, and collision resolution decides
 * where it goes: each word starts no earlier than the end of the one before
 * it. Dense speech drifts right of true time, and every pause pulls it back,
 * because the next word's time-derived position overtakes the accumulated
 * drift. Alignment stays honest at the scale that matters — which utterance
 * you are hearing — while remaining readable.
 */
object TimelineLayout {

    /** A word with the width it will occupy once drawn. */
    data class Item(val t0Ms: Long, val t1Ms: Long, val widthPx: Float)

    /** Where a word ends up on the lane's own axis. */
    data class Placed(val index: Int, val xPx: Float, val widthPx: Float)

    /**
     * @param pxPerMs horizontal scale of the timeline.
     * @param gapPx minimum space between neighbouring words.
     */
    fun place(
        items: List<Item>,
        pxPerMs: Float,
        gapPx: Float = DEFAULT_GAP_PX,
    ): List<Placed> {
        val out = ArrayList<Placed>(items.size)
        var cursor = Float.NEGATIVE_INFINITY
        for ((i, item) in items.withIndex()) {
            val wanted = item.t0Ms * pxPerMs
            val x = if (cursor.isFinite()) maxOf(wanted, cursor) else wanted
            out += Placed(i, x, item.widthPx)
            cursor = x + item.widthPx + gapPx
        }
        return out
    }

    /**
     * Where the playhead sits on the same axis.
     *
     * Interpolated through the placed words rather than computed from time
     * directly, so the marker stays on the word being spoken even where
     * collision resolution has pushed the lane out of strict time.
     */
    fun playheadX(
        items: List<Item>,
        placed: List<Placed>,
        nowMs: Long,
        pxPerMs: Float,
    ): Float {
        if (items.isEmpty() || placed.isEmpty()) return nowMs * pxPerMs

        // Before the first word: fall back to true time so the lane still
        // moves during a silent lead-in.
        val first = items.first()
        if (nowMs <= first.t0Ms) {
            return placed.first().xPx - (first.t0Ms - nowMs) * pxPerMs
        }

        for (i in items.indices) {
            val item = items[i]
            val start = item.t0Ms
            val end = maxOf(item.t1Ms, start + 1)
            if (nowMs in start..end) {
                val fraction = (nowMs - start).toFloat() / (end - start).toFloat()
                return placed[i].xPx + fraction * placed[i].widthPx
            }
            // In the gap between this word and the next.
            val next = items.getOrNull(i + 1) ?: continue
            if (nowMs > end && nowMs < next.t0Ms) {
                val span = (next.t0Ms - end).toFloat().coerceAtLeast(1f)
                val fraction = (nowMs - end).toFloat() / span
                val from = placed[i].xPx + placed[i].widthPx
                val to = placed[i + 1].xPx
                return from + fraction * (to - from)
            }
        }

        // Past the last word.
        val last = items.last()
        val lastPlaced = placed.last()
        return lastPlaced.xPx + lastPlaced.widthPx + (nowMs - last.t1Ms) * pxPerMs
    }

    /** Roughly a character's width; enough to keep neighbours distinct. */
    const val DEFAULT_GAP_PX = 10f
}
