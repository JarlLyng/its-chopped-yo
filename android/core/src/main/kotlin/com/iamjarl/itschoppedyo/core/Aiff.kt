// Spec sections 2a (reading AIFF) and 8a (writing AIFF). Every number in AIFF is big-endian.
package com.iamjarl.itschoppedyo.core

import com.iamjarl.itschoppedyo.core.ChoppedException.Reason
import java.io.ByteArrayOutputStream

object Aiff {
    private class Comm(val channels: Int, val frames: Long, val bits: Int, val rate: Int, val comp: String)

    // ---- Reading

    fun read(b: ByteArray): Audio {
        if (b.size < 12 || ascii(b, 0) != "FORM") fail(Reason.NOT_AIFF)
        val form = ascii(b, 8)
        if (form != "AIFF" && form != "AIFC") fail(Reason.NOT_AIFF)
        val aifc = form == "AIFC"
        var pos = 12
        var comm: Comm? = null
        var ssndStart = -1
        var ssndAvailable = 0L
        while (pos + 8 <= b.size) {
            val id = ascii(b, pos)
            val size = be32(b, pos + 4)
            val body = pos + 8
            if (id == "COMM") {
                if (body + 18 > b.size) fail(Reason.BAD_FORMAT)
                val rate = extendedToRate(b.copyOfRange(body + 8, body + 18))
                var comp = "NONE"
                if (aifc) {
                    if (body + 22 > b.size) fail(Reason.BAD_FORMAT)
                    comp = ascii(b, body + 18)
                }
                comm = Comm(be16(b, body), be32(b, body + 2), be16(b, body + 6), rate, comp)
            } else if (id == "SSND") {
                if (body + 8 > b.size) fail(Reason.BAD_FORMAT)
                val offset = be32(b, body)
                val start = body.toLong() + 8 + offset
                if (start <= Int.MAX_VALUE) {
                    ssndStart = start.toInt()
                    ssndAvailable = maxOf(minOf(size - 8 - offset, b.size - start), 0L)
                }
            }
            val next = body.toLong() + size + (size and 1L)
            if (next > Int.MAX_VALUE) break
            pos = next.toInt()
        }
        val c = comm ?: fail(Reason.NO_DATA)
        if (ssndStart < 0) fail(Reason.NO_DATA)
        if (c.channels < 1) fail(Reason.BAD_FORMAT)
        val width = c.bits / 8
        val conv: (Int) -> Double = when {
            c.comp == "NONE" && c.bits == 8 -> { o -> b[o].toDouble() / 128.0 }
            c.comp == "NONE" && c.bits == 16 -> { o -> be16(b, o).toShort().toDouble() / 32768.0 }
            c.comp == "NONE" && c.bits == 24 -> { o ->
                var v = ((b[o].toInt() and 0xFF) shl 16) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                    (b[o + 2].toInt() and 0xFF)
                if (v >= 0x800000) v -= 0x1000000
                v.toDouble() / 8388608.0
            }
            c.comp == "NONE" && c.bits == 32 -> { o -> be32(b, o).toInt().toDouble() / 2147483648.0 }
            c.comp == "sowt" && c.bits == 16 -> { o ->
                ((b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)).toShort().toDouble() / 32768.0
            }
            (c.comp == "fl32" || c.comp == "FL32") && c.bits == 32 -> { o -> Float.fromBits(be32(b, o).toInt()).toDouble() }
            (c.comp == "fl64" || c.comp == "FL64") && c.bits == 64 -> { o ->
                Double.fromBits((be32(b, o) shl 32) or be32(b, o + 4))
            }
            else -> fail(Reason.UNSUPPORTED_FORMAT)
        }
        val frameBytes = c.channels * width
        val n = minOf(c.frames, ssndAvailable / frameBytes).toInt()
        val channels = List(c.channels) { DoubleArray(n) }
        for (i in 0 until n) {
            val base = ssndStart + i * frameBytes
            for (ch in 0 until c.channels) channels[ch][i] = conv(base + ch * width)
        }
        return Audio(c.rate, channels)
    }

    /** The 80-bit extended sample rate as a whole number of hertz, without floating point. */
    internal fun extendedToRate(x: ByteArray): Int {
        val e = ((x[0].toInt() and 0xFF) shl 8) or (x[1].toInt() and 0xFF)
        var m = 0L
        for (k in 2 until 10) m = (m shl 8) or (x[k].toLong() and 0xFF)
        val shift = 16383 + 63 - (e and 0x7FFF)
        if (e and 0x8000 != 0 || shift !in 0..63) fail(Reason.UNSUPPORTED_SAMPLE_RATE)
        if (shift > 0 && (m and ((1L shl shift) - 1)) != 0L) fail(Reason.UNSUPPORTED_SAMPLE_RATE)
        val r = m ushr shift
        if (r < 0 || r > Int.MAX_VALUE) fail(Reason.UNSUPPORTED_SAMPLE_RATE)
        return r.toInt()
    }

    // ---- Writing

    /** Plain AIFF: FORM, COMM (18 bytes) and SSND, nothing else. [pcm] is big-endian interleaved. */
    fun write(sampleRate: Int, channelCount: Int, bits: Int, frames: Int, pcm: ByteArray): ByteArray {
        val dataSize = pcm.size
        val pad = dataSize and 1
        val out = ByteArrayOutputStream(54 + dataSize + pad)
        out.write("FORM".toByteArray(Charsets.US_ASCII))
        putBE32(out, (4 + 26 + 16 + dataSize + pad).toLong())
        out.write("AIFF".toByteArray(Charsets.US_ASCII))
        out.write("COMM".toByteArray(Charsets.US_ASCII))
        putBE32(out, 18)
        putBE16(out, channelCount)
        putBE32(out, frames.toLong())
        putBE16(out, bits)
        out.write(rateToExtended(sampleRate))
        out.write("SSND".toByteArray(Charsets.US_ASCII))
        putBE32(out, (8 + dataSize).toLong())
        putBE32(out, 0)
        putBE32(out, 0)
        out.write(pcm)
        if (pad == 1) out.write(0)
        return out.toByteArray()
    }

    /** A positive integer rate as an 80-bit extended float, without floating point. */
    internal fun rateToExtended(r: Int): ByteArray {
        val v = r.toLong()
        val p = 63 - java.lang.Long.numberOfLeadingZeros(v)
        val exponent = 16383 + p
        val mantissa = v shl (63 - p)
        val out = ByteArray(10)
        out[0] = (exponent shr 8).toByte()
        out[1] = (exponent and 0xFF).toByte()
        for (k in 0 until 8) out[2 + k] = ((mantissa ushr (56 - 8 * k)) and 0xFF).toByte()
        return out
    }

    // ---- Byte helpers

    private fun ascii(b: ByteArray, o: Int) = String(b, o, 4, Charsets.US_ASCII)

    private fun be16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun be32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun putBE16(out: ByteArrayOutputStream, v: Int) {
        out.write((v shr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun putBE32(out: ByteArrayOutputStream, v: Long) {
        out.write(((v shr 24) and 0xFF).toInt())
        out.write(((v shr 16) and 0xFF).toInt())
        out.write(((v shr 8) and 0xFF).toInt())
        out.write((v and 0xFF).toInt())
    }
}

/** Section 1, steps 1 and 7: the first four bytes pick the reader; [Settings.format] picks the writer. */
object AudioFile {
    fun read(bytes: ByteArray): Audio =
        if (bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "FORM") Aiff.read(bytes) else Wav.read(bytes)

    fun fileExtension(format: OutputFormat): String = if (format == OutputFormat.AIFF) "aif" else "wav"
}
