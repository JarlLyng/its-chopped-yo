package com.iamjarl.itschoppedyo.core

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.floor

/**
 * A built-in format preset from presets/presets.json, the same file the iOS app reads.
 * Presets are named by format, never by machine (the owner's decision, so the stores accept them).
 */
data class Preset(
    val id: String,
    val name: String,
    val sampleRate: Int,
    val bits: Int,
    val channels: Channels,
    val format: OutputFormat,
) {
    enum class Channels { MONO, STEREO }

    /**
     * The processing settings for this preset. Mono sums the channels as (L+R)/2 (spec section 3);
     * stereo keeps the channels as they are. Nothing else is touched: no trim, fade or normalize.
     */
    fun settings(dither: Boolean): Settings = Settings(
        mono = if (channels == Channels.MONO) MonoMode.SUM else MonoMode.KEEP,
        sampleRate = sampleRate,
        bits = bits,
        dither = dither,
        format = format,
    )
}

object Presets {
    class ParseException(message: String) : Exception(message)

    /**
     * Reads presets.json. Throws on anything the app would not know how to use, so a bad file
     * fails the tests instead of showing a broken list.
     */
    fun parse(json: String): List<Preset> {
        val list = try {
            JSONObject(json).getJSONArray("presets")
        } catch (e: Exception) {
            throw ParseException("no presets list")
        }
        val seen = HashSet<String>()
        return (0 until list.length()).map { i ->
            val p = list.getJSONObject(i)
            val preset = try {
                Preset(
                    id = p.getString("id"),
                    name = p.getString("name"),
                    sampleRate = p.getInt("sampleRate"),
                    bits = p.getInt("bits"),
                    channels = when (p.getString("channels")) {
                        "mono" -> Preset.Channels.MONO
                        "stereo" -> Preset.Channels.STEREO
                        else -> throw ParseException("bad channels")
                    },
                    format = when (p.getString("format")) {
                        "wav" -> OutputFormat.WAV
                        "aiff" -> OutputFormat.AIFF
                        else -> throw ParseException("bad format")
                    },
                )
            } catch (e: Exception) {
                throw ParseException("bad preset ${p.optString("id", "?")}")
            }
            if ((preset.bits != 16 && preset.bits != 24) || preset.sampleRate <= 0) throw ParseException("bad format in ${preset.id}")
            if (!seen.add(preset.id)) throw ParseException("duplicate id ${preset.id}")
            preset
        }
    }
}

/**
 * Text for the screen. The same strings on iOS and Android, whatever the phone's language, so a
 * rate reads "44.1 kHz" on both (no locale thousands separators). Display only, never audio.
 */
object Describe {
    /** 48000 → "48 kHz", 44100 → "44.1 kHz", 46875 → "46.875 kHz". */
    fun rate(hz: Int): String {
        val whole = hz / 1000
        val frac = hz % 1000
        if (frac == 0) return "$whole kHz"
        return "$whole.${frac.toString().padStart(3, '0').trimEnd('0')} kHz"
    }

    /** 1 → "mono", 2 → "stereo", n → "n ch". */
    fun channels(n: Int): String = when (n) {
        1 -> "mono"
        2 -> "stereo"
        else -> "$n ch"
    }

    /** Length, rounded down: under a minute "4.25 s", otherwise "1:05". */
    fun duration(frames: Int, sampleRate: Int): String {
        if (sampleRate <= 0) return "0.00 s"
        val centi = (frames.toLong() * 100 / sampleRate).toInt()
        if (centi < 6000) return "${centi / 100}.${(centi % 100).toString().padStart(2, '0')} s"
        val s = centi / 100
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    /** The highest absolute sample as dBFS with one decimal, or "silent". */
    fun peak(a: Audio): String {
        var p = 0.0
        for (ch in a.channels) for (x in ch) if (abs(x) > p) p = abs(x)
        if (p <= 0.0) return "silent"
        // floor(x + 0.5), as in the Swift twin, so a value on a half rounds the same way on both.
        val db = floor(20 * log10(p) * 10 + 0.5) / 10.0
        val tenths = floor(abs(db) * 10 + 0.5).toInt()
        val sign = if (db < 0 && tenths != 0) "-" else ""
        return "peak $sign${tenths / 10}.${tenths % 10} dBFS"
    }

    /** "96 kHz · stereo · 0.10 s" */
    fun summary(a: Audio): String =
        "${rate(a.sampleRate)} · ${channels(a.channels.size)} · ${duration(a.frameCount, a.sampleRate)}"
}
