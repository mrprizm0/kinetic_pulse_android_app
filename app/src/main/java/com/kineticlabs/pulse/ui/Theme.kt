package com.kineticlabs.pulse.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Metro: true black, flat square tiles, light type, one accent.
// Green / red / gold only appear when they *mean* something
// (wheelie / stoppie / record). Same colours as the Pulse's screen.
val Orange = Color(0xFFFF8A1F)
val Bg = Color(0xFF000000)
val CardBg = Color(0xFF141417)      // surface for sheets and dialogs
val TileBg = Color(0xFF1E1E23)      // neutral Metro tile (matches the Pulse's C_TILE)
val Line = Color(0xFF1C1C21)        // hairlines
val TextMain = Color(0xFFF2F2F3)
val Muted = Color(0xFF7A7A84)
val Faint = Color(0xFF3A3A42)
val Go = Color(0xFF2EE07A)
val Gold = Color(0xFFF5C84B)
val Red = Color(0xFFFF4D5E)
val Blue = Color(0xFF5B8CFF)
// deep tile colours (white text stays readable on them), as on the Pulse
val TileBlue = Color(0xFF0050DC)
val TileGreen = Color(0xFF008A28)
val TileAmber = Color(0xFFCD7D00)
val TileRed = Color(0xFFB01030)
val TileViolet = Color(0xFF6E28DC)
val WheelieBg = Color(0xFF000000)
val WheelieTrack = Color(0xFF0E1F15)
val StoppieBg = Color(0xFF000000)
val StoppieTrack = Color(0xFF241015)

private val Square = RoundedCornerShape(0.dp)

@Composable
fun KineticTheme(content: @Composable () -> Unit) {
    val base = Typography()
    fun TextStyle.light() = copy(fontWeight = FontWeight.Light)
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
            surfaceContainerHighest = CardBg,
            outline = Faint,
            outlineVariant = Line,
        ),
        // Metro has no rounded corners: dialogs, fields, menus all square
        shapes = Shapes(
            extraSmall = Square, small = Square, medium = Square, large = Square, extraLarge = Square,
        ),
        typography = base.copy(
            headlineLarge = base.headlineLarge.light(),
            headlineMedium = base.headlineMedium.light(),
            headlineSmall = base.headlineSmall.light().copy(fontSize = 26.sp),
            titleLarge = base.titleLarge.light(),
            titleMedium = base.titleMedium.light(),
            bodyLarge = base.bodyLarge.copy(fontWeight = FontWeight.Light),
        ),
        content = content,
    )
}
