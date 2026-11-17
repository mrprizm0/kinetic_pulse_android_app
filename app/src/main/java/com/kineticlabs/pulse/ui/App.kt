package com.kineticlabs.pulse.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kineticlabs.pulse.PulseViewModel
import kotlinx.coroutines.launch

enum class Tab(val label: String) {
    LIVE("live"), RIDE("ride"), STATS("stats"), CREW("crew"), HISTORY("history"), DEVICE("device"),
}

/**
 * Metro "pivot": the app name small at the top, then the page names in big
 * light lowercase. Swipe sideways (or tap a name) to move between pages; the
 * header slides so the page you're on is always the bright one at the left.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KineticApp(
    vm: PulseViewModel,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onRequestLocation: () -> Unit,
) {
    var saved by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    val pager = rememberPagerState(initialPage = saved) { Tab.entries.size }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager.currentPage) { saved = pager.currentPage }

    Scaffold(
        containerColor = Bg,
        topBar = { PivotHeader(pager.currentPage) { i -> scope.launch { pager.animateScrollToPage(i) } } },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            SosBanner(vm)
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                beyondViewportPageCount = 0,
                key = { it },
            ) { page ->
                Box(Modifier.fillMaxSize()) {
                    when (Tab.entries[page]) {
                        Tab.LIVE -> LiveScreen(vm, onRequestPermissions, onEnableBluetooth)
                        Tab.RIDE -> RideScreen(vm, onRequestLocation)
                        Tab.STATS -> StatsScreen(vm)
                        Tab.CREW -> CrewScreen(vm)
                        Tab.HISTORY -> HistoryScreen(vm)
                        Tab.DEVICE -> DeviceScreen(vm)
                    }
                }
            }
        }
    }
}

/** SOS from the Pulse: shown on every page until it's dealt with. */
@Composable
private fun SosBanner(vm: PulseViewModel) {
    val s by vm.sosUi.collectAsState()
    if (s.state == 0 && s.note == null) return
    val counting = s.state == 1
    val bg = when (s.state) { 1, 2 -> TileRed; 3 -> TileGreen; else -> TileBg }
    Column(
        Modifier.fillMaxWidth().background(bg)
            .clickable(enabled = s.state == 0 || s.state == 3) { vm.dismissSosNote() }
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(
            when (s.state) {
                1 -> (if (s.panic) "panic" else "crash detected") + " · SOS in ${s.secondsLeft}s"
                2 -> "SOS " + (if (s.panic) "· panic" else "· crash")
                3 -> "all clear"
                else -> "sos"
            },
            fontSize = 22.sp, fontWeight = FontWeight.Light, color = TextMain,
        )
        s.note?.let { Text(it, fontSize = 13.sp, color = TextMain.copy(alpha = .85f)) }
        if (s.state == 2) Text("Hold the front button on the Pulse when you're OK.", fontSize = 12.sp, color = TextMain.copy(alpha = .7f))
        if (!counting && s.state != 2) Text("tap to close", fontSize = 11.sp, color = TextMain.copy(alpha = .6f))
    }
}

@Composable
private fun PivotHeader(current: Int, onPick: (Int) -> Unit) {
    val scroll = rememberScrollState()
    val starts = remember { IntArray(Tab.entries.size) }
    // keep the current page name at the left edge, like a Metro pivot
    LaunchedEffect(current) { scroll.animateScrollTo(starts[current], tween(260)) }

    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 10.dp)) {
        Text(
            "KINETIC PULSE", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp,
            color = Orange, modifier = Modifier.padding(horizontal = 20.dp),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(scroll).padding(start = 18.dp, end = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Tab.entries.forEachIndexed { i, t ->
                val col by animateColorAsState(if (i == current) TextMain else Faint, tween(220), label = "pivot")
                Text(
                    t.label, fontSize = 40.sp, fontWeight = FontWeight.Light, color = col, maxLines = 1,
                    modifier = Modifier
                        .onGloballyPositioned { starts[i] = it.positionInParent().x.toInt().coerceAtLeast(0) }
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onPick(i) },
                )
            }
        }
        Spacer(Modifier.height(4.dp).width(1.dp))
    }
}
