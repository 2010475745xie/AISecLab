package com.xieguojian.aiseclab.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF3C3489),
    secondary = Color(0xFF1D9E75),
    tertiary = Color(0xFFD85A30)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAFA9EC),
    secondary = Color(0xFF5DCAA5),
    tertiary = Color(0xFFF0997B)
)

@Composable
fun AISecLabTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
