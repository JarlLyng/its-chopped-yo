package com.iamjarl.itschoppedyo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * presets/presets.json and the screen text. ios/ChoppedCore/Tests/ChoppedCoreTests/PresetsTests.swift
 * checks the same values, so both apps show the same list and the same numbers.
 */
class PresetsTest {
    private val presetsFile = File(System.getProperty("presets.file") ?: "../../presets/presets.json")

    @Test
    fun theSharedPresetsFileParses() {
        val presets = Presets.parse(presetsFile.readText())
        assertEquals(listOf("wav-48k-16-mono", "wav-48k-16-stereo", "wav-48k-24-mono", "wav-48k-24-stereo"), presets.map { it.id })
        val first = presets[0].settings(dither = true)
        assertEquals(MonoMode.SUM, first.mono)
        assertEquals(48000, first.sampleRate)
        assertEquals(16, first.bits)
        assertTrue(first.dither)
        assertEquals(OutputFormat.WAV, first.format)
        assertNull(first.normalizeDb)
        assertEquals(MonoMode.KEEP, presets[1].settings(dither = false).mono)
    }

    @Test
    fun aBadPresetsFileIsRejected() {
        val dup = """{"presets":[{"id":"a","name":"A","sampleRate":48000,"bits":16,"channels":"mono","format":"wav"},{"id":"a","name":"B","sampleRate":48000,"bits":16,"channels":"mono","format":"wav"}]}"""
        assertThrows(Presets.ParseException::class.java) { Presets.parse(dup) }
        val bits = """{"presets":[{"id":"a","name":"A","sampleRate":48000,"bits":12,"channels":"mono","format":"wav"}]}"""
        assertThrows(Presets.ParseException::class.java) { Presets.parse(bits) }
    }

    @Test
    fun screenText() {
        assertEquals("48 kHz", Describe.rate(48000))
        assertEquals("44.1 kHz", Describe.rate(44100))
        assertEquals("46.875 kHz", Describe.rate(46875))
        assertEquals("22.05 kHz", Describe.rate(22050))
        assertEquals("8 kHz", Describe.rate(8000))
        assertEquals("96.001 kHz", Describe.rate(96001))
        assertEquals("mono", Describe.channels(1))
        assertEquals("stereo", Describe.channels(2))
        assertEquals("6 ch", Describe.channels(6))
        assertEquals("0.10 s", Describe.duration(9600, 96000))
        assertEquals("4.25 s", Describe.duration(204_000, 48000))
        assertEquals("1:05", Describe.duration(48000 * 65, 48000))
        assertEquals("60:00", Describe.duration(48000 * 3600, 48000))
        assertEquals("peak -6.0 dBFS", Describe.peak(Audio(48000, listOf(doubleArrayOf(0.0, 0.5, -0.25)))))
        assertEquals("peak 0.0 dBFS", Describe.peak(Audio(48000, listOf(doubleArrayOf(0.0, -1.0)))))
        assertEquals("silent", Describe.peak(Audio(48000, listOf(doubleArrayOf(0.0, 0.0)))))
        assertEquals("96 kHz · stereo · 0.10 s", Describe.summary(Audio(96000, listOf(DoubleArray(9600), DoubleArray(9600)))))
    }

    @Test
    fun displayText() {
        val st = Audio(96000, listOf(DoubleArray(136_320), DoubleArray(136_320) { 0.8 }))
        val mono = Audio(44100, listOf(DoubleArray(44100 * 65)))
        assertEquals("IN 96K · ST · 1.42S", Describe.inLine(st))
        assertEquals("IN 44.1K · MONO · 1:05", Describe.inLine(mono))
        assertEquals("OUT 44.1K · 16B · MONO · WAV", Describe.outLine(mono, 16, OutputFormat.WAV))
        assertEquals("OUT 96K · 24B · ST · AIFF", Describe.outLine(st, 24, OutputFormat.AIFF))
        assertEquals("6CH", Describe.shortChannels(6))
        assertEquals("46.875K", Describe.shortRate(46875))
        assertEquals("PK -1.9", Describe.shortPeak(st))
        assertEquals("SILENT", Describe.shortPeak(mono))
        assertEquals("512 B", Describe.size(512))
        assertEquals("66 KB", Describe.size(67_000))
        assertEquals("3.2 MB", Describe.size(3_400_000))
    }
}
