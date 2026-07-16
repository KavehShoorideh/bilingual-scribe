package dev.bscribe.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Green = Color(0xFF1B5E4A)
private val GreenLight = Color(0xFF4C8C77)
private val RecordRed = Color(0xFFC62828)

private val LightColors = lightColorScheme(
    primary = Green,
    secondary = GreenLight,
    error = RecordRed,
)

private val DarkColors = darkColorScheme(
    primary = GreenLight,
    secondary = Green,
    error = Color(0xFFEF5350),
)

@Composable
fun ScribeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
