package dev.bscribe.core.asr

/**
 * Places two readings of the same audio on one horizontal axis.
 *
 * Two constraints pull against each other. Words must not overlap: speech runs
 * three or four words a second, so a word occupies a few tens of milliseconds
 * of timeline but needs far more width than that to be legible. And the same
 * instant must land at the same place in both lanes, or comparing them is
 * meaningless — which is the whole purpose of showing them together.
 *
 * Resolving collisions per lane satisfies the first and breaks the second: a
 * dense lane drifts far to the right of a sparse one, and the marker then
 * shows two different moments. So the mapping from time to position is built
 * once, from both lanes at once, giving every interval enough room for
 * whichever lane needs more. Both lanes then share it exactly.
 *
 * The cost is that the axis is not linear in time — a busy second is wider
 * than a quiet one. That is the right trade: alignment between the lanes is
 * what the view is for, and a uniform scale is not.
 */
object TimelineLayout {

    /** A word with the width it will occupy once drawn. */
    data class Item(val t0Ms: Long, val t1Ms: Long, val widthPx: Float)

    /** Where a word ends up on the shared axis. */
    data class Placed(val index: Int, val xPx: Float, val widthPx: Float)

    /**
     * A monotonic, piecewise-linear map from time to horizontal position,
     * shared by every lane so equal times land at equal positions.
     */
    class Mapping internal constructor(
        private val times: LongArray,
        private val xs: FloatArray,
        private val pxPerMs: Float,
    ) {
        fun xOf(timeMs: Long): Float {
            if (times.isEmpty()) return timeMs * pxPerMs
            if (timeMs <= times.first()) {
                return xs.first() - (times.first() - timeMs) * pxPerMs
            }
            if (timeMs >= times.last()) {
                return xs.last() + (timeMs - times.last()) * pxPerMs
            }
            // Binary search for the interval containing timeMs.
            var lo = 0
            var hi = times.size - 1
            while (lo + 1 < hi) {
                val mid = (lo + hi) / 2
                if (times[mid] <= timeMs) lo = mid else hi = mid
            }
            val span = (times[hi] - times[lo]).toFloat()
            if (span <= 0f) return xs[lo]
            val fraction = (timeMs - times[lo]).toFloat() / span
            return xs[lo] + fraction * (xs[hi] - xs[lo])
        }
    }

    data class Layout(val mapping: Mapping, val lanes: List<List<Placed>>)

    /**
     * @param lanes each lane's words, in time order.
     * @param pxPerMs the scale where nothing is competing for space.
     * @param gapPx minimum space between neighbouring words in a lane.
     */
    fun place(
        lanes: List<List<Item>>,
        pxPerMs: Float,
        gapPx: Float = DEFAULT_GAP_PX,
    ): Layout {
        // Every word start from every lane becomes a breakpoint, so between
        // two consecutive breakpoints each lane starts at most one word — and
        // giving that interval room for the widest of them is enough to keep
        // every lane collision-free.
        val breakpoints = lanes.flatMap { lane -> lane.map { it.t0Ms } }
            .distinct()
            .sorted()

        if (breakpoints.isEmpty()) {
            return Layout(
                Mapping(LongArray(0), FloatArray(0), pxPerMs),
                lanes.map { emptyList() },
            )
        }

        // Width needed immediately after each breakpoint, across all lanes.
        val needAfter = HashMap<Long, Float>()
        for (lane in lanes) {
            for (item in lane) {
                val required = item.widthPx + gapPx
                val current = needAfter[item.t0Ms]
                if (current == null || required > current) needAfter[item.t0Ms] = required
            }
        }

        val times = breakpoints.toLongArray()
        val xs = FloatArray(times.size)
        xs[0] = times[0] * pxPerMs
        for (k in 1 until times.size) {
            val elapsed = (times[k] - times[k - 1]) * pxPerMs
            val needed = needAfter[times[k - 1]] ?: 0f
            xs[k] = xs[k - 1] + maxOf(elapsed, needed)
        }

        val mapping = Mapping(times, xs, pxPerMs)
        return Layout(
            mapping = mapping,
            lanes = lanes.map { lane ->
                lane.mapIndexed { i, item -> Placed(i, mapping.xOf(item.t0Ms), item.widthPx) }
            },
        )
    }

    /** Roughly a character's width; enough to keep neighbours distinct. */
    const val DEFAULT_GAP_PX = 10f
}
