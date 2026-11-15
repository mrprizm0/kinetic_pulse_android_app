package com.kineticlabs.pulse.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

// ---------- formatting ---------------------------------------

/** 2400 -> "2.4s", 65200 -> "1:05.2s" */
fun fmtSecs(ms: Long): String {
    val tenths = ms / 100
    val s = tenths / 10
    return if (s >= 60) String.format("%d:%02d.%ds", s / 60, s % 60, tenths % 10)
    else String.format("%d.%ds", s, tenths % 10)
}

fun scoreColor(s: Int): Color = when {
    s >= 80 -> Go
    s >= 50 -> Gold
    else -> Red
}

const val DASH = "—"

// ---------- type -----------------------------------------------

/** Big, thin numbers with fixed-width digits so they don't jitter. */
fun numberStyle(size: TextUnit, weight: FontWeight = FontWeight.Thin) =
    TextStyle(fontSize = size, fontWeight = weight, fontFeatureSettings = "tnum", letterSpacing = (-1).sp)

/** Small, wide-spaced caps for labels. */
val LabelStyle = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.sp, color = Muted)

// ---------- building blocks ----------------------------------

/** Screen title: light weight, with a tiny accent dot. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Orange))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 24.sp, fontWeight = FontWeight.Light, letterSpacing = 0.5.sp)
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier = modifier, style = LabelStyle)
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Line))
}

/** One stat: tiny label over a big light number. No box, just space. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    color: Color = TextMain,
    sub: String? = null,
) {
    Column(modifier.padding(vertical = 10.dp)) {
        Text(label.uppercase(), style = LabelStyle, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = color, style = numberStyle(26.sp, FontWeight.Light), maxLines = 1)
            if (sub != null) {
                Text(
                    " $sub", color = Muted, fontSize = 11.sp, maxLines = 1,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
    }
}

data class Tile(val label: String, val value: String, val color: Color = TextMain, val sub: String? = null)

/** Grid of stats separated by hairlines between rows. */
@Composable
fun TileGrid(tiles: List<Tile>, columns: Int = 2, modifier: Modifier = Modifier) {
    Column(modifier) {
        tiles.chunked(columns).forEachIndexed { i, row ->
            if (i > 0) Hairline()
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { t ->
                    StatTile(t.label, t.value, Modifier.weight(1f), t.color, t.sub)
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Thin progress line with a soft glowing head. */
@Composable
fun Bar(fraction: Float, color: Color, track: Color, modifier: Modifier = Modifier, height: Int = 3) {
    val f = fraction.coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape(50))
            .background(track)
    ) {
        Box(
            Modifier
                .fillMaxWidth(f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.35f), color)))
        )
    }
}

/** Thin half-ring gauge, 0..45 degrees, with a glowing tip and a threshold tick. */
@Composable
fun AngleGauge(angle: Float, threshold: Float, accent: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().aspectRatio(2f)) {
        val stroke = 3.dp.toPx()
        val pad = 14.dp.toPx()
        val d = size.width - pad * 2
        val topLeft = Offset(pad, pad)
        val arc = Size(d, d)
        val c = Offset(size.width / 2, pad + d / 2)
        val r = d / 2

        // ticks every 15 degrees of tilt
        for (k in 0..3) {
            val a = Math.toRadians(180.0 + 180.0 * (k * 15f / 45f))
            val p0 = Offset(c.x + (cos(a) * (r - 10.dp.toPx())).toFloat(), c.y + (sin(a) * (r - 10.dp.toPx())).toFloat())
            val p1 = Offset(c.x + (cos(a) * (r - 4.dp.toPx())).toFloat(), c.y + (sin(a) * (r - 4.dp.toPx())).toFloat())
            drawLine(Faint, p0, p1, strokeWidth = 1.dp.toPx())
        }

        drawArc(Line, 180f, 180f, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
        val f = (angle / 45f).coerceIn(0f, 1f)
        val col = if (angle >= threshold) Go else accent
        if (f > 0.005f) {
            drawArc(col, 180f, 180f * f, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        // glowing tip
        val ta = Math.toRadians(180.0 + 180.0 * f)
        val tip = Offset(c.x + (cos(ta) * r).toFloat(), c.y + (sin(ta) * r).toFloat())
        drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.45f), Color.Transparent), tip, 18.dp.toPx()), 18.dp.toPx(), tip)
        drawCircle(col, 4.dp.toPx(), tip)

        // wheelie threshold mark
        val th = Math.toRadians(180.0 + 180.0 * (threshold / 45f))
        drawLine(
            TextMain,
            Offset(c.x + (cos(th) * (r - 9.dp.toPx())).toFloat(), c.y + (sin(th) * (r - 9.dp.toPx())).toFloat()),
            Offset(c.x + (cos(th) * (r + 9.dp.toPx())).toFloat(), c.y + (sin(th) * (r + 9.dp.toPx())).toFloat()),
            strokeWidth = 1.5f.dp.toPx(),
        )
    }
}

/** Two text tabs with an accent underline (This ride / All time). */
@Composable
fun SegmentToggle(left: String, right: String, rightSelected: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        listOf(false to left, true to right).forEach { (isRight, label) ->
            val sel = isRight == rightSelected
            Column(
                Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onChange(isRight) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label.uppercase(), fontSize = 12.sp, letterSpacing = 2.sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (sel) TextMain else Muted,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
                Box(
                    Modifier.fillMaxWidth().height(if (sel) 2.dp else 1.dp)
                        .background(if (sel) Orange else Line)
                )
            }
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
