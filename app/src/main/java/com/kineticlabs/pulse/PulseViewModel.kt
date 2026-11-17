package com.kineticlabs.pulse

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kineticlabs.pulse.ble.LivePacket
import com.kineticlabs.pulse.ble.PulseBle
import com.kineticlabs.pulse.ble.TrickKind
import com.kineticlabs.pulse.ble.TrickPacket
import com.kineticlabs.pulse.ble.WheelieEvent
import com.kineticlabs.pulse.crew.CrewSim
import com.kineticlabs.pulse.data.HistoryStore
import com.kineticlabs.pulse.data.WheelieRecord
import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.location.LocationManagerCompat
import com.kineticlabs.pulse.ble.PlaceForPulse
import com.kineticlabs.pulse.ble.PlaceKind
import com.kineticlabs.pulse.ble.placesBytes
import com.kineticlabs.pulse.ble.rideInfoBytes
import com.kineticlabs.pulse.ride.RideRecorder
import com.kineticlabs.pulse.ride.RideService
import com.kineticlabs.pulse.ride.RideState
import com.kineticlabs.pulse.ride.RideStore
import com.kineticlabs.pulse.ride.SavedRide
import com.kineticlabs.pulse.ride.NavRoute
import com.kineticlabs.pulse.ride.RouteNav
import com.kineticlabs.pulse.ride.RoutePacket
import com.kineticlabs.pulse.ride.RouteProgress
import com.kineticlabs.pulse.ride.Turn
import com.kineticlabs.pulse.spots.Rideout
import com.kineticlabs.pulse.spots.Spot
import com.kineticlabs.pulse.spots.SpotsApi
import com.kineticlabs.pulse.spots.SpotsException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** What the Spots screen shows. */
data class SpotsUi(
    val loading: Boolean = false,
    val error: String? = null,
    val spots: List<Spot> = emptyList(),
    val rideouts: List<Rideout> = emptyList(),
    val from: Location? = null,
    val updatedAt: Long = 0L,
)

/** Route following (beta). */
data class RouteUi(
    val loading: Boolean = false,
    val error: String? = null,
    val route: NavRoute? = null,
    val progress: RouteProgress? = null,
)

/** SOS banner on the phone. state uses SosPacket's numbers (0 none, 1 counting, 2 sent, 3 all clear). */
data class SosUi(
    val state: Int = 0,
    val panic: Boolean = true,
    val secondsLeft: Int = 0,
    val note: String? = null,
)

/** Plate-camera alerts, for the app's own display. */
data class AlprUi(
    val alert: com.kineticlabs.pulse.alpr.AlprAlert? = null,
    val mapped: Int = 0,            // cameras on the map around you
    val waitingForGps: Boolean = true,
    val error: String? = null,
)

/** What the post-wheelie summary shows. */
data class Summary(
    val rec: WheelieRecord,
    val prevBestMs: Long,
    val number: Int,        // wheelie # this session
    val rank: Int,          // 1 = longest this session
    val sessionTotalMs: Long,
    val shownAt: Long,
)

val GOAL_OPTIONS = listOf(0, 3, 5, 8, 10, 15, 20, 30)  // seconds, 0 = off

class PulseViewModel(app: Application) : AndroidViewModel(app) {
    val ble = PulseBle(app)
    val crew = CrewSim()
    private val store = HistoryStore(app)
    private val prefs = app.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    private val _history = MutableStateFlow(store.load())
    val history: StateFlow<List<WheelieRecord>> = _history  // oldest first

    // demo wheelies aren't saved, but still count for badges while the app is open
    private val _demoRecords = MutableStateFlow<List<WheelieRecord>>(emptyList())
    val demoRecords: StateFlow<List<WheelieRecord>> = _demoRecords

    // wheelies since the app opened (demo ones too), for rank / session total
    private val session = mutableListOf<WheelieRecord>()

    private val _summary = MutableStateFlow<Summary?>(null)
    val summary: StateFlow<Summary?> = _summary

    // ---- ride tracking
    private val rideStore = RideStore(app)
    private val _rides = MutableStateFlow(rideStore.load())
    val rides: StateFlow<List<SavedRide>> = _rides  // oldest first
    val ride: StateFlow<RideState> = RideRecorder.state

    fun hasLocationPermission() = RideService.hasLocationPermission(getApplication())

    // Watched by the Ride screen, so it updates the moment you allow location
    // (a plain function call didn't trigger a redraw, so it kept asking).
    private val _locationOk = MutableStateFlow(hasLocationPermission())
    val locationOk: StateFlow<Boolean> = _locationOk
    fun refreshPermissions() { _locationOk.value = hasLocationPermission() }

    fun startRide() {
        if (ride.value.active) return
        RideService.start(getApplication())
        setStartBattery(-1)  // a new ride: you tap in how full the battery is
        say("Ride started")
    }

    fun stopRide() {
        val done = RideService.stop(getApplication()) ?: return
        RideStore.from(done)?.let { saved ->
            _rides.value = _rides.value + saved
            rideStore.saveAll(_rides.value)
        }
        pushRideToPulse()
        say("Ride saved")
    }

    fun deleteRide(r: SavedRide) {
        _rides.value = _rides.value - r
        rideStore.saveAll(_rides.value)
    }

    // ---- spots + rideouts (when connected to the internet)
    private val _spots = MutableStateFlow(SpotsUi())
    val spots: StateFlow<SpotsUi> = _spots

    fun refreshSpots() {
        if (_spots.value.loading) return
        _spots.value = _spots.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val here = currentLocation()
            if (here == null) {
                _spots.value = _spots.value.copy(loading = false, error = "Couldn't get your location. Turn on location and try again.")
                return@launch
            }
            try {
                val places = SpotsApi.places(here.latitude, here.longitude)
                val outs = try { SpotsApi.rideouts(here.latitude, here.longitude) } catch (_: SpotsException) { emptyList() }
                _spots.value = SpotsUi(spots = places, rideouts = outs, from = here, updatedAt = System.currentTimeMillis())
                pushPlacesToPulse()
            } catch (e: SpotsException) {
                _spots.value = _spots.value.copy(loading = false, error = e.message)
            }
        }
    }

    /** Posts a rideout at the rider's current spot. Calls back with an error message, or null on success. */
    fun postRideout(title: String, note: String, startsAt: Long, done: (String?) -> Unit) {
        viewModelScope.launch {
            val here = currentLocation()
            if (here == null) { done("Couldn't get your location for the meeting spot."); return@launch }
            try {
                SpotsApi.postRideout(title, note, here.latitude, here.longitude, startsAt)
                done(null)
                _spots.value = _spots.value.copy(loading = false)
                refreshSpots()
            } catch (e: SpotsException) {
                done(e.message)
            }
        }
    }

    @SuppressLint("MissingPermission")  // only called after the permission check
    private suspend fun currentLocation(): Location? {
        RideRecorder.lastFix.value?.let { if (System.currentTimeMillis() - it.time < 120_000) return it }
        if (!hasLocationPermission()) return null
        val lm = getApplication<Application>().getSystemService(LocationManager::class.java) ?: return null
        val fresh = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER
                               else LocationManager.NETWORK_PROVIDER
                try {
                    LocationManagerCompat.getCurrentLocation(lm, provider, cancel, androidx.core.content.ContextCompat.getMainExecutor(getApplication<Application>())) { loc ->
                        if (cont.isActive) cont.resume(loc)
                    }
                } catch (_: Exception) { if (cont.isActive) cont.resume(null) }
            }
        }
        val loc = fresh
            ?: try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (_: Exception) { null }
            ?: try { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (_: Exception) { null }
        loc?.let { RideRecorder.noteFix(it) }
        return loc
    }


    // ---- route following (beta)
    private val _route = MutableStateFlow(RouteUi())
    val route: StateFlow<RouteUi> = _route
    private var routeHint = 0
    private var lastTurnSaidAt = -1e9  // where along the route the last spoken turn is (m)

    /** A road route from where you are to a spot or rideout. */
    fun routeTo(lat: Double, lon: Double, name: String) {
        if (_route.value.loading) return
        _route.value = _route.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val here = currentLocation()
            if (here == null) {
                _route.value = _route.value.copy(loading = false, error = "Couldn't get your location. Turn on location and try again.")
                return@launch
            }
            try {
                startRoute(RouteNav.fetchRoad(here.latitude, here.longitude, lat, lon, name))
            } catch (e: RouteNav.RouteException) {
                _route.value = _route.value.copy(loading = false, error = e.message)
            } catch (e: Exception) {
                _route.value = _route.value.copy(loading = false, error = "Couldn't make a route to there.")
            }
        }
    }

    /** Ride one of your saved rides again, following its line. */
    fun followRide(r: SavedRide) {
        val fmt = java.text.SimpleDateFormat("d MMM", Locale.getDefault())
        val nav = RouteNav.fromRide(r, "ride " + fmt.format(java.util.Date(r.startedAt)))
        if (nav == null) { _route.value = RouteUi(error = "That ride has no route to follow."); return }
        startRoute(nav)
    }

    private fun startRoute(nav: NavRoute) {
        _route.value = RouteUi(route = nav)
        routeHint = 0
        lastTurnSaidAt = -1e9
        if (!ride.value.active && hasLocationPermission()) startRide()  // the ride's GPS drives the route
        pushRouteToPulse()
        say("Route started. " + fmtDistanceWords(nav.lengthM))
    }

    fun endRoute() {
        _route.value = RouteUi()
        if (ble.isConnected && !ble.isDemo && ble.supportsRoute) ble.sendRoute(listOf(RoutePacket.clear()))
    }

    fun dismissRouteError() { _route.value = _route.value.copy(error = null) }

    private fun pushRouteToPulse() {
        if (!ble.isConnected || ble.isDemo || !ble.supportsRoute) return
        val nav = _route.value.route ?: return
        ble.sendRoute(RoutePacket.load(nav))
    }

    private fun updateRouteProgress() {
        val nav = _route.value.route ?: return
        val fix = RideRecorder.lastFix.value ?: return
        if (System.currentTimeMillis() - fix.time > 10_000) return
        val bearing = if (fix.hasBearing() && fix.hasSpeed() && fix.speed > 1.5f) fix.bearing else null
        val p = RouteNav.progress(nav, fix.latitude, fix.longitude, bearing, routeHint)
        routeHint = p.index
        val was = _route.value.progress
        _route.value = _route.value.copy(progress = p)
        if (ble.isConnected && !ble.isDemo && ble.supportsRoute) ble.sendRouteProgress(RoutePacket.progress(nav, p))
        // voice: each turn once, ~120 m before it; off route / arrived once when they start
        // (the old key rounded the turn's position to whole metres, and rounding
        // jitter could make the same turn look new and get said every 2 s)
        val turnAt = p.alongM + p.nextTurnM
        if (p.nextTurn != 0 && p.nextTurnM < 120 && kotlin.math.abs(turnAt - lastTurnSaidAt) > 25 && !p.offRoute) {
            lastTurnSaidAt = turnAt
            say(if (p.nextTurn == 9) "Arriving" else Turn.words(p.nextTurn).replaceFirstChar { it.uppercase() })
        }
        if (p.offRoute && was?.offRoute != true) say("Off route")
        if (p.arrived && was?.arrived != true) { say("You've arrived"); vibrate(short = false, best = true) }
    }

    private fun fmtDistanceWords(m: Double): String =
        if (m < 1000) "${m.toInt()} metres" else String.format(Locale.US, "%.1f kilometres", m / 1000)

    // ---- send ride + spots to the Pulse
    private fun pushRideToPulse() {
        if (!ble.isConnected || ble.isDemo || !ble.supportsRide) return
        val r = ride.value
        val batt = if (r.active) batteryEstimate.value else null
        ble.sendRideInfo(
            rideInfoBytes(
                active = r.active, hasFix = r.hasFix, elapsedS = r.elapsedMs() / 1000, distanceM = r.distanceM.toLong(),
                speedKmh = r.speedMs * 3.6f, topKmh = r.topSpeedMs * 3.6f, wheelies = r.wheelies, bestMs = r.bestWheelieMs,
                batteryPct = batt?.percent, rangeKm = batt?.rangeKm,
            )
        )
    }

    // ---- vehicle: scooter / e-moto profile, battery estimate, speed alert, care reminders
    private val vehicleSettings = com.kineticlabs.pulse.vehicle.VehicleSettings(app)
    val vehicle = MutableStateFlow(com.kineticlabs.pulse.vehicle.Vehicles.byId(vehicleSettings.modelId))
    val startBattery = MutableStateFlow(vehicleSettings.startBattery)
    val speedLimit = MutableStateFlow(vehicleSettings.speedLimit)
    private val _batteryEstimate = MutableStateFlow<com.kineticlabs.pulse.vehicle.BatteryEstimate?>(null)
    val batteryEstimate: StateFlow<com.kineticlabs.pulse.vehicle.BatteryEstimate?> = _batteryEstimate
    val tyreCheckKm = MutableStateFlow(vehicleSettings.tyreCheckKm)
    val brakeCheckKm = MutableStateFlow(vehicleSettings.brakeCheckKm)
    private var overLimit = false

    fun setVehicle(id: String) {
        vehicleSettings.modelId = id
        vehicle.value = com.kineticlabs.pulse.vehicle.Vehicles.byId(id)
    }

    fun setStartBattery(pct: Int) {
        vehicleSettings.startBattery = pct
        startBattery.value = pct
        updateBattery()
    }

    fun cycleSpeedLimit() {
        val l = com.kineticlabs.pulse.vehicle.VehicleSettings.LIMITS
        val next = l[(l.indexOf(speedLimit.value) + 1) % l.size]
        vehicleSettings.speedLimit = next
        speedLimit.value = next
    }

    /** All saved rides' distance plus the one in progress, km. */
    fun odometerKm(): Double = (_rides.value.sumOf { it.distanceM } + (if (ride.value.active) ride.value.distanceM else 0.0)) / 1000

    fun markTyresChecked() { val k = odometerKm(); vehicleSettings.tyreCheckKm = k; tyreCheckKm.value = k }
    fun markBrakesChecked() { val k = odometerKm(); vehicleSettings.brakeCheckKm = k; brakeCheckKm.value = k }

    private fun updateBattery() {
        val r = ride.value
        val avgKmh = if (r.movingMs > 60_000) (r.distanceM / (r.movingMs / 1000.0) * 3.6).toFloat() else 25f
        _batteryEstimate.value = if (!r.active) null else
            com.kineticlabs.pulse.vehicle.estimateBattery(vehicle.value, startBattery.value, r.distanceM / 1000, avgKmh)
    }

    /** Every 2 s: over your speed limit? Amber "slow down" on the Pulse (unless a camera warning is up). */
    private fun updateSpeedAlert() {
        val limit = speedLimit.value
        val r = ride.value
        val kmh = (r.speedMs * 3.6f).toInt()
        val over = limit > 0 && r.active && r.hasFix && kmh > limit + 1
        val canSend = ble.isConnected && !ble.isDemo && ble.supportsAlert && !alprShowing
        if (over) {
            val isNew = !overLimit
            overLimit = true
            if (canSend) ble.sendAlert(speedAlertBytes(kmh, limit, isNew))
            if (isNew) { vibrate(short = true); say("Slow down, $kmh") }
        } else if (overLimit && kmh < limit - 1 || overLimit && limit == 0 || overLimit && !r.active) {
            overLimit = false
            if (canSend) ble.sendAlert(com.kineticlabs.pulse.alpr.AlprWatch.packet(null))
        }
    }

    /** 000D ALERT, kind 2: distance field = your km/h, "nearby" field = the limit. */
    private fun speedAlertBytes(kmh: Int, limit: Int, isNew: Boolean): ByteArray {
        val b = java.nio.ByteBuffer.allocate(26).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put(2); b.put((if (isNew) 1 else 0).toByte())
        b.putShort(kmh.coerceIn(0, 999).toShort()); b.putShort(0)
        b.put(limit.coerceIn(0, 255).toByte())
        val label = "SLOW DOWN".toByteArray(Charsets.US_ASCII).copyOf(19)
        b.put(label)
        return b.array()
    }

    private fun pushPlacesToPulse() {
        if (!ble.isConnected || ble.isDemo || !ble.supportsRide) return
        val s = _spots.value
        val from = RideRecorder.lastFix.value ?: s.from ?: return
        fun bearing(lat: Double, lon: Double): Int {
            val to = Location("").apply { latitude = lat; longitude = lon }
            return from.bearingTo(to).toInt()
        }
        fun dist(lat: Double, lon: Double): Int {
            val out = FloatArray(1)
            Location.distanceBetween(from.latitude, from.longitude, lat, lon, out)
            return out[0].toInt()
        }
        // the next rideout first, then the closest places that allow motorbikes, then the rest
        val list = mutableListOf<PlaceForPulse>()
        s.rideouts.firstOrNull()?.let { list += PlaceForPulse(PlaceKind.RIDEOUT, true, dist(it.lat, it.lon), bearing(it.lat, it.lon), it.title) }
        val sorted = s.spots.sortedWith(compareBy({ !it.motorsOk }, { it.distanceM }))
        sorted.take(4 - list.size).forEach {
            list += PlaceForPulse(PlaceKind.of(it.kind), it.motorsOk, dist(it.lat, it.lon), bearing(it.lat, it.lon), it.name)
        }
        ble.sendPlaces(placesBytes(list))
    }
    // ---- settings (saved)
    val keepScreenOn = MutableStateFlow(prefs.getBoolean("keep_on", true))
    val voiceOn = MutableStateFlow(prefs.getBoolean("voice", false))
    val goalSec = MutableStateFlow(prefs.getInt("goal", 5))

    fun setKeepScreenOn(v: Boolean) { keepScreenOn.value = v; prefs.edit().putBoolean("keep_on", v).apply() }
    fun setVoice(v: Boolean) {
        voiceOn.value = v
        prefs.edit().putBoolean("voice", v).apply()
        if (v) say("Voice callouts on")
    }
    // ---- plate-camera (ALPR / Flock) alerts, from the DeFlock / OpenStreetMap map
    val cameraAlerts = MutableStateFlow(prefs.getBoolean("alpr", false))
    private val _alpr = MutableStateFlow(AlprUi())
    val alpr: StateFlow<AlprUi> = _alpr
    private var alprShowing = false

    fun setCameraAlerts(v: Boolean) {
        cameraAlerts.value = v
        prefs.edit().putBoolean("alpr", v).apply()
        com.kineticlabs.pulse.alpr.AlprWatch.reset()
        if (!v) clearAlprOnPulse()
        _alpr.value = AlprUi()
    }

    private fun clearAlprOnPulse() {
        alprShowing = false
        if (ble.isConnected && !ble.isDemo && ble.supportsAlert) ble.sendAlert(com.kineticlabs.pulse.alpr.AlprWatch.packet(null))
    }

    /** Every 2 s: load the map around you when needed, warn about the nearest camera ahead. */
    private fun updateAlpr() {
        if (!cameraAlerts.value) return
        val w = com.kineticlabs.pulse.alpr.AlprWatch
        val fix = RideRecorder.lastFix.value
        if (fix == null || System.currentTimeMillis() - fix.time > 10_000) {
            if (alprShowing) clearAlprOnPulse()
            _alpr.value = _alpr.value.copy(alert = null, waitingForGps = true)
            return
        }
        if (w.needsFetch(fix)) viewModelScope.launch {
            try {
                w.fetch(fix)
                _alpr.value = _alpr.value.copy(error = null, mapped = w.cameras.size)
            } catch (e: com.kineticlabs.pulse.alpr.AlprWatch.AlprException) {
                _alpr.value = _alpr.value.copy(error = e.message)
            } catch (_: Exception) {
                _alpr.value = _alpr.value.copy(error = "Couldn't load the camera map.")
            }
        }
        val heading = if (fix.hasBearing() && fix.hasSpeed() && fix.speed > 2f) fix.bearing else null
        val a = w.check(fix, heading)
        _alpr.value = _alpr.value.copy(alert = a, waitingForGps = false, mapped = w.cameras.size)
        if (a != null) {
            alprShowing = true
            if (ble.isConnected && !ble.isDemo && ble.supportsAlert) ble.sendAlert(w.packet(a))
            if (a.isNew) {
                vibrate(short = false, best = true)
                val what = if (a.camera.maker.contains("flock", true)) "Flock camera" else "Plate camera"
                say("$what ahead, ${(a.distanceM / 10) * 10} metres")
            }
        } else if (alprShowing) {
            clearAlprOnPulse()
        }
    }

    // ---- SOS: the Pulse's panic button / crash detection texts your emergency contacts
    private val ctxApp get() = getApplication<Application>()
    val sosContacts = MutableStateFlow(com.kineticlabs.pulse.sos.Sos.contacts(app))
    val riderName = MutableStateFlow(com.kineticlabs.pulse.sos.Sos.riderName(app))
    private val _sosUi = MutableStateFlow(SosUi())
    val sosUi: StateFlow<SosUi> = _sosUi
    private var sosWarnedEvent = -1      // countdown already announced on the phone
    private var sosSending = -1          // event being texted right now

    fun addSosContact(name: String, number: String) {
        val c = com.kineticlabs.pulse.sos.Contact(name.trim().ifBlank { number.trim() }, number.trim())
        val list = (sosContacts.value + c).take(com.kineticlabs.pulse.sos.Sos.MAX_CONTACTS)
        com.kineticlabs.pulse.sos.Sos.saveContacts(ctxApp, list)
        sosContacts.value = com.kineticlabs.pulse.sos.Sos.contacts(ctxApp)
    }

    fun removeSosContact(c: com.kineticlabs.pulse.sos.Contact) {
        com.kineticlabs.pulse.sos.Sos.saveContacts(ctxApp, sosContacts.value - c)
        sosContacts.value = com.kineticlabs.pulse.sos.Sos.contacts(ctxApp)
    }

    fun setRiderName(n: String) {
        com.kineticlabs.pulse.sos.Sos.saveRiderName(ctxApp, n)
        riderName.value = com.kineticlabs.pulse.sos.Sos.riderName(ctxApp)
    }

    /** Texts every contact a test message. Calls back with what happened. */
    fun sendSosTest(done: (String) -> Unit) {
        val s = com.kineticlabs.pulse.sos.Sos
        if (sosContacts.value.isEmpty()) { done("Add a contact first."); return }
        try {
            val n = s.sendToAll(ctxApp, sosContacts.value, s.testText(riderName.value))
            done(if (n > 0) "Test text sent to $n contact${if (n == 1) "" else "s"}." else "Couldn't send. Check your phone has signal.")
        } catch (_: SecurityException) {
            done("Allow SMS for Kinetic Pulse first.")
        }
    }

    private fun onSos(p: com.kineticlabs.pulse.ble.SosPacket?) {
        if (p == null) return
        val s = com.kineticlabs.pulse.sos.Sos
        val P = com.kineticlabs.pulse.ble.SosPacket
        when (p.state) {
            P.COUNTING -> {
                _sosUi.value = SosUi(state = P.COUNTING, panic = p.isPanic, secondsLeft = p.secondsLeft,
                    note = "Press any button on the Pulse to cancel.")
                if (sosWarnedEvent != p.eventId) {
                    sosWarnedEvent = p.eventId
                    vibrate(short = false, best = true)
                    say("S O S in ${p.secondsLeft} seconds. Press any button on the Pulse to cancel.")
                }
            }
            P.SENT -> {
                val (last, at) = s.lastSentEvent(ctxApp)
                val already = last == p.eventId && System.currentTimeMillis() - at < 12 * 3_600_000L
                if (already || sosSending == p.eventId) {
                    if (_sosUi.value.state != P.SENT) _sosUi.value = SosUi(state = P.SENT, panic = p.isPanic, note = "SOS already sent.")
                    return
                }
                sosSending = p.eventId
                _sosUi.value = SosUi(state = P.SENT, panic = p.isPanic, note = "Sending SOS…")
                viewModelScope.launch {
                    val loc = try { currentLocation() } catch (_: Exception) { null }
                    val note = when {
                        sosContacts.value.isEmpty() -> "No emergency contacts set: nobody was texted. Add them on the Device page."
                        else -> try {
                            val n = s.sendToAll(ctxApp, sosContacts.value, s.sosText(riderName.value, p.isPanic, loc))
                            if (n > 0) { s.markSent(ctxApp, p.eventId); "SOS texted to $n contact${if (n == 1) "" else "s"}" + if (loc == null) " (no location)." else " with your location." }
                            else "Couldn't send the texts. Check your phone has signal."
                        } catch (_: SecurityException) {
                            "SMS isn't allowed for Kinetic Pulse, so nobody was texted. Allow it on the Device page."
                        }
                    }
                    sosSending = -1
                    _sosUi.value = SosUi(state = P.SENT, panic = p.isPanic, note = note)
                    vibrate(short = false, best = true)
                    say(if (note.startsWith("SOS texted")) "S O S sent" else "S O S could not be sent")
                }
            }
            P.ALL_CLEAR -> {
                val (last, _) = s.lastSentEvent(ctxApp)
                val prefs = ctxApp.getSharedPreferences("sos", Context.MODE_PRIVATE)
                if (last == p.eventId && prefs.getInt("clear_event", -1) != p.eventId) {
                    prefs.edit().putInt("clear_event", p.eventId).apply()
                    try { s.sendToAll(ctxApp, sosContacts.value, s.allClearText(riderName.value)) } catch (_: SecurityException) {}
                    _sosUi.value = SosUi(state = P.ALL_CLEAR, note = "All clear texted to your contacts.")
                } else {
                    _sosUi.value = SosUi()
                }
            }
            else -> if (_sosUi.value.state == P.COUNTING) _sosUi.value = SosUi(state = P.NONE, note = "SOS cancelled.")
        }
    }

    fun dismissSosNote() { _sosUi.value = SosUi() }

    fun cycleGoal() {
        val i = GOAL_OPTIONS.indexOf(goalSec.value)
        val next = GOAL_OPTIONS[(i + 1) % GOAL_OPTIONS.size]
        goalSec.value = next
        prefs.edit().putInt("goal", next).apply()
    }

    // ---- voice
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        tts = TextToSpeech(app) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.getDefault()
        }
        viewModelScope.launch { ble.wheelies.collect { onWheelie(it) } }
        viewModelScope.launch { ble.tricks.collect { onTrick(it) } }
        viewModelScope.launch { ble.live.collect { onLive(it) } }
        viewModelScope.launch { ble.sos.collect { onSos(it) } }
        // keep the Pulse's trip screen up to date (and resend spots now and then)
        viewModelScope.launch {
            var n = 0
            while (true) {
                updateBattery()
                pushRideToPulse()
                updateRouteProgress()
                updateAlpr()
                updateSpeedAlert()
                if (n++ % 15 == 0) pushPlacesToPulse()
                delay(2000)
            }
        }
        // a freshly connected Pulse gets everything straight away
        viewModelScope.launch {
            ble.status.collect { st ->
                if (st == PulseBle.Status.CONNECTED) { delay(1500); pushRideToPulse(); pushPlacesToPulse(); pushRouteToPulse() }
            }
        }
    }

    private fun say(text: String, flush: Boolean = true) {
        if (!voiceOn.value || !ttsReady) return
        tts?.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "k${System.nanoTime()}")
    }

    // ---- live callouts: seconds, goal, record
    private var wasInWheelie = false
    private var lastSecondSaid = 0L
    private var goalHit = false
    private var recordCalled = false
    private val _goalReached = MutableStateFlow(false)
    val goalReached: StateFlow<Boolean> = _goalReached

    private fun onLive(l: LivePacket?) {
        if (l == null) return
        if (l.inStoppie) {  // stoppies: just count the seconds
            val sec = l.wheelieMs / 1000
            if (!wasInWheelie) { wasInWheelie = true; lastSecondSaid = 0 }
            if (sec >= 1 && sec > lastSecondSaid) { lastSecondSaid = sec; say(sec.toString()) }
            return
        }
        if (!l.inWheelie) {
            wasInWheelie = false
            return
        }
        if (!wasInWheelie) {
            wasInWheelie = true
            lastSecondSaid = 0
            goalHit = false
            recordCalled = false
            _goalReached.value = false
        }
        val sec = l.wheelieMs / 1000
        val best = ble.allTime.value?.longestMs ?: 0L
        val goalMs = goalSec.value * 1000L
        when {
            goalMs > 0 && !goalHit && l.wheelieMs >= goalMs -> {
                goalHit = true
                _goalReached.value = true
                lastSecondSaid = sec
                vibrate(short = true)
                say("Goal!")
            }
            best > 0 && !recordCalled && l.wheelieMs > best -> {
                recordCalled = true
                lastSecondSaid = sec
                say("New record!")
            }
            sec >= 1 && sec > lastSecondSaid -> {
                lastSecondSaid = sec
                say(sec.toString())
            }
        }
    }

    private fun onWheelie(e: WheelieEvent) {
        val rec = WheelieRecord.from(e.packet)
        if (ble.isDemo) {
            _demoRecords.value = _demoRecords.value + rec
        } else {
            store.append(rec)
            _history.value = _history.value + rec
        }
        session += rec
        RideRecorder.addWheelie(rec.ms)
        showSummary(rec, e.prevBestMs)
        crew.addYourWheelie(rec.ms / 1000f, rec.peak.toInt())
        vibrate(short = false, best = e.packet.newBest)
        val secs = String.format(Locale.US, "%.1f", rec.ms / 1000f)
        say(if (e.packet.newBest) "New best! $secs seconds" else "$secs seconds")
    }

    /** Summary for a record, compared against others of the same kind this session. */
    private fun showSummary(rec: WheelieRecord, prevBestMs: Long) {
        val same = session.filter { it.isWheelie == rec.isWheelie }
        _summary.value = Summary(
            rec = rec,
            prevBestMs = prevBestMs,
            number = same.size,
            rank = 1 + same.count { it.ms > rec.ms },
            sessionTotalMs = same.sumOf { it.ms },
            shownAt = System.currentTimeMillis(),
        )
    }

    /** All tricks seen (saved + demo), for best-of comparisons. */
    private fun allRecords() = _history.value + _demoRecords.value

    private fun onTrick(t: TrickPacket) {
        when (t.kind) {
            TrickKind.STOPPIE -> {
                val prevBest = allRecords().filter { it.kind == TrickKind.STOPPIE }.maxOfOrNull { it.ms } ?: 0L
                val rec = WheelieRecord.fromStoppie(t).copy(newBest = t.ms > prevBest)
                if (ble.isDemo) {
                    _demoRecords.value = _demoRecords.value + rec
                } else {
                    store.append(rec)
                    _history.value = _history.value + rec
                }
                session += rec
                RideRecorder.addStoppie()
                showSummary(rec, prevBest)
                val secs = String.format(Locale.US, "%.1f", t.ms / 1000f)
                crew.addYourEvent("You held a ${secs}s stoppie · ${t.peak.toInt()}°")
                vibrate(short = false, best = rec.newBest)
                say(if (rec.newBest) "Best stoppie! $secs seconds" else "Stoppie, $secs seconds")
            }
            TrickKind.CIRCLE -> {
                // The wheelie itself already arrived; mark it as a circle.
                fun upgrade(list: List<WheelieRecord>): List<WheelieRecord>? {
                    val i = list.indexOfLast { it.isWheelie && kotlin.math.abs(it.ms - t.ms) <= 50 }
                    if (i < 0) return null
                    return list.toMutableList().also { it[i] = it[i].copy(kind = TrickKind.CIRCLE, turns = t.turns) }
                }
                if (ble.isDemo) {
                    upgrade(_demoRecords.value)?.let { _demoRecords.value = it }
                } else {
                    upgrade(_history.value)?.let { _history.value = it; store.saveAll(it) }
                }
                upgrade(session)?.let { session.clear(); session.addAll(it) }
                _summary.value?.let { s ->
                    if (kotlin.math.abs(s.rec.ms - t.ms) <= 50 && s.rec.isWheelie) {
                        _summary.value = s.copy(rec = s.rec.copy(kind = TrickKind.CIRCLE, turns = t.turns))
                    }
                }
                val turns = String.format(Locale.US, "%.1f", kotlin.math.abs(t.turns))
                crew.addYourEvent("You spun a ${turns}x wheelie circle")
                vibrate(short = false, best = true)
                say("Wheelie circle! $turns turns")
            }
            TrickKind.WHEELIE -> {}  // already handled by the WHEELIE message
        }
    }

    fun dismissSummary(shownAt: Long? = null) {
        val s = _summary.value ?: return
        if (shownAt == null || s.shownAt == shownAt) _summary.value = null
    }

    fun clearHistory() {
        store.clear()
        _history.value = emptyList()
    }

    /** All wheelies as CSV text, for sharing. */
    fun historyCsv(): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder("time,trick,duration_s,peak_deg,avg_deg,balance,pop_dps,landing_g,turns,new_best\n")
        _history.value.forEach { r ->
            sb.append(fmt.format(java.util.Date(r.time))).append(',')
                .append(r.kind.name.lowercase()).append(',')
                .append(String.format(Locale.US, "%.1f,%.1f,%.1f,%d,%d,%.2f,%.2f,%d\n",
                    r.ms / 1000f, r.peak, r.avg, r.balance, r.pop, r.landingG, r.turns,
                    if (r.newBest) 1 else 0))
        }
        return sb.toString()
    }

    private fun vibrate(short: Boolean, best: Boolean = false) {
        val ctx = getApplication<Application>()
        val vib: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            ctx.getSystemService(Vibrator::class.java)
        }
        val effect = when {
            short -> VibrationEffect.createWaveform(longArrayOf(0, 40, 60, 40), -1)
            best -> VibrationEffect.createWaveform(longArrayOf(0, 80, 70, 220), -1)
            else -> VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        try { vib?.vibrate(effect) } catch (_: Exception) {}
    }

    override fun onCleared() {
        ble.stop()
        tts?.shutdown()
    }
}
