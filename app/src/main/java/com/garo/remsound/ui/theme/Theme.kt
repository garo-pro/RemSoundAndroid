package com.garo.remsound.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * The app takes the system's dynamic colours where the platform offers them, and a plain
 * Material palette otherwise. Nothing here is decorative: the contrast has to survive a
 * high-contrast display setting, so the scheme is left to the platform rather than hand-tuned.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF0B5FA5),
    secondary = Color(0xFF3F6070),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9FCBFF),
    secondary = Color(0xFFA7C8DA),
)

@Composable
fun RemSoundTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
