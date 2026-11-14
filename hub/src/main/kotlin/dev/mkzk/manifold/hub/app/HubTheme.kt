package dev.mkzk.manifold.hub.app

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class ThemePreference(saved: Boolean, private val save: (Boolean) -> Unit) {
    private val current = MutableStateFlow(saved)

    val wallpaperColors: StateFlow<Boolean> = current

    fun setWallpaperColors(on: Boolean) {
        current.value = on
        save(on)
    }

    companion object {
        val available = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }
}

// The blues of the launcher icon, so the app matches it when wallpaper colors are off.
private val CobaltDark = darkColorScheme(
    primary = Color(0xFF8AB0FF),
    onPrimary = Color(0xFF0B1F4D),
    primaryContainer = Color(0xFF23407F),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFFB8C4E0),
    onSecondary = Color(0xFF223047),
    secondaryContainer = Color(0xFF39465E),
    onSecondaryContainer = Color(0xFFD7E1FA),
    tertiary = Color(0xFFE6B87A),
    background = Color(0xFF131418),
    onBackground = Color(0xFFE4E5EA),
    surface = Color(0xFF131418),
    onSurface = Color(0xFFE4E5EA),
    onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceContainerLowest = Color(0xFF0E0F12),
    surfaceContainerLow = Color(0xFF17181C),
    surfaceContainer = Color(0xFF1B1C21),
    surfaceContainerHigh = Color(0xFF24252B),
    surfaceContainerHighest = Color(0xFF2E2F36),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44464F),
)

private val CobaltLight = lightColorScheme(
    primary = Color(0xFF2F5BD3),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF0B1F4D),
    secondary = Color(0xFF545F78),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD7E1FA),
    onSecondaryContainer = Color(0xFF111C31),
    tertiary = Color(0xFF8A5A1E),
    background = Color(0xFFFBFBFE),
    onBackground = Color(0xFF1A1B20),
    surface = Color(0xFFFBFBFE),
    onSurface = Color(0xFF1A1B20),
    onSurfaceVariant = Color(0xFF44464F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F6FA),
    surfaceContainer = Color(0xFFEFF0F5),
    surfaceContainerHigh = Color(0xFFE9EAF0),
    surfaceContainerHighest = Color(0xFFE3E4EA),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
)

@Composable
internal fun HubTheme(wallpaperColors: Boolean, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        wallpaperColors && ThemePreference.available ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> CobaltDark
        else -> CobaltLight
    }
    MaterialTheme(colorScheme = colors, content = content)
}
