package com.kineticlabs.pulse.ride

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.max

/** One GPS fix on the route. */
data class RoutePoint(val lat: Double, val lon: Double, val time: Long, val speed: Float)

/** Everything about the ride in progress (or the last one, once stopped). */
data class RideState(
    val active: Boolean = false,
    val startedAt: Long = 0L,
    val endedAt: Long = 0L,
    val distanceM: Double = 0.0,
    val movingMs: Long = 0L,
    val speedMs: Float = 0f,       // current speed, m/s
    val topSpeedMs: Float = 0f,
    val hasFix: Boolean = false,
    val route: List<RoutePoint> = emptyList(),
    val wheelies: Int = 0,
    val stoppies: Int = 0,
    val bestWheelieMs: Long = 0L,
    val totalWheelieMs: Long = 0L,
) {
    fun elapsedMs(now: Long = System.currentTimeMillis()): Long =
        if (startedAt == 0L) 0L else (if (active) now else endedAt) - startedAt
    /** average while actually moving, m/s */
    val avgMovingMs: Float get() = if (movingMs > 0) (distanceM / (movingMs / 1000.0)).toFloat() else 0f
}

/**
 * Builds up a ride from GPS fixes (fed by [RideService]) and tricks from the
 * Pulse. Lives as a singleton so the service, the screen and the Pulse link
 * all see the same ride.
 */
object RideRecorder {
    private val _state = MutableStateFlow(RideState())
    val state: StateFlow<RideState> = _state

    /** Latest location seen at all (ride or not), used for nearby spots. */
    private val _lastFix = MutableStateFlow<Location?>(null)
    val lastFix: StateFlow<Location?> = _lastFix

    private var last: Location? = null
    private const val MAX_ROUTE = 4000  // ~1 h at one fix a second; thinned beyond that

    fun begin() {
        last = null
        _state.value = RideState(active = true, startedAt = System.currentTimeMillis())
    }

    /** Stops the ride and returns it (or null if nothing was recorded). */
    fun end(): RideState? {
        val s = _state.value
        if (!s.active) return null
        val done = s.copy(active = false, endedAt = System.currentTimeMillis(), speedMs = 0f)
        _state.value = done
        return done
    }

    fun noteFix(loc: Location) {
        _lastFix.value = loc
    }

    fun onLocation(loc: Location) {
        _lastFix.value = loc
        val s = _state.value
        if (!s.active) return
        // ignore poor fixes; they make the distance jump around
        if (loc.hasAccuracy() && loc.accuracy > 30f) {
            _state.value = s.copy(hasFix = false)
            return
        }
        val prev = last
        var dist = s.distanceM
        var moving = s.movingMs
        var speed = if (loc.hasSpeed()) loc.speed else 0f
        if (prev != null) {
            val d = prev.distanceTo(loc).toDouble()
            val dtMs = (loc.time - prev.time).coerceAtLeast(1)
            val implied = (d / (dtMs / 1000.0)).toFloat()
            if (!loc.hasSpeed()) speed = implied
            // skip GPS jumps faster than ~250 km/h; ignore jitter while stopped
            if (implied < 70f && d >= 2.5) {
                dist += d
                if (speed > 1.5f) moving += dtMs
            }
        }
        last = loc
        val point = RoutePoint(loc.latitude, loc.longitude, loc.time, speed)
        val route = (s.route + point).let { if (it.size > MAX_ROUTE) it.filterIndexed { i, _ -> i % 2 == 0 } else it }
        _state.value = s.copy(
            distanceM = dist,
            movingMs = moving,
            speedMs = speed,
            topSpeedMs = max(s.topSpeedMs, if (speed < 70f) speed else 0f),
            hasFix = true,
            route = route,
        )
    }

    /** A trick from the Pulse while the ride is running. */
    fun addWheelie(ms: Long) {
        val s = _state.value
        if (!s.active) return
        _state.value = s.copy(
            wheelies = s.wheelies + 1,
            bestWheelieMs = max(s.bestWheelieMs, ms),
            totalWheelieMs = s.totalWheelieMs + ms,
        )
    }

    fun addStoppie() {
        val s = _state.value
        if (!s.active) return
        _state.value = s.copy(stoppies = s.stoppies + 1)
    }
}
