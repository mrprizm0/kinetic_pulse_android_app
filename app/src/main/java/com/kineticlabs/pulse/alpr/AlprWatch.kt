package com.kineticlabs.pulse.alpr

import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Licence-plate camera (ALPR) alerts, e.g. Flock Safety cameras.
 *
 * Camera locations come from OpenStreetMap, where the DeFlock project
 * (deflock.me) and other volunteers map them as
 *   man_made=surveillance + surveillance:type=ALPR (+ manufacturer=...)
 * We ask the public Overpass API for the ones within ~15 km, then, every
 * couple of seconds while you ride, pick the nearest one AHEAD of you.
 * Map data (c) OpenStreetMap contributors, ODbL.
 */

data class AlprCamera(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val maker: String,        // "Flock Safety", or "" if not tagged
    val facing: Int?,         // which way the camera looks (deg), if mapped
)

/** The camera to warn about right now. */
data class AlprAlert(
    val camera: AlprCamera,
    val distanceM: Int,
    val relBearing: Int,      // where it is relative to your heading, -180..180 (+ = right)
    val nearbyCount: Int,     // cameras within 1 km
    val isNew: Boolean,       // first time this camera came into range: beep
)

object AlprWatch {
    private const val FETCH_RADIUS_M = 15_000
    private const val REFETCH_MOVED_M = 7_000f
    private const val REFETCH_AGE_MS = 30 * 60_000L
    const val WARN_AHEAD_M = 500        // cameras ahead of you, within this
    private const val WARN_CLOSE_M = 150 // any direction, this close
    private const val AHEAD_DEG = 55

    @Volatile var cameras: List<AlprCamera> = emptyList(); private set
    private var fetchedAt = 0L
    private var fetchedFrom: Location? = null
    private var fetching = false
    private val warned = HashSet<Long>()   // cameras already beeped for (until you're well clear)

    class AlprException(message: String) : Exception(message)

    private var lastAttempt = 0L

    fun needsFetch(here: Location): Boolean {
        if (fetching) return false
        if (System.currentTimeMillis() - lastAttempt < 60_000) return false  // at most once a minute (be kind to Overpass)
        val from = fetchedFrom ?: return true
        return System.currentTimeMillis() - fetchedAt > REFETCH_AGE_MS || from.distanceTo(here) > REFETCH_MOVED_M
    }

    /** Loads the cameras around [here] from OpenStreetMap (Overpass). */
    suspend fun fetch(here: Location) = withContext(Dispatchers.IO) {
        if (fetching) return@withContext
        fetching = true
        lastAttempt = System.currentTimeMillis()
        try {
            val q = "[out:json][timeout:25];" +
                "node[\"surveillance:type\"=\"ALPR\"](around:$FETCH_RADIUS_M,${here.latitude},${here.longitude});" +
                "out body;"
            val c = URL("https://overpass-api.de/api/interpreter").openConnection() as HttpURLConnection
            val text = try {
                c.requestMethod = "POST"
                c.connectTimeout = 15_000
                c.readTimeout = 40_000
                c.doOutput = true
                c.setRequestProperty("User-Agent", "KineticPulse-Android/1.6 (ALPR alerts)")
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
                if (c.responseCode !in 200..299) throw AlprException("The camera map didn't answer (error ${c.responseCode}).")
                c.inputStream.bufferedReader().use { it.readText() }
            } catch (e: AlprException) { throw e } catch (e: Exception) {
                throw AlprException("Couldn't reach the camera map. Check your connection.")
            } finally { c.disconnect() }
            val els = try { JSONObject(text).optJSONArray("elements") } catch (_: Exception) { null }
                ?: throw AlprException("The camera map sent something unexpected.")
            val list = ArrayList<AlprCamera>(els.length())
            for (i in 0 until els.length()) {
                val e = els.optJSONObject(i) ?: continue
                if (!e.has("lat") || !e.has("lon")) continue
                val tags = e.optJSONObject("tags")
                val maker = tags?.optString("manufacturer").orEmpty()
                    .ifBlank { tags?.optString("brand").orEmpty() }
                val dir = (tags?.optString("camera:direction").orEmpty().ifBlank { tags?.optString("direction").orEmpty() })
                    .substringBefore(';').trim().toDoubleOrNull()?.roundToInt()
                list += AlprCamera(e.optLong("id"), e.getDouble("lat"), e.getDouble("lon"), maker, dir)
            }
            cameras = list
            fetchedAt = System.currentTimeMillis()
            fetchedFrom = Location(here)
        } finally {
            fetching = false
        }
    }

    /**
     * The camera to warn about from [here], or null. [heading] is your travel
     * direction (null when stopped / unknown: then only very close ones count).
     */
    fun check(here: Location, heading: Float?): AlprAlert? {
        val cams = cameras
        if (cams.isEmpty()) return null
        val out = FloatArray(2)
        var best: AlprAlert? = null
        var nearby = 0
        for (cam in cams) {
            Location.distanceBetween(here.latitude, here.longitude, cam.lat, cam.lon, out)
            val d = out[0]
            if (d < 1000) nearby++
            if (d > 900) warned.remove(cam.id)  // well clear: it can warn again next time
            var rel = 0
            if (heading != null) {
                rel = (((out[1] - heading) % 360 + 540) % 360 - 180).roundToInt()
            }
            val ahead = heading != null && d <= WARN_AHEAD_M && abs(rel) <= AHEAD_DEG
            val close = d <= WARN_CLOSE_M
            if (!ahead && !close) continue
            if (best == null || d < best.distanceM) best = AlprAlert(cam, d.roundToInt(), rel, 0, false)
        }
        val b = best ?: return null
        val isNew = warned.add(b.camera.id)
        return b.copy(nearbyCount = nearby, isNew = isNew)
    }

    fun reset() { warned.clear() }

    /**
     * 000D ALERT packet for the Pulse (see Ble.h):
     *   u8 kind (0 clear, 1 camera), u8 flags (bit0 new = beep), u16 distance m,
     *   i16 bearing relative to your heading (deg), u8 cameras within 1 km, char[19] label
     */
    fun packet(a: AlprAlert?): ByteArray {
        val b = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN)
        if (a == null) { b.put(0); return b.array() }
        b.put(1)
        b.put((if (a.isNew) 1 else 0).toByte())
        b.putShort(a.distanceM.coerceIn(0, 65535).toShort())
        b.putShort(a.relBearing.coerceIn(-180, 180).toShort())
        b.put(a.nearbyCount.coerceIn(0, 255).toByte())
        val label = (if (a.camera.maker.contains("flock", true)) "FLOCK CAMERA"
                     else if (a.camera.maker.isNotBlank()) a.camera.maker.uppercase() else "PLATE CAMERA")
            .map { if (it.code in 32..126) it else '?' }.joinToString("").take(19)
        val bytes = ByteArray(19); label.toByteArray(Charsets.US_ASCII).copyInto(bytes)
        b.put(bytes)
        return b.array()
    }
}
