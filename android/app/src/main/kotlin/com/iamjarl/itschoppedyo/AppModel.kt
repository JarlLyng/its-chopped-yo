package com.iamjarl.itschoppedyo

import android.content.Context
import android.content.res.AssetManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.iamjarl.itschoppedyo.core.Audio
import com.iamjarl.itschoppedyo.core.AudioFile
import com.iamjarl.itschoppedyo.core.ChoppedException
import com.iamjarl.itschoppedyo.core.Describe
import com.iamjarl.itschoppedyo.core.OutputFormat
import com.iamjarl.itschoppedyo.core.Preset
import com.iamjarl.itschoppedyo.core.Presets
import com.iamjarl.itschoppedyo.core.Processing
import com.iamjarl.itschoppedyo.core.SpecCaseRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Where the back stack can go. */
enum class Screen { START, FILE, PRESETS, SETTINGS, SELF_TEST }

/**
 * Everything Pro adds. The free app is one file, a built-in preset, then save or share
 * (the owner's decision, hub `ItsChoppedYo/screen-flow.md`); every one of these opens the Pro sheet.
 * The same list and words as ProFeature in the iOS app.
 */
enum class ProFeature(val title: String, val detail: String) {
    RECORD("Record", "From the microphone or a USB audio interface."),
    BATCH("Batch a folder", "A whole folder or many files in, one folder out, the same preset for all."),
    CHAIN("Sample chains", "Several files joined in equal slots, for samplers that slice evenly."),
    CUSTOM_PRESET("Your own presets", "Any sample rate, bit depth, channels and WAV or AIFF, saved on this phone."),
    TRIM("Trim", "Cut the start and the end, snapped to a zero crossing."),
    FADE("Fade in and out", "Linear or equal power, in milliseconds."),
    NORMALIZE("Normalize", "Set the peak to a fixed level."),
    SLICES("Slices", "Cut a file into 2 to 64 equal parts, saved as numbered files."),
}

class LoadedFile(val name: String, val audio: Audio)

/** The processed result for the current file and preset. */
class Output(val audio: Audio, val file: File, val bytes: ByteArray, val waveform: List<Pair<Float, Float>>)

/** The app's state. Work runs on a background dispatcher; results come back to the main thread. */
class AppModel(private val context: Context, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val presets: List<Preset> = try {
        Presets.parse(context.assets.open("presets.json").use { it.readBytes().toString(Charsets.UTF_8) })
    } catch (e: Exception) {
        emptyList()
    }

    val stack = mutableStateListOf(Screen.START)
    val screen: Screen get() = stack.last()

    var presetId by mutableStateOf(
        prefs.getString("presetID", null)?.takeIf { id -> presets.any { it.id == id } } ?: presets.firstOrNull()?.id ?: ""
    )
        private set
    var dither by mutableStateOf(prefs.getBoolean("dither", true))
        private set

    var file by mutableStateOf<LoadedFile?>(null)
    var output by mutableStateOf<Output?>(null)
    var working by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var note by mutableStateOf<String?>(null)
    var saved by mutableStateOf<String?>(null)
    var pro by mutableStateOf<ProFeature?>(null)
    var playing by mutableStateOf(false)

    private var generation = 0
    private var player: MediaPlayer? = null

    val preset: Preset? get() = presets.firstOrNull { it.id == presetId }

    // ---- Navigation

    fun push(s: Screen) { stack.add(s) }
    fun back(): Boolean {
        if (stack.size <= 1) return false
        if (screen == Screen.FILE) stop()
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun choosePreset(id: String) {
        presetId = id
        prefs.edit().putString("presetID", id).apply()
        reprocess()
    }

    fun setDitherOn(on: Boolean) {
        dither = on
        prefs.edit().putBoolean("dither", on).apply()
        reprocess()
    }

    // ---- Input

    /** Opens the first of the picked files. More than one at a time is Pro (batch). */
    fun open(uris: List<Uri>) {
        val first = uris.firstOrNull() ?: return
        scope.launch {
            load(first)
            if (uris.size > 1 && file != null) {
                note = "Opened ${displayName(first)}. More than one file at a time is part of Pro."
            }
        }
    }

    suspend fun load(uri: Uri) {
        stop()
        error = null
        note = null
        saved = null
        output = null
        val name = withContext(Dispatchers.IO) { displayName(uri) }
        val result = withContext(Dispatchers.IO) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("could not open")
                Result.success(AudioFile.read(bytes))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        result.onSuccess { audio ->
            file = LoadedFile(name.substringBeforeLast('.'), audio)
            stack.clear()
            stack.addAll(listOf(Screen.START, Screen.FILE))
            reprocess()
        }.onFailure { e ->
            file = null
            error = readError(name, e)
        }
    }

    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"

    private fun readError(name: String, e: Throwable): String = when ((e as? ChoppedException)?.reason) {
        ChoppedException.Reason.NOT_WAVE, ChoppedException.Reason.NOT_AIFF, ChoppedException.Reason.NO_DATA,
        ChoppedException.Reason.BAD_FORMAT, ChoppedException.Reason.DATA_BEFORE_FMT ->
            "$name is not a WAV or AIFF file this app can read."
        ChoppedException.Reason.UNSUPPORTED_FORMAT, ChoppedException.Reason.UNSUPPORTED_SAMPLE_RATE ->
            "$name uses an encoding this app does not read yet."
        else -> "Could not open $name."
    }

    // ---- Process

    /** Runs the current preset on the current file. A newer run wins over an older one. */
    fun reprocess() {
        val f = file ?: return
        val p = preset ?: return
        stop()
        saved = null
        val run = ++generation
        val settings = p.settings(dither)
        working = true
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                try {
                    val audio = Processing.process(f.audio, settings)
                    val bytes = Processing.encode(audio, settings)
                    val dir = File(context.cacheDir, "out").apply { deleteRecursively(); mkdirs() }
                    val out = File(dir, "${f.name}.${AudioFile.fileExtension(settings.format)}")
                    out.writeBytes(bytes)
                    Result.success(Output(audio, out, bytes, waveform(audio, 160)))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            if (run != generation) return@launch
            working = false
            result.onSuccess { output = it; error = null }
                .onFailure { output = null; error = "Could not convert this file." }
        }
    }

    /** "48 kHz · 16-bit · mono · WAV", what the saved file will be. */
    val afterText: String?
        get() {
            val o = output ?: return null
            val p = preset ?: return null
            return "${Describe.rate(o.audio.sampleRate)} · ${p.bits}-bit · ${Describe.channels(o.audio.channels.size)} · ${if (p.format == OutputFormat.AIFF) "AIFF" else "WAV"}"
        }

    /**
     * The type for the save dialog, taken from Android's own table for the extension. With any other
     * spelling (audio/wav) the system adds " (1)" after ".wav" when the name is taken, and a
     * sampler no longer sees a WAV file (seen on the emulator).
     */
    val exportMime: String get() = mimeFor(AudioFile.fileExtension(preset?.format ?: OutputFormat.WAV))

    // ---- Play

    fun togglePlay() {
        if (playing) { stop(); return }
        val o = output ?: return
        try {
            player = MediaPlayer().apply {
                setDataSource(o.file.path)
                setOnCompletionListener { stop() }
                prepare()
                start()
            }
            playing = true
        } catch (e: Exception) {
            error = "Could not play the file."
        }
    }

    fun stop() {
        player?.release()
        player = null
        playing = false
    }

    // ---- Save

    /** Writes the result into the document the user created with the system's save dialog. */
    fun saveTo(uri: Uri) {
        val o = output ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(o.bytes) } != null
                } catch (e: Exception) {
                    false
                }
            }
            if (ok) {
                val name = withContext(Dispatchers.IO) { displayName(uri) }
                saved = "Saved $name."
            } else {
                error = "Could not save the file."
            }
        }
    }

    fun newFile() {
        stop()
        file = null
        output = null
        saved = null
        note = null
        error = null
        stack.clear()
        stack.add(Screen.START)
    }

    // ---- Self-test (call off the main thread)

    val hasSelfTest: Boolean = try {
        context.assets.list("testdata")?.contains("cases.json") == true
    } catch (e: Exception) {
        false
    }

    fun runSelfTest(assets: AssetManager): Triple<String, Boolean, List<String>> {
        val started = System.nanoTime()
        return try {
            fun text(name: String) = assets.open("testdata/$name").use { it.readBytes().toString(Charsets.UTF_8) }
            val r = SpecCaseRunner.runAll(
                text("cases.json"),
                text("expected.json"),
                readInput = { name -> assets.open("testdata/input/$name").use { it.readBytes() } },
            )
            val tenths = ((System.nanoTime() - started) / 100_000_000).toInt()
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
            val line = "${r.checked - r.mismatches.size} of ${r.expectedTotal} files match the reference ($abi, Android ${Build.VERSION.RELEASE}, ${tenths / 10}.${tenths % 10} s)"
            val passed = r.checked > 0 && r.checked == r.expectedTotal && r.mismatches.isEmpty() && r.nameProblems.isEmpty()
            Triple(line, passed, r.nameProblems + r.mismatches)
        } catch (e: Exception) {
            Triple("The self-test stopped: $e", false, emptyList())
        }
    }

    companion object {
        fun mimeFor(ext: String): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: if (ext == "aif") "audio/x-aiff" else "audio/x-wav"

        /** The min and max of each slice of the file, for drawing. Display only. */
        fun waveform(a: Audio, count: Int): List<Pair<Float, Float>> {
            val n = a.frameCount
            if (n == 0) return emptyList()
            return (0 until count).map { b ->
                val start = (b.toLong() * n / count).toInt()
                val end = maxOf(start + 1, ((b + 1).toLong() * n / count).toInt()).coerceAtMost(n)
                var lo = 0.0
                var hi = 0.0
                for (ch in a.channels) for (i in start until end) {
                    lo = minOf(lo, ch[i]); hi = maxOf(hi, ch[i])
                }
                maxOf(-1.0, lo).toFloat() to minOf(1.0, hi).toFloat()
            }
        }
    }
}
