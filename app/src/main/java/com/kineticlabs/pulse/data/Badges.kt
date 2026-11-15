package com.kineticlabs.pulse.data

import com.kineticlabs.pulse.ble.TrickKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Badge(
    val title: String,
    val desc: String,
    val glyph: String,
    val progress: Float,   // 0..1
    val unlocked: Boolean,
    val progressText: String,
)

/** Achievements, worked out from every wheelie the app has seen. */
fun computeBadges(all: List<WheelieRecord>): List<Badge> {
    val recs = all.filter { it.isWheelie }  // wheelie badges ignore stoppies
    val stoppies = all.filter { it.kind == TrickKind.STOPPIE }
    val circles = all.filter { it.kind == TrickKind.CIRCLE }
    val bestStoppie = (stoppies.maxOfOrNull { it.ms } ?: 0L) / 1000f
    val mostTurns = circles.maxOfOrNull { kotlin.math.abs(it.turns) } ?: 0f
    val n = recs.size
    val longest = (recs.maxOfOrNull { it.ms } ?: 0L) / 1000f
    val peak = recs.maxOfOrNull { it.peak } ?: 0f
    val balance = recs.maxOfOrNull { it.balance } ?: 0
    val pop = recs.maxOfOrNull { it.pop } ?: 0
    val softest = recs.filter { it.landingG > 0f }.minOfOrNull { it.landingG }
    val totalMin = recs.sumOf { it.ms } / 60000f
    val day = SimpleDateFormat("yyyyMMdd", Locale.US)
    val bestDay = recs.groupBy { day.format(Date(it.time)) }.maxOfOrNull { it.value.size } ?: 0

    fun b(title: String, desc: String, glyph: String, value: Float, target: Float, text: String) =
        Badge(title, desc, glyph, (value / target).coerceIn(0f, 1f), value >= target, text)

    fun s(v: Float) = String.format(Locale.US, "%.1f", v)

    return listOf(
        b("First Lift", "Pop your first wheelie", "1", n.toFloat(), 1f, "$n/1"),
        b("5s Club", "Hold one for 5 seconds", "5s", longest, 5f, "${s(longest)}/5s"),
        b("10s Club", "Hold one for 10 seconds", "10s", longest, 10f, "${s(longest)}/10s"),
        b("Half Minute", "Hold one for 30 seconds", "30s", longest, 30f, "${s(longest)}/30s"),
        b("Sky High", "Reach 30° peak", "30°", peak, 30f, "${peak.toInt()}/30°"),
        b("Vertical", "Reach 45° peak", "45°", peak, 45f, "${peak.toInt()}/45°"),
        b("Steady Hands", "Balance score of 90+", "90", balance.toFloat(), 90f, "$balance/90"),
        b("Rocket", "Pop at 120°/s or faster", "⚡", pop.toFloat(), 120f, "$pop/120"),
        Badge(
            "Feather", "Land softer than 1.3 g", "~",
            progress = softest?.let { ((3f - it) / (3f - 1.3f)).coerceIn(0f, 1f) } ?: 0f,
            unlocked = softest != null && softest <= 1.3f,
            progressText = softest?.let { "${s(it)}g" } ?: "–",
        ),
        b("Grinder", "25 wheelies in one day", "25", bestDay.toFloat(), 25f, "$bestDay/25"),
        b("Century", "100 wheelies total", "100", n.toFloat(), 100f, "$n/100"),
        b("Air Time", "5 minutes total wheelie time", "5m", totalMin, 5f, "${s(totalMin)}/5m"),
        b("Nose Dive", "Your first stoppie", "ST", stoppies.size.toFloat(), 1f, "${stoppies.size}/1"),
        b("Hang Time", "Hold a 2 second stoppie", "2s", bestStoppie, 2f, "${s(bestStoppie)}/2s"),
        b("Full Circle", "Spin a wheelie circle", "O", circles.size.toFloat(), 1f, "${circles.size}/1"),
        b("Spin Cycle", "Two full turns on the back wheel", "2x", mostTurns, 2f, "${s(mostTurns)}/2x"),
    )
}
