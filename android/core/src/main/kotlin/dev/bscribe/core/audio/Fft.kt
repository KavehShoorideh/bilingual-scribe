package dev.bscribe.core.audio

/**
 * In-place radix-2 FFT.
 *
 * Hand-rolled rather than pulled in as a dependency: this is the only DSP the
 * project needs beyond what whisper.cpp already does internally, and :core is
 * deliberately a pure-JVM module with no Android or native dependencies so its
 * logic stays unit-testable.
 */
object Fft {

    /**
     * Transforms [re]/[im] in place. Both must be the same power-of-two length.
     */
    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n == im.size) { "re/im length mismatch: $n vs ${im.size}" }
        require(n > 0 && n and (n - 1) == 0) { "length must be a power of two, was $n" }
        if (n == 1) return

        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }

        var len = 2
        while (len <= n) {
            val angle = -2.0 * Math.PI / len
            val wRe = Math.cos(angle)
            val wIm = Math.sin(angle)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Power spectrum of a real signal, length n/2 + 1. */
    fun powerSpectrum(signal: DoubleArray): DoubleArray {
        val re = signal.copyOf()
        val im = DoubleArray(signal.size)
        transform(re, im)
        val out = DoubleArray(signal.size / 2 + 1)
        for (i in out.indices) {
            out[i] = re[i] * re[i] + im[i] * im[i]
        }
        return out
    }
}
