package com.fieldnote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF245B4F),
    onPrimary = Color.White,
    secondary = Color(0xFF6B5F2A),
    tertiary = Color(0xFF475D7A),
    background = Color(0xFFF8F7F2),
    surface = Color(0xFFFFFCF6),
    surfaceVariant = Color(0xFFE8E2D4)
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF8DD5C4),
    secondary = Color(0xFFD7C26A),
    tertiary = Color(0xFFB4C7E5),
    background = Color(0xFF141512),
    surface = Color(0xFF1D1E1A),
    surfaceVariant = Color(0xFF45473F)
)

@Composable
fun FieldNoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content
    )
}
