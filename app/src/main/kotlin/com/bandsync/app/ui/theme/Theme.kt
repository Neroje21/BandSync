package com.bandsync.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BeatColor = Color(0xFF7CE0C3)
val DownbeatColor = Color(0xFFFFC857)

/** Always dark: this gets read on a dim stage, next to a bright PDF. */
private val StageColors = darkColorScheme(
    primary = BeatColor,
    onPrimary = Color(0xFF00382C),
    secondary = DownbeatColor,
    onSecondary = Color(0xFF3F2E00),
    background = Color(0xFF0E1012),
    surface = Color(0xFF0E1012),
    surfaceVariant = Color(0xFF22262A),
    onSurfaceVariant = Color(0xFFC3C8CC),
    error = Color(0xFFFF8A80),
)

@Composable
fun BandSyncTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = StageColors, content = content)
}
