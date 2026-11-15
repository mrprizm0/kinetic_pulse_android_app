package com.kineticlabs.pulse.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kineticlabs.pulse.PulseViewModel
import com.kineticlabs.pulse.ride.SavedRide
import com.kineticlabs.pulse.ride.Turn
import com.kineticlabs.pulse.ble.PulseBle
import com.kineticlabs.pulse.spots.Rideout
import com.kineticlabs.pulse.spots.Spot
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

private val SpotMotor = Color(0xFF008A28)
private val SpotNoMotor = Color(0xFF6E28DC)
private val RideoutColor = Color(0xFFC97800)

@Composable
fun RideScreen(vm: PulseViewModel, onRequestLocation: () -> Unit) {
    var spotsTab by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp)) {
        ScreenTitle("Ride")
        Spacer(Modifier.height(14.dp))
        SegmentToggle("Track", "Spots", spotsTab) { spotsTab = it }
        Spacer(Modifier.height(20.dp))
        if (vm.hasLocationPermission()) RouteCard(vm)
        if (!vm.hasLocationPermission()) {
            Text("Ride tracking and nearby spots need your location.", fontSize = 16.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.height(6.dp))
            Text("It's only used on this phone, and to look up spots near you. Your route is never uploaded.", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(18.dp))
            Pill("Allow location", primary = true, onClick = onRequestLocation)
            return@Column
        }
        if (spotsTab) SpotsPanel(vm) else TrackPanel(vm)
    }
}


// ------------------------------------------------------------------- route (beta)

@Composable
private fun RouteCard(vm: PulseViewModel) {
    val ui by vm.route.collectAsState()
    val status by vm.ble.status.collectAsState()
    ui.error?.let { err ->
        Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(err, color = Red, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("OK", style = LabelStyle.copy(color = Muted), modifier = Modifier.clickable { vm.dismissRouteError() }.padding(8.dp))
        }
    }
    if (ui.loading) {
        Text("Finding a route…", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 14.dp))
        return
    }
    val nav = ui.route ?: return
    val p = ui.progress
    Column(Modifier.fillMaxWidth().background(CardBg).border(1.dp, if (p?.offRoute == true) Red else Line).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ROUTE", style = LabelStyle.copy(color = Orange))
            Spacer(Modifier.width(8.dp))
            Text("BETA", style = LabelStyle.copy(color = Color.Black, fontSize = 9.sp),
                modifier = Modifier.background(Gold).padding(horizontal = 5.dp, vertical = 1.dp))
            Spacer(Modifier.weight(1f))
            Text("END", style = LabelStyle.copy(color = Red), modifier = Modifier.clickable { vm.endRoute() }.padding(6.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(nav.name, fontSize = 18.sp, fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        val line = when {
            p == null -> "${fmtKm(nav.lengthM)} · waiting for GPS"
            p.arrived -> "You've arrived"
            p.offRoute -> "Off route · head back to the orange line"
            p.nextTurn != 0 -> "${fmtKm(p.leftM)} to go · ${Turn.words(p.nextTurn)} in ${fmtKm(p.nextTurnM)}"
            else -> "${fmtKm(p.leftM)} to go"
        }
        Text(line, color = if (p?.offRoute == true) Red else Muted, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        RouteCanvas(nav.points, Modifier.fillMaxWidth().aspectRatio(2.2f),
            you = p?.let { nav.points[it.index.coerceIn(0, nav.points.size - 1)] })
        Spacer(Modifier.height(8.dp))
        val pulseNote = when {
            status != PulseBle.Status.CONNECTED -> "Connect your Pulse to see it on the route screen."
            !vm.ble.supportsRoute -> "Update your Pulse to firmware V2.6 to see routes on it."
            else -> "Showing on your Pulse's route screen."
        }
        Text(pulseNote, color = Faint, fontSize = 11.sp)
    }
    Spacer(Modifier.height(18.dp))
}

// ------------------------------------------------------------------ tracking

@Composable
private fun TrackPanel(vm: PulseViewModel) {
    val r by vm.ride.collectAsState()
    val rides by vm.rides.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(r.active) { while (r.active) { now = System.currentTimeMillis(); delay(1000) } }

    if (r.active) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (r.hasFix) Go else Gold))
            Spacer(Modifier.width(8.dp))
            Text(if (r.hasFix) "RECORDING" else "WAITING FOR GPS", style = LabelStyle.copy(color = if (r.hasFix) Go else Gold))
        }
        Spacer(Modifier.height(6.dp))
        Text(fmtClock(r.elapsedMs(now)), style = numberStyle(72.sp))
        Spacer(Modifier.height(4.dp))
        TileGrid(
            columns = 3,
            tiles = listOf(
                Tile("Distance", fmtKm(r.distanceM)),
                Tile("Speed", "${(r.speedMs * 3.6f).roundToInt()}", sub = "km/h"),
                Tile("Top", "${(r.topSpeedMs * 3.6f).roundToInt()}", sub = "km/h"),
                Tile("Wheelies", "${r.wheelies}", Orange),
                Tile("Best", if (r.bestWheelieMs > 0) fmtSecs(r.bestWheelieMs) else DASH),
                Tile("Avg", "${(r.avgMovingMs * 3.6f).roundToInt()}", sub = "km/h"),
            ),
        )
        Spacer(Modifier.height(14.dp))
        RouteCanvas(r.route.map { it.lat to it.lon }, Modifier.fillMaxWidth().aspectRatio(1.6f))
        Spacer(Modifier.height(18.dp))
        Pill("Stop and save", primary = false, danger = true) { vm.stopRide() }
        Spacer(Modifier.height(8.dp))
        Text("Your Pulse shows this on its trip screen while it's connected.", color = Muted, fontSize = 12.sp)
    } else {
        Text("Track a ride", fontSize = 22.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.height(4.dp))
        Text("Time, distance, speed and your route, with every wheelie counted along the way. Keeps going with the screen off.",
            color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))
        Pill("Start ride", primary = true) { vm.startRide() }
    }

    Spacer(Modifier.height(32.dp))
    SectionLabel("Past rides")
    Spacer(Modifier.height(6.dp))
    if (rides.isEmpty()) {
        Text("Your rides show up here.", color = Muted, fontSize = 13.sp)
    } else {
        var open by remember { mutableStateOf<Long?>(null) }
        val fmt = remember { SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()) }
        rides.asReversed().forEach { ride ->
            RideRow(ride, fmt.format(Date(ride.startedAt)), open == ride.startedAt,
                onClick = { open = if (open == ride.startedAt) null else ride.startedAt },
                onDelete = { vm.deleteRide(ride) },
                onFollow = { vm.followRide(ride) })
        }
    }
}

@Composable
private fun RideRow(r: SavedRide, date: String, expanded: Boolean, onClick: () -> Unit, onDelete: () -> Unit, onFollow: () -> Unit) {
    Column(Modifier.fillMaxWidth().rowDivider().clickable(onClick = onClick).padding(vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(date, color = Muted, fontSize = 12.sp)
                Text("${fmtClock(r.durationMs)} · ${r.wheelies} wheelies · top ${(r.topSpeedMs * 3.6f).roundToInt()} km/h",
                    color = Muted, fontSize = 12.sp)
            }
            Text(fmtKm(r.distanceM), style = numberStyle(26.sp, FontWeight.Light))
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            RouteCanvas(r.path, Modifier.fillMaxWidth().aspectRatio(1.6f))
            Spacer(Modifier.height(8.dp))
            TileGrid(columns = 3, tiles = listOf(
                Tile("Moving", fmtClock(r.movingMs)),
                Tile("Avg", "${(r.avgMovingMs * 3.6f).roundToInt()}", sub = "km/h"),
                Tile("Best", if (r.bestWheelieMs > 0) fmtSecs(r.bestWheelieMs) else DASH, Orange),
            ))
            Row {
                if (r.path.size >= 2) TextButton(onClick = onFollow) { Text("FOLLOW ON PULSE · BETA", style = LabelStyle.copy(color = Orange)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDelete) { Text("DELETE RIDE", style = LabelStyle.copy(color = Red)) }
            }
        }
    }
}

/** The route as a line, scaled to fit. No map tiles: just the shape of where you went. */
@Composable
private fun RouteCanvas(path: List<Pair<Double, Double>>, modifier: Modifier, you: Pair<Double, Double>? = null) {
    Box(modifier.background(CardBg).border(1.dp, Line), contentAlignment = Alignment.Center) {
        if (path.size < 2) {
            Text("route appears once you start moving", color = Faint, fontSize = 12.sp)
            return@Box
        }
        Canvas(Modifier.fillMaxSize().padding(16.dp)) {
            val lat0 = path.sumOf { it.first } / path.size
            val k = cos(Math.toRadians(lat0))
            val xs = path.map { it.second * k }; val ys = path.map { it.first }
            val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
            val span = max(maxX - minX, maxY - minY).coerceAtLeast(1e-6)
            val s = minOf(size.width, size.height) / span
            val ox = (size.width - (maxX - minX) * s) / 2; val oy = (size.height - (maxY - minY) * s) / 2
            fun pt(i: Int) = Offset((ox + (xs[i] - minX) * s).toFloat(), (size.height - oy - (ys[i] - minY) * s).toFloat())
            val p = Path().apply { moveTo(pt(0).x, pt(0).y); for (i in 1 until path.size) lineTo(pt(i).x, pt(i).y) }
            drawPath(p, Orange.copy(alpha = 0.25f), style = Stroke(10.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(p, Orange, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(Go, 5.dp.toPx(), pt(0))
            drawCircle(TextMain, 5.dp.toPx(), pt(path.size - 1))
            you?.let { (la, lo) ->
                val c = Offset((ox + (lo * k - minX) * s).toFloat(), (size.height - oy - (la - minY) * s).toFloat())
                drawCircle(Blue, 7.dp.toPx(), c)
                drawCircle(TextMain, 3.dp.toPx(), c)
            }
        }
    }
}

// --------------------------------------------------------------------- spots

@Composable
private fun SpotsPanel(vm: PulseViewModel) {
    val ui by vm.spots.collectAsState()
    val ctx = LocalContext.current
    var filter by rememberSaveable { mutableStateOf(0) }  // 0 all, 1 motors ok, 2 skate & bmx
    var posting by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (ui.updatedAt == 0L || System.currentTimeMillis() - ui.updatedAt > 600_000) vm.refreshSpots() }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Places to ride near you", fontSize = 18.sp, fontWeight = FontWeight.Light, modifier = Modifier.weight(1f))
        Text(if (ui.loading) "LOADING…" else "REFRESH", style = LabelStyle.copy(color = if (ui.loading) Faint else Orange),
            modifier = Modifier.clickable(enabled = !ui.loading) { vm.refreshSpots() }.padding(8.dp))
    }
    ui.error?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, color = Red, fontSize = 13.sp)
    }

    // ---- rideouts
    Spacer(Modifier.height(18.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionLabel("Rideouts", Modifier.weight(1f))
        Text("POST ONE", style = LabelStyle.copy(color = Orange), modifier = Modifier.clickable { posting = true }.padding(8.dp))
    }
    if (ui.rideouts.isEmpty() && !ui.loading) {
        Text("No group rides posted nearby yet. Post one and the crew can find it.", color = Muted, fontSize = 13.sp)
    }
    val whenFmt = remember { SimpleDateFormat("EEE d MMM · h:mm a", Locale.getDefault()) }
    ui.rideouts.forEach { r ->
        RideoutRow(r, whenFmt.format(Date(r.startsAt)), onRoute = { vm.routeTo(r.lat, r.lon, r.title) }) { openMap(ctx, r.lat, r.lon, r.title) }
    }

    // ---- spots
    Spacer(Modifier.height(22.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("All", "Motors OK", "Skate & BMX").forEachIndexed { i, label ->
            val on = filter == i
            Text(label.uppercase(), fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Medium,
                color = if (on) Color.Black else TextMain,
                modifier = Modifier.background(if (on) Orange else Color.Transparent).border(1.dp, if (on) Orange else Faint)
                    .clickable { filter = i }.padding(horizontal = 10.dp, vertical = 7.dp))
        }
    }
    Spacer(Modifier.height(8.dp))
    val shown = ui.spots.filter { when (filter) { 1 -> it.motorsOk; 2 -> !it.motorsOk; else -> true } }
    if (shown.isEmpty() && !ui.loading && ui.error == null) Text("Nothing found within 30 km.", color = Muted, fontSize = 13.sp)
    shown.forEach { s -> SpotRow(s, onRoute = { vm.routeTo(s.lat, s.lon, s.name) }) { openMap(ctx, s.lat, s.lon, s.name) } }

    Spacer(Modifier.height(14.dp))
    Text("ROUTE (beta) gets a road route from OpenStreetMap through the public OSRM router. It sends your start and end points there, nothing else. Check the route makes sense before you ride it.",
        color = Faint, fontSize = 11.sp)
    Spacer(Modifier.height(6.dp))
    Text("Places come from OpenStreetMap. Most skateparks, BMX and pump tracks don't allow motorbikes, so check the rules before you ride, and ride where it's legal.",
        color = Faint, fontSize = 11.sp)

    if (posting) PostRideoutDialog(vm) { posting = false }
}

@Composable
private fun RideoutRow(r: Rideout, whenText: String, onRoute: () -> Unit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().rowDivider().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).background(RideoutColor), contentAlignment = Alignment.Center) {
            Text("R", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(whenText + if (r.note.isNotBlank()) " · ${r.note}" else "", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(fmtKm(r.distanceM.toDouble()), style = numberStyle(18.sp, FontWeight.Light))
        RouteButton(onRoute)
    }
}

@Composable
private fun SpotRow(s: Spot, onRoute: () -> Unit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().rowDivider().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).background(if (s.motorsOk) SpotMotor else SpotNoMotor), contentAlignment = Alignment.Center) {
            Text(s.kind.first().uppercase(), color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.kind.uppercase() + if (s.motorsOk) " · MOTORS OK" else " · USUALLY NO MOTORS",
                style = LabelStyle.copy(fontSize = 9.sp, letterSpacing = 1.sp, color = if (s.motorsOk) Go else Muted))
        }
        Text(fmtKm(s.distanceM.toDouble()), style = numberStyle(18.sp, FontWeight.Light))
        RouteButton(onRoute)
    }
}

@Composable
private fun RouteButton(onClick: () -> Unit) {
    Spacer(Modifier.width(10.dp))
    Text("ROUTE", style = LabelStyle.copy(color = Orange, fontSize = 10.sp),
        modifier = Modifier.border(1.dp, Orange).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp))
}

@Composable
private fun PostRideoutDialog(vm: PulseViewModel, close: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var day by remember { mutableStateOf(0) }       // days from today
    var hour by remember { mutableStateOf(18f) }    // 6..22
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val dayFmt = remember { SimpleDateFormat("EEE d", Locale.getDefault()) }
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Orange, unfocusedBorderColor = Faint, cursorColor = Orange)

    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        containerColor = CardBg,
        title = { Text("Post a rideout", fontWeight = FontWeight.Light) },
        text = {
            Column {
                OutlinedTextField(title, { title = it.take(60) }, label = { Text("Name") }, singleLine = true, colors = fieldColors)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(note, { note = it.take(200) }, label = { Text("Note (optional)") }, colors = fieldColors)
                Spacer(Modifier.height(14.dp))
                SectionLabel("Day")
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (0..6).forEach { d ->
                        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, d) }
                        val on = d == day
                        Text(if (d == 0) "TODAY" else dayFmt.format(cal.time).uppercase(), fontSize = 9.sp, letterSpacing = 1.sp,
                            color = if (on) Color.Black else TextMain,
                            modifier = Modifier.background(if (on) Orange else Color.Transparent).border(1.dp, if (on) Orange else Faint)
                                .clickable { day = d }.padding(horizontal = 6.dp, vertical = 6.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                SectionLabel("Time · ${fmtHour(hour.roundToInt())}")
                Slider(hour, { hour = it }, valueRange = 6f..22f, steps = 15,
                    colors = SliderDefaults.colors(thumbColor = Orange, activeTrackColor = Orange, inactiveTrackColor = Line))
                Text("The meeting spot is where you are now, rounded to about 100 m.", color = Muted, fontSize = 12.sp)
                error?.let { Spacer(Modifier.height(6.dp)); Text(it, color = Red, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && title.trim().length >= 3, onClick = {
                busy = true; error = null
                val cal = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, day); set(Calendar.HOUR_OF_DAY, hour.roundToInt())
                    set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                vm.postRideout(title.trim(), note.trim(), cal.timeInMillis) { err ->
                    busy = false
                    if (err == null) close() else error = err
                }
            }) { Text(if (busy) "POSTING…" else "POST", color = Orange) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = close) { Text("CANCEL", color = Muted) } },
    )
}

// ------------------------------------------------------------------- helpers

@Composable
private fun Pill(text: String, primary: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    val c = if (danger) Red else Orange
    Text(
        text.uppercase(), fontSize = 13.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium,
        color = if (primary) Color.Black else (if (danger) Red else TextMain),
        modifier = Modifier.fillMaxWidth().background(if (primary) c else Color.Transparent, RoundedCornerShape(50))
            .border(1.dp, if (primary) c else (if (danger) Red else Faint), RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(vertical = 15.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

private fun openMap(ctx: android.content.Context, lat: Double, lon: Double, label: String) {
    val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(label)})")
    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) {}
}

fun fmtClock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
}

fun fmtKm(m: Double): String =
    if (m < 1000) "${m.roundToInt()} m" else String.format(Locale.US, "%.1f km", m / 1000)

private fun fmtHour(h: Int): String = when {
    h == 0 -> "12 am"; h < 12 -> "$h am"; h == 12 -> "12 pm"; else -> "${h - 12} pm"
}
