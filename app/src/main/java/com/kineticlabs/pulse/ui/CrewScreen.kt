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

private val MapBg = Color(0xFF0B1410)
private val MapGrid = Color(0xFF13211A)
private val ParkColor = Color(0xFF10261A)
private val LakeColor = Color(0xFF0E2233)
private val TrailOuter = Color(0xFF3A3124)
private val TrailInner = Color(0xFF8A7556)
private val TrailSide = Color(0xFF4A4034)
private val RingColor = Color(0x33FFFFFF)

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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Crew", fontSize = 28.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.size(10.dp))
            Text(
                "DEMO", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Gold)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.weight(1f))
            Text("${riders.size} riders", color = Muted, fontSize = 13.sp)
        }
        Text("Fake riders, to preview friend tracking. No real locations are used.", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))

        sim.alert?.let { msg ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Red.copy(alpha = 0.18f))
                    .border(1.dp, Red, RoundedCornerShape(14.dp)).padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("⚠  $msg", color = TextMain, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (mp == null) TextButton(onClick = { sim.regroup() }) { Text("Regroup", color = Red) }
            }
            Spacer(Modifier.height(10.dp))
        }

        // ---- the mini-map
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp))) {
            MiniMap(sim, ZOOMS[zoomIdx], selected, Modifier.fillMaxSize())
            Column(Modifier.align(Alignment.BottomEnd).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MapButton("+", enabled = zoomIdx > 0) { zoomIdx-- }
                MapButton("–", enabled = zoomIdx < ZOOMS.lastIndex) { zoomIdx++ }
            }
            Text(
                "${ZOOMS[zoomIdx].roundToInt()} m",
                color = Muted, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            )
            Text(
                String.format(Locale.US, "%d km/h", (you.speed * 3.6f).roundToInt()),
                color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Color(0xAA000000))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(12.dp))

        // ---- regroup
        if (mp == null) {
            Button(
                onClick = { sim.regroup() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.Black),
            ) { Text("Regroup at my spot", fontWeight = FontWeight.SemiBold) }
        } else {
            val arrived = riders.count { !it.isYou && it.state == RiderState.ARRIVED }
            val total = riders.size - 1
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardBg).padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    Text(
                        if (arrived == total) "Everyone's here!" else "Waiting for the crew",
                        fontWeight = FontWeight.SemiBold, color = if (arrived == total) Go else TextMain,
                    )
                    Text("$arrived of $total at the meeting point", color = Muted, fontSize = 12.sp)
                }
                TextButton(onClick = { sim.cancelRegroup() }) { Text("Cancel") }
            }
        }
        Spacer(Modifier.height(18.dp))

        // ---- riders
        SectionLabel("Riders")
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            riders.drop(1).forEach { r ->
                RiderCard(r, you, mp, selected == r.id) { selected = if (selected == r.id) null else r.id }
            }
        }
        Spacer(Modifier.height(18.dp))

        // ---- activity
        SectionLabel("Activity")
        Spacer(Modifier.height(8.dp))
        sim.feed.take(12).forEach { f ->
            Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(f.color))
                Spacer(Modifier.size(10.dp))
                Text(f.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(ago(sim.time - f.time), color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun MapButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Color(0xCC18181C))
            .border(1.dp, Line, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) TextMain else Muted, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardBg)
            .border(if (selected) 1.5.dp else 0.dp, if (selected) r.color else Color.Transparent, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(r.color), contentAlignment = Alignment.Center) {
            Text(r.name.take(1), color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name, fontWeight = FontWeight.SemiBold)
            Text(status, color = statusColor, fontSize = 12.sp)
            r.lastWheelie?.let { Text("Last wheelie $it", color = Muted, fontSize = 11.sp) }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${fmtDist(dist)} ${compass(you.pos, r.pos)}", fontWeight = FontWeight.Bold)
            Text("Battery ${r.battery}%", color = Muted, fontSize = 11.sp)
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
