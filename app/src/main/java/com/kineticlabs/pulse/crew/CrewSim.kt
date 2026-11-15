package com.kineticlabs.pulse.crew

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.kineticlabs.pulse.ui.Go
import com.kineticlabs.pulse.ui.Orange
import com.kineticlabs.pulse.ui.Red
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * DEMO ONLY: a pretend group ride for previewing Kinetic Labs Crew.
 * Four fake riders follow a trail loop with you. Now and then one pops a
 * wheelie, one stops and falls behind (the "lost rider" alert), and you
 * can call a regroup so everyone rides to your position.
 *
 * World units are metres: x = east, y = north. Nothing here uses GPS.
 */

enum class RiderState { RIDING, STOPPED, CATCHING_UP, REGROUPING, ARRIVED }

data class Rider(
    val id: Int,
    val name: String,
    val color: Color,
    val isYou: Boolean,
    val s: Float,             // distance along the loop
    val pos: Offset,
    val heading: Float,       // degrees, 0 = north, clockwise
    val speed: Float,         // m/s
    val targetSpeed: Float,
    val state: RiderState,
    val stateUntil: Float,
    val battery: Int,
    val wheelieUntil: Float = 0f,
    val wheelieDur: Float = 0f,
    val wheelieDeg: Int = 0,
    val lastWheelie: String? = null,
) {
    val inWheelie: Boolean get() = wheelieUntil > 0f
}

data class FeedItem(val time: Float, val text: String, val color: Color)

class CrewSim {
    private val rnd = kotlin.random.Random(7)

    val track: List<Offset> = buildTrack()
    private val cum = FloatArray(track.size + 1)
    val trackLength: Float
    val sideTrails: List<Pair<Offset, Offset>>
    val parks = listOf(
        Offset(-160f, 130f) to 150f,
        Offset(270f, -230f) to 110f,
        Offset(-390f, -320f) to 95f,
        Offset(560f, 380f) to 130f,
    )
    val lake = Offset(70f, 30f) to 125f

    var time by mutableStateOf(0f)
        private set
    var riders by mutableStateOf(listOf<Rider>())
        private set
    var feed by mutableStateOf(listOf<FeedItem>())
        private set
    var meetPoint by mutableStateOf<Offset?>(null)
        private set
    var alert by mutableStateOf<String?>(null)
        private set

    private var regroupDoneAt = -1f
    private var nextStopAt = 20f
    private var nextWheelieAt = 5f
    private var lastAlertName: String? = null

    init {
        for (i in track.indices) {
            val a = track[i]
            val b = track[(i + 1) % track.size]
            cum[i + 1] = cum[i] + (b - a).getDistance()
        }
        trackLength = cum[track.size]
        sideTrails = listOf(20, 65, 110, 150).map { i ->
            val p = track[i % track.size]
            p to (p + p / p.getDistance() * 280f)
        }

        val names = listOf("You", "Jay", "Mia", "Leo", "Ava")
        val colors = listOf(
            Orange, Color(0xFF3B82F6), Color(0xFFF472B6), Color(0xFF22C55E), Color(0xFFA78BFA),
        )
        val starts = listOf(0f, -70f, -150f, 90f, -240f)
        riders = names.indices.map { i ->
            val s = wrap(starts[i])
            val (p, h) = pointAt(s)
            Rider(
                id = i, name = names[i], color = colors[i], isYou = i == 0,
                s = s, pos = p, heading = h, speed = 8f, targetSpeed = 8f,
                state = RiderState.RIDING, stateUntil = 0f, battery = 55 + rnd.nextInt(45),
            )
        }
        addFeed("Crew ride started · 5 riders", Orange)
    }

    // ------------------------------------------------------------ public

    fun regroup() {
        if (meetPoint != null) return
        meetPoint = riders[0].pos
        regroupDoneAt = -1f
        riders = riders.map { if (it.isYou) it else it.copy(state = RiderState.REGROUPING) }
        addFeed("You called a regroup", Orange)
    }

    fun cancelRegroup() {
        if (meetPoint == null) return
        endRegroup()
        addFeed("Regroup cancelled", Color(0xFF8B8B94))
    }

    /** Your real (or Pulse-demo) wheelies show up in the crew feed too. */
    fun addYourWheelie(seconds: Float, deg: Int) {
        val label = String.format(Locale.US, "%.1fs · %d°", seconds, deg)
        riders = riders.mapIndexed { i, r -> if (i == 0) r.copy(lastWheelie = label) else r }
        addFeed("You popped a $label wheelie", Orange)
    }

    /** Any other event of yours (stoppies, circles) for the crew feed. */
    fun addYourEvent(text: String) = addFeed(text, Orange)

    fun step(dt: Float) {
        time += dt
        val t = time
        var list = riders.toMutableList()
        val you = list[0]

        // a rider stops (flat tyre, phone call...) and starts falling behind
        if (meetPoint == null && t >= nextStopAt) {
            val idx = list.indices.filter { !list[it].isYou && list[it].state == RiderState.RIDING }
                .randomOrNull(rnd)
            if (idx != null) {
                list[idx] = list[idx].copy(state = RiderState.STOPPED, stateUntil = t + 18f + rnd.nextFloat() * 8f)
            }
            nextStopAt = t + 40f + rnd.nextFloat() * 20f
        }

        // someone pops a wheelie
        if (t >= nextWheelieAt) {
            val idx = list.indices.filter {
                !list[it].isYou && !list[it].inWheelie &&
                    (list[it].state == RiderState.RIDING || list[it].state == RiderState.CATCHING_UP)
            }.randomOrNull(rnd)
            if (idx != null) {
                val dur = 1.5f + rnd.nextFloat() * 6f
                list[idx] = list[idx].copy(
                    wheelieUntil = t + dur, wheelieDur = dur, wheelieDeg = 18 + rnd.nextInt(20),
                )
            }
            nextWheelieAt = t + 6f + rnd.nextFloat() * 8f
        }

        for (i in list.indices) list[i] = move(list[i], dt, you)

        // wheelies that just finished
        for (i in list.indices) {
            val r = list[i]
            if (r.inWheelie && t >= r.wheelieUntil) {
                val label = String.format(Locale.US, "%.1fs · %d°", r.wheelieDur, r.wheelieDeg)
                list[i] = r.copy(wheelieUntil = 0f, lastWheelie = label)
                addFeed("${r.name} popped a $label wheelie", r.color)
            }
        }

        // regroup finished?
        if (meetPoint != null) {
            if (regroupDoneAt < 0f && list.all { it.isYou || it.state == RiderState.ARRIVED }) {
                regroupDoneAt = t
                addFeed("Everyone's at the meeting point", Go)
            }
            if (regroupDoneAt >= 0f && t - regroupDoneAt > 6f) {
                riders = list
                endRegroup()
                list = riders.toMutableList()
            }
        }

        // lost-rider alert
        val me = list[0]
        val lost = list.filter { !it.isYou && it.state == RiderState.STOPPED }
            .map { it to (it.pos - me.pos).getDistance() }
            .filter { it.second > 250f }
            .maxByOrNull { it.second }
        alert = lost?.let { "${it.first.name} has stopped ${it.second.roundToInt()} m away" }
        if (lost != null && lastAlertName != lost.first.name) {
            addFeed("${lost.first.name} stopped and is falling behind", Red)
            lastAlertName = lost.first.name
        }
        if (lost == null) lastAlertName = null

        riders = list
    }

    // ----------------------------------------------------------- helpers

    private fun endRegroup() {
        meetPoint = null
        regroupDoneAt = -1f
        riders = riders.map {
            if (it.isYou) it.copy(state = RiderState.RIDING)
            else it.copy(state = RiderState.RIDING, s = nearestS(it.pos))
        }
    }

    private fun move(r: Rider, dt: Float, you: Rider): Rider {
        val t = time
        val mp = meetPoint
        if (mp != null) {
            if (r.isYou) return r.copy(speed = approach(r.speed, 0f, dt, 4f))  // you wait
            val toMp = mp - r.pos
            val d = toMp.getDistance()
            if (r.state == RiderState.ARRIVED || d < 12f) return r.copy(state = RiderState.ARRIVED, speed = 0f)
            val sp = approach(r.speed, 9f, dt, 3f)
            val dir = toMp / d
            return r.copy(
                pos = r.pos + dir * min(d, sp * dt),
                heading = headingOf(dir),
                speed = sp,
                state = RiderState.REGROUPING,
            )
        }

        var state = r.state
        var target = r.targetSpeed
        when (state) {
            RiderState.STOPPED -> {
                target = 0f
                if (t >= r.stateUntil) state = RiderState.CATCHING_UP
            }
            RiderState.CATCHING_UP -> {
                target = 13f
                if (gap(you, r) > -120f) state = RiderState.RIDING
            }
            else -> {}
        }
        if (state == RiderState.RIDING) {
            if (rnd.nextFloat() < dt * 0.3f) target = 6.5f + rnd.nextFloat() * 4.5f
            if (!r.isYou) {  // stay roughly with the group
                val g = gap(you, r)
                if (g < -220f) target = max(target, 11f)
                if (g > 180f) target = min(target, 6f)
            }
        }
        val sp = approach(r.speed, target, dt, if (state == RiderState.STOPPED) 5f else 2.5f)
        val s = wrap(r.s + sp * dt)
        val (p, h) = pointAt(s)
        return r.copy(s = s, pos = p, heading = h, speed = sp, targetSpeed = target, state = state)
    }

    /** Signed distance along the loop: + = r is ahead of you. */
    private fun gap(you: Rider, r: Rider): Float {
        var d = r.s - you.s
        if (d > trackLength / 2) d -= trackLength
        if (d < -trackLength / 2) d += trackLength
        return d
    }

    private fun approach(v: Float, target: Float, dt: Float, rate: Float): Float =
        v + (target - v).coerceIn(-rate * dt, rate * dt)

    private fun addFeed(text: String, color: Color) {
        feed = (listOf(FeedItem(time, text, color)) + feed).take(30)
    }

    private fun wrap(s: Float): Float = ((s % trackLength) + trackLength) % trackLength

    fun pointAt(sIn: Float): Pair<Offset, Float> {
        val s = wrap(sIn)
        var lo = 0
        var hi = track.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (cum[mid] <= s) lo = mid else hi = mid - 1
        }
        val a = track[lo]
        val b = track[(lo + 1) % track.size]
        val segLen = cum[lo + 1] - cum[lo]
        val f = if (segLen > 0f) (s - cum[lo]) / segLen else 0f
        return (a + (b - a) * f) to headingOf(b - a)
    }

    private fun nearestS(p: Offset): Float {
        var best = 0
        var bd = Float.MAX_VALUE
        track.forEachIndexed { i, q ->
            val d = (q - p).getDistanceSquared()
            if (d < bd) { bd = d; best = i }
        }
        return cum[best]
    }

    private fun headingOf(v: Offset): Float =
        Math.toDegrees(atan2(v.x.toDouble(), v.y.toDouble())).toFloat()

    private fun buildTrack(): List<Offset> {
        val n = 180
        return (0 until n).map { i ->
            val a = 2 * PI * i / n
            val r = 430 + 110 * sin(3 * a) + 55 * cos(5 * a + 1)
            Offset((r * cos(a)).toFloat(), (r * sin(a)).toFloat())
        }
    }
}
