package com.iamjarl.itschoppedyo

import android.content.ContentResolver
import android.content.res.AssetManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.iamjarl.itschoppedyo.core.Audio
import com.iamjarl.itschoppedyo.core.AudioFile
import com.iamjarl.itschoppedyo.core.Fade
import com.iamjarl.itschoppedyo.core.FadeCurve
import com.iamjarl.itschoppedyo.core.MonoMode
import com.iamjarl.itschoppedyo.core.OutputFormat
import com.iamjarl.itschoppedyo.core.Processing
import com.iamjarl.itschoppedyo.core.Settings
import com.iamjarl.itschoppedyo.core.SpecCaseRunner
import kotlin.math.abs
import kotlin.math.log10

/** The state behind the test screen. The work runs on a background dispatcher (MainActivity). */
class TestModel {
    companion object {
        val rates = listOf(22050, 32000, 44100, 46875, 48000, 96000)
        val normalizeTargets = listOf(0.0, -0.1, -0.3, -0.5, -1.0, -3.0, -6.0)

        /** Display only: log10 never touches the audio path. */
        fun describe(a: Audio): String {
            var peak = 0.0
            for (ch in a.channels) for (x in ch) peak = maxOf(peak, abs(x))
            val seconds = a.frameCount.toDouble() / maxOf(a.sampleRate, 1)
            val peakText = if (peak > 0) "%.1f dBFS".format(20 * log10(peak)) else "silent"
            return "%d Hz, %d ch, %d frames (%.2f s), peak %s".format(
                a.sampleRate, a.channels.size, a.frameCount, seconds, peakText)
        }
    }

    // Self-test
    var selfTestRunning by mutableStateOf(false)
    var selfTestSummary by mutableStateOf<String?>(null)
    var selfTestPassed by mutableStateOf(false)
    var selfTestProblems by mutableStateOf<List<String>>(emptyList())

    // Input
    var input by mutableStateOf<Audio?>(null)
    var inputName by mutableStateOf("")
    var inputInfo by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)

    // Settings
    var mono by mutableStateOf(MonoMode.KEEP)
    var sampleRate by mutableStateOf(0)
    var bits by mutableStateOf(16)
    var dither by mutableStateOf(true)
    var normalizeIndex by mutableStateOf(-1)
    var fadeInMs by mutableStateOf(0)
    var fadeOutMs by mutableStateOf(0)
    var equalPower by mutableStateOf(false)
    var slices by mutableStateOf(1)
    var format by mutableStateOf(OutputFormat.WAV)

    // Output
    var processing by mutableStateOf(false)
    var outputSummary by mutableStateOf<String?>(null)
    var outputs by mutableStateOf<List<Pair<String, ByteArray>>>(emptyList())
    var saved by mutableStateOf<String?>(null)

    // ---- Self-test (call off the main thread)

    fun runSelfTest(assets: AssetManager): Triple<String, Boolean, List<String>> {
        val started = System.nanoTime()
        return try {
            fun text(name: String) = assets.open("testdata/$name").use { it.readBytes().toString(Charsets.UTF_8) }
            val r = SpecCaseRunner.runAll(
                text("cases.json"),
                text("expected.json"),
                readInput = { name -> assets.open("testdata/input/$name").use { it.readBytes() } },
            )
            val seconds = (System.nanoTime() - started) / 1e9
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
            val line = "%d of %d files match the reference (%s, Android %s, %.1f s)".format(
                r.checked - r.mismatches.size, r.expectedTotal, abi, Build.VERSION.RELEASE, seconds)
            val passed = r.checked > 0 && r.checked == r.expectedTotal && r.mismatches.isEmpty() && r.nameProblems.isEmpty()
            Triple(line, passed, r.nameProblems + r.mismatches)
        } catch (e: Exception) {
            Triple("The self-test stopped: $e", false, emptyList())
        }
    }

    // ---- Input (call off the main thread)

    fun load(resolver: ContentResolver, uri: Uri): Triple<Audio?, String, String> {
        val name = displayName(resolver, uri).substringBeforeLast('.')
        return try {
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("could not open")
            val audio = AudioFile.read(bytes)
            Triple(audio, name, describe(audio))
        } catch (e: Exception) {
            Triple(null, name, "Could not read $name: $e")
        }
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "input"

    // ---- Process (call off the main thread)

    fun settings(): Settings {
        val rate = input?.sampleRate ?: 48000
        val curve = if (equalPower) FadeCurve.EQUAL_POWER else FadeCurve.LINEAR
        return Settings(
            mono = mono,
            fadeIn = if (fadeInMs > 0) Fade(fadeInMs * rate / 1000, curve) else null,
            fadeOut = if (fadeOutMs > 0) Fade(fadeOutMs * rate / 1000, curve) else null,
            sampleRate = if (sampleRate == 0) null else sampleRate,
            normalizeDb = if (normalizeIndex >= 0) normalizeTargets[normalizeIndex] else null,
            bits = bits,
            dither = dither,
            format = format,
        )
    }

    fun process(a: Audio, s: Settings, count: Int, name: String): Pair<List<Pair<String, ByteArray>>, String> {
        val processed = Processing.process(a, s)
        val ext = AudioFile.fileExtension(s.format)
        val parts = if (count > 1) Processing.slices(processed, count) else listOf(processed)
        val files = parts.mapIndexed { k, p ->
            val file = if (count > 1) "%s_%02d.%s".format(name, k + 1, ext) else "${name}_chopped.$ext"
            file to Processing.encode(p, s)
        }
        return files to "${files.size} file${if (files.size == 1) "" else "s"}: ${describe(processed)}"
    }

    /** Writes every output into the folder the user picked (a tree URI from OpenDocumentTree). */
    fun save(resolver: ContentResolver, tree: Uri, files: List<Pair<String, ByteArray>>, format: OutputFormat): String {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val mime = if (format == OutputFormat.AIFF) "audio/aiff" else "audio/wav"
        for ((name, bytes) in files) {
            val doc = DocumentsContract.createDocument(resolver, parent, mime, name) ?: error("could not create $name")
            resolver.openOutputStream(doc)?.use { it.write(bytes) } ?: error("could not write $name")
        }
        return "Saved ${files.size} file${if (files.size == 1) "" else "s"}"
    }
}
