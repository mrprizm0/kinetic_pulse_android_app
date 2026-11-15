package com.kineticlabs.pulse.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Minimal palette: true black, one accent, everything else greys.
// Green / red / gold only appear when they *mean* something
// (wheelie / stoppie / record).
val Orange = Color(0xFFFF8A1F)
val Bg = Color(0xFF000000)
val CardBg = Color(0xFF0C0C0E)      // barely-there surface for overlays
val Line = Color(0xFF1C1C21)        // hairlines
val TextMain = Color(0xFFF2F2F3)
val Muted = Color(0xFF6E6E78)
val Faint = Color(0xFF3A3A42)
val Go = Color(0xFF2EE07A)
val Gold = Color(0xFFF5C84B)
val Red = Color(0xFFFF4D5E)
val Blue = Color(0xFF5B8CFF)
val WheelieBg = Color(0xFF000000)
val WheelieTrack = Color(0xFF0E1F15)
val StoppieBg = Color(0xFF000000)
val StoppieTrack = Color(0xFF241015)

@Composable
fun KineticTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Orange,
            onPrimary = Color.Black,
            secondary = Orange,
            background = Bg,
            onBackground = TextMain,
            surface = Bg,
            onSurface = TextMain,
            surfaceVariant = CardBg,
            onSurfaceVariant = Muted,
            surfaceContainer = Bg,
            surfaceContainerHigh = CardBg,
            outline = Line,
            outlineVariant = Line,
        ),
        typography = Typography(),
        content = content,
    )
}
