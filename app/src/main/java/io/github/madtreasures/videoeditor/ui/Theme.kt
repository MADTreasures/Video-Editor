package io.github.madtreasures.videoeditor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// A video editor is always dark, so the picture is not outshone by the UI around it.
private val EditorColors = darkColorScheme(
    primary = Color(0xFFFFC53D),
    onPrimary = Color(0xFF261A00),
    primaryContainer = Color(0xFF4D3A00),
    onPrimaryContainer = Color(0xFFFFE29A),
    secondary = Color(0xFFD9C7A0),
    onSecondary = Color(0xFF261A00),
    secondaryContainer = Color(0xFF3F3520),
    onSecondaryContainer = Color(0xFFF2E3BF),
    tertiary = Color(0xFF8FD6C4),
    onTertiary = Color(0xFF00201A),
    background = Color(0xFF0F1013),
    onBackground = Color(0xFFE8E4DC),
    surface = Color(0xFF0F1013),
    onSurface = Color(0xFFE8E4DC),
    surfaceVariant = Color(0xFF2B2C33),
    onSurfaceVariant = Color(0xFFB8B5AD),
    surfaceContainerLowest = Color(0xFF0A0B0D),
    surfaceContainerLow = Color(0xFF17181C),
    surfaceContainer = Color(0xFF1B1C21),
    surfaceContainerHigh = Color(0xFF24252B),
    surfaceContainerHighest = Color(0xFF2E2F36),
    outline = Color(0xFF8A8880),
    outlineVariant = Color(0xFF3C3D44),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun VideoEditorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = EditorColors, content = content)
}
