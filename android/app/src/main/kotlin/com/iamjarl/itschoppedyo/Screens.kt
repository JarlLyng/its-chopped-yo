package com.iamjarl.itschoppedyo

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.core.content.FileProvider
import com.iamjarl.design.DesignTokens
import com.iamjarl.design.DesignTokens.ColorToken
import com.iamjarl.itschoppedyo.core.Describe
import com.iamjarl.itschoppedyo.core.OutputFormat
import com.iamjarl.itschoppedyo.core.Preset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * "The panel": the app looks like a small piece of studio gear. A display that is always dark sits
 * on a panel of keys with LEDs and small printed group labels. Every colour is a design token
 * (iamjarl-design 1.38.0): the panel follows light and dark, the display always uses the dark
 * theme's colours. The same screens and words as the iOS app.
 */

/** The types the importer offers: WAV and AIFF only (the owner's decision for the first version). */
private val importTypes = arrayOf("audio/wav", "audio/x-wav", "audio/vnd.wave", "audio/wave", "audio/aiff", "audio/x-aiff")

/** The system's "save as" dialog, with the type and the suggested name chosen at launch. */
private object CreateAudioDocument : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>) =
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(input.first)
            .putExtra(Intent.EXTRA_TITLE, input.second)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data
}

// ---- The panel's colours and type

private object Panel {
    val key: Color @Composable get() = DesignTokens.Palette.Background.card
    val keyBorder: Color @Composable get() = DesignTokens.Palette.Border.default
    val rule: Color @Composable get() = DesignTokens.Palette.Border.subtle
    val ledOff: Color @Composable get() = DesignTokens.Palette.Border.default
    val displayBorder: Color @Composable get() = DesignTokens.adaptive(ColorToken.Dark.Background.app, ColorToken.Dark.Border.default)

    val display = ColorToken.Dark.Background.app
    val displayText = ColorToken.Dark.primary
    val displayText2 = ColorToken.Dark.Text.secondary
    val displayText3 = ColorToken.Dark.Text.tertiary
    val displayDim = ColorToken.Dark.primarySubtle
    val displayInk = ColorToken.Dark.onPrimary
    val playhead = ColorToken.Dark.Text.primary
}

private object Size {
    val xs = DesignTokens.Typography.Size.xs
    val sm = DesignTokens.Typography.Size.sm
    val base = DesignTokens.Typography.Size.base
    val lg = DesignTokens.Typography.Size.lg
    val xl = DesignTokens.Typography.Size.xl
    val xxl = DesignTokens.Typography.Size.xxl
}

private fun mono(size: TextUnit, tracking: Float = 0.06f) =
    TextStyle(fontFamily = FontFamily.Monospace, fontSize = size, letterSpacing = tracking.em)

private fun ui(size: TextUnit, weight: FontWeight = FontWeight.Normal) = TextStyle(fontSize = size, fontWeight = weight)

// ---- Root

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreens(model: AppModel) {
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        model.open(uris)
    }
    val doImport = { importer.launch(importTypes) }

    when (model.screen) {
        Screen.START -> StartScreen(model, doImport)
        Screen.FILE -> FileScreen(model)
        Screen.PRESETS -> PresetScreen(model)
        Screen.SAVED -> SavedScreen(model, doImport)
        Screen.SETTINGS -> SettingsScreen(model)
        Screen.SELF_TEST -> SelfTestScreen(model)
    }

    model.pro?.let { feature ->
        ModalBottomSheet(
            onDismissRequest = { model.pro = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = DesignTokens.Palette.Background.app,
        ) { ProSheet(feature) }
    }
}

// ---- Building blocks

/** A screen of the app: the panel background, a scrolling column, an optional bar at the bottom. */
@Composable
private fun PanelScreen(bottom: (@Composable RowScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        containerColor = DesignTokens.Palette.Background.app,
        contentColor = DesignTokens.Palette.Text.primary,
        bottomBar = {
            if (bottom != null) {
                Row(
                    // Above the system's navigation bar: three-button navigation is tall (seen on a
                    // Galaxy), gesture navigation is a thin line.
                    Modifier.fillMaxWidth().background(DesignTokens.Palette.Background.app)
                        .navigationBarsPadding()
                        .padding(horizontal = DesignTokens.Spacing.xl, vertical = DesignTokens.Spacing.sm)
                        .padding(bottom = DesignTokens.Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
                    content = bottom,
                )
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = DesignTokens.Spacing.xl, vertical = DesignTokens.Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.lg),
            content = content,
        )
    }
}

/** The small printed label over a group, with a line after it. */
@Composable
private fun SilkLabel(title: String, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().semantics { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
    ) {
        Text(title, style = mono(Size.xs, 0.14f), color = DesignTokens.Palette.Text.tertiary)
        Box(Modifier.weight(1f).height(1.dp).background(Panel.rule))
        trailing()
    }
}

@Composable
private fun Led(on: Boolean, size: Dp = 10.dp) {
    val primary = DesignTokens.Palette.primary
    val off = Panel.ledOff
    Canvas(Modifier.size(size)) {
        if (on) {
            drawCircle(primary)
        } else {
            val w = 1.5.dp.toPx()
            drawCircle(off, radius = this.size.minDimension / 2 - w / 2, style = Stroke(w))
        }
    }
}

@Composable
private fun ProTag() {
    Text(
        "PRO", style = mono(Size.xs, 0.1f), color = DesignTokens.Palette.primary,
        modifier = Modifier.clip(CircleShape).background(DesignTokens.Palette.primarySubtle)
            .padding(horizontal = DesignTokens.Spacing.sm - 2.dp, vertical = 1.dp),
    )
}

/** A key on the panel. A selected key gets a primary border. */
@Composable
private fun Key(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    radius: Dp = DesignTokens.Radius.md,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier.dimmedWhenDisabled(enabled).clip(shape).background(Panel.key)
            .border(if (selected) BorderStroke(2.dp, DesignTokens.Palette.primary) else BorderStroke(1.dp, Panel.keyBorder), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected },
        content = content,
    )
}

/**
 * Dims the whole key or pad, background included, while it is disabled. Only added then: with an
 * alpha layer that went from 0.4 to 1 inside the key, its background and border were not drawn
 * again once it was enabled (seen on a Galaxy A17, Android 17).
 */
private fun Modifier.dimmedWhenDisabled(enabled: Boolean) =
    if (enabled) this else this.alpha(DesignTokens.Opacity.disabled)

/** The one big action on a screen: a pad in the primary colour. */
@Composable
private fun Pad(modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(DesignTokens.Radius.lg)
    Box(
        modifier.dimmedWhenDisabled(enabled).clip(shape).background(DesignTokens.Palette.primary)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        content = content,
    )
}

/** The display: always dark, values in the monospaced face. */
@Composable
private fun DisplayPanel(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(DesignTokens.Radius.lg)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Panel.display).border(1.dp, Panel.displayBorder, shape)
            .padding(DesignTokens.Spacing.md + 2.dp),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
        content = content,
    )
}

/** The top of a screen: a key to go back, a printed label and a title. */
@Composable
private fun PanelHeader(label: String, title: String, back: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.md)) {
        if (back != null) {
            Key(Modifier.size(44.dp).semantics { contentDescription = "Back" }, onClick = back) {
                ChevronIcon(Modifier.align(Alignment.Center))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = mono(Size.xs, 0.14f), color = DesignTokens.Palette.Text.tertiary)
            Text(title, style = ui(Size.lg, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A Pro tool as a key: dark LED, the Pro tag, a tap opens the Pro sheet. */
@Composable
private fun ProKey(model: AppModel, feature: ProFeature, modifier: Modifier, sub: String? = null) {
    Key(
        modifier.semantics { contentDescription = "${feature.title}, part of Pro" },
        radius = if (sub == null) DesignTokens.Radius.md else DesignTokens.Radius.lg - 2.dp,
        onClick = { model.pro = feature },
    ) {
        Column(Modifier.padding(DesignTokens.Spacing.sm + 2.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Led(false, if (sub == null) 8.dp else 10.dp)
                Spacer(Modifier.weight(1f))
                if (sub != null) ProTag()
            }
            Column {
                Text(
                    feature.key, style = mono(if (sub == null) Size.xs else Size.sm, 0.08f),
                    color = if (sub == null) DesignTokens.Palette.Text.secondary else DesignTokens.Palette.Text.primary,
                )
                if (sub != null) Text(sub, style = ui(Size.xs), color = DesignTokens.Palette.Text.tertiary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ChevronIcon(modifier: Modifier) {
    val c = DesignTokens.Palette.Text.primary
    Canvas(modifier.size(20.dp)) {
        val s = 1.8.dp.toPx()
        drawLine(c, Offset(size.width * 0.62f, size.height * 0.2f), Offset(size.width * 0.33f, size.height * 0.5f), strokeWidth = s)
        drawLine(c, Offset(size.width * 0.33f, size.height * 0.5f), Offset(size.width * 0.62f, size.height * 0.8f), strokeWidth = s)
    }
}

@Composable
private fun SlidersIcon(modifier: Modifier) {
    val c = DesignTokens.Palette.Text.primary
    val bg = Panel.key
    Canvas(modifier.size(20.dp)) {
        val s = 1.8.dp.toPx()
        val r = 2.6.dp.toPx()
        listOf(0.25f to 0.35f, 0.5f to 0.65f, 0.75f to 0.3f).forEach { (y, x) ->
            drawLine(c, Offset(size.width * 0.1f, size.height * y), Offset(size.width * 0.9f, size.height * y), strokeWidth = s)
            drawCircle(bg, radius = r, center = Offset(size.width * x, size.height * y))
            drawCircle(c, radius = r, center = Offset(size.width * x, size.height * y), style = Stroke(s))
        }
    }
}

private fun Modifier.dashedBorder(color: Color, radius: Dp) = drawBehind {
    val r = radius.toPx()
    drawRoundRect(
        color, cornerRadius = CornerRadius(r, r),
        style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
    )
}

// ---- Start

@Composable
private fun StartScreen(model: AppModel, doImport: () -> Unit) {
    PanelScreen {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("SAMPLE PREP", style = mono(Size.xs, 0.14f), color = DesignTokens.Palette.Text.tertiary)
                Text("It's chopped, yo!", style = ui(Size.xl, FontWeight.Bold))
            }
            Key(Modifier.size(44.dp).semantics { contentDescription = "Settings" }, onClick = { model.push(Screen.SETTINGS) }) {
                SlidersIcon(Modifier.align(Alignment.Center))
            }
        }

        DisplayPanel {
            Row(Modifier.fillMaxWidth()) {
                Text("READY", style = mono(Size.xs), color = Panel.displayText)
                Spacer(Modifier.weight(1f))
                Text("WAV · AIFF IN", style = mono(Size.xs), color = Panel.displayText3)
            }
            Box(Modifier.padding(vertical = DesignTokens.Spacing.lg).fillMaxWidth().height(2.dp).background(Panel.displayDim))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
                Text("NO FILE", style = mono(Size.base), color = Panel.displayText)
                Box(Modifier.width(9.dp).height(16.dp).background(Panel.displayText))
            }
        }

        Pad(Modifier.fillMaxWidth().semantics { contentDescription = "Import a file" }, onClick = doImport) {
            Column(
                Modifier.padding(DesignTokens.Spacing.lg + 2.dp).fillMaxWidth().heightIn(min = 154.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(DesignTokens.Palette.onPrimary))
                Column {
                    Text("Import a file", style = ui(Size.xl, FontWeight.Bold), color = DesignTokens.Palette.onPrimary)
                    Text(
                        "Turn a WAV or AIFF file into the format your sampler wants.", style = ui(Size.sm),
                        color = DesignTokens.Palette.onPrimary, modifier = Modifier.alpha(0.85f),
                    )
                }
            }
        }
        if (model.file == null) model.error?.let { Text(it, style = ui(Size.sm), color = DesignTokens.Palette.State.error) }

        SilkLabel("MORE")
        Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm + 2.dp)) {
            ProKey(model, ProFeature.RECORD, Modifier.weight(1f).height(104.dp), "Record")
            ProKey(model, ProFeature.BATCH, Modifier.weight(1f).height(104.dp), "A whole folder")
            ProKey(model, ProFeature.CHAIN, Modifier.weight(1f).height(104.dp), "Sample chains")
        }

        Text(
            "Everything happens on this phone.", style = ui(Size.xs), color = DesignTokens.Palette.Text.tertiary,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = DesignTokens.Spacing.lg),
        )
    }
}

// ---- File

@Composable
private fun FileScreen(model: AppModel) {
    val context = LocalContext.current
    val saver = rememberLauncherForActivityResult(CreateAudioDocument) { uri -> uri?.let { model.saveTo(it) } }
    val file = model.file
    val output = model.output
    val preset = model.preset

    PanelScreen(bottom = {
        Pad(Modifier.weight(2f).height(64.dp), enabled = output != null, onClick = {
            if (output != null) saver.launch(model.exportMime to output.file.name)
        }) {
            Text(
                "Save to a folder", style = ui(Size.lg, FontWeight.Bold), color = DesignTokens.Palette.onPrimary,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Key(
            Modifier.weight(1f).height(64.dp), radius = DesignTokens.Radius.lg, enabled = output != null,
            onClick = { output?.let { share(context, it) } },
        ) {
            Text("Share", style = ui(Size.base, FontWeight.SemiBold), modifier = Modifier.align(Alignment.Center))
        }
    }) {
        PanelHeader("FILE", output?.file?.name ?: file?.name ?: "") { model.back() }

        DisplayPanel {
            if (file != null) {
                Row(Modifier.fillMaxWidth()) {
                    Text(Describe.inLine(file.audio), style = mono(Size.xs), color = Panel.displayText)
                    Spacer(Modifier.weight(1f))
                    Text(Describe.shortPeak(file.audio), style = mono(Size.xs), color = Panel.displayText)
                }
            }
            Box(Modifier.fillMaxWidth().height(84.dp), contentAlignment = Alignment.Center) {
                WaveformDisplay(output?.waveform ?: emptyList(), model.playing) { model.progress() }
                if (model.working) CircularProgressIndicator(color = Panel.displayText)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (output != null && preset != null) {
                    Text(
                        Describe.outLine(output.audio, preset.bits, preset.format), style = mono(Size.sm), color = Panel.displayInk,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Panel.displayText)
                            .padding(horizontal = DesignTokens.Spacing.sm, vertical = 2.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(Describe.shortDuration(output.audio.frameCount, output.audio.sampleRate), style = mono(Size.sm), color = Panel.displayText2)
                } else {
                    Text(" ", style = mono(Size.sm))
                }
            }
        }

        Key(
            Modifier.fillMaxWidth().heightIn(min = 56.dp), selected = model.playing, radius = DesignTokens.Radius.lg - 2.dp,
            enabled = output != null, onClick = { model.togglePlay() },
        ) {
            Row(
                Modifier.padding(horizontal = DesignTokens.Spacing.md + 2.dp).heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm + 2.dp),
            ) {
                Led(model.playing)
                Text(if (model.playing) "■" else "▶", style = ui(Size.base))
                Text(if (model.playing) "STOP" else "PLAY", style = mono(Size.sm, 0.08f))
                Text("the result", style = ui(Size.xs), color = DesignTokens.Palette.Text.tertiary)
            }
        }
        model.note?.let { Text(it, style = ui(Size.sm), color = DesignTokens.Palette.Text.tertiary) }
        model.error?.let { Text(it, style = ui(Size.sm), color = DesignTokens.Palette.State.error) }

        SilkLabel("FORMAT") {
            Text(
                "ALL ›", style = mono(Size.xs, 0.14f), color = DesignTokens.Palette.Text.secondary,
                modifier = Modifier.clickable(role = Role.Button) { model.push(Screen.PRESETS) }
                    .padding(vertical = DesignTokens.Spacing.md)
                    .semantics { contentDescription = "All presets" },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
            model.presets.forEach { p -> PresetKey(model, p, Modifier.weight(1f)) }
        }

        SilkLabel("EDIT") { ProTag() }
        Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
            listOf(ProFeature.TRIM, ProFeature.FADE, ProFeature.NORMALIZE, ProFeature.SLICES).forEach {
                ProKey(model, it, Modifier.weight(1f).height(56.dp))
            }
        }
    }
}

@Composable
private fun PresetKey(model: AppModel, preset: Preset, modifier: Modifier) {
    val on = preset.id == model.presetId
    Key(
        modifier.heightIn(min = 76.dp).semantics { contentDescription = preset.name }, selected = on,
        onClick = { model.choosePreset(preset.id) },
    ) {
        Column(Modifier.padding(DesignTokens.Spacing.sm).heightIn(min = 60.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Led(on)
            Column {
                Text(Describe.shortRate(preset.sampleRate), style = mono(Size.base, 0.02f))
                // Shrinks a little on narrow phones instead of losing a word, like iOS's minimumScaleFactor.
                BasicText(
                    "${preset.bits} · ${if (preset.channels == Preset.Channels.MONO) "MONO" else "ST"}",
                    style = mono(Size.xs, 0.02f).copy(color = DesignTokens.Palette.Text.tertiary),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = Size.xs * 0.8f, maxFontSize = Size.xs),
                )
            }
        }
    }
}

private fun share(context: Context, output: Output) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", output.file)
    val send = Intent(Intent.ACTION_SEND).setType(AppModel.mimeFor(output.file.extension))
        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null))
}

/** The output's waveform on the display, with a playhead while it plays. Display only. */
@Composable
private fun WaveformDisplay(buckets: List<Pair<Float, Float>>, playing: Boolean, progress: () -> Float) {
    var at by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        while (playing) {
            at = progress()
            delay(16)
        }
        at = 0f
    }
    Canvas(Modifier.fillMaxSize()) {
        val mid = size.height / 2
        if (buckets.isEmpty()) {
            drawLine(Panel.displayDim, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 2.dp.toPx())
            return@Canvas
        }
        val w = size.width / buckets.size
        buckets.forEachIndexed { i, (lo, hi) ->
            val x = i * w + w / 2
            drawLine(Panel.displayText, Offset(x, mid - hi * mid), Offset(x, mid - lo * mid + 1f), strokeWidth = maxOf(1f, w * 0.6f))
        }
        if (playing) {
            val x = size.width * at
            drawLine(Panel.playhead, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
        }
    }
}

// ---- Presets

@Composable
private fun PresetScreen(model: AppModel) {
    PanelScreen {
        PanelHeader("FORMAT", "Presets") { model.back() }
        model.preset?.let { p ->
            DisplayPanel {
                Text("SELECTED", style = mono(Size.xs), color = Panel.displayText3)
                Text(p.name, style = mono(Size.lg, 0.02f), color = Panel.displayText)
                Text(
                    "${if (p.format == OutputFormat.AIFF) "AIFF" else "WAV"} · ${if (p.channels == Preset.Channels.MONO) "LEFT + RIGHT SUMMED" else "CHANNELS AS THEY ARE"}",
                    style = mono(Size.sm), color = Panel.displayText2,
                )
            }
        }
        SilkLabel("BUILT IN")
        Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
            model.presets.forEach { p ->
                val on = p.id == model.presetId
                Key(Modifier.fillMaxWidth(), selected = on, onClick = { model.choosePreset(p.id) }) {
                    Row(
                        Modifier.padding(horizontal = DesignTokens.Spacing.lg).heightIn(min = 60.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.md + 2.dp),
                    ) {
                        Led(on)
                        Text(p.name, style = mono(Size.base, 0.02f), modifier = Modifier.weight(1f))
                        Text(if (p.format == OutputFormat.AIFF) "AIFF" else "WAV", style = mono(Size.xs), color = DesignTokens.Palette.Text.tertiary)
                    }
                }
            }
        }
        Text(
            "Presets are named by format. Pick the one your sampler's manual asks for. Mono adds left and right together.",
            style = ui(Size.sm), color = DesignTokens.Palette.Text.tertiary,
        )

        SilkLabel("YOURS")
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).dashedBorder(Panel.keyBorder, DesignTokens.Radius.md)
                .clip(RoundedCornerShape(DesignTokens.Radius.md))
                .clickable(role = Role.Button) { model.pro = ProFeature.CUSTOM_PRESET }
                .padding(horizontal = DesignTokens.Spacing.lg)
                .semantics { contentDescription = "Your own presets, part of Pro" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.md + 2.dp),
        ) {
            Text("+", style = ui(Size.lg), color = DesignTokens.Palette.Text.secondary)
            Text("Your own presets", style = ui(Size.base), color = DesignTokens.Palette.Text.secondary, modifier = Modifier.weight(1f))
            ProTag()
        }
    }
}

// ---- Saved

@Composable
private fun SavedScreen(model: AppModel, doImport: () -> Unit) {
    val context = LocalContext.current
    val output = model.output
    val preset = model.preset
    PanelScreen(bottom = {
        Pad(Modifier.weight(2f).height(64.dp), onClick = { model.newFile(); doImport() }) {
            Text(
                "New file", style = ui(Size.lg, FontWeight.Bold), color = DesignTokens.Palette.onPrimary,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Key(
            Modifier.weight(1f).height(64.dp), radius = DesignTokens.Radius.lg, enabled = output != null,
            onClick = { output?.let { share(context, it) } },
        ) {
            Text("Share", style = ui(Size.base, FontWeight.SemiBold), modifier = Modifier.align(Alignment.Center))
        }
    }) {
        PanelHeader("FILE", model.saved ?: "") { model.back() }
        DisplayPanel {
            Row(Modifier.fillMaxWidth()) {
                Text("WRITE", style = mono(Size.xs), color = Panel.displayText)
                Spacer(Modifier.weight(1f))
                Text("OK", style = mono(Size.xs), color = Panel.displayText3)
            }
            Spacer(Modifier.height(DesignTokens.Spacing.xxl))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.md)) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(Panel.displayText))
                Text("SAVED", style = mono(Size.xxl), color = Panel.displayText)
            }
            Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Panel.displayText))
            Spacer(Modifier.height(DesignTokens.Spacing.xxl))
            model.saved?.let { Text(it, style = mono(Size.sm), color = Panel.displayText) }
            if (output != null && preset != null) {
                Text(
                    Describe.outLine(output.audio, preset.bits, preset.format).removePrefix("OUT ") + " · " + Describe.size(output.bytes.size.toLong()),
                    style = mono(Size.sm), color = Panel.displayText2,
                )
            }
        }
        Text(
            "Put the file on your sampler the way its manual says: a card, USB or the maker's transfer app.",
            style = ui(Size.sm), color = DesignTokens.Palette.Text.secondary,
        )
    }
}

// ---- Pro

@Composable
private fun ProSheet(feature: ProFeature) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = DesignTokens.Spacing.xl, vertical = DesignTokens.Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.lg),
    ) {
        Column {
            Text("UNLOCK", style = mono(Size.xs, 0.14f), color = DesignTokens.Palette.Text.tertiary)
            Text("Pro", style = ui(Size.xl, FontWeight.Bold), color = DesignTokens.Palette.Text.primary)
        }
        DisplayPanel {
            Text("YOU TAPPED", style = mono(Size.xs), color = Panel.displayText3)
            Text(feature.key, style = mono(Size.lg), color = Panel.displayText)
            Text("ONE-TIME PURCHASE · NO SUBSCRIPTION", style = mono(Size.sm), color = Panel.displayText2)
        }
        SilkLabel("PRO ADDS")
        ProFeature.entries.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
                pair.forEach { f ->
                    val shape = RoundedCornerShape(DesignTokens.Radius.md)
                    Column(
                        Modifier.weight(1f).heightIn(min = 84.dp).clip(shape).background(Panel.key)
                            .border(if (f == feature) BorderStroke(2.dp, DesignTokens.Palette.primary) else BorderStroke(1.dp, Panel.keyBorder), shape)
                            .padding(DesignTokens.Spacing.sm + 2.dp),
                        verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
                            Led(true, 8.dp)
                            Text(f.key, style = mono(Size.xs, 0.08f), color = DesignTokens.Palette.Text.primary)
                        }
                        Text(f.detail, style = ui(Size.xs), color = DesignTokens.Palette.Text.secondary)
                    }
                }
            }
        }
        Pad(Modifier.fillMaxWidth().height(64.dp), enabled = false, onClick = {}) {
            Text("Buy Pro", style = ui(Size.lg, FontWeight.Bold), color = DesignTokens.Palette.onPrimary, modifier = Modifier.align(Alignment.Center))
        }
        Text(
            "Restore purchase", style = ui(Size.base), color = DesignTokens.Palette.Text.disabled,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Purchases are not set up in this build yet.", style = ui(Size.xs), color = DesignTokens.Palette.Text.tertiary,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(DesignTokens.Spacing.xl))
    }
}

// ---- Settings

@Composable
private fun SettingsScreen(model: AppModel) {
    val context = LocalContext.current
    PanelScreen {
        PanelHeader("SYSTEM", "Settings") { model.back() }

        SilkLabel("OUTPUT")
        Key(
            Modifier.fillMaxWidth().semantics { contentDescription = if (model.dither) "Dither, on" else "Dither, off" },
            selected = model.dither, onClick = { model.setDitherOn(!model.dither) },
        ) {
            Row(Modifier.padding(DesignTokens.Spacing.md + 2.dp), horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.md + 2.dp)) {
                Box(Modifier.padding(top = 4.dp)) { Led(model.dither) }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (model.dither) "DITHER ON" else "DITHER OFF", style = mono(Size.sm, 0.08f))
                    Text(
                        "A tiny amount of noise when the bit depth goes down, so quiet parts fade out smoothly. On is the usual choice.",
                        style = ui(Size.sm), color = DesignTokens.Palette.Text.tertiary,
                    )
                }
            }
        }

        SilkLabel("PRO")
        Key(Modifier.fillMaxWidth(), onClick = { model.pro = ProFeature.BATCH }) {
            Row(
                Modifier.padding(horizontal = DesignTokens.Spacing.lg).heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.md + 2.dp),
            ) {
                Led(false)
                Text("Pro and restore purchase", style = ui(Size.base), modifier = Modifier.weight(1f))
                ProTag()
            }
        }

        SilkLabel("ABOUT")
        val shape = RoundedCornerShape(DesignTokens.Radius.md)
        Column(Modifier.fillMaxWidth().clip(shape).background(Panel.key).border(1.dp, Panel.keyBorder, shape)) {
            Column(Modifier.padding(DesignTokens.Spacing.md + 2.dp), verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs)) {
                Text("Privacy", style = ui(Size.base, FontWeight.SemiBold))
                Text("Everything happens on this phone. No account, no ads, no tracking.", style = ui(Size.sm), color = DesignTokens.Palette.Text.secondary)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Panel.rule))
            Column(Modifier.padding(DesignTokens.Spacing.md + 2.dp), verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs)) {
                Text("Licenses", style = ui(Size.base, FontWeight.SemiBold))
                Text(
                    "Resampling uses libsamplerate 0.2.2 by Erik de Castro Lopo, under the BSD 2-Clause license.",
                    style = ui(Size.sm), color = DesignTokens.Palette.Text.secondary,
                )
            }
            if (model.hasSelfTest) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Panel.rule))
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button) { model.push(Screen.SELF_TEST) }
                        .padding(horizontal = DesignTokens.Spacing.md + 2.dp).heightIn(min = 52.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Self-test", style = ui(Size.base, FontWeight.SemiBold), modifier = Modifier.weight(1f))
                    Text("RUN ›", style = mono(Size.xs, 0.08f), color = DesignTokens.Palette.Text.tertiary)
                }
            }
        }

        val version = context.packageManager.getPackageInfo(context.packageName, 0)
            .let { "VERSION ${it.versionName} (${it.longVersionCode}) · IAMJARL" }
        Text(
            version, style = mono(Size.xs, 0.1f), color = DesignTokens.Palette.Text.tertiary,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = DesignTokens.Spacing.lg),
        )
    }
}

// ---- Self-test

@Composable
private fun SelfTestScreen(model: AppModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Triple<String, Boolean, List<String>>?>(null) }
    PanelScreen {
        PanelHeader("SYSTEM", "Self-test") { model.back() }
        DisplayPanel {
            val r = result
            Text(
                if (running) "RUNNING" else if (r == null) "READY" else if (r.second) "PASS" else "FAIL",
                style = mono(Size.lg), color = Panel.displayText,
            )
            r?.let { (line, _, problems) ->
                Text(line, style = mono(Size.sm), color = Panel.displayText2)
                problems.forEach { Text(it, style = mono(Size.xs), color = Panel.displayText2) }
            }
        }
        Key(Modifier.fillMaxWidth(), selected = running, enabled = !running, onClick = {
            running = true
            result = null
            scope.launch {
                result = withContext(Dispatchers.Default) { model.runSelfTest(context.assets) }
                running = false
            }
        }) {
            Row(
                Modifier.padding(horizontal = DesignTokens.Spacing.lg).heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.md),
            ) {
                Led(running)
                Text(if (running) "RUNNING…" else "RUN THE SELF-TEST", style = mono(Size.sm, 0.08f))
            }
        }
        Text(
            "Converts every test file on this phone and checks that each result is exactly what the reference gives, byte for byte.",
            style = ui(Size.sm), color = DesignTokens.Palette.Text.tertiary,
        )
    }
}
