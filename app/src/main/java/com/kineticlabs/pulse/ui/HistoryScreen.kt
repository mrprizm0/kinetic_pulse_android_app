package com.kineticlabs.pulse.ui

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kineticlabs.pulse.PulseViewModel
import com.kineticlabs.pulse.data.WheelieRecord
import com.kineticlabs.pulse.ble.TrickKind
import com.kineticlabs.pulse.ble.landingScore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun HistoryScreen(vm: PulseViewModel) {
    val history by vm.history.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()) }
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(20.dp))
        ScreenTitle("History") {
            if (history.isNotEmpty()) {
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Kinetic Pulse wheelies")
                        putExtra(Intent.EXTRA_TEXT, vm.historyCsv())
                    }
                    context.startActivity(Intent.createChooser(send, "Share wheelies (CSV)"))
                }) { Text("SHARE", style = LabelStyle.copy(color = TextMain)) }
                TextButton(onClick = { confirmClear = true }) { Text("CLEAR", style = LabelStyle.copy(color = Red)) }
            }
        }

        if (history.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Wheelies show up here while the app is connected to your Pulse. Demo wheelies aren't saved.",
                color = Muted,
            )
            return@Column
        }

        val wheelies = history.filter { it.isWheelie }
        val best = wheelies.maxOfOrNull { it.ms } ?: 0L
        val bestStoppie = history.filter { !it.isWheelie }.maxOfOrNull { it.ms } ?: -1L
        val nStoppies = history.size - wheelies.size
        Text(
            "${wheelies.size} wheelies · best ${fmtSecs(best)}" +
                (if (nStoppies > 0) " · $nStoppies stoppies" else "") +
                " · total ${fmtSecs(wheelies.sumOf { it.ms })}",
            color = Muted, fontSize = 13.sp,
        )
        Spacer(Modifier.height(14.dp))
        SectionLabel("Last ${minOf(30, history.size)}")
        Spacer(Modifier.height(6.dp))
        DurationChart(history.takeLast(30), best)
        Spacer(Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(history.asReversed(), key = { it.time }) { r ->
                HistoryRow(r, fmt.format(Date(r.time)), if (r.isWheelie) r.ms == best else r.ms == bestStoppie)
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("This deletes every wheelie saved on this phone. Stats on the Pulse are not affected.") },
            confirmButton = {
                TextButton(onClick = { vm.clearHistory(); confirmClear = false }) { Text("Delete", color = Red) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DurationChart(items: List<WheelieRecord>, allBest: Long) {
    val max = (items.maxOfOrNull { it.ms } ?: 1L).coerceAtLeast(1L)
    Canvas(
        Modifier.fillMaxWidth().height(96.dp).padding(vertical = 6.dp)
    ) {
        val n = items.size.coerceAtLeast(1)
        val slot = size.width / n
        val w = (slot * 0.38f).coerceAtLeast(2f)
        drawLine(Line, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
        items.forEachIndexed { i, r ->
            val h = size.height * (r.ms.toFloat() / max).coerceAtLeast(0.03f)
            drawRoundRect(
                color = when {
                    r.kind == TrickKind.STOPPIE -> Red
                    r.ms == allBest -> Gold
                    r.kind == TrickKind.CIRCLE -> Blue
                    else -> Faint
                },
                topLeft = Offset(i * slot + (slot - w) / 2, size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 3, w / 3),
            )
        }
    }
}

@Composable
private fun TrickChip(label: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        label, color = androidx.compose.ui.graphics.Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(5.dp)).background(color)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun HistoryRow(r: WheelieRecord, date: String, isBest: Boolean) {
    Row(
        Modifier.fillMaxWidth().rowDivider().padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (r.kind) {
                    TrickKind.STOPPIE -> TrickChip("STOPPIE", Red)
                    TrickKind.CIRCLE -> TrickChip(String.format("CIRCLE %.1fx", kotlin.math.abs(r.turns)), Gold)
                    else -> {}
                }
                Text(date, color = Muted, fontSize = 12.sp)
            }
            Text(
                if (r.kind == TrickKind.STOPPIE) "depth ${r.peak.roundToInt()}° · bal ${r.balance}"
                else "peak ${r.peak.roundToInt()}° · bal ${r.balance} · pop ${r.pop} · land ${landingScore(r.landingG)}",
                color = Muted, fontSize = 12.sp,
            )
        }
        Text(
            fmtSecs(r.ms),
            color = if (isBest) Gold else TextMain,
            style = numberStyle(26.sp, FontWeight.Light),
        )
    }
}
