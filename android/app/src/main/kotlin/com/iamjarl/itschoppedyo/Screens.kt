package com.iamjarl.itschoppedyo

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.iamjarl.design.DesignTokens
import com.iamjarl.itschoppedyo.core.Describe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The types the importer offers: WAV and AIFF only (the owner's decision for the first version). */
private val importTypes = arrayOf("audio/wav", "audio/x-wav", "audio/vnd.wave", "audio/wave", "audio/aiff", "audio/x-aiff")

/** The system's "save as" dialog, with the type and the suggested name chosen at launch. */
private object CreateAudioDocument : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>) =
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(input.first)
            .putExtra(Intent.EXTRA_TITLE, input.second)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreens(model: AppModel) {
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        model.open(uris)
    }
    val doImport = { importer.launch(importTypes) }

    when (model.screen) {
        Screen.START -> StartScreen(model, doImport)
        Screen.FILE -> FileScreen(model, doImport)
        Screen.PRESETS -> PresetScreen(model)
        Screen.SETTINGS -> SettingsScreen(model)
        Screen.SELF_TEST -> SelfTestScreen(model)
    }

    model.pro?.let { feature ->
        ModalBottomSheet(onDismissRequest = { model.pro = null }) { ProSheet(feature) }
    }
}

// ---- Building blocks

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Page(
    title: String,
    model: AppModel,
    actions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    if (model.stack.size > 1) TextButton(onClick = { model.back() }) { Text("Back") }
                },
                actions = { actions() },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = DesignTokens.Spacing.lg, vertical = DesignTokens.Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.lg),
            content = content,
        )
    }
}

/** A rounded group of rows, like a list section. */
@Composable
private fun Group(header: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs)) {
        header?.let {
            Text(it, style = MaterialTheme.typography.titleSmall, color = DesignTokens.Palette.Text.secondary,
                modifier = Modifier.padding(horizontal = DesignTokens.Spacing.lg))
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(DesignTokens.Radius.lg))
                .background(DesignTokens.Palette.Background.card),
            content = content,
        )
        footer?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = DesignTokens.Palette.Text.tertiary,
                modifier = Modifier.padding(horizontal = DesignTokens.Spacing.lg))
        }
    }
}

@Composable
private fun ListRow(text: String, trailing: @Composable () -> Unit = {}, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = DesignTokens.Spacing.lg, vertical = DesignTokens.Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        trailing()
    }
}

@Composable
private fun ProBadge() {
    Text(
        "Pro",
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        color = DesignTokens.Palette.primary,
        modifier = Modifier.clip(CircleShape).background(DesignTokens.Palette.primarySubtle)
            .padding(horizontal = DesignTokens.Spacing.sm, vertical = DesignTokens.Spacing.xs / 2),
    )
}

/** A Pro tool as a row: the name, the badge, and a tap that opens the Pro sheet. */
@Composable
private fun ProRow(model: AppModel, feature: ProFeature) {
    ListRow(feature.title, trailing = { ProBadge() }) { model.pro = feature }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(start = DesignTokens.Spacing.lg), color = DesignTokens.Palette.Border.subtle)

// ---- Start

@Composable
private fun StartScreen(model: AppModel, doImport: () -> Unit) {
    Page("It's chopped, yo!", model, actions = {
        TextButton(onClick = { model.push(Screen.SETTINGS) }) { Text("Settings") }
    }) {
        Group {
            Column(Modifier.padding(DesignTokens.Spacing.lg), verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.md)) {
                Text("Turn a WAV or AIFF file into the format your sampler wants.",
                    style = MaterialTheme.typography.bodyLarge, color = DesignTokens.Palette.Text.secondary)
                Button(onClick = doImport, modifier = Modifier.fillMaxWidth()) {
                    Text("Import a file", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = DesignTokens.Spacing.xs))
                }
                if (model.file == null) model.error?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = DesignTokens.Palette.State.error)
                }
            }
        }
        Group {
            ProRow(model, ProFeature.RECORD); Divider()
            ProRow(model, ProFeature.BATCH); Divider()
            ProRow(model, ProFeature.CHAIN)
        }
    }
}

// ---- File

@Composable
private fun FileScreen(model: AppModel, doImport: () -> Unit) {
    val context = LocalContext.current
    val saver = rememberLauncherForActivityResult(CreateAudioDocument) { uri -> uri?.let { model.saveTo(it) } }
    val file = model.file

    Page(file?.name ?: "", model) {
        Group {
            Box(Modifier.fillMaxWidth().height(96.dp).padding(DesignTokens.Spacing.lg), contentAlignment = Alignment.Center) {
                Waveform(model.output?.waveform ?: emptyList())
                if (model.working) CircularProgressIndicator()
            }
            Divider()
            if (file != null) {
                Column(Modifier.padding(horizontal = DesignTokens.Spacing.lg, vertical = DesignTokens.Spacing.md)) {
                    Text("${Describe.summary(file.audio)} · ${Describe.peak(file.audio)}",
                        style = MaterialTheme.typography.bodyMedium, color = DesignTokens.Palette.Text.secondary)
                    model.afterText?.let { Text("→ $it", style = MaterialTheme.typography.bodyMedium) }
                }
                Divider()
            }
            ListRow(if (model.playing) "Stop" else "Play the result",
                onClick = if (model.output != null) ({ model.togglePlay() }) else null)
            model.note?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = DesignTokens.Palette.Text.tertiary,
                    modifier = Modifier.padding(horizontal = DesignTokens.Spacing.lg, vertical = DesignTokens.Spacing.sm))
            }
            model.error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = DesignTokens.Palette.State.error,
                    modifier = Modifier.padding(horizontal = DesignTokens.Spacing.lg, vertical = DesignTokens.Spacing.sm))
            }
        }

        Group(header = "Format") {
            ListRow("Preset", trailing = {
                Text(model.preset?.name ?: "None", style = MaterialTheme.typography.bodyLarge, color = DesignTokens.Palette.Text.secondary)
            }) { model.push(Screen.PRESETS) }
        }

        Group(header = "Edit") {
            ProRow(model, ProFeature.TRIM); Divider()
            ProRow(model, ProFeature.FADE); Divider()
            ProRow(model, ProFeature.NORMALIZE); Divider()
            ProRow(model, ProFeature.SLICES)
        }

        Group {
            val output = model.output
            ListRow("Save to a folder", onClick = if (output != null && file != null) ({
                saver.launch(model.exportMime to output.file.name)
            }) else null)
            Divider()
            ListRow("Share", onClick = if (output != null) ({ share(context, output) }) else null)
            model.saved?.let {
                Divider()
                Text(it, style = MaterialTheme.typography.bodyLarge, color = DesignTokens.Palette.State.success,
                    modifier = Modifier.padding(horizontal = DesignTokens.Spacing.lg, vertical = DesignTokens.Spacing.md))
                Divider()
                ListRow("New file") { model.newFile(); doImport() }
            }
        }
        Spacer(Modifier.height(DesignTokens.Spacing.lg))
    }
}

private fun share(context: Context, output: Output) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", output.file)
    val send = Intent(Intent.ACTION_SEND).setType(AppModel.mimeFor(output.file.extension))
        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null))
}

@Composable
private fun Waveform(buckets: List<Pair<Float, Float>>) {
    val color = DesignTokens.Palette.primary
    Canvas(Modifier.fillMaxSize()) {
        if (buckets.isEmpty()) return@Canvas
        val mid = size.height / 2
        val w = size.width / buckets.size
        buckets.forEachIndexed { i, (lo, hi) ->
            val x = i * w + w / 2
            drawLine(color, Offset(x, mid - hi * mid), Offset(x, mid - lo * mid + 0.5f), strokeWidth = maxOf(1f, w * 0.6f))
        }
    }
}

// ---- Presets

@Composable
private fun PresetScreen(model: AppModel) {
    Page("Preset", model) {
        Group(footer = "Presets are named by format. Pick the one your sampler's manual asks for. All of them save a WAV file; mono adds left and right together.") {
            model.presets.forEachIndexed { i, p ->
                if (i > 0) Divider()
                ListRow(p.name, trailing = {
                    if (p.id == model.presetId) Text("✓", color = DesignTokens.Palette.primary, style = MaterialTheme.typography.titleMedium)
                }) { model.choosePreset(p.id) }
            }
        }
        Group { ProRow(model, ProFeature.CUSTOM_PRESET) }
    }
}

// ---- Pro

@Composable
private fun ProSheet(feature: ProFeature) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = DesignTokens.Spacing.lg, vertical = DesignTokens.Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.md),
    ) {
        Text("Pro", style = MaterialTheme.typography.titleLarge)
        Text("That is part of Pro. Pro is a one-time purchase, no subscription.", style = MaterialTheme.typography.bodyLarge)
        ProFeature.entries.forEach { f ->
            Column {
                Text(f.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (f == feature) FontWeight.SemiBold else FontWeight.Normal))
                Text(f.detail, style = MaterialTheme.typography.bodyMedium, color = DesignTokens.Palette.Text.secondary)
            }
        }
        Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Buy Pro") }
        TextButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Restore purchase") }
        Text("Purchases are not set up in this build yet.", style = MaterialTheme.typography.bodySmall, color = DesignTokens.Palette.Text.tertiary)
        Spacer(Modifier.height(DesignTokens.Spacing.xl))
    }
}

// ---- Settings

@Composable
private fun SettingsScreen(model: AppModel) {
    val context = LocalContext.current
    Page("Settings", model) {
        Group(footer = "Adds a tiny amount of noise when the bit depth goes down, so quiet parts fade out smoothly instead of breaking up. On is the usual choice.") {
            ListRow("Dither", trailing = { Switch(checked = model.dither, onCheckedChange = { model.setDitherOn(it) }) })
        }
        Group { ListRow("Pro", trailing = { ProBadge() }) { model.pro = ProFeature.BATCH } }
        Group(header = "Privacy") {
            Text("Everything happens on this phone. No account, no ads, no tracking.",
                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(DesignTokens.Spacing.lg))
        }
        Group(header = "Licenses") {
            Text("Resampling uses libsamplerate 0.2.2 by Erik de Castro Lopo, under the BSD 2-Clause license.",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(DesignTokens.Spacing.lg))
        }
        val version = context.packageManager.getPackageInfo(context.packageName, 0).let { "Version ${it.versionName} (${it.longVersionCode})" }
        Group(footer = version) {
            if (model.hasSelfTest) ListRow("Self-test") { model.push(Screen.SELF_TEST) }
        }
    }
}

// ---- Self-test

@Composable
private fun SelfTestScreen(model: AppModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Triple<String, Boolean, List<String>>?>(null) }
    Page("Self-test", model) {
        Group(footer = "Converts every test file on this phone and checks that each result is exactly what the reference gives, byte for byte.") {
            ListRow(if (running) "Running…" else "Run the self-test", onClick = if (running) null else ({
                running = true
                result = null
                scope.launch {
                    result = withContext(Dispatchers.Default) { model.runSelfTest(context.assets) }
                    running = false
                }
            }))
            result?.let { (line, passed, problems) ->
                Divider()
                Text(line, color = if (passed) DesignTokens.Palette.State.success else DesignTokens.Palette.State.error,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = DesignTokens.Spacing.lg, vertical = DesignTokens.Spacing.md))
                problems.forEach {
                    Text(it, color = DesignTokens.Palette.State.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = DesignTokens.Spacing.lg))
                }
            }
        }
    }
}
