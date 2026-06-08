package com.blokqr.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Blue,
    background = NavyDeep,
    surface = Surface,
    onPrimary = Color.White,
    onBackground = OnSurfaceDim,
    onSurface = OnSurfaceDim,
)

@Composable
fun BlokQrTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Thème sombre par défaut : confort de lecture lors du scan.
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography(),
        content = content,
    )
}
