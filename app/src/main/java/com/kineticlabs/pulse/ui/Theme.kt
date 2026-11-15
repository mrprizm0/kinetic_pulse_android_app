package com.kineticlabs.pulse.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Orange = Color(0xFFFF8C1A)
val Bg = Color(0xFF0D0D0F)
val CardBg = Color(0xFF18181C)
val Line = Color(0xFF2A2A30)
val TextMain = Color(0xFFF4F4F5)
val Muted = Color(0xFF8B8B94)
val Go = Color(0xFF22C55E)
val Gold = Color(0xFFFACC15)
val Red = Color(0xFFEF4444)
val Blue = Color(0xFF3B82F6)
val WheelieBg = Color(0xFF052E18)
val WheelieTrack = Color(0xFF031C0E)

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
            surfaceContainer = CardBg,
            outline = Line,
        ),
        content = content,
    )
}
