package com.iamjarl.itschoppedyo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import com.iamjarl.design.DesignTokens
import com.iamjarl.design.DesignTokens.ColorToken
import com.iamjarl.itschoppedyo.core.MonoMode
import com.iamjarl.itschoppedyo.core.OutputFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The test app: a self-test of the audio core on this device, and a manual test of one file.
 * It is a test screen for the spike, not the app's design. Colours, spacing and type come from the
 * IAMJARL design tokens (com.iamjarl.design.DesignTokens, copied from iamjarl-design v1.38.0).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ChoppedTheme { TestScreen() } }
    }
}

/** Material 3 with the design tokens: their colours for light and dark, their type scale. */
@Composable
fun ChoppedTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = ColorToken.Dark.primary,
            onPrimary = ColorToken.Dark.onPrimary,
            background = ColorToken.Dark.Background.app,
            onBackground = ColorToken.Dark.Text.primary,
            surface = ColorToken.Dark.Surface.default,
            onSurface = ColorToken.Dark.Text.primary,
            onSurfaceVariant = ColorToken.Dark.Text.secondary,
            outlineVariant = ColorToken.Dark.Border.subtle,
            error = ColorToken.Dark.State.error,
        )
    } else {
        lightColorScheme(
            primary = ColorToken.Light.primary,
            onPrimary = ColorToken.Light.onPrimary,
            background = ColorToken.Light.Background.app,
            onBackground = ColorToken.Light.Text.primary,
            surface = ColorToken.Light.Surface.default,
            onSurface = ColorToken.Light.Text.primary,
            onSurfaceVariant = ColorToken.Light.Text.secondary,
            outlineVariant = ColorToken.Light.Border.subtle,
            error = ColorToken.Light.State.error,
        )
    }
    MaterialTheme(colorScheme = colors, typography = DesignTokens.Typography.material3(), content = content)
}

@Composable
fun TestScreen() {
    val model = remember { TestModel() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resolver = context.contentResolver

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            model.error = null
            model.outputs = emptyList()
            model.outputSummary = null
            model.saved = null
            val (audio, name, info) = withContext(Dispatchers.IO) { model.load(resolver, uri) }
            model.input = audio
            model.inputName = name
            if (audio != null) model.inputInfo = info else { model.inputInfo = null; model.error = info }
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@rememberLauncherForActivityResult
        val files = model.outputs
        val format = model.format
        scope.launch {
            model.saved = withContext(Dispatchers.IO) {
                try { model.save(resolver, tree, files, format) } catch (e: Exception) { "Could not save: $e" }
            }
        }
    }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(DesignTokens.Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
        ) {
            Text("It's chopped, yo!", style = MaterialTheme.typography.headlineSmall)

            // ---- Self-test
            Text("Self-test", style = MaterialTheme.typography.titleMedium, color = DesignTokens.Palette.Text.secondary)
            Button(
                enabled = !model.selfTestRunning,
                onClick = {
                    model.selfTestRunning = true
                    model.selfTestSummary = null
                    scope.launch {
                        val (line, passed, problems) = withContext(Dispatchers.Default) { model.runSelfTest(context.assets) }
                        model.selfTestSummary = line
                        model.selfTestPassed = passed
                        model.selfTestProblems = problems
                        model.selfTestRunning = false
                    }
                },
            ) { Text(if (model.selfTestRunning) "Running…" else "Run the self-test") }
            model.selfTestSummary?.let { Text(it, color = if (model.selfTestPassed) DesignTokens.Palette.State.success else DesignTokens.Palette.State.error) }
            model.selfTestProblems.forEach { Text(it, color = DesignTokens.Palette.State.error, style = MaterialTheme.typography.bodySmall) }
            Text(
                "Runs every case in the test files on this device and compares each output's SHA-256 with the reference.",
                style = MaterialTheme.typography.bodySmall,
                color = DesignTokens.Palette.Text.tertiary,
            )
            HorizontalDivider()

            // ---- File
            Text("File", style = MaterialTheme.typography.titleMedium, color = DesignTokens.Palette.Text.secondary)
            OutlinedButton(onClick = { openFile.launch(arrayOf("audio/*")) }) { Text("Choose a WAV or AIFF file") }
            model.inputInfo?.let { Text(it, fontFamily = FontFamily.Monospace) }
            model.error?.let { Text(it, color = DesignTokens.Palette.State.error) }

            if (model.input != null) {
                HorizontalDivider()
                Text("Settings", style = MaterialTheme.typography.titleMedium, color = DesignTokens.Palette.Text.secondary)
                Choice("Channels", listOf("Keep" to MonoMode.KEEP, "Mono (sum)" to MonoMode.SUM,
                    "Left only" to MonoMode.LEFT, "Right only" to MonoMode.RIGHT), model.mono) { model.mono = it }
                Choice("Sample rate", listOf("Keep" to 0) + TestModel.rates.map { "$it Hz" to it }, model.sampleRate) { model.sampleRate = it }
                Choice("Bit depth", listOf("16-bit" to 16, "24-bit" to 24), model.bits) { model.bits = it }
                Toggle("Dither", model.dither) { model.dither = it }
                Choice("Normalize", listOf("Off" to -1) + TestModel.normalizeTargets.mapIndexed { i, d -> "%.1f dBFS".format(d) to i },
                    model.normalizeIndex) { model.normalizeIndex = it }
                Stepper("Fade in", model.fadeInMs) { model.fadeInMs = it }
                Stepper("Fade out", model.fadeOutMs) { model.fadeOutMs = it }
                Toggle("Equal-power fades", model.equalPower) { model.equalPower = it }
                Choice("Slices", listOf("None" to 1) + listOf(2, 4, 8, 16, 32, 64).map { "$it" to it }, model.slices) { model.slices = it }
                Choice("Format", listOf("WAV" to OutputFormat.WAV, "AIFF" to OutputFormat.AIFF), model.format) { model.format = it }

                HorizontalDivider()
                Text("Output", style = MaterialTheme.typography.titleMedium, color = DesignTokens.Palette.Text.secondary)
                Button(
                    enabled = !model.processing,
                    onClick = {
                        val input = model.input ?: return@Button
                        val s = model.settings()
                        val count = model.slices
                        val name = model.inputName
                        model.processing = true
                        model.saved = null
                        scope.launch {
                            try {
                                val (files, summary) = withContext(Dispatchers.Default) { model.process(input, s, count, name) }
                                model.outputs = files
                                model.outputSummary = summary
                                model.error = null
                            } catch (e: Exception) {
                                model.outputs = emptyList()
                                model.outputSummary = null
                                model.error = "Could not process: $e"
                            }
                            model.processing = false
                        }
                    },
                ) { Text(if (model.processing) "Processing…" else "Process") }
                model.outputSummary?.let { Text(it, fontFamily = FontFamily.Monospace) }
                if (model.outputs.isNotEmpty()) {
                    OutlinedButton(onClick = { pickFolder.launch(null) }) {
                        Text(if (model.outputs.size == 1) "Save to a folder" else "Save ${model.outputs.size} files to a folder")
                    }
                }
                model.saved?.let { Text(it) }
            }
            Spacer(Modifier.padding(DesignTokens.Spacing.lg))
        }
    }
}

@Composable
private fun <T> Choice(label: String, options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Box {
            TextButton(onClick = { open = true }) { Text(options.firstOrNull { it.second == selected }?.first ?: "?") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (text, value) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); open = false })
                }
            }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Stepper(label: String, ms: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$label: $ms ms", Modifier.weight(1f))
        TextButton(onClick = { onChange(maxOf(0, ms - 5)) }) { Text("−") }
        TextButton(onClick = { onChange(minOf(2000, ms + 5)) }) { Text("+") }
    }
}
