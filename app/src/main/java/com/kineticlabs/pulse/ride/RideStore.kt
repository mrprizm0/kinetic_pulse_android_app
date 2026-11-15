package com.kineticlabs.pulse.ride

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A finished ride, as saved on the phone. */
data class SavedRide(
    val startedAt: Long,
    val durationMs: Long,
    val movingMs: Long,
    val distanceM: Double,
    val topSpeedMs: Float,
    val avgMovingMs: Float,
    val wheelies: Int,
    val stoppies: Int,
    val bestWheelieMs: Long,
    val path: List<Pair<Double, Double>>,  // thinned route for drawing
)

/** Rides are kept in one small JSON file in the app's private storage. */
class RideStore(context: Context) {
    private val file = File(context.filesDir, "rides.json")

    fun load(): List<SavedRide> = try {
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i -> parse(arr.getJSONObject(i)) }
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun saveAll(rides: List<SavedRide>) {
        val arr = JSONArray()
        rides.forEach { arr.put(toJson(it)) }
        val tmp = File(file.parentFile, "rides.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    fun clear() { file.delete() }

    companion object {
        /** Turns a finished ride into something worth saving (null if it's too short). */
        fun from(s: RideState): SavedRide? {
            val dur = s.elapsedMs()
            if (dur < 30_000 && s.distanceM < 50 && s.wheelies == 0) return null
            val step = maxOf(1, s.route.size / 400)
            return SavedRide(
                startedAt = s.startedAt,
                durationMs = dur,
                movingMs = s.movingMs,
                distanceM = s.distanceM,
                topSpeedMs = s.topSpeedMs,
                avgMovingMs = s.avgMovingMs,
                wheelies = s.wheelies,
                stoppies = s.stoppies,
                bestWheelieMs = s.bestWheelieMs,
                path = s.route.filterIndexed { i, _ -> i % step == 0 }.map { it.lat to it.lon },
            )
        }

        private fun toJson(r: SavedRide) = JSONObject().apply {
            put("start", r.startedAt); put("dur", r.durationMs); put("moving", r.movingMs)
            put("dist", r.distanceM); put("top", r.topSpeedMs.toDouble()); put("avg", r.avgMovingMs.toDouble())
            put("wheelies", r.wheelies); put("stoppies", r.stoppies); put("best", r.bestWheelieMs)
            put("path", JSONArray().apply { r.path.forEach { (a, b) -> put(JSONArray().put(a).put(b)) } })
        }

        private fun parse(o: JSONObject): SavedRide? = try {
            val p = o.optJSONArray("path") ?: JSONArray()
            SavedRide(
                startedAt = o.getLong("start"),
                durationMs = o.getLong("dur"),
                movingMs = o.optLong("moving"),
                distanceM = o.getDouble("dist"),
                topSpeedMs = o.optDouble("top").toFloat(),
                avgMovingMs = o.optDouble("avg").toFloat(),
                wheelies = o.optInt("wheelies"),
                stoppies = o.optInt("stoppies"),
                bestWheelieMs = o.optLong("best"),
                path = (0 until p.length()).map { i -> p.getJSONArray(i).let { it.getDouble(0) to it.getDouble(1) } },
            )
        } catch (_: Exception) { null }
    }
}
