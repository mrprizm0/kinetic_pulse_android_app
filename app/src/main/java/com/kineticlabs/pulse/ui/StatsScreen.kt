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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
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
    val rides by vm.rides.collectAsState()
    val progress = remember(history, demoRecs, rides) {
        com.kineticlabs.pulse.data.computeProgress(history + demoRecs, rides.map { it.startedAt })
    }

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
                Tile("Longest", if (has) fmtSecs(s!!.longestMs) else DASH, bg = Orange),
                Tile("Highest", if (has) "${s!!.highest.roundToInt()}°" else DASH),
                Tile("Air time", if (has) fmtSecs(s!!.totalMs) else DASH, bg = TileBlue),
                Tile("Avg length", if (has) fmtSecs(s!!.avgMs) else DASH),
                Tile(
                    "Avg balance",
                    if (has && x != null) "${x.avgBalance}" else DASH,
                    bg = if (has && x != null) scoreTile(x.avgBalance) else TileBg,
                ),
                Tile("Best balance", if (has && x != null) "${x.bestBalance}" else DASH),
                Tile("Best pop", if (has && x != null) "${x.bestPop}" else DASH, sub = "°/s"),
                Tile(
                    "Soft landing",
                    x?.softestLandingG?.let { String.format(java.util.Locale.US, "%.1fg", it) } ?: DASH,
                ),
                Tile("5s+ club", "${x?.over5 ?: 0}", bg = if ((x?.over5 ?: 0) > 0) TileAmber else TileBg),
                Tile("10s+ club", "${x?.over10 ?: 0}", bg = if ((x?.over10 ?: 0) > 0) TileAmber else TileBg),
                Tile("Saved on phone", "${history.size}"),
            ),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (allTime) "All-time stats are stored on the Pulse itself."
            else "This ride = since the Pulse was turned on (or reset).",
            color = Muted, fontSize = 12.sp,
        )

        // ---- streaks + week by week
        Spacer(Modifier.height(24.dp))
        SectionLabel("Progress")
        Spacer(Modifier.height(10.dp))
        ProgressSection(progress)

        // ---- tricks (from wheelies saved on this phone, plus demo ones)
        val all = history + demoRecs
        val stoppies = all.filter { it.kind == TrickKind.STOPPIE }
        val circles = all.filter { it.kind == TrickKind.CIRCLE }
        Spacer(Modifier.height(24.dp))
        SectionLabel("Tricks")
        Spacer(Modifier.height(10.dp))
        TileGrid(
            listOf(
                Tile("Stoppies", "${stoppies.size}", bg = if (stoppies.isNotEmpty()) TileRed else TileBg),
                Tile("Best stoppie", stoppies.maxOfOrNull { it.ms }?.let { fmtSecs(it) } ?: DASH),
                Tile("Deepest", stoppies.maxOfOrNull { it.peak }?.let { "${it.roundToInt()}°" } ?: DASH),
                Tile("Stoppie time", if (stoppies.isEmpty()) DASH else fmtSecs(stoppies.sumOf { it.ms })),
                Tile("Wheelie circles", "${circles.size}", bg = if (circles.isNotEmpty()) TileAmber else TileBg),
                Tile(
                    "Most turns",
                    circles.maxOfOrNull { kotlin.math.abs(it.turns) }?.let { String.format(java.util.Locale.US, "%.1fx", it) } ?: DASH,
                ),
            ),
        )

        Spacer(Modifier.height(24.dp))
        SectionLabel("Badges · ${badges.count { it.unlocked }}/${badges.size}")
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            badges.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { BadgeTile(it, Modifier.weight(1f)) }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** Streak tiles, then a bar for each of the last 8 weeks (this week in orange). */
@Composable
private fun ProgressSection(p: com.kineticlabs.pulse.data.Progress) {
    val diff = p.thisWeek - p.lastWeek
    TileGrid(
        columns = 3,
        tiles = listOf(
            Tile("Day streak", "${p.currentStreak}", bg = if (p.currentStreak >= 3) Orange else TileBg),
            Tile("Longest", "${p.longestStreak}", sub = "days"),
            Tile("Days ridden", "${p.daysRidden}"),
            Tile("This week", "${p.thisWeek}", sub = "tricks", bg = TileBlue),
            Tile("Last week", "${p.lastWeek}", sub = "tricks"),
            Tile(
                "Change", if (diff > 0) "+$diff" else "$diff",
                bg = if (diff > 0) TileGreen else if (diff < 0) TileRed else TileBg,
            ),
        ),
    )
    Spacer(Modifier.height(10.dp))
    val max = (p.weeks.maxOrNull() ?: 0).coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().height(110.dp).background(TileBg).padding(12.dp)) {
        val n = p.weeks.size
        val gap = 6.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        p.weeks.forEachIndexed { i, v ->
            val h = (size.height * v / max).coerceAtLeast(2.dp.toPx())
            drawRect(
                if (i == n - 1) Orange else Faint,
                topLeft = androidx.compose.ui.geometry.Offset(i * (w + gap), size.height - h),
                size = androidx.compose.ui.geometry.Size(w, h),
            )
        }
    }
    Text("tricks per week, last 8 weeks", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
}

/** A badge as a square Metro tile: gold when earned, otherwise grey with a progress strip along the bottom. */
@Composable
private fun BadgeTile(b: Badge, modifier: Modifier) {
    Column(
        modifier.aspectRatio(1f).background(if (b.unlocked) Gold else TileBg),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(start = 8.dp, top = 6.dp)) {
            Text(
                b.glyph, fontWeight = FontWeight.Light, fontSize = 22.sp,
                color = if (b.unlocked) Color.Black else Muted,
            )
        }
        Text(
            b.title.lowercase(), fontSize = 11.sp, maxLines = 1,
            color = if (b.unlocked) Color.Black else TextMain,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Text(
            if (b.unlocked) "earned" else b.progressText,
            fontSize = 10.sp, maxLines = 1,
            color = if (b.unlocked) Color.Black.copy(alpha = 0.6f) else Faint,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
        )
        if (!b.unlocked) Bar(b.progress, Orange, Line, height = 3)
    }
}
