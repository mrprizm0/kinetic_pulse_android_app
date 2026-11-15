package com.kineticlabs.pulse.spots

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A place to ride, from OpenStreetMap via the Kinetic Labs server. */
data class Spot(
    val name: String,
    val kind: String,       // "skatepark", "motocross track", "pump track", ...
    val motorsOk: Boolean,  // false = usually no motorbikes allowed: check the rules
    val lat: Double,
    val lon: Double,
    val distanceM: Int,
)

/** A group ride someone posted. */
data class Rideout(
    val id: Long,
    val title: String,
    val note: String,
    val lat: Double,
    val lon: Double,
    val startsAt: Long,
    val distanceM: Int,
)

class SpotsException(message: String) : Exception(message)

/** Talks to the Kinetic Labs website's API. */
object SpotsApi {
    const val BASE = "https://www.kineticlabs.workers.dev"

    suspend fun places(lat: Double, lon: Double): List<Spot> = withContext(Dispatchers.IO) {
        val o = get("$BASE/api/places?lat=$lat&lon=$lon")
        val arr = o.optJSONArray("places") ?: return@withContext emptyList()
        (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            Spot(
                name = p.optString("name"),
                kind = p.optString("kind"),
                motorsOk = p.optString("motors") == "yes",
                lat = p.getDouble("lat"),
                lon = p.getDouble("lon"),
                distanceM = p.optInt("distanceM"),
            )
        }
    }

    suspend fun rideouts(lat: Double, lon: Double): List<Rideout> = withContext(Dispatchers.IO) {
        val o = get("$BASE/api/rideouts?lat=$lat&lon=$lon")
        val arr = o.optJSONArray("rideouts") ?: return@withContext emptyList()
        (0 until arr.length()).map { i -> parseRideout(arr.getJSONObject(i)) }
    }

    suspend fun postRideout(title: String, note: String, lat: Double, lon: Double, startsAt: Long): Rideout =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("title", title).put("note", note)
                .put("lat", lat).put("lon", lon).put("startsAt", startsAt)
            val o = request("$BASE/api/rideouts", "POST", body.toString())
            parseRideout(o.getJSONObject("rideout").put("distanceM", 0))
        }

    private fun parseRideout(r: JSONObject) = Rideout(
        id = r.optLong("id"),
        title = r.optString("title"),
        note = r.optString("note"),
        lat = r.getDouble("lat"),
        lon = r.getDouble("lon"),
        startsAt = r.getLong("startsAt"),
        distanceM = r.optInt("distanceM"),
    )

    private fun get(url: String) = request(url, "GET", null)

    private fun request(url: String, method: String, body: String?): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = 40_000  // the map lookup can take a while the first time
            c.setRequestProperty("accept", "application/json")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("content-type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val o = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
            if (code !in 200..299) throw SpotsException(o.optString("error").ifBlank { "The server didn't answer (error $code)." })
            return o
        } catch (e: SpotsException) {
            throw e
        } catch (e: Exception) {
            throw SpotsException("Couldn't reach the server. Check your connection.")
        } finally {
            c.disconnect()
        }
    }
}
