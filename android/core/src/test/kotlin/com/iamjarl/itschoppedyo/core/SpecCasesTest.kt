package com.iamjarl.itschoppedyo.core

import com.iamjarl.itschoppedyo.core.ChoppedException.Reason
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Runs every case in testdata/cases.json and compares each output's SHA-256 with
 * testdata/expected.json, which tools/reference.py wrote. Outputs land in testdata/out/kotlin/.
 */
class SpecCasesTest {
    private val testdata = File(System.getProperty("testdata.dir") ?: "../../testdata")

    @Test
    fun everyCaseMatchesTheReference() {
        val cases = JSONObject(File(testdata, "cases.json").readText()).getJSONArray("cases")
        val expected = JSONObject(File(testdata, "expected.json").readText()).getJSONObject("outputs")
        val outDir = File(testdata, "out/kotlin").apply { mkdirs() }

        assertTrue(cases.length() > 0)
        val mismatches = mutableListOf<String>()
        var checked = 0
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val id = c.getString("id")
            val outputs = run(c)
            val want = expected.getJSONObject(id)
            assertEquals("$id: output file names", keys(want), outputs.keys)
            for ((name, bytes) in outputs) {
                File(outDir, name).writeBytes(bytes)
                if (sha256(bytes) != want.getString(name)) mismatches += "$id: $name"
                checked++
            }
        }
        assertEquals("files differing from the reference: $mismatches", emptyList<String>(), mismatches)
        val total = keys(expected).sumOf { expected.getJSONObject(it).length() }
        assertEquals(total, checked)
    }

    @Test
    fun rightPannedSoundSurvivesTheMonoSum() {
        val a = Audio(48000, listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.5, -0.5)))
        assertArrayEquals(doubleArrayOf(0.25, -0.25), Processing.applyChannels(a, MonoMode.SUM).channels[0], 0.0)
    }

    @Test
    fun errorsFollowTheSpec() {
        val a = Audio(48000, listOf(doubleArrayOf(0.1, 0.2, 0.3)))
        expect(Reason.EMPTY_AFTER_TRIM) { Processing.trim(a, TrimRange(2, 2)) }
        expect(Reason.UNKNOWN_NORMALIZE_TARGET) { Processing.normalize(a, -0.2) }
        expect(Reason.BAD_BITS) { Processing.quantize(a, 20, false) }
        expect(Reason.FEWER_FRAMES_THAN_SLICES) { Processing.sliceBounds(3, 4) }
        expect(Reason.NOT_WAVE) { Wav.read("RIFX....WAVE".toByteArray()) }
        expect(Reason.BAD_RATIO) { Processing.resample(a, 48000 * 300) }
    }

    // ---- Helpers

    private fun keys(o: JSONObject): Set<String> = o.keys().asSequence().toSet()

    private fun expect(reason: Reason, block: () -> Unit) {
        try {
            block()
            fail("expected $reason")
        } catch (e: ChoppedException) {
            assertEquals(reason, e.reason)
        }
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun input(name: String): Audio = Wav.read(File(File(testdata, "input"), name).readBytes())

    private fun fade(o: JSONObject?): Fade? = o?.let {
        Fade(it.getInt("frames"), if (it.getString("curve") == "linear") FadeCurve.LINEAR else FadeCurve.EQUAL_POWER)
    }

    private fun settings(d: JSONObject) = Settings(
        mono = MonoMode.valueOf(d.optString("mono", "keep").uppercase()),
        trim = d.optJSONArray("trim")?.let { TrimRange(it.getInt(0), it.getInt(1)) },
        fadeIn = fade(d.optJSONObject("fadeIn")),
        fadeOut = fade(d.optJSONObject("fadeOut")),
        sampleRate = if (d.has("sampleRate")) d.getInt("sampleRate") else null,
        normalizeDb = if (d.has("normalize")) d.getDouble("normalize") else null,
        bits = d.getInt("bits"),
        dither = d.optBoolean("dither", false),
    )

    private fun run(c: JSONObject): Map<String, ByteArray> {
        val id = c.getString("id")
        val s = settings(c.getJSONObject("settings"))
        val inputs = c.getJSONArray("inputs").let { arr -> List(arr.length()) { arr.getString(it) } }
        return when (c.optString("mode", "single")) {
            "single" -> mapOf("$id.wav" to Processing.encode(Processing.process(input(inputs[0]), s), s))
            "slices" -> {
                val a = Processing.process(input(inputs[0]), s)
                Processing.slices(a, c.getInt("slices")).mapIndexed { k, p ->
                    "%s_%02d.wav".format(id, k + 1) to Processing.encode(p, s)
                }.toMap()
            }
            "chain" -> {
                val items = inputs.map { Processing.process(input(it), s) }
                val slots = if (c.has("slots")) c.getInt("slots") else null
                mapOf("$id.wav" to Processing.encode(Processing.chain(items, slots), s))
            }
            else -> error("unknown mode in $id")
        }
    }
}
