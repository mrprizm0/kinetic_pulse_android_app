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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kineticlabs.pulse.PulseViewModel
import com.kineticlabs.pulse.ble.PulseBle
import com.kineticlabs.pulse.ble.PulseCommand

@Composable
fun DeviceScreen(vm: PulseViewModel) {
    val status by vm.ble.status.collectAsState()
    val name by vm.ble.deviceName.collectAsState()
    val live by vm.ble.live.collectAsState()
    val keepOn by vm.keepScreenOn.collectAsState()
    val voiceOn by vm.voiceOn.collectAsState()
    val goal by vm.goalSec.collectAsState()
    val connected = status == PulseBle.Status.CONNECTED || status == PulseBle.Status.DEMO
    val demo = status == PulseBle.Status.DEMO

    var confirmResetAll by remember { mutableStateOf(false) }
    var showCalHelp by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Device", fontSize = 28.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(16.dp))

        // status card
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardBg).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(if (connected) (if (demo) Gold else Blue) else Muted))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name ?: "Kinetic Pulse", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text(
                    when (status) {
                        PulseBle.Status.CONNECTED -> "Connected"
                        PulseBle.Status.DEMO -> "Demo mode (fake data)"
                        PulseBle.Status.CONNECTING -> "Connecting…"
                        PulseBle.Status.SCANNING -> "Searching…"
                        PulseBle.Status.BLUETOOTH_OFF -> "Bluetooth is off"
                        PulseBle.Status.NO_PERMISSION -> "Bluetooth permission needed"
                    },
                    color = Muted, fontSize = 13.sp,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                live?.battery?.let { Text("$it%", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
                live?.let {
                    Text(
                        if (it.calibrated) "Calibrated" else "Not calibrated",
                        color = if (it.calibrated) Go else Gold, fontSize = 12.sp,
                    )
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        SectionLabel("Pulse controls")
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            WideButton("Calibrate", enabled = connected && !demo, primary = true) {
                vm.ble.send(PulseCommand.CALIBRATE); showCalHelp = true
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { vm.ble.send(PulseCommand.SOUND_ON) }, enabled = connected && !demo,
                    modifier = Modifier.weight(1f),
                ) { Text("Sound on", color = TextMain) }
                OutlinedButton(
                    onClick = { vm.ble.send(PulseCommand.SOUND_OFF) }, enabled = connected && !demo,
                    modifier = Modifier.weight(1f),
                ) { Text("Sound off", color = TextMain) }
            }
            WideButton("Reset ride stats", enabled = connected) { vm.ble.send(PulseCommand.RESET_RIDE) }
            WideButton("Reset all-time stats", enabled = connected, danger = true) { confirmResetAll = true }
        }

        Spacer(Modifier.height(22.dp))
        SectionLabel("App")
        Spacer(Modifier.height(8.dp))
        ToggleRow("Keep screen on", "Handy when the phone is mounted on the bike", keepOn) {
            vm.setKeepScreenOn(it)
        }
        Spacer(Modifier.height(8.dp))
        ToggleRow("Voice callouts", "Counts seconds out loud and calls goals and records", voiceOn) {
            vm.setVoice(it)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardBg)
                .clickable { vm.cycleGoal() }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Wheelie goal", fontWeight = FontWeight.SemiBold)
                Text("Tap to change. Buzzes and calls out when you hit it", color = Muted, fontSize = 12.sp)
            }
            Text(
                if (goal == 0) "Off" else "${goal}s",
                color = Color.Black, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (goal == 0) Line else Orange)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        ToggleRow("Demo mode", "Fake wheelies, to try the app without a Pulse", demo) {
            vm.ble.setDemo(it)
        }
        Spacer(Modifier.height(10.dp))
        WideButton("Reconnect", enabled = !demo) { vm.ble.reconnect() }

        Spacer(Modifier.height(24.dp))
        Text("Kinetic Pulse for Android 1.1 · needs Pulse firmware V1.3+", color = Muted, fontSize = 12.sp)
    }

    if (confirmResetAll) {
        AlertDialog(
            onDismissRequest = { confirmResetAll = false },
            title = { Text("Reset all-time stats?") },
            text = { Text("This erases the all-time stats stored on the Pulse. It can't be undone. Your phone history stays.") },
            confirmButton = {
                TextButton(onClick = { vm.ble.send(PulseCommand.RESET_ALL_TIME); confirmResetAll = false }) {
                    Text("Reset", color = Red)
                }
            },
            dismissButton = { TextButton(onClick = { confirmResetAll = false }) { Text("Cancel") } },
        )
    }
    if (showCalHelp) {
        AlertDialog(
            onDismissRequest = { showCalHelp = false },
            title = { Text("Calibrating") },
            text = {
                Text(
                    "Follow the steps on the Pulse screen:\n\n" +
                        "1. Bike level, wheels down, hold still\n" +
                        "2. Lift the front wheel and hold it\n" +
                        "3. Put it back down"
                )
            },
            confirmButton = { TextButton(onClick = { showCalHelp = false }) { Text("OK") } },
        )
    }
}

@Composable
private fun WideButton(
    text: String,
    enabled: Boolean,
    primary: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    if (primary) {
        Button(
            onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.Black),
        ) { Text(text, fontWeight = FontWeight.SemiBold) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(text, color = if (danger) Red else TextMain)
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardBg).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 12.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Orange, checkedThumbColor = Color.Black),
        )
    }
}
