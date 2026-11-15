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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp)) {
        ScreenTitle("Device")
        Spacer(Modifier.height(28.dp))

        // status: name, state, and battery as a big thin number
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(name ?: "Kinetic Pulse", fontSize = 20.sp, fontWeight = FontWeight.Light)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (connected) (if (demo) Gold else Go) else Faint))
                    Spacer(Modifier.size(8.dp))
                    Text(
                        when (status) {
                            PulseBle.Status.CONNECTED -> if (live?.calibrated == false) "CONNECTED · NOT CALIBRATED" else "CONNECTED"
                            PulseBle.Status.DEMO -> "DEMO MODE"
                            PulseBle.Status.CONNECTING -> "CONNECTING"
                            PulseBle.Status.SCANNING -> "SEARCHING"
                            PulseBle.Status.BLUETOOTH_OFF -> "BLUETOOTH OFF"
                            PulseBle.Status.NO_PERMISSION -> "NEEDS PERMISSION"
                        },
                        style = LabelStyle,
                    )
                }
            }
            live?.battery?.let {
                Text("$it", style = numberStyle(44.sp))
                Text("%", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp, start = 2.dp))
            }
        }

        Spacer(Modifier.height(32.dp))
        SectionLabel("Pulse")
        ActionRow("Calibrate", enabled = connected && !demo, accent = true) {
            vm.ble.send(PulseCommand.CALIBRATE); showCalHelp = true
        }
        ActionRow("Sound on", enabled = connected && !demo) { vm.ble.send(PulseCommand.SOUND_ON) }
        ActionRow("Sound off", enabled = connected && !demo) { vm.ble.send(PulseCommand.SOUND_OFF) }
        ActionRow("Reset ride stats", enabled = connected) { vm.ble.send(PulseCommand.RESET_RIDE) }
        ActionRow("Reset all-time stats", enabled = connected, danger = true) { confirmResetAll = true }

        Spacer(Modifier.height(32.dp))
        SectionLabel("App")
        ToggleRow("Keep screen on", "For when the phone is mounted", keepOn) { vm.setKeepScreenOn(it) }
        ToggleRow("Voice callouts", "Seconds, goals and records out loud", voiceOn) { vm.setVoice(it) }
        Row(
            Modifier.fillMaxWidth().rowDivider().clickable { vm.cycleGoal() }.padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Wheelie goal", fontSize = 16.sp, fontWeight = FontWeight.Light)
                Text("Tap to change", color = Muted, fontSize = 12.sp)
            }
            Text(
                if (goal == 0) "OFF" else "${goal}s",
                style = numberStyle(22.sp, FontWeight.Light), color = if (goal == 0) Muted else Orange,
            )
        }
        ToggleRow("Demo mode", "Fake tricks, no Pulse needed", demo) { vm.ble.setDemo(it) }
        ActionRow("Reconnect", enabled = !demo) { vm.ble.reconnect() }

        Spacer(Modifier.height(32.dp))
        Text("KINETIC PULSE 1.4 · RIDE ON THE PULSE NEEDS FIRMWARE V2.0+", style = LabelStyle.copy(color = Faint, fontSize = 9.sp))
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

/** A tappable list row with a thin arrow. */
@Composable
private fun ActionRow(
    text: String,
    enabled: Boolean,
    accent: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val color = when {
        !enabled -> Faint
        danger -> Red
        accent -> Orange
        else -> TextMain
    }
    Row(
        Modifier.fillMaxWidth().rowDivider().clickable(enabled = enabled, onClick = onClick).padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = color, fontSize = 16.sp, fontWeight = FontWeight.Light, modifier = Modifier.weight(1f))
        Text("›", color = if (enabled) Muted else Faint, fontSize = 22.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().rowDivider().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Light)
            Text(subtitle, color = Muted, fontSize = 12.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Orange, checkedThumbColor = Color.Black,
                uncheckedTrackColor = Bg, uncheckedThumbColor = Faint, uncheckedBorderColor = Faint,
            ),
        )
    }
}
