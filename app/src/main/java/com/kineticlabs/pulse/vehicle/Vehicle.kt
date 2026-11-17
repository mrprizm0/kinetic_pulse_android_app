package com.kineticlabs.pulse.vehicle

import android.content.Context
import kotlin.math.max

/**
 * What you ride. Scooters like the Kukirin G2 / G4 don't share their battery
 * level over Bluetooth, so the app ESTIMATES what's left: you say how full the
 * battery was when the ride started, and it takes off energy for every km,
 * more at higher speed. It's an estimate, not a reading.
 *
 * Specs are the makers' published figures (48 V 15 Ah = 720 Wh and so on).
 * Real-world range is usually ~60-70 % of the advertised range.
 */
data class VehicleModel(
    val id: String,
    val name: String,
    val scooter: Boolean,
    val batteryWh: Int,
    val topKmh: Int,
    val ratedKm: Int,
    val wheel: String,
)

object Vehicles {
    val models = listOf(
        VehicleModel("kukirin_g2", "Kukirin G2", true, 720, 45, 55, "10\" pneumatic"),
        VehicleModel("kukirin_g2_pro", "Kukirin G2 Pro", true, 750, 45, 58, "9\" tubeless"),
        VehicleModel("kukirin_g2_max", "Kukirin G2 Max", true, 1000, 55, 70, "10\" pneumatic"),
        VehicleModel("kukirin_g4", "Kukirin G4", true, 1200, 70, 75, "11\" tubeless"),
        VehicleModel("kukirin_g4_max", "Kukirin G4 Max", true, 2110, 85, 95, "12\" off-road"),
        VehicleModel("other_scooter", "Other e-scooter", true, 600, 35, 45, "—"),
        VehicleModel("emoto", "E-moto (Sur-Ron style)", false, 2000, 75, 100, "—"),
        VehicleModel("none", "Not set", false, 0, 0, 0, "—"),
    )

    fun byId(id: String?) = models.firstOrNull { it.id == id } ?: models.last()

    /** Energy used per km (Wh) at an average speed: about 30 % more than the
     *  maker's figure, rising above ~25 km/h as air drag takes over. */
    fun whPerKm(m: VehicleModel, avgKmh: Float): Float {
        if (m.batteryWh <= 0 || m.ratedKm <= 0) return 0f
        val base = m.batteryWh / (m.ratedKm * 0.7f)
        return base * (1f + max(0f, avgKmh - 25f) / 35f)
    }
}

/** Saved vehicle settings. */
class VehicleSettings(ctx: Context) {
    private val p = ctx.getSharedPreferences("vehicle", Context.MODE_PRIVATE)
    var modelId: String
        get() = p.getString("model", "none") ?: "none"
        set(v) = p.edit().putString("model", v).apply()
    /** Battery % when this ride started (you tap it in), -1 = not set. */
    var startBattery: Int
        get() = p.getInt("start_batt", -1)
        set(v) = p.edit().putInt("start_batt", v).apply()
    /** Speed alert in km/h, 0 = off. */
    var speedLimit: Int
        get() = p.getInt("limit", 0)
        set(v) = p.edit().putInt("limit", v).apply()
    /** Odometer (all saved rides, km) when you last checked the tyres / brakes. */
    var tyreCheckKm: Double
        get() = p.getFloat("tyre_km", 0f).toDouble()
        set(v) = p.edit().putFloat("tyre_km", v.toFloat()).apply()
    var brakeCheckKm: Double
        get() = p.getFloat("brake_km", 0f).toDouble()
        set(v) = p.edit().putFloat("brake_km", v.toFloat()).apply()

    companion object {
        val LIMITS = listOf(0, 20, 25, 32, 40, 45)
        const val TYRE_EVERY_KM = 150.0    // check tyre pressure
        const val BRAKE_EVERY_KM = 500.0   // check brakes / pads
    }
}

/** Battery estimate for the ride in progress. */
data class BatteryEstimate(val percent: Int, val rangeKm: Float)

fun estimateBattery(m: VehicleModel, startPct: Int, distanceKm: Double, avgKmh: Float): BatteryEstimate? {
    if (m.batteryWh <= 0 || startPct !in 1..100) return null
    val perKm = Vehicles.whPerKm(m, avgKmh)
    if (perKm <= 0f) return null
    val leftWh = (m.batteryWh * startPct / 100f - (distanceKm * perKm).toFloat()).coerceAtLeast(0f)
    // keep ~5 % back: scooters cut power before 0
    val usable = (leftWh - m.batteryWh * 0.05f).coerceAtLeast(0f)
    return BatteryEstimate((leftWh / m.batteryWh * 100).toInt().coerceIn(0, 100), usable / perKm)
}
