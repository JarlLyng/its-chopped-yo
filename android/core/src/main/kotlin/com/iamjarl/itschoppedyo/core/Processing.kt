// Spec sections 3 to 7, 9 and 10. Only + - * /, floor, sqrt and abs touch the audio, apart from
// libsamplerate in section 5a, which is the same C code on both platforms.
package com.iamjarl.itschoppedyo.core

import com.iamjarl.itschoppedyo.core.ChoppedException.Reason
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

object Processing {
    /** Section 6: dBFS to the double nearest 10^(dB/20), written out so nothing computes a power. */
    val normalizeTargets: Map<Double, Double> = mapOf(
        0.0 to 1.0,
        -0.1 to 0.9885530946569389,
        -0.3 to 0.9660508789898133,
        -0.5 to 0.9440608762859234,
        -1.0 to 0.8912509381337456,
        -3.0 to 0.7079457843841379,
        -6.0 to 0.5011872336272722,
    )

    /** Section 3: downmix weights before they are divided by their sum. */
    internal fun downmixK(c: Int): DoubleArray = when (c) {
        1 -> doubleArrayOf(1.0)
        2 -> doubleArrayOf(1.0, 1.0)
        3 -> doubleArrayOf(1.0, 1.0, 1.414)
        4 -> doubleArrayOf(1.0, 1.0, 1.0, 1.0)
        5 -> doubleArrayOf(1.0, 1.0, 0.707, 0.707, 0.707)
        6 -> doubleArrayOf(1.0, 1.0, 0.707, 0.0, 0.707, 0.707)
        else -> DoubleArray(c) { 1.0 }
    }

    /** Steps 2 to 5 of the pipeline. */
    fun process(input: Audio, s: Settings): Audio {
        var a = applyChannels(input, s.mono)
        a = trim(a, s.trim)
        a = fade(a, s.fadeIn, s.fadeOut)
        a = resample(a, s.sampleRate)
        a = normalize(a, s.normalizeDb)
        return a
    }

    // ---- Section 3

    fun applyChannels(a: Audio, mode: MonoMode): Audio {
        val c = a.channels.size
        return when (mode) {
            MonoMode.KEEP -> a
            MonoMode.LEFT -> Audio(a.sampleRate, listOf(a.channels[0]))
            MonoMode.RIGHT -> Audio(a.sampleRate, listOf(if (c > 1) a.channels[1] else a.channels[0]))
            MonoMode.SUM -> {
                val k = downmixK(c)
                var total = 0.0
                for (v in k) total = total + v
                val w = DoubleArray(c) { k[it] / total }
                val out = DoubleArray(a.frameCount)
                for (i in 0 until a.frameCount) {
                    var acc = 0.0
                    for (ch in 0 until c) acc = acc + a.channels[ch][i] * w[ch]
                    out[i] = acc
                }
                Audio(a.sampleRate, listOf(out))
            }
        }
    }

    // ---- Section 4

    fun trim(a: Audio, range: TrimRange?): Audio {
        if (range == null) return a
        val start = range.start.coerceIn(0, a.frameCount)
        val end = range.end.coerceIn(0, a.frameCount)
        if (end <= start) fail(Reason.EMPTY_AFTER_TRIM)
        return Audio(a.sampleRate, a.channels.map { it.copyOfRange(start, end) })
    }

    // ---- Section 5

    private fun gain(t: Double, curve: FadeCurve): Double = when (curve) {
        FadeCurve.LINEAR -> t
        FadeCurve.EQUAL_POWER -> sqrt(t)
    }

    fun fade(a: Audio, fadeIn: Fade?, fadeOut: Fade?): Audio {
        val length = a.frameCount
        val chans = a.channels.map { it.copyOf() }
        if (fadeIn != null) {
            val n = minOf(fadeIn.frames, length)
            for (i in 0 until n) {
                val g = gain(i.toDouble() / n.toDouble(), fadeIn.curve)
                for (ch in chans) ch[i] = ch[i] * g
            }
        }
        if (fadeOut != null) {
            val n = minOf(fadeOut.frames, length)
            for (i in 0 until n) {
                val g = gain((n - 1 - i).toDouble() / n.toDouble(), fadeOut.curve)
                val frame = length - n + i
                for (ch in chans) ch[frame] = ch[frame] * g
            }
        }
        return Audio(a.sampleRate, chans)
    }

    // ---- Section 5a

    /** libsamplerate's `src_simple` with SRC_SINC_BEST_QUALITY, through interleaved 32-bit floats. */
    fun resample(a: Audio, rate: Int?): Audio {
        if (rate == null || rate == a.sampleRate) return a
        val ratio = rate.toDouble() / a.sampleRate.toDouble()
        if (ratio < 1.0 / 256.0 || ratio > 256.0) fail(Reason.BAD_RATIO)
        val nch = a.channels.size
        val frames = a.frameCount
        val input = FloatArray(frames * nch)
        for (i in 0 until frames) for (c in 0 until nch) input[i * nch + c] = a.channels[c][i].toFloat()
        val capacity = floor(frames.toDouble() * ratio).toInt() + 256
        val out = NativeResampler.simple(input, frames, nch, ratio, capacity) ?: fail(Reason.RESAMPLE_FAILED)
        val generated = out.size / nch
        val chans = List(nch) { c -> DoubleArray(generated) { i -> out[i * nch + c].toDouble() } }
        return Audio(rate, chans)
    }

    // ---- Section 6

    fun normalize(a: Audio, db: Double?): Audio {
        if (db == null) return a
        val target = normalizeTargets[db] ?: fail(Reason.UNKNOWN_NORMALIZE_TARGET)
        var peak = 0.0
        for (ch in a.channels) for (x in ch) {
            val ax = abs(x)
            if (ax > peak) peak = ax
        }
        if (peak == 0.0) return a
        val g = target / peak
        return Audio(a.sampleRate, a.channels.map { ch -> DoubleArray(ch.size) { ch[it] * g } })
    }

    // ---- Section 7

    /** xorshift32 on an unsigned 32-bit state, restarted for every output file. */
    class XorShift32(seed: Int = SEED) {
        private var s: Int = seed

        fun next(): Double {
            s = s xor (s shl 13)
            s = s xor (s ushr 17)
            s = s xor (s shl 5)
            return (s ushr 8).toDouble() / 16777216.0
        }

        companion object {
            const val SEED: Int = 0x2545F491
        }
    }

    /** Interleaved little-endian PCM, 2 or 3 bytes per sample. */
    fun quantize(a: Audio, bits: Int, dither: Boolean): ByteArray {
        if (bits != 16 && bits != 24) fail(Reason.BAD_BITS)
        val scale = (1 shl (bits - 1)).toDouble()
        val hi = (1 shl (bits - 1)) - 1
        val lo = -(1 shl (bits - 1))
        val rng = XorShift32()
        val nch = a.channels.size
        val bytesPer = bits / 8
        val out = ByteArray(a.frameCount * nch * bytesPer)
        var o = 0
        for (i in 0 until a.frameCount) {
            for (c in 0 until nch) {
                var v = a.channels[c][i] * scale
                if (dither) {
                    val r1 = rng.next()
                    val r2 = rng.next()
                    v = v + (r1 + r2 - 1.0)
                }
                val q = floor(v + 0.5).toInt().coerceIn(lo, hi)
                out[o++] = (q and 0xFF).toByte()
                out[o++] = ((q shr 8) and 0xFF).toByte()
                if (bits == 24) out[o++] = ((q shr 16) and 0xFF).toByte()
            }
        }
        return out
    }

    /** Steps 6 and 7: quantize and write one WAV file. */
    fun encode(a: Audio, s: Settings): ByteArray =
        Wav.write(a.sampleRate, a.channels.size, s.bits, quantize(a, s.bits, s.dither))

    // ---- Sections 9 and 10

    fun sliceBounds(length: Int, count: Int): IntArray {
        if (count !in 2..64) fail(Reason.BAD_SLICE_COUNT)
        if (length < count) fail(Reason.FEWER_FRAMES_THAN_SLICES)
        return IntArray(count + 1) { ((it.toLong() * length.toLong()) / count.toLong()).toInt() }
    }

    fun slices(a: Audio, count: Int): List<Audio> {
        val b = sliceBounds(a.frameCount, count)
        return (0 until count).map { k -> Audio(a.sampleRate, a.channels.map { it.copyOfRange(b[k], b[k + 1]) }) }
    }

    fun chain(items: List<Audio>, slots: Int? = null): Audio {
        val first = items.firstOrNull() ?: fail(Reason.FEWER_SLOTS_THAN_INPUTS)
        val nch = first.channels.size
        if (items.any { it.channels.size != nch || it.sampleRate != first.sampleRate }) fail(Reason.CHAIN_MISMATCH)
        val slotCount = slots ?: items.size
        if (slotCount < items.size) fail(Reason.FEWER_SLOTS_THAN_INPUTS)
        val size = items.maxOf { it.frameCount }
        val out = List(nch) { DoubleArray(slotCount * size) }
        items.forEachIndexed { j, it ->
            for (c in 0 until nch) it.channels[c].copyInto(out[c], destinationOffset = j * size)
        }
        return Audio(first.sampleRate, out)
    }
}
