package com.kineticlabs.pulse.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

// ---------- building blocks ----------------------------------

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        color = Muted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
    )
}

@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    color: Color = TextMain,
    sub: String? = null,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(CardBg)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(label.uppercase(), color = Muted, fontSize = 11.sp, letterSpacing = 1.sp, maxLines = 1)
        Row {
            Text(value, color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            if (sub != null) {
                Text(
                    " $sub", color = Muted, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp), maxLines = 1,
                )
            }
        }
    }
}

data class Tile(val label: String, val value: String, val color: Color = TextMain, val sub: String? = null)

@Composable
fun TileGrid(tiles: List<Tile>, columns: Int = 2, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { t ->
                    StatTile(t.label, t.value, Modifier.weight(1f), t.color, t.sub)
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun Bar(fraction: Float, color: Color, track: Color, modifier: Modifier = Modifier, height: Int = 10) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape(50))
            .background(track)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(color)
        )
    }
}

/** Half-ring gauge, 0..45 degrees, with a tick at the wheelie angle. */
@Composable
fun AngleGauge(angle: Float, threshold: Float, accent: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().aspectRatio(2f)) {
        val stroke = size.width * 0.07f
        val d = size.width - stroke
        val topLeft = Offset(stroke / 2, stroke / 2)
        val arc = Size(d, d)
        drawArc(Line, 180f, 180f, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
        val f = (angle / 45f).coerceIn(0f, 1f)
        if (f > 0.005f) {
            drawArc(
                if (angle >= threshold) Go else accent, 180f, 180f * f, false, topLeft, arc,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        // threshold tick
        val a = Math.toRadians(180.0 + 180.0 * (threshold / 45f))
        val c = Offset(size.width / 2, stroke / 2 + d / 2)
        val r0 = d / 2 - stroke * 0.9f
        val r1 = d / 2 + stroke * 0.9f
        drawLine(
            TextMain,
            Offset(c.x + (kotlin.math.cos(a) * r0).toFloat(), c.y + (kotlin.math.sin(a) * r0).toFloat()),
            Offset(c.x + (kotlin.math.cos(a) * r1).toFloat(), c.y + (kotlin.math.sin(a) * r1).toFloat()),
            strokeWidth = 4f,
        )
    }
}

/** Two-option toggle (This ride / All time). */
@Composable
fun SegmentToggle(left: String, right: String, rightSelected: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
            .padding(4.dp)
    ) {
        listOf(false to left, true to right).forEach { (isRight, label) ->
            val sel = isRight == rightSelected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (sel) Orange else Color.Transparent)
                    .clickable { onChange(isRight) }
                    .padding(vertical = 10.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Text(label, color = if (sel) Color.Black else TextMain, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
