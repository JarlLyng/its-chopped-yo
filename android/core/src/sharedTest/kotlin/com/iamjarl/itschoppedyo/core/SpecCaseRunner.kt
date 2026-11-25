package com.iamjarl.itschoppedyo.core

import org.json.JSONObject
import java.security.MessageDigest

/**
 * Runs every case in testdata/cases.json and compares each output's SHA-256 with
 * testdata/expected.json. Shared by the JVM unit test and the instrumented test on a device, so
 * both run exactly the same thing; only where the files come from differs.
 */
object SpecCaseRunner {
    class Result(val mismatches: List<String>, val checked: Int, val expectedTotal: Int, val nameProblems: List<String>)

    fun runAll(
        casesJson: String,
        expectedJson: String,
        readInput: (String) -> ByteArray,
        onOutput: (String, ByteArray) -> Unit = { _, _ -> },
    ): Result {
        val cases = JSONObject(casesJson).getJSONArray("cases")
        val expected = JSONObject(expectedJson).getJSONObject("outputs")
        val mismatches = mutableListOf<String>()
        val nameProblems = mutableListOf<String>()
        var checked = 0
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val id = c.getString("id")
            val outputs = run(c, readInput)
            val want = expected.getJSONObject(id)
            if (keys(want) != outputs.keys) nameProblems += "$id: ${outputs.keys} != ${keys(want)}"
            for ((name, bytes) in outputs) {
                onOutput(name, bytes)
                if (!want.has(name) || sha256(bytes) != want.getString(name)) mismatches += "$id: $name"
                checked++
            }
        }
        val total = keys(expected).sumOf { expected.getJSONObject(it).length() }
        return Result(mismatches, checked, total, nameProblems)
    }

    fun keys(o: JSONObject): Set<String> = o.keys().asSequence().toSet()

    fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun fade(o: JSONObject?): Fade? = o?.let {
        Fade(it.getInt("frames"), if (it.getString("curve") == "linear") FadeCurve.LINEAR else FadeCurve.EQUAL_POWER)
    }

    fun settings(d: JSONObject) = Settings(
        mono = MonoMode.valueOf(d.optString("mono", "keep").uppercase()),
        trim = d.optJSONArray("trim")?.let { TrimRange(it.getInt(0), it.getInt(1)) },
        fadeIn = fade(d.optJSONObject("fadeIn")),
        fadeOut = fade(d.optJSONObject("fadeOut")),
        sampleRate = if (d.has("sampleRate")) d.getInt("sampleRate") else null,
        normalizeDb = if (d.has("normalize")) d.getDouble("normalize") else null,
        bits = d.getInt("bits"),
        dither = d.optBoolean("dither", false),
        format = if (d.optString("format", "wav") == "aiff") OutputFormat.AIFF else OutputFormat.WAV,
    )

    private fun run(c: JSONObject, readInput: (String) -> ByteArray): Map<String, ByteArray> {
        val id = c.getString("id")
        val s = settings(c.getJSONObject("settings"))
        val inputs = c.getJSONArray("inputs").let { arr -> List(arr.length()) { arr.getString(it) } }
        val ext = AudioFile.fileExtension(s.format)
        fun input(name: String) = AudioFile.read(readInput(name))
        return when (c.optString("mode", "single")) {
            "single" -> mapOf("$id.$ext" to Processing.encode(Processing.process(input(inputs[0]), s), s))
            "slices" -> {
                val a = Processing.process(input(inputs[0]), s)
                Processing.slices(a, c.getInt("slices")).mapIndexed { k, p ->
                    "%s_%02d.%s".format(id, k + 1, ext) to Processing.encode(p, s)
                }.toMap()
            }
            "chain" -> {
                val items = inputs.map { Processing.process(input(it), s) }
                val slots = if (c.has("slots")) c.getInt("slots") else null
                mapOf("$id.$ext" to Processing.encode(Processing.chain(items, slots), s))
            }
            else -> error("unknown mode in $id")
        }
    }
}
