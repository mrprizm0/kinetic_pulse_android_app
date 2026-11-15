package com.kineticlabs.pulse.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kineticlabs.pulse.PulseViewModel
import com.kineticlabs.pulse.ble.TrickKind
import com.kineticlabs.pulse.data.Badge
import com.kineticlabs.pulse.data.computeBadges
import kotlin.math.roundToInt

@Composable
fun StatsScreen(vm: PulseViewModel) {
    var allTime by rememberSaveable { mutableStateOf(false) }
    val ride by vm.ble.ride.collectAsState()
    val all by vm.ble.allTime.collectAsState()
    val rideX by vm.ble.rideExtra.collectAsState()
    val allX by vm.ble.allTimeExtra.collectAsState()
    val history by vm.history.collectAsState()
    val demoRecs by vm.demoRecords.collectAsState()
    val badges = remember(history, demoRecs) { computeBadges(history + demoRecs) }

    val s = if (allTime) all else ride
    val x = if (allTime) allX else rideX
    val has = s != null && s.count > 0

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
    ) {
        ScreenTitle("Stats")
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

        // ---- tricks (from wheelies saved on this phone, plus demo ones)
        val all = history + demoRecs
        val stoppies = all.filter { it.kind == TrickKind.STOPPIE }
        val circles = all.filter { it.kind == TrickKind.CIRCLE }
        Spacer(Modifier.height(24.dp))
        SectionLabel("Tricks")
        Spacer(Modifier.height(10.dp))
        TileGrid(
            listOf(
                Tile("Stoppies", "${stoppies.size}", if (stoppies.isNotEmpty()) Red else TextMain),
                Tile("Best stoppie", stoppies.maxOfOrNull { it.ms }?.let { fmtSecs(it) } ?: DASH),
                Tile("Deepest", stoppies.maxOfOrNull { it.peak }?.let { "${it.roundToInt()}°" } ?: DASH),
                Tile("Stoppie time", if (stoppies.isEmpty()) DASH else fmtSecs(stoppies.sumOf { it.ms })),
                Tile("Wheelie circles", "${circles.size}", if (circles.isNotEmpty()) Gold else TextMain),
                Tile(
                    "Most turns",
                    circles.maxOfOrNull { kotlin.math.abs(it.turns) }?.let { String.format("%.1fx", it) } ?: DASH,
                ),
            ),
        )

        Spacer(Modifier.height(24.dp))
        SectionLabel("Badges · ${badges.count { it.unlocked }}/${badges.size}")
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            badges.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { BadgeTile(it, Modifier.weight(1f)) }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** A badge: a thin progress ring with the glyph inside. Gold when earned. */
@Composable
private fun BadgeTile(b: Badge, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val sw = 2.dp.toPx()
                val inset = sw / 2
                val arcSize = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw)
                val tl = androidx.compose.ui.geometry.Offset(inset, inset)
                drawArc(Line, 0f, 360f, false, tl, arcSize, style = Stroke(sw))
                val sweep = if (b.unlocked) 360f else 360f * b.progress
                if (sweep > 0f) {
                    drawArc(
                        if (b.unlocked) Gold else Orange, -90f, sweep, false, tl, arcSize,
                        style = Stroke(sw, cap = StrokeCap.Round),
                    )
                }
            }
            Text(
                b.glyph, fontWeight = FontWeight.Light, fontSize = 15.sp,
                color = if (b.unlocked) Gold else Muted,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            b.title, fontSize = 11.sp, maxLines = 1, textAlign = TextAlign.Center,
            color = if (b.unlocked) TextMain else Muted,
        )
        Text(
            if (b.unlocked) "EARNED" else b.progressText,
            style = LabelStyle.copy(fontSize = 9.sp, letterSpacing = 1.sp, color = if (b.unlocked) Gold else Faint),
            maxLines = 1,
        )
    }
}
