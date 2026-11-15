package com.kineticlabs.pulse.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kineticlabs.pulse.PulseViewModel
import kotlin.math.roundToInt

@Composable
fun StatsScreen(vm: PulseViewModel) {
    var allTime by rememberSaveable { mutableStateOf(false) }
    val ride by vm.ble.ride.collectAsState()
    val all by vm.ble.allTime.collectAsState()
    val rideX by vm.ble.rideExtra.collectAsState()
    val allX by vm.ble.allTimeExtra.collectAsState()
    val history by vm.history.collectAsState()

    val s = if (allTime) all else ride
    val x = if (allTime) allX else rideX
    val has = s != null && s.count > 0

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
    ) {
        Text("Stats", fontSize = 28.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(14.dp))
        SegmentToggle("This ride", "All time", allTime) { allTime = it }
        Spacer(Modifier.height(16.dp))

        if (s == null) {
            Text("Connect your Pulse (or try demo mode) to see stats.", color = Muted)
            Spacer(Modifier.height(16.dp))
        }

        TileGrid(
            listOf(
                Tile("Wheelies", "${s?.count ?: 0}"),
                Tile("Longest", if (has) fmtSecs(s!!.longestMs) else DASH, Orange),
                Tile("Highest", if (has) "${s!!.highest.roundToInt()}°" else DASH),
                Tile("Air time", if (has) fmtSecs(s!!.totalMs) else DASH),
                Tile("Avg length", if (has) fmtSecs(s!!.avgMs) else DASH),
                Tile(
                    "Avg balance",
                    if (has && x != null) "${x.avgBalance}" else DASH,
                    if (has && x != null) scoreColor(x.avgBalance) else TextMain,
                ),
                Tile("Best balance", if (has && x != null) "${x.bestBalance}" else DASH),
                Tile("Best pop", if (has && x != null) "${x.bestPop}" else DASH, sub = "°/s"),
                Tile(
                    "Soft landing",
                    x?.softestLandingG?.let { String.format("%.1fg", it) } ?: DASH,
                ),
                Tile("5s+ club", "${x?.over5 ?: 0}", if ((x?.over5 ?: 0) > 0) Gold else TextMain),
                Tile("10s+ club", "${x?.over10 ?: 0}", if ((x?.over10 ?: 0) > 0) Gold else TextMain),
                Tile("Saved on phone", "${history.size}"),
            ),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (allTime) "All-time stats are stored on the Pulse itself."
            else "This ride = since the Pulse was turned on (or reset).",
            color = Muted, fontSize = 12.sp,
        )
    }
}
