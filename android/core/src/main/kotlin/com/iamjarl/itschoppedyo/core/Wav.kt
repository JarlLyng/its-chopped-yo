// Spec sections 2 (reading) and 8 (writing).
package com.iamjarl.itschoppedyo.core

import com.iamjarl.itschoppedyo.core.ChoppedException.Reason
import java.io.ByteArrayOutputStream

object Wav {
    private class Fmt(val tag: Int, val channels: Int, val rate: Int, val block: Int, val bits: Int)

    // ---- Reading

    fun read(b: ByteArray): Audio {
        if (b.size < 12 || ascii(b, 0) != "RIFF" || ascii(b, 8) != "WAVE") fail(Reason.NOT_WAVE)
        var pos = 12
        var fmt: Fmt? = null
        while (pos + 8 <= b.size) {
            val id = ascii(b, pos)
            val size = u32(b, pos + 4)
            val body = pos + 8
            if (id == "fmt ") {
                if (body + 16 > b.size) fail(Reason.BAD_FORMAT)
                var tag = u16(b, body)
                if (tag == 0xFFFE) {
                    if (body + 26 > b.size) fail(Reason.BAD_FORMAT)
                    tag = u16(b, body + 24)
                }
                fmt = Fmt(tag, u16(b, body + 2), u32(b, body + 4).toInt(), u16(b, body + 12), u16(b, body + 14))
            } else if (id == "data") {
                val f = fmt ?: fail(Reason.DATA_BEFORE_FMT)
                val dataSize = minOf(size, (b.size - body).toLong()).toInt()
                return decode(b, body, dataSize, f)
            }
            val next = body.toLong() + size + (size and 1L)
            if (next > Int.MAX_VALUE) break
            pos = next.toInt()
        }
        fail(Reason.NO_DATA)
    }

    private fun decode(b: ByteArray, start: Int, size: Int, f: Fmt): Audio {
        if (f.channels < 1 || f.block < 1) fail(Reason.BAD_FORMAT)
        val frames = size / f.block
        val width = f.bits / 8
        val conv: (Int) -> Double = when {
            f.tag == 1 && f.bits == 8 -> { o -> ((b[o].toInt() and 0xFF) - 128).toDouble() / 128.0 }
            f.tag == 1 && f.bits == 16 -> { o -> u16(b, o).toShort().toDouble() / 32768.0 }
            f.tag == 1 && f.bits == 24 -> { o ->
                var v = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                    ((b[o + 2].toInt() and 0xFF) shl 16)
                if (v >= 0x800000) v -= 0x1000000
                v.toDouble() / 8388608.0
            }
            f.tag == 1 && f.bits == 32 -> { o -> u32(b, o).toInt().toDouble() / 2147483648.0 }
            f.tag == 3 && f.bits == 32 -> { o -> Float.fromBits(u32(b, o).toInt()).toDouble() }
            f.tag == 3 && f.bits == 64 -> { o -> Double.fromBits(u32(b, o) or (u32(b, o + 4) shl 32)) }
            else -> fail(Reason.UNSUPPORTED_FORMAT)
        }
        val channels = List(f.channels) { DoubleArray(frames) }
        for (i in 0 until frames) {
            val base = start + i * f.block
            for (c in 0 until f.channels) channels[c][i] = conv(base + c * width)
        }
        return Audio(f.rate, channels)
    }

    // ---- Writing

    /** A PCM WAV with a 44-byte header and nothing else; [pcm] is interleaved samples. */
    fun write(sampleRate: Int, channelCount: Int, bits: Int, pcm: ByteArray): ByteArray {
        val dataSize = pcm.size
        val pad = dataSize and 1
        val block = channelCount * bits / 8
        val out = ByteArrayOutputStream(44 + dataSize + pad)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        put32(out, (4 + 24 + 8 + dataSize + pad).toLong())
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        put32(out, 16)
        put16(out, 1)
        put16(out, channelCount)
        put32(out, sampleRate.toLong())
        put32(out, sampleRate.toLong() * block)
        put16(out, block)
        put16(out, bits)
        out.write("data".toByteArray(Charsets.US_ASCII))
        put32(out, dataSize.toLong())
        out.write(pcm)
        if (pad == 1) out.write(0)
        return out.toByteArray()
    }

    // ---- Byte helpers

    private fun ascii(b: ByteArray, o: Int) = String(b, o, 4, Charsets.US_ASCII)

    private fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    private fun put16(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    private fun put32(out: ByteArrayOutputStream, v: Long) {
        out.write((v and 0xFF).toInt())
        out.write(((v shr 8) and 0xFF).toInt())
        out.write(((v shr 16) and 0xFF).toInt())
        out.write(((v shr 24) and 0xFF).toInt())
    }
}
