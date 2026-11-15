package com.kineticlabs.pulse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.kineticlabs.pulse.ble.landingScore
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

    val connected = status == PulseBle.Status.CONNECTED || status == PulseBle.Status.DEMO
    val l = live

    Box(Modifier.fillMaxSize()) {
        when {
            !connected -> ConnectPanel(status, onRequestPermissions, onEnableBluetooth) { vm.ble.setDemo(true) }
            l != null && l.inWheelie -> WheelieTakeover(l, all?.longestMs ?: 0)
            else -> IdleLive(l, ride, all, status == PulseBle.Status.DEMO)
        }

        val s = summary
        if (s != null && l?.inWheelie != true) {
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
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("KINETIC LABS", color = Orange, fontWeight = FontWeight.Black, fontSize = 22.sp, letterSpacing = 3.sp)
        Text("PULSE", color = Muted, fontSize = 13.sp, letterSpacing = 6.sp)
        Spacer(Modifier.height(40.dp))

        when (status) {
            PulseBle.Status.NO_PERMISSION -> {
                Text("Bluetooth permission needed", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "The app uses Bluetooth only to talk to your Pulse.",
                    color = Muted, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = onRequestPermissions) { Text("Allow Bluetooth") }
            }
            PulseBle.Status.BLUETOOTH_OFF -> {
                Text("Bluetooth is off", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(20.dp))
                Button(onClick = onEnableBluetooth) { Text("Turn on Bluetooth") }
            }
            else -> {
                CircularProgressIndicator(color = Orange, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(20.dp))
                Text(
                    if (status == PulseBle.Status.CONNECTING) "Connecting…" else "Looking for your Pulse…",
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Turn the Pulse on and keep it close. It shows a blue BT badge once connected.",
                    color = Muted, textAlign = TextAlign.Center,
                )
            }
        }

        Spacer(Modifier.height(36.dp))
        OutlinedButton(onClick = onDemo) { Text("Try demo mode", color = TextMain) }
    }
}

@Composable
private fun IdleLive(live: LivePacket?, ride: StatsPacket?, all: StatsPacket?, demo: Boolean) {
    val angle = live?.pitch ?: 0f
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("KINETIC LABS", color = Orange, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            if (demo) {
                Spacer(Modifier.size(8.dp))
                Text(
                    "DEMO", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Gold)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            live?.battery?.let { Text("Battery $it%", color = Muted, fontSize = 13.sp) }
        }

        Spacer(Modifier.height(36.dp))
        Box(contentAlignment = Alignment.BottomCenter, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            AngleGauge(angle, WHEELIE_ANGLE, Orange)
            Text("${angle.roundToInt()}°", fontSize = 76.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (live?.calibrated == false) "NOT CALIBRATED — open Device to calibrate" else "READY",
            color = if (live?.calibrated == false) Gold else Muted,
            fontSize = 13.sp, letterSpacing = 2.sp,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        )

        Spacer(Modifier.weight(1f))
        TileGrid(
            listOf(
                Tile("This ride", "${ride?.count ?: 0}", sub = "wheelies"),
                Tile("Ride best", ride?.let { fmtSecs(it.longestMs) } ?: DASH, Orange),
                Tile("All-time best", all?.let { fmtSecs(it.longestMs) } ?: DASH, Gold),
                Tile("Highest", all?.let { "${it.highest.roundToInt()}°" } ?: DASH),
            ),
        )
    }
}

/** During a wheelie: the angle is huge, time along the bottom. */
@Composable
private fun WheelieTakeover(live: LivePacket, bestMs: Long) {
    val ms = live.wheelieMs
    val record = bestMs == 0L || ms > bestMs
    Column(
        Modifier.fillMaxSize().background(WheelieBg).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (bestMs > 0 && record) "NEW RECORD!" else "WHEELIE",
            color = if (bestMs > 0 && record) Gold else TextMain,
            fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp,
        )
        Spacer(Modifier.weight(1f))
        Text("${live.pitch.roundToInt()}°", fontSize = 150.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(fmtSecs(ms), fontSize = 44.sp, fontWeight = FontWeight.Bold, color = if (record) Gold else TextMain)
            Spacer(Modifier.weight(1f))
            Text(
                if (bestMs > 0) "BEST ${fmtSecs(bestMs)}" else "FIRST ONE!",
                color = if (record) Gold else Muted, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Bar(if (bestMs > 0) ms.toFloat() / bestMs else 1f, if (record) Gold else Go, WheelieTrack, height = 12)
    }
}

/** After a wheelie: every stat for that run. Tap to close. */
@Composable
private fun SummaryOverlay(s: Summary, onDismiss: () -> Unit) {
    val r = s.rec
    val best = r.newBest
    val vsBest = when {
        best && s.prevBestMs > 0 -> "+" + fmtSecs(r.ms - s.prevBestMs)
        best -> "1ST!"
        s.prevBestMs > 0 -> "${(100f * r.ms / s.prevBestMs).roundToInt()}%"
        else -> DASH
    }
    val land = landingScore(r.landingG)

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(16.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Bg)
                .padding(16.dp)
        ) {
            Text(
                if (best) "NEW BEST!" else "WHEELIE #${s.number}",
                color = if (best) Gold else Orange, fontSize = 24.sp, fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(14.dp))
            TileGrid(
                columns = 3,
                tiles = listOf(
                    Tile("Time", fmtSecs(r.ms), if (best) Gold else TextMain),
                    Tile("Peak", "${r.peak.roundToInt()}°"),
                    Tile("Average", "${r.avg.roundToInt()}°"),
                    Tile("Balance", "${r.balance}", scoreColor(r.balance)),
                    Tile("Pop", "${r.pop}", sub = "°/s"),
                    Tile("Landing", "$land", scoreColor(land), String.format("%.1fg", r.landingG)),
                    Tile("Vs best", vsBest, if (best) Gold else Orange),
                    Tile("Rank", "#${s.rank}", if (s.rank == 1) Gold else TextMain),
                    Tile("Session", fmtSecs(s.sessionTotalMs)),
                ),
            )
            Spacer(Modifier.height(12.dp))
            Text("Tap to close", color = Muted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }
}

@Suppress("unused")
@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = Orange)) { Text(text) }
}
