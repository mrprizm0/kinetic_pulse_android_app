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
                Text((name ?: "Kinetic Pulse").lowercase(), fontSize = 28.sp, fontWeight = FontWeight.Light)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusSquare(if (connected) (if (demo) Gold else Go) else Faint)
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
        VehicleSection(vm)

        Spacer(Modifier.height(32.dp))
        SosSection(vm)

        Spacer(Modifier.height(32.dp))
        SectionLabel("App")
        ToggleRow("Keep screen on", "For when the phone is mounted", keepOn) { vm.setKeepScreenOn(it) }
        ToggleRow("Voice callouts", "Seconds, goals and records out loud", voiceOn) { vm.setVoice(it) }
        val alprOn by vm.cameraAlerts.collectAsState()
        ToggleRow(
            "Camera alerts",
            "Warns about Flock and other plate cameras ahead, from the DeFlock map. Works while a ride is recording.",
            alprOn,
        ) { vm.setCameraAlerts(it) }
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
        Text("kinetic pulse 1.6 · routes on the pulse need firmware V2.6+", color = Faint, fontSize = 12.sp)
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

/** What you ride: model (Kukirin G2 / G4 ...), speed alert, tyre + brake reminders. */
@Composable
private fun VehicleSection(vm: PulseViewModel) {
    val v by vm.vehicle.collectAsState()
    val limit by vm.speedLimit.collectAsState()
    val tyreAt by vm.tyreCheckKm.collectAsState()
    val brakeAt by vm.brakeCheckKm.collectAsState()
    val rides by vm.rides.collectAsState()
    val odo = remember(rides, tyreAt, brakeAt) { vm.odometerKm() }
    var picking by remember { mutableStateOf(false) }
    val vs = com.kineticlabs.pulse.vehicle.VehicleSettings

    SectionLabel("Vehicle")
    ActionRow(if (v.id == "none") "Choose your scooter or e-moto" else v.name, enabled = true, accent = v.id == "none") { picking = true }
    if (v.batteryWh > 0) {
        Text(
            "${v.batteryWh} Wh · up to ${v.topKmh} km/h · ${v.ratedKm} km rated · ${v.wheel} · on the Pulse, set Settings > mount to \"scooter\" for a stem mount",
            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
        )
    }
    Row(
        Modifier.fillMaxWidth().rowDivider().clickable { vm.cycleSpeedLimit() }.padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Speed alert", fontSize = 16.sp, fontWeight = FontWeight.Light)
            Text("Amber \"slow down\" on the Pulse when you go over. Tap to change.", color = Muted, fontSize = 12.sp)
        }
        Text(if (limit == 0) "OFF" else "$limit km/h", style = numberStyle(20.sp, FontWeight.Light),
            color = if (limit == 0) Muted else Orange)
    }
    // care reminders, by distance ridden (all saved rides)
    val tyreDue = odo - tyreAt >= vs.TYRE_EVERY_KM
    val brakeDue = odo - brakeAt >= vs.BRAKE_EVERY_KM
    Row(Modifier.fillMaxWidth().rowDivider().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Tyre pressure", fontSize = 16.sp, fontWeight = FontWeight.Light, color = if (tyreDue) Gold else TextMain)
            Text(String.format(java.util.Locale.US, "%.0f km since checked · every %.0f km", odo - tyreAt, vs.TYRE_EVERY_KM),
                color = Muted, fontSize = 12.sp)
        }
        Text("checked", color = Orange, fontSize = 13.sp, modifier = Modifier.clickable { vm.markTyresChecked() }.padding(8.dp))
    }
    Row(Modifier.fillMaxWidth().rowDivider().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Brakes", fontSize = 16.sp, fontWeight = FontWeight.Light, color = if (brakeDue) Gold else TextMain)
            Text(String.format(java.util.Locale.US, "%.0f km since checked · every %.0f km", odo - brakeAt, vs.BRAKE_EVERY_KM),
                color = Muted, fontSize = 12.sp)
        }
        Text("checked", color = Orange, fontSize = 13.sp, modifier = Modifier.clickable { vm.markBrakesChecked() }.padding(8.dp))
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("What do you ride?") },
            text = {
                Column {
                    com.kineticlabs.pulse.vehicle.Vehicles.models.forEach { m ->
                        Text(
                            m.name, fontSize = 17.sp, fontWeight = FontWeight.Light,
                            color = if (m.id == v.id) Orange else TextMain,
                            modifier = Modifier.fillMaxWidth().clickable { vm.setVehicle(m.id); picking = false }.padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Close") } },
        )
    }
}

/** Emergency SOS: who gets texted, your name, SMS permission and a test text. */
@Composable
private fun SosSection(vm: PulseViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val contacts by vm.sosContacts.collectAsState()
    val name by vm.riderName.collectAsState()
    var smsOk by remember { mutableStateOf(com.kineticlabs.pulse.sos.Sos.hasSmsPermission(ctx)) }
    val askSms = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> smsOk = granted }
    var adding by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    SectionLabel("Emergency SOS")
    Text(
        "Press the Pulse's front button 5 times fast for panic. Crash detection (beta) can be turned on " +
            "in the Pulse's settings. After a countdown you can cancel, your phone texts these contacts a map link.",
        color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
    )
    ActionRow(if (name.isBlank()) "Your name (for the text)" else "Your name: $name", enabled = true) { editingName = true }
    contacts.forEach { c ->
        Row(Modifier.fillMaxWidth().rowDivider().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.name, fontSize = 16.sp, fontWeight = FontWeight.Light)
                Text(c.number, color = Muted, fontSize = 12.sp)
            }
            Text("remove", color = Red, fontSize = 13.sp, modifier = Modifier.clickable { vm.removeSosContact(c) }.padding(8.dp))
        }
    }
    if (contacts.size < com.kineticlabs.pulse.sos.Sos.MAX_CONTACTS) {
        ActionRow("Add emergency contact", enabled = true, accent = true) {
            adding = true
            if (!smsOk) askSms.launch(android.Manifest.permission.SEND_SMS)
        }
    }
    if (!smsOk) {
        ActionRow("Allow SMS (needed to send SOS texts)", enabled = true, danger = true) {
            askSms.launch(android.Manifest.permission.SEND_SMS)
        }
    }
    ActionRow("Send a test text", enabled = contacts.isNotEmpty() && smsOk) {
        vm.sendSosTest { result = it }
    }
    result?.let { Text(it, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }

    if (adding) {
        var cn by remember { mutableStateOf("") }
        var num by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Emergency contact") },
            text = {
                Column {
                    androidx.compose.material3.OutlinedTextField(cn, { cn = it.take(30) }, label = { Text("Name") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        num, { num = it.filter { ch -> ch.isDigit() || ch == '+' || ch == ' ' }.take(20) },
                        label = { Text("Mobile number") }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = num.count { it.isDigit() } >= 6, onClick = { vm.addSosContact(cn, num); adding = false }) {
                    Text("Add", color = Orange)
                }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
    if (editingName) {
        var n by remember { mutableStateOf(name) }
        AlertDialog(
            onDismissRequest = { editingName = false },
            title = { Text("Your name") },
            text = { androidx.compose.material3.OutlinedTextField(n, { n = it.take(40) }, label = { Text("Shown in the SOS text") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.setRiderName(n); editingName = false }) { Text("Save", color = Orange) } },
            dismissButton = { TextButton(onClick = { editingName = false }) { Text("Cancel") } },
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
        MetroToggle(checked, onChange)
    }
}
