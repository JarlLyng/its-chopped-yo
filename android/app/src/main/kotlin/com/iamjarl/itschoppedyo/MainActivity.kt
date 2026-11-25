package com.iamjarl.itschoppedyo

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.iamjarl.design.DesignTokens
import com.iamjarl.design.DesignTokens.ColorToken
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * It's chopped, yo! on Android. The free flow: import one WAV or AIFF file, pick a built-in format
 * preset, play the result, save it to a folder or share it. Everything Pro adds is shown with a
 * badge and opens the Pro sheet (hub `ItsChoppedYo/screen-flow.md`). Colours, spacing and type come
 * from the IAMJARL design tokens (com.iamjarl.design.DesignTokens, copied from iamjarl-design v1.38.0).
 */
class MainActivity : ComponentActivity() {
    /** Files handed to the app by another app (share sheet or "open with"). */
    private val incoming = Channel<Uri>(Channel.CONFLATED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent {
            ChoppedTheme {
                val scope = rememberCoroutineScope()
                val context = LocalContext.current
                val model = remember { AppModel(context.applicationContext, scope) }
                LaunchedEffect(Unit) { incoming.receiveAsFlow().collect { model.load(it) } }
                BackHandler(enabled = model.stack.size > 1) { model.back() }
                AppScreens(model)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        uri?.let { incoming.trySend(it) }
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
            surface = ColorToken.Dark.Background.app,
            onSurface = ColorToken.Dark.Text.primary,
            onSurfaceVariant = ColorToken.Dark.Text.secondary,
            surfaceContainerLow = ColorToken.Dark.Surface.default,
            outlineVariant = ColorToken.Dark.Border.subtle,
            error = ColorToken.Dark.State.error,
        )
    } else {
        lightColorScheme(
            primary = ColorToken.Light.primary,
            onPrimary = ColorToken.Light.onPrimary,
            background = ColorToken.Light.Background.app,
            onBackground = ColorToken.Light.Text.primary,
            surface = ColorToken.Light.Background.app,
            onSurface = ColorToken.Light.Text.primary,
            onSurfaceVariant = ColorToken.Light.Text.secondary,
            surfaceContainerLow = ColorToken.Light.Surface.default,
            outlineVariant = ColorToken.Light.Border.subtle,
            error = ColorToken.Light.State.error,
        )
    }
    MaterialTheme(colorScheme = colors, typography = DesignTokens.Typography.material3(), content = content)
}
