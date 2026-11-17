package com.kineticlabs.pulse.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

// ---------- formatting ---------------------------------------

/** 2400 -> "2.4s", 65200 -> "1:05.2s" */
fun fmtSecs(ms: Long): String {
    val tenths = ms.coerceAtLeast(0) / 100
    val s = tenths / 10
    return if (s >= 60) String.format(Locale.US, "%d:%02d.%ds", s / 60, s % 60, tenths % 10)
    else String.format(Locale.US, "%d.%ds", s, tenths % 10)
}

fun scoreColor(s: Int): Color = when {
    s >= 80 -> Go
    s >= 50 -> Gold
    else -> Red
}

/** Score as a Metro tile colour (deep, so white text reads on it). */
fun scoreTile(s: Int): Color = when {
    s >= 80 -> TileGreen
    s >= 50 -> TileAmber
    else -> TileRed
}

const val DASH = "—"

// ---------- type -----------------------------------------------

/** Big, thin numbers with fixed-width digits so they don't jitter. */
fun numberStyle(size: TextUnit, weight: FontWeight = FontWeight.Thin) =
    TextStyle(fontSize = size, fontWeight = weight, fontFeatureSettings = "tnum", letterSpacing = (-1).sp)

/** Small caps for status and actions. */
val LabelStyle = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.5.sp, color = Muted)

// ---------- building blocks ----------------------------------

/**
 * The page name lives in the pivot header at the top now (see App.kt), so
 * this only lays out a screen's actions on the right.
 */
@Composable
fun ScreenTitle(@Suppress("UNUSED_PARAMETER") text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        trailing()
    }
}

/** Metro section header: lowercase, light. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.lowercase(Locale.getDefault()), modifier = modifier,
        fontSize = 20.sp, fontWeight = FontWeight.Light, color = Muted, maxLines = 1,
    )
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Line))
}

/**
 * One stat as a flat Metro tile: big light value up top, small lowercase
 * label at the bottom. [color] tints the value; [bg] fills the tile.
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    color: Color = TextMain,
    sub: String? = null,
    bg: Color = TileBg,
) {
    Column(
        modifier.background(bg).heightIn(min = 74.dp).padding(start = 10.dp, end = 8.dp, top = 8.dp, bottom = 7.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value, color = color, style = numberStyle(26.sp, FontWeight.Light), maxLines = 1,
                overflow = TextOverflow.Clip, modifier = Modifier.weight(1f, fill = false),
            )
            if (sub != null) {
                Text(
                    " $sub", color = color.copy(alpha = 0.55f), fontSize = 11.sp, maxLines = 1,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label.lowercase(Locale.getDefault()), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (bg == TileBg) Muted else Color.White.copy(alpha = 0.72f),
        )
    }
}

data class Tile(
    val label: String,
    val value: String,
    val color: Color = TextMain,
    val sub: String? = null,
    val bg: Color = TileBg,
)

/** Grid of Metro tiles with even 6 dp gaps. */
@Composable
fun TileGrid(tiles: List<Tile>, columns: Int = 2, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tiles.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { t ->
                    StatTile(t.label, t.value, Modifier.weight(1f), t.color, t.sub, t.bg)
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Flat progress bar (square ends). */
@Composable
fun Bar(fraction: Float, color: Color, track: Color, modifier: Modifier = Modifier, height: Int = 3) {
    val f = fraction.coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(height.dp).background(track)) {
        Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(color))
    }
}

/** Metro button: a flat block (primary) or a 2 dp outline, square, lowercase. */
@Composable
fun MetroButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    color: Color = Orange,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = if (enabled) color else Faint
    Text(
        text.lowercase(Locale.getDefault()), fontSize = 16.sp, fontWeight = FontWeight.Normal,
        textAlign = TextAlign.Center,
        // dark text on bright blocks (orange), white on deep ones (red tile)
        color = if (primary) (if (!enabled) Muted else if (c.luminance() > 0.3f) Color.Black else Color.White)
                else (if (enabled) TextMain else Faint),
        modifier = modifier
            .background(if (primary) c else Color.Transparent)
            .border(2.dp, if (primary) c else (if (enabled) TextMain.copy(alpha = 0.85f) else Faint))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    )
}

/** Metro toggle switch: an outlined bar with a solid block thumb that slides. */
@Composable
fun MetroToggle(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val thumbX by animateDpAsState(if (checked) 30.dp else 0.dp, tween(140), label = "thumb")
    val fill by animateColorAsState(if (checked) Orange else Color.Transparent, tween(140), label = "fill")
    Box(
        modifier
            .size(width = 44.dp, height = 22.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
            ) { onChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier.fillMaxWidth().height(18.dp).border(2.dp, if (checked) Orange else Muted)
                .padding(4.dp).background(fill)
        )
        Box(Modifier.offset(x = thumbX).size(width = 14.dp, height = 22.dp).background(TextMain))
    }
}

/** Thin half-ring gauge, 0..90 degrees, with a threshold tick. Flat ends (Metro). */
@Composable
fun AngleGauge(angle: Float, threshold: Float, accent: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().aspectRatio(2f)) {
        val stroke = 4.dp.toPx()
        val pad = 14.dp.toPx()
        val d = size.width - pad * 2
        val topLeft = Offset(pad, pad)
        val arc = Size(d, d)
        val c = Offset(size.width / 2, pad + d / 2)
        val r = d / 2
        val maxDeg = 90f

        // ticks every 15 degrees of tilt
        for (k in 0..6) {
            val a = Math.toRadians(180.0 + 180.0 * (k * 15f / maxDeg))
            val p0 = Offset(c.x + (cos(a) * (r - 11.dp.toPx())).toFloat(), c.y + (sin(a) * (r - 11.dp.toPx())).toFloat())
            val p1 = Offset(c.x + (cos(a) * (r - 5.dp.toPx())).toFloat(), c.y + (sin(a) * (r - 5.dp.toPx())).toFloat())
            drawLine(Faint, p0, p1, strokeWidth = 1.dp.toPx())
        }

        drawArc(TileBg, 180f, 180f, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Butt))
        val f = (angle / maxDeg).coerceIn(0f, 1f)
        val col = if (angle >= threshold) Go else accent
        if (f > 0.003f) {
            drawArc(col, 180f, 180f * f, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Butt))
        }
        // tip: a small square block, Metro style
        val ta = Math.toRadians(180.0 + 180.0 * f)
        val tip = Offset(c.x + (cos(ta) * r).toFloat(), c.y + (sin(ta) * r).toFloat())
        drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.40f), Color.Transparent), tip, 18.dp.toPx()), 18.dp.toPx(), tip)
        val b = 4.dp.toPx()
        drawRect(col, Offset(tip.x - b, tip.y - b), Size(b * 2, b * 2))

        // wheelie threshold mark
        val th = Math.toRadians(180.0 + 180.0 * (threshold / maxDeg))
        drawLine(
            TextMain,
            Offset(c.x + (cos(th) * (r - 9.dp.toPx())).toFloat(), c.y + (sin(th) * (r - 9.dp.toPx())).toFloat()),
            Offset(c.x + (cos(th) * (r + 9.dp.toPx())).toFloat(), c.y + (sin(th) * (r + 9.dp.toPx())).toFloat()),
            strokeWidth = 1.5f.dp.toPx(),
        )
    }
}

/** Two lowercase pivot items (this ride / all time): selected one bright, the other faint. */
@Composable
fun SegmentToggle(left: String, right: String, rightSelected: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        listOf(false to left, true to right).forEach { (isRight, label) ->
            val sel = isRight == rightSelected
            val col by animateColorAsState(if (sel) TextMain else Faint, tween(180), label = "seg")
            Text(
                label.lowercase(Locale.getDefault()), fontSize = 22.sp, fontWeight = FontWeight.Light,
                color = col,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onChange(isRight) }
                    .padding(vertical = 4.dp),
            )
        }
    }
}

/** A list row: no box, just a hairline underneath. */
fun Modifier.rowDivider(): Modifier = drawBehind {
    val y = size.height - 0.5.dp.toPx()
    drawLine(Line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
}

/** Soft radial tint behind takeover screens (green wheelie, red stoppie). */
fun Modifier.glow(color: Color): Modifier = drawBehind {
    drawRect(
        Brush.radialGradient(
            listOf(color.copy(alpha = 0.22f), Color.Transparent),
            center = Offset(size.width / 2, size.height * 0.42f),
            radius = size.maxDimension * 0.6f,
        )
    )
}

/** Small square status block (Metro has no round dots). */
@Composable
fun StatusSquare(color: Color, size: Int = 7) {
    Box(Modifier.size(size.dp).background(color))
}
