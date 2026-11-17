package com.kineticlabs.pulse.ride

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/*
 * Route following (beta).
 *
 * A route is a list of points plus the turns along it. It comes either from
 * the public OSRM router (OpenStreetMap roads, "route here" on a spot) or from
 * one of your own saved rides. The phone does all the work: where you are on
 * the route, how far is left, what the next turn is. The Pulse only draws.
 */

/** Turn codes, as the Pulse knows them (see Ble.h, 000C ROUTE). */
object Turn {
    const val NONE = 0; const val STRAIGHT = 1
    const val SLIGHT_LEFT = 2; const val LEFT = 3; const val SHARP_LEFT = 4
    const val SLIGHT_RIGHT = 5; const val RIGHT = 6; const val SHARP_RIGHT = 7
    const val UTURN = 8; const val ARRIVE = 9

    fun words(t: Int): String = when (t) {
        STRAIGHT -> "straight on"; SLIGHT_LEFT -> "bear left"; LEFT -> "turn left"; SHARP_LEFT -> "sharp left"
        SLIGHT_RIGHT -> "bear right"; RIGHT -> "turn right"; SHARP_RIGHT -> "sharp right"
        UTURN -> "u-turn"; ARRIVE -> "arrive"; else -> "follow the route"
    }

    /** From a change of direction in degrees (+ = to the right). */
    fun fromAngle(d: Double): Int {
        val a = abs(d)
        return when {
            a < 25 -> NONE
            a < 55 -> if (d > 0) SLIGHT_RIGHT else SLIGHT_LEFT
            a < 125 -> if (d > 0) RIGHT else LEFT
            a < 160 -> if (d > 0) SHARP_RIGHT else SHARP_LEFT
            else -> UTURN
        }
    }
}

/** A turn at one of the route's points. */
data class Maneuver(val index: Int, val turn: Int)

data class NavRoute(
    val name: String,
    val points: List<Pair<Double, Double>>,  // lat, lon
    val maneuvers: List<Maneuver>,
    val fromRide: Boolean,                   // a saved ride (true) or the router (false)
) {
    // flat metres east / north of the first point
    private val lat0 = points.first().first
    private val lon0 = points.first().second
    private val kx = 111_320.0 * cos(Math.toRadians(lat0))
    private val ky = 110_540.0

    fun toXY(lat: Double, lon: Double) = (lon - lon0) * kx to (lat - lat0) * ky

    val xy: List<Pair<Double, Double>> = points.map { toXY(it.first, it.second) }
    val cum: DoubleArray = DoubleArray(xy.size).also { c ->
        for (i in 1 until xy.size) c[i] = c[i - 1] + hypot(xy[i].first - xy[i - 1].first, xy[i].second - xy[i - 1].second)
    }
    val lengthM: Double get() = cum.last()

    /** The route shrunk to <= [RoutePacket.MAX_POINTS] points for the Pulse, with each one's index here. */
    val forPulse: List<Int> by lazy { simplifyTo(xy, RoutePacket.MAX_POINTS) }
}

/** Where you are on the route right now. */
data class RouteProgress(
    val hasFix: Boolean,
    val offRoute: Boolean,
    val arrived: Boolean,
    val alongM: Double,
    val leftM: Double,
    val nextTurn: Int,
    val nextTurnM: Double,
    val x: Double, val y: Double,  // you, metres east / north of the start
    val headingDeg: Int,
    val pulseSeg: Int,             // segment of the Pulse's shrunk route you're on
    val index: Int,                // segment of the full route you're on
)

object RouteNav {
    private const val OFF_ROUTE_M = 45.0
    private const val ARRIVE_M = 30.0

    /**
     * Where you are on [r]. [hint] is the segment you were on last time, so the
     * search stays local (a route can cross itself).
     */
    fun progress(r: NavRoute, lat: Double, lon: Double, bearing: Float?, hint: Int): RouteProgress {
        val (px, py) = r.toXY(lat, lon)
        fun nearest(from: Int, to: Int): Triple<Int, Double, Double> {  // segment, distance, along
            var best = Triple(0, Double.MAX_VALUE, 0.0)
            for (i in from.coerceAtLeast(0) until to.coerceAtMost(r.xy.size - 1)) {
                val (ax, ay) = r.xy[i]; val (bx, by) = r.xy[i + 1]
                val dx = bx - ax; val dy = by - ay
                val len2 = dx * dx + dy * dy
                val t = if (len2 < 1e-6) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
                val d = hypot(px - (ax + t * dx), py - (ay + t * dy))
                if (d < best.second) best = Triple(i, d, r.cum[i] + t * hypot(dx, dy))
            }
            return best
        }
        var hit = nearest(hint - 5, hint + 80)
        if (hit.second > OFF_ROUTE_M) {
            // lost it: look everywhere. A loop passes the same spot at its start and
            // its end; of the parts you're actually on, take the one nearest to where
            // you were along the route (else you could "arrive" at the start).
            val was = r.cum[hint.coerceIn(0, r.cum.size - 1)]
            var best = nearest(0, r.xy.size)
            var bestGap = Double.MAX_VALUE
            for (i in 0 until r.xy.size - 1) {
                val h = nearest(i, i + 1)
                if (h.second > OFF_ROUTE_M) continue
                val gap = kotlin.math.abs(h.third - was)
                if (gap < bestGap) { bestGap = gap; best = h }
            }
            hit = best
        }
        val (seg, dist, along) = hit
        val left = (r.lengthM - along).coerceAtLeast(0.0)
        val off = dist > OFF_ROUTE_M
        val arrived = left < ARRIVE_M && dist < OFF_ROUTE_M

        val next = r.maneuvers.firstOrNull { r.cum[it.index.coerceIn(0, r.cum.size - 1)] > along + 5 }
        val nextM = next?.let { r.cum[it.index.coerceIn(0, r.cum.size - 1)] - along } ?: left

        // heading: the phone's own when moving, otherwise the route's direction here
        val heading = bearing?.toInt() ?: run {
            val (ax, ay) = r.xy[seg]; val (bx, by) = r.xy[(seg + 1).coerceAtMost(r.xy.size - 1)]
            ((Math.toDegrees(atan2(bx - ax, by - ay)) + 360) % 360).toInt()
        }
        val idx = r.forPulse
        var ps = 0
        while (ps + 1 < idx.size && idx[ps + 1] <= seg) ps++
        return RouteProgress(
            hasFix = true, offRoute = off, arrived = arrived, alongM = along, leftM = left,
            nextTurn = next?.turn ?: Turn.NONE, nextTurnM = nextM, x = px, y = py,
            headingDeg = heading, pulseSeg = ps, index = seg,
        )
    }

    /** One of your saved rides as a route. Turns come from the shape of the line. */
    fun fromRide(ride: SavedRide, name: String): NavRoute? {
        val pts = dedupe(ride.path)
        if (pts.size < 2) return null
        val probe = NavRoute(name, pts, emptyList(), true)
        return probe.copy(maneuvers = turnsFromShape(probe))
    }

    private fun dedupe(p: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>(p.size)
        for (q in p) if (out.isEmpty() || out.last() != q) out += q
        return out
    }

    /** Turns found by smoothing the line and looking for sharp changes of direction. */
    private fun turnsFromShape(r: NavRoute): List<Maneuver> {
        val keep = douglasPeucker(r.xy, 12.0)
        val out = mutableListOf<Maneuver>()
        for (k in 1 until keep.size - 1) {
            val a = r.xy[keep[k - 1]]; val b = r.xy[keep[k]]; val c = r.xy[keep[k + 1]]
            if (hypot(b.first - a.first, b.second - a.second) < 15 || hypot(c.first - b.first, c.second - b.second) < 15) continue
            val h1 = Math.toDegrees(atan2(b.first - a.first, b.second - a.second))
            val h2 = Math.toDegrees(atan2(c.first - b.first, c.second - b.second))
            var d = h2 - h1
            while (d > 180) d -= 360
            while (d < -180) d += 360
            val t = Turn.fromAngle(d)
            if (t != Turn.NONE) out += Maneuver(keep[k], t)
        }
        out += Maneuver(r.xy.size - 1, Turn.ARRIVE)
        return out
    }

    // ------------------------------------------------------------ router

    class RouteException(message: String) : Exception(message)

    /**
     * A road route from the public OSRM router (OpenStreetMap data). This sends
     * the start and end points to router.project-osrm.org; nothing else.
     */
    suspend fun fetchRoad(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, name: String): NavRoute =
        withContext(Dispatchers.IO) {
            val url = String.format(
                Locale.US,
                "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=false&steps=true&geometries=geojson",
                fromLon, fromLat, toLon, toLat,
            )
            val c = URL(url).openConnection() as HttpURLConnection
            val text = try {
                c.connectTimeout = 10_000
                c.readTimeout = 20_000
                c.setRequestProperty("User-Agent", "KineticPulse-Android/1.6 (route beta)")
                val code = c.responseCode
                if (code !in 200..299) throw RouteException("The router didn't answer (error $code). Try again in a minute.")
                c.inputStream.bufferedReader().use { it.readText() }
            } catch (e: RouteException) {
                throw e
            } catch (e: Exception) {
                throw RouteException("Couldn't reach the router. Check your connection.")
            } finally {
                c.disconnect()
            }
            val o = JSONObject(text)
            if (o.optString("code") != "Ok") throw RouteException("No road route found to there.")
            val steps = o.getJSONArray("routes").getJSONObject(0).getJSONArray("legs").getJSONObject(0).getJSONArray("steps")
            val pts = mutableListOf<Pair<Double, Double>>()
            val turns = mutableListOf<Maneuver>()
            for (i in 0 until steps.length()) {
                val st = steps.getJSONObject(i)
                val coords = st.getJSONObject("geometry").getJSONArray("coordinates")
                val startIndex = if (pts.isEmpty()) 0 else pts.size - 1
                for (k in 0 until coords.length()) {
                    val ll = coords.getJSONArray(k)
                    val p = ll.getDouble(1) to ll.getDouble(0)
                    if (pts.isEmpty() || pts.last() != p) pts += p
                }
                val m = st.getJSONObject("maneuver")
                val type = m.optString("type")
                val mod = m.optString("modifier")
                val turn = when {
                    type == "arrive" -> Turn.ARRIVE
                    type == "depart" -> Turn.NONE
                    (type == "new name" || type == "continue") && (mod == "straight" || mod.isEmpty()) -> Turn.NONE
                    else -> when (mod) {
                        "uturn" -> Turn.UTURN; "sharp right" -> Turn.SHARP_RIGHT; "right" -> Turn.RIGHT
                        "slight right" -> Turn.SLIGHT_RIGHT; "straight" -> Turn.STRAIGHT
                        "slight left" -> Turn.SLIGHT_LEFT; "left" -> Turn.LEFT; "sharp left" -> Turn.SHARP_LEFT
                        else -> Turn.NONE
                    }
                }
                if (turn != Turn.NONE) turns += Maneuver(if (turn == Turn.ARRIVE) pts.size - 1 else startIndex, turn)
            }
            if (pts.size < 2) throw RouteException("That's right where you are.")
            if (turns.none { it.turn == Turn.ARRIVE }) turns += Maneuver(pts.size - 1, Turn.ARRIVE)
            NavRoute(name, pts, turns, fromRide = false)
        }
}

// ------------------------------------------------------------ simplify

/** Indexes of the points to keep so the line has at most [max] points. */
internal fun simplifyTo(xy: List<Pair<Double, Double>>, max: Int): List<Int> {
    if (xy.size <= max) return xy.indices.toList()
    var eps = 2.0
    while (true) {
        val keep = douglasPeucker(xy, eps)
        if (keep.size <= max) return keep
        eps *= 1.5
    }
}

internal fun douglasPeucker(xy: List<Pair<Double, Double>>, eps: Double): List<Int> {
    if (xy.size < 3) return xy.indices.toList()
    val keep = BooleanArray(xy.size)
    keep[0] = true; keep[xy.size - 1] = true
    val stack = ArrayDeque<Pair<Int, Int>>()
    stack.addLast(0 to xy.size - 1)
    while (stack.isNotEmpty()) {
        val (a, b) = stack.removeLast()
        if (b - a < 2) continue
        val (ax, ay) = xy[a]; val (bx, by) = xy[b]
        val dx = bx - ax; val dy = by - ay
        val len = hypot(dx, dy)
        var far = -1; var farD = 0.0
        for (i in a + 1 until b) {
            val (px, py) = xy[i]
            val d = if (len < 1e-6) hypot(px - ax, py - ay) else abs(dx * (ay - py) - (ax - px) * dy) / len
            if (d > farD) { farD = d; far = i }
        }
        if (far >= 0 && farD > eps) {
            keep[far] = true
            stack.addLast(a to far); stack.addLast(far to b)
        }
    }
    return keep.indices.filter { keep[it] }
}

// ------------------------------------------------------------ Bluetooth packets

/** 000C ROUTE packets (see Ble.h). */
object RoutePacket {
    const val MAX_POINTS = 120
    private const val PER_CHUNK = 40  // 3 + 160 bytes: fits the 185-byte MTU

    private fun buf(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)

    fun clear(): ByteArray = byteArrayOf(0)

    /** Metres per unit, so every point fits in a signed 16-bit number. */
    fun unitFor(r: NavRoute): Int {
        val maxAbs = r.forPulse.maxOf { maxOf(abs(r.xy[it].first), abs(r.xy[it].second)) }
        return ceil((maxAbs + 1) / 32_000.0).toInt().coerceIn(1, 255)
    }

    /** Header + points, in the order they should be written. */
    fun load(r: NavRoute): List<ByteArray> {
        val idx = r.forPulse
        val unit = unitFor(r)
        val out = mutableListOf<ByteArray>()
        val h = buf(23)
        h.put(1); h.put(idx.size.toByte()); h.put(unit.toByte())
        h.putInt(r.lengthM.roundToInt())
        val name = ByteArray(16)
        r.name.map { if (it.code in 32..126) it else '?' }.joinToString("").take(16)
            .toByteArray(Charsets.US_ASCII).copyInto(name)
        h.put(name)
        out += h.array()
        var i = 0
        while (i < idx.size) {
            val n = minOf(PER_CHUNK, idx.size - i)
            val b = buf(3 + 4 * n)
            b.put(2); b.put(i.toByte()); b.put(n.toByte())
            for (k in i until i + n) {
                val (x, y) = r.xy[idx[k]]
                b.putShort(clamp16(x / unit)); b.putShort(clamp16(y / unit))
            }
            out += b.array()
            i += n
        }
        return out
    }

    fun progress(r: NavRoute, p: RouteProgress): ByteArray {
        val unit = unitFor(r)
        val b = buf(16)
        b.put(3)
        b.put(((if (p.hasFix) 1 else 0) or (if (p.offRoute) 2 else 0) or (if (p.arrived) 4 else 0)).toByte())
        b.putShort(clamp16(p.x / unit)); b.putShort(clamp16(p.y / unit))
        b.putShort((((p.headingDeg % 360) + 360) % 360).toShort())
        b.putInt(p.leftM.roundToInt().coerceAtLeast(0))
        b.put(p.pulseSeg.coerceIn(0, 255).toByte())
        b.put(p.nextTurn.toByte())
        b.putShort(p.nextTurnM.roundToInt().coerceIn(0, 65535).toShort())
        return b.array()
    }

    private fun clamp16(v: Double): Short = v.roundToInt().coerceIn(-32768, 32767).toShort()
}
