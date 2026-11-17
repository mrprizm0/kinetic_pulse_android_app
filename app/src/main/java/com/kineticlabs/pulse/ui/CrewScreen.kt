package com.kineticlabs.pulse.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kineticlabs.pulse.PulseViewModel
import com.kineticlabs.pulse.crew.CrewSim
import com.kineticlabs.pulse.crew.Rider
import com.kineticlabs.pulse.crew.RiderState
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

private val ZOOMS = listOf(150f, 300f, 600f, 1200f)  // map radius in metres

// monochrome map: only riders and you carry colour
private val MapBg = Color(0xFF060607)
private val MapGrid = Color(0xFF0E0E11)
private val ParkColor = Color(0xFF0B0E0C)
private val LakeColor = Color(0xFF090D13)
private val TrailOuter = Color(0xFF1E1E23)
private val TrailInner = Color(0xFF55555E)
private val TrailSide = Color(0xFF1A1A1F)
private val RingColor = Color(0x1FFFFFFF)

@Composable
fun CrewScreen(vm: PulseViewModel) {
    val sim = vm.crew
    LaunchedEffect(Unit) {  // runs only while this tab is open
        var last = System.nanoTime()
        while (true) {
            delay(33)
            val now = System.nanoTime()
            sim.step(((now - last) / 1e9f).coerceAtMost(0.1f))
            last = now
        }
    }

    var zoomIdx by rememberSaveable { mutableIntStateOf(1) }
    var selected by remember { mutableStateOf<Int?>(null) }
    val riders = sim.riders
    val you = riders.first()
    val mp = sim.meetPoint

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp)) {
        ScreenTitle("Crew") {
            Text("DEMO · ${riders.size} RIDERS", style = LabelStyle.copy(color = Gold))
        }
        Spacer(Modifier.height(4.dp))
        Text("Pretend riders, to preview friend tracking.", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))

        sim.alert?.let { msg ->
            Row(
                Modifier.fillMaxWidth().rowDivider().padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusSquare(Red)
                Spacer(Modifier.size(10.dp))
                Text(msg, color = Red, fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (mp == null) {
                    Text(
                        "REGROUP", style = LabelStyle.copy(color = Red),
                        modifier = Modifier.clickable { sim.regroup() }.padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ---- the mini-map
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(androidx.compose.ui.graphics.RectangleShape)
        ) {
            MiniMap(sim, ZOOMS[zoomIdx], selected, Modifier.fillMaxSize())
            Column(Modifier.align(Alignment.BottomEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MapButton("+", enabled = zoomIdx > 0) { zoomIdx-- }
                MapButton("–", enabled = zoomIdx < ZOOMS.lastIndex) { zoomIdx++ }
            }
            Text(
                "${ZOOMS[zoomIdx].roundToInt()} M",
                style = LabelStyle,
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
            )
            Row(Modifier.align(Alignment.TopStart).padding(16.dp), verticalAlignment = Alignment.Bottom) {
                Text("${(you.speed * 3.6f).roundToInt()}", style = numberStyle(28.sp, FontWeight.ExtraLight))
                Text(" KM/H", style = LabelStyle, modifier = Modifier.padding(bottom = 5.dp))
            }
        }
        Spacer(Modifier.height(16.dp))

        // ---- regroup
        if (mp == null) {
            Text(
                "REGROUP AT MY SPOT",
                fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium, color = Color.Black,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().background(Orange)
                    .clickable { sim.regroup() }.padding(vertical = 14.dp),
            )
        } else {
            val arrived = riders.count { !it.isYou && it.state == RiderState.ARRIVED }
            val total = riders.size - 1
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (arrived == total) "Everyone's here" else "Waiting for the crew",
                        fontSize = 16.sp, fontWeight = FontWeight.Light, color = if (arrived == total) Go else TextMain,
                    )
                    Bar(arrived / total.toFloat(), Gold, Line, Modifier.padding(top = 8.dp))
                }
                Spacer(Modifier.size(16.dp))
                Text("$arrived/$total", style = numberStyle(28.sp, FontWeight.ExtraLight))
                Text(
                    "CANCEL", style = LabelStyle,
                    modifier = Modifier.clickable { sim.cancelRegroup() }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(28.dp))

        // ---- riders
        SectionLabel("Riders")
        riders.drop(1).forEach { r ->
            RiderCard(r, you, mp, selected == r.id) { selected = if (selected == r.id) null else r.id }
        }
        Spacer(Modifier.height(28.dp))

        // ---- activity
        SectionLabel("Activity")
        Spacer(Modifier.height(6.dp))
        sim.feed.take(10).forEach { f ->
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusSquare(f.color, 5)
                Spacer(Modifier.size(12.dp))
                Text(f.text, fontSize = 13.sp, color = TextMain.copy(alpha = 0.85f), modifier = Modifier.weight(1f))
                Text(ago(sim.time - f.time).uppercase(), style = LabelStyle.copy(color = Faint))
            }
        }
    }
}

@Composable
private fun MapButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).background(Color(0xCC000000))
            .border(2.dp, Faint)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) TextMain else Faint, fontSize = 18.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
private fun MiniMap(sim: CrewSim, zoomM: Float, selected: Int?, modifier: Modifier) {
    val label = remember {
        Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    }
    Canvas(modifier.background(MapBg)) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = min(center.x, center.y)
        val scale = radius / zoomM
        val riders = sim.riders
        val you = riders.first()
        val t = sim.time
        fun w2s(p: Offset) = Offset(center.x + (p.x - you.pos.x) * scale, center.y - (p.y - you.pos.y) * scale)

        // grid
        val step = when {
            zoomM <= 150f -> 25f
            zoomM <= 300f -> 50f
            zoomM <= 600f -> 100f
            else -> 200f
        }
        val span = zoomM * 1.5f
        var gx = floor((you.pos.x - span) / step) * step
        while (gx <= you.pos.x + span) {
            val x = w2s(Offset(gx, 0f)).x
            drawLine(MapGrid, Offset(x, 0f), Offset(x, size.height), 1f)
            gx += step
        }
        var gy = floor((you.pos.y - span) / step) * step
        while (gy <= you.pos.y + span) {
            val y = w2s(Offset(0f, gy)).y
            drawLine(MapGrid, Offset(0f, y), Offset(size.width, y), 1f)
            gy += step
        }

        // terrain
        sim.parks.forEach { (c, r) -> drawCircle(ParkColor, r * scale, w2s(c)) }
        drawCircle(LakeColor, sim.lake.second * scale, w2s(sim.lake.first))

        // trails
        val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 12f))
        sim.sideTrails.forEach { (a, b) ->
            drawLine(TrailSide, w2s(a), w2s(b), strokeWidth = 3.dp.toPx(), pathEffect = dash)
        }
        val path = Path().apply {
            sim.track.forEachIndexed { i, p ->
                val s = w2s(p)
                if (i == 0) moveTo(s.x, s.y) else lineTo(s.x, s.y)
            }
            close()
        }
        drawPath(path, TrailOuter, style = Stroke(width = 10.dp.toPx(), join = StrokeJoin.Round))
        drawPath(path, TrailInner, style = Stroke(width = 2.5f.dp.toPx(), join = StrokeJoin.Round, pathEffect = dash))

        // range rings + radar sweep
        drawCircle(RingColor, radius * 0.5f, center, style = Stroke(1.dp.toPx()))
        drawCircle(RingColor, radius * 0.97f, center, style = Stroke(1.dp.toPx()))
        label.textSize = 10.sp.toPx()
        label.color = Muted.toArgb()
        drawContext.canvas.nativeCanvas.drawText(
            "${(zoomM / 2).roundToInt()} m", center.x, center.y - radius * 0.5f - 4.dp.toPx(), label,
        )
        rotate(degrees = (t * 70f) % 360f, pivot = center) {
            drawArc(
                brush = Brush.sweepGradient(
                    0f to Color.Transparent, 0.85f to Color.Transparent, 1f to Orange.copy(alpha = 0.22f),
                    center = center,
                ),
                startAngle = -55f, sweepAngle = 55f, useCenter = true,
                topLeft = Offset(center.x - radius, center.y - radius), size = Size(radius * 2, radius * 2),
            )
        }
        label.color = TextMain.toArgb()
        label.textSize = 12.sp.toPx()
        drawContext.canvas.nativeCanvas.drawText("N", center.x, 18.dp.toPx(), label)

        // meeting point
        sim.meetPoint?.let { mp ->
            val s = w2s(mp)
            val pulse = (t * 1.5f) % 1f
            drawCircle(Gold.copy(alpha = 0.5f * (1 - pulse)), (12 + 22 * pulse).dp.toPx(), s, style = Stroke(2.dp.toPx()))
            drawCircle(Gold, 7.dp.toPx(), s)
            drawCircle(MapBg, 3.dp.toPx(), s)
        }

        // other riders
        val edge = radius * 0.9f
        riders.drop(1).forEach { r -> drawRider(r, w2s(r.pos), center, edge, you, selected == r.id, t, label) }

        // you, in the middle
        val pulse = (t * 1.2f) % 1f
        drawCircle(Orange.copy(alpha = 0.35f * (1 - pulse)), (10 + 20 * pulse).dp.toPx(), center)
        rotate(degrees = you.heading, pivot = center) {
            val u = 1.dp.toPx()
            val arrow = Path().apply {
                moveTo(center.x, center.y - 13 * u)
                lineTo(center.x + 9 * u, center.y + 10 * u)
                lineTo(center.x, center.y + 5 * u)
                lineTo(center.x - 9 * u, center.y + 10 * u)
                close()
            }
            drawPath(arrow, Orange)
            drawPath(arrow, Color.White, style = Stroke(1.5f * u, join = StrokeJoin.Round))
        }
    }
}

private fun DrawScope.drawRider(
    r: Rider, sp: Offset, center: Offset, edge: Float, you: Rider, sel: Boolean, t: Float, label: Paint,
) {
    val u = 1.dp.toPx()
    val off = sp - center
    val d = off.getDistance()
    val canvas = drawContext.canvas.nativeCanvas

    if (d > edge) {  // off the map: pin it to the edge with its distance
        val p = center + off / d * edge
        drawCircle(r.color, 7 * u, p)
        drawCircle(Color.White, 7 * u, p, style = Stroke(1.5f * u))
        val inward = center + off / d * (edge - 20 * u)
        label.textSize = 10.sp.toPx()
        label.color = TextMain.toArgb()
        canvas.drawText(fmtDist((r.pos - you.pos).getDistance()), inward.x, inward.y + 4 * u, label)
        return
    }

    if (r.state == RiderState.STOPPED) {
        val pulse = (t * 1.6f) % 1f
        drawCircle(Red.copy(alpha = 0.8f * (1 - pulse)), (12 + 18 * pulse) * u, sp, style = Stroke(2.5f * u))
    }
    if (r.inWheelie) drawCircle(Orange.copy(alpha = 0.35f), 18 * u, sp)
    if (sel) drawCircle(Color.White, 15 * u, sp, style = Stroke(2 * u))

    rotate(degrees = r.heading, pivot = sp) {  // little heading pointer
        val tip = Path().apply {
            moveTo(sp.x, sp.y - 16 * u)
            lineTo(sp.x + 5 * u, sp.y - 8 * u)
            lineTo(sp.x - 5 * u, sp.y - 8 * u)
            close()
        }
        drawPath(tip, r.color)
    }
    drawCircle(r.color, 9 * u, sp)
    drawCircle(Color.White, 9 * u, sp, style = Stroke(1.5f * u))

    label.textSize = 11.sp.toPx()
    label.color = Color.White.toArgb()
    canvas.drawText(r.name.take(1), sp.x, sp.y + 4 * u, label)
    label.textSize = 10.sp.toPx()
    label.color = (if (r.inWheelie) Orange else TextMain).toArgb()
    canvas.drawText(if (r.inWheelie) "${r.name} ↑" else r.name, sp.x, sp.y + 24 * u, label)
}

@Composable
private fun RiderCard(r: Rider, you: Rider, meet: Offset?, selected: Boolean, onClick: () -> Unit) {
    val dist = (r.pos - you.pos).getDistance()
    val kmh = (r.speed * 3.6f).roundToInt()
    val (status, statusColor) = when {
        r.inWheelie -> "WHEELIE!" to Orange
        r.state == RiderState.STOPPED -> "Stopped" to Red
        r.state == RiderState.CATCHING_UP -> "Catching up · $kmh km/h" to Gold
        r.state == RiderState.REGROUPING -> {
            val eta = meet?.let { ((it - r.pos).getDistance() / r.speed.coerceAtLeast(1f)).roundToInt() } ?: 0
            "Heading to you · ${eta}s" to Blue
        }
        r.state == RiderState.ARRIVED -> "At the meeting point" to Go
        else -> "Riding · $kmh km/h" to Muted
    }
    Row(
        Modifier.fillMaxWidth().rowDivider()
            .clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // coloured ring, filled when selected
        Box(
            Modifier.size(30.dp)
                .background(if (selected) r.color else Color.Transparent)
                .border(2.dp, r.color),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                r.name.take(1), color = if (selected) Color.Black else r.color,
                fontSize = 13.sp, fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name, fontSize = 16.sp, fontWeight = FontWeight.Light)
            Text(status.uppercase(), style = LabelStyle.copy(color = statusColor, fontSize = 9.sp, letterSpacing = 1.5.sp))
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(fmtDist(dist), style = numberStyle(20.sp, FontWeight.Light))
            Text(
                "${compass(you.pos, r.pos)} · ${r.battery}%",
                style = LabelStyle.copy(fontSize = 9.sp, letterSpacing = 1.sp),
            )
        }
    }
}

private fun fmtDist(m: Float): String =
    if (m >= 1000f) String.format(Locale.US, "%.1f km", m / 1000f) else "${m.roundToInt()} m"

private fun compass(from: Offset, to: Offset): String {
    val v = to - from
    var b = Math.toDegrees(atan2(v.x.toDouble(), v.y.toDouble())).toFloat()
    if (b < 0) b += 360f
    val names = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return names[((b + 22.5f) / 45f).toInt() % 8]
}

private fun ago(sec: Float): String = when {
    sec < 5f -> "now"
    sec < 60f -> "${sec.roundToInt()}s"
    else -> "${(sec / 60f).roundToInt()}m"
}
