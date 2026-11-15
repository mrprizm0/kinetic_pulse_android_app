package com.kineticlabs.pulse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kineticlabs.pulse.PulseViewModel
import com.kineticlabs.pulse.Summary
import com.kineticlabs.pulse.ble.LivePacket
import com.kineticlabs.pulse.ble.PulseBle
import com.kineticlabs.pulse.ble.StatsPacket
import com.kineticlabs.pulse.ble.TrickKind
import com.kineticlabs.pulse.ble.landingScore
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val WHEELIE_ANGLE = 8f  // device default ("Normal" sensitivity)
private const val SUMMARY_MS = 8000L

@Composable
fun LiveScreen(vm: PulseViewModel, onRequestPermissions: () -> Unit, onEnableBluetooth: () -> Unit) {
    val status by vm.ble.status.collectAsState()
    val live by vm.ble.live.collectAsState()
    val ride by vm.ble.ride.collectAsState()
    val all by vm.ble.allTime.collectAsState()
    val summary by vm.summary.collectAsState()
    val goal by vm.goalSec.collectAsState()
    val history by vm.history.collectAsState()
    val demoRecs by vm.demoRecords.collectAsState()
    val bestStoppieMs = remember(history, demoRecs) {
        (history + demoRecs).filter { it.kind == TrickKind.STOPPIE }.maxOfOrNull { it.ms } ?: 0L
    }

    val connected = status == PulseBle.Status.CONNECTED || status == PulseBle.Status.DEMO
    val l = live

    Box(Modifier.fillMaxSize()) {
        when {
            !connected -> ConnectPanel(status, onRequestPermissions, onEnableBluetooth) { vm.ble.setDemo(true) }
            l != null && l.inWheelie -> WheelieTakeover(l, all?.longestMs ?: 0, goal)
            l != null && l.inStoppie -> StoppieTakeover(l, bestStoppieMs)
            else -> IdleLive(l, ride, all, status == PulseBle.Status.DEMO, goal)
        }

        val s = summary
        if (s != null && l?.inWheelie != true && l?.inStoppie != true) {
            SummaryOverlay(s) { vm.dismissSummary() }
            LaunchedEffect(s.shownAt) {
                delay(SUMMARY_MS)
                vm.dismissSummary(s.shownAt)
            }
        }
    }
}

@Composable
private fun ConnectPanel(
    status: PulseBle.Status,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onDemo: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("KINETIC", fontSize = 44.sp, fontWeight = FontWeight.Thin, letterSpacing = 6.sp)
        Text("LABS · PULSE", style = LabelStyle.copy(color = Orange))
        Spacer(Modifier.height(56.dp))

        val (headline, body) = when (status) {
            PulseBle.Status.NO_PERMISSION ->
                "Bluetooth permission needed" to "The app only uses Bluetooth to talk to your Pulse."
            PulseBle.Status.BLUETOOTH_OFF -> "Bluetooth is off" to "Turn it on to connect."
            PulseBle.Status.CONNECTING -> "Connecting" to "Hold on a second."
            else -> "Looking for your Pulse" to "Turn it on and keep it close."
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (status == PulseBle.Status.SCANNING || status == PulseBle.Status.CONNECTING) {
                CircularProgressIndicator(color = Orange, strokeWidth = 1.5.dp, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(12.dp))
            }
            Text(headline, fontSize = 18.sp, fontWeight = FontWeight.Light)
        }
        Spacer(Modifier.height(6.dp))
        Text(body, color = Muted, fontSize = 14.sp)

        Spacer(Modifier.height(32.dp))
        when (status) {
            PulseBle.Status.NO_PERMISSION -> PillButton("Allow Bluetooth", primary = true, onClick = onRequestPermissions)
            PulseBle.Status.BLUETOOTH_OFF -> PillButton("Turn on Bluetooth", primary = true, onClick = onEnableBluetooth)
            else -> {}
        }
        Spacer(Modifier.height(12.dp))
        PillButton("Try demo mode", primary = false, onClick = onDemo)
    }
}

@Composable
private fun PillButton(text: String, primary: Boolean, onClick: () -> Unit) {
    Text(
        text.uppercase(), fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium,
        color = if (primary) Color.Black else TextMain,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (primary) Orange else Color.Transparent)
            .border(1.dp, if (primary) Orange else Faint, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
    )
}

@Composable
private fun IdleLive(live: LivePacket?, ride: StatsPacket?, all: StatsPacket?, demo: Boolean, goalSec: Int) {
    val angle = live?.pitch ?: 0f
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (demo) Gold else Go))
            Spacer(Modifier.size(8.dp))
            Text(if (demo) "DEMO" else "CONNECTED", style = LabelStyle)
            Spacer(Modifier.weight(1f))
            live?.battery?.let { Text("$it%", style = LabelStyle) }
        }

        Spacer(Modifier.weight(0.6f))
        Box(contentAlignment = Alignment.BottomCenter, modifier = Modifier.fillMaxWidth()) {
            AngleGauge(angle, WHEELIE_ANGLE, Orange)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 4.dp)) {
                Text("${angle.roundToInt()}°", style = numberStyle(84.sp))
                Text(
                    if (live?.calibrated == false) "CALIBRATE IN DEVICE" else "PITCH",
                    style = LabelStyle.copy(color = if (live?.calibrated == false) Gold else Muted),
                )
            }
        }
        if (goalSec > 0) {
            Spacer(Modifier.height(18.dp))
            Text(
                "GOAL ${goalSec}s", style = LabelStyle.copy(color = Orange),
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.weight(1f))
        TileGrid(
            listOf(
                Tile("This ride", "${ride?.count ?: 0}"),
                Tile("Ride best", ride?.let { fmtSecs(it.longestMs) } ?: DASH),
                Tile("Best ever", all?.let { fmtSecs(it.longestMs) } ?: DASH, Orange),
                Tile("Highest", all?.let { "${it.highest.roundToInt()}°" } ?: DASH),
            ),
        )
    }
}

/**
 * Shared layout while a trick is happening: one enormous thin number, the
 * trick name above, time and a hairline progress bar below, and a soft
 * coloured glow behind it all.
 */
@Composable
private fun TrickTakeover(
    label: String,
    angle: Int,
    ms: Long,
    bestMs: Long,
    tint: Color,
    track: Color,
    goalSec: Int = 0,
) {
    val record = bestMs == 0L || ms > bestMs
    val showRecord = bestMs > 0 && record
    val goalHit = goalSec > 0 && ms >= goalSec * 1000L
    Column(
        Modifier.fillMaxSize().background(Bg).glow(if (showRecord) Gold else tint).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (showRecord) "NEW RECORD" else label,
            style = LabelStyle.copy(color = if (showRecord) Gold else tint, fontSize = 12.sp, letterSpacing = 4.sp),
        )
        if (goalSec > 0) {
            Spacer(Modifier.height(10.dp))
            Text(
                if (goalHit) "GOAL ${goalSec}s ✓" else "GOAL ${goalSec}s",
                style = LabelStyle.copy(color = if (goalHit) Go else Muted),
                modifier = Modifier.border(1.dp, if (goalHit) Go else Faint, RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Text("$angle°", style = numberStyle(168.sp), color = TextMain)
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(fmtSecs(ms), style = numberStyle(48.sp, FontWeight.ExtraLight), color = if (record) Gold else TextMain)
            Spacer(Modifier.weight(1f))
            Text(
                if (bestMs > 0) "BEST ${fmtSecs(bestMs)}" else "FIRST ONE",
                style = LabelStyle.copy(color = if (record) Gold else Muted),
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Bar(if (bestMs > 0) ms.toFloat() / bestMs else 1f, if (record) Gold else tint, track)
    }
}

/** During a wheelie. */
@Composable
private fun WheelieTakeover(live: LivePacket, bestMs: Long, goalSec: Int) {
    TrickTakeover(
        label = "WHEELIE", angle = live.pitch.roundToInt(), ms = live.wheelieMs, bestMs = bestMs,
        tint = Go, track = WheelieTrack, goalSec = goalSec,
    )
}

/** During a stoppie. */
@Composable
private fun StoppieTakeover(live: LivePacket, bestMs: Long) {
    TrickTakeover(
        label = "STOPPIE", angle = (-live.pitch).roundToInt(), ms = live.wheelieMs, bestMs = bestMs,
        tint = Red, track = StoppieTrack,
    )
}

/** After a trick: every stat for that run. Tap to close. */
@Composable
private fun SummaryOverlay(s: Summary, onDismiss: () -> Unit) {
    val r = s.rec
    val best = r.newBest
    val stoppie = r.kind == TrickKind.STOPPIE
    val circle = r.kind == TrickKind.CIRCLE
    val vsBest = when {
        best && s.prevBestMs > 0 -> "+" + fmtSecs(r.ms - s.prevBestMs)
        best -> "1ST!"
        s.prevBestMs > 0 -> "${(100f * r.ms / s.prevBestMs).roundToInt()}%"
        else -> DASH
    }
    val land = landingScore(r.landingG)
    val turnsText = String.format("%.1fx %s", kotlin.math.abs(r.turns), if (r.turns > 0) "L" else "R")

    val title = when {
        stoppie && best -> "BEST STOPPIE"
        stoppie -> "STOPPIE  ·  #${s.number}"
        circle -> String.format("WHEELIE CIRCLE  ·  %.1fx", kotlin.math.abs(r.turns))
        best -> "NEW BEST"
        else -> "WHEELIE  ·  #${s.number}"
    }
    val titleColor = when {
        best || circle -> Gold
        stoppie -> Red
        else -> Orange
    }
    // the time is the hero; everything else sits quietly underneath
    val tiles = if (stoppie) listOf(
        Tile("Depth", "${r.peak.roundToInt()}°"),
        Tile("Balance", "${r.balance}", scoreColor(r.balance)),
        Tile("Vs best", vsBest, if (best) Gold else TextMain),
        Tile("Rank", "#${s.rank}", if (s.rank == 1) Gold else TextMain),
        Tile("Session", fmtSecs(s.sessionTotalMs)),
    ) else listOf(
        Tile("Peak", "${r.peak.roundToInt()}°"),
        Tile("Average", "${r.avg.roundToInt()}°"),
        Tile("Balance", "${r.balance}", scoreColor(r.balance)),
        Tile("Pop", "${r.pop}", sub = "°/s"),
        Tile("Landing", "$land", scoreColor(land), String.format("%.1fg", r.landingG)),
        Tile("Vs best", vsBest, if (best) Gold else TextMain),
        if (circle) Tile("Turns", turnsText, Gold) else Tile("Rank", "#${s.rank}", if (s.rank == 1) Gold else TextMain),
        Tile("Session", fmtSecs(s.sessionTotalMs)),
    )

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(CardBg)
                .border(1.dp, Line, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .padding(horizontal = 24.dp, vertical = 22.dp)
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 3.dp).clip(RoundedCornerShape(50)).background(Faint))
            Spacer(Modifier.height(18.dp))
            Text(title, style = LabelStyle.copy(color = titleColor, fontSize = 11.sp, letterSpacing = 3.sp))
            Text(fmtSecs(r.ms), style = numberStyle(72.sp), color = if (best) Gold else TextMain)
            Spacer(Modifier.height(6.dp))
            Hairline()
            TileGrid(columns = 3, tiles = tiles)
            Spacer(Modifier.height(10.dp))
            Text("TAP TO CLOSE", style = LabelStyle.copy(color = Faint), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }
}
