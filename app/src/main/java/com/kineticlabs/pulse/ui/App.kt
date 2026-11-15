package com.kineticlabs.pulse.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.kineticlabs.pulse.PulseViewModel

enum class Tab(val label: String, val icon: ImageVector) {
    LIVE("Live", Icons.Filled.Home),
    RIDE("Ride", Icons.Filled.PlayArrow),
    STATS("Stats", Icons.Filled.Star),
    CREW("Crew", Icons.Filled.LocationOn),
    HISTORY("History", Icons.AutoMirrored.Filled.List),
    DEVICE("Device", Icons.Filled.Settings),
}

@Composable
fun KineticApp(
    vm: PulseViewModel,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onRequestLocation: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.LIVE) }

    Scaffold(
        containerColor = Bg,
        bottomBar = {
            Column {
                Hairline()
                NavigationBar(containerColor = Bg, tonalElevation = 0.dp) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label.uppercase(), fontSize = 9.sp, letterSpacing = 1.5.sp) },
                            alwaysShowLabel = false,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Orange,
                                indicatorColor = Color.Transparent,
                                selectedTextColor = Orange,
                                unselectedIconColor = Faint,
                                unselectedTextColor = Faint,
                            ),
                        )
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
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
