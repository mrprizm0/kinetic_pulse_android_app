package com.kineticlabs.pulse.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** A finished wheelie, plus the all-time best from *before* it (for "vs best"). */
data class WheelieEvent(val packet: WheeliePacket, val prevBestMs: Long)

/**
 * Finds the Pulse, connects, turns on notifications, and keeps reconnecting.
 * Android only allows one Bluetooth GATT operation at a time, so every read,
 * write and notification setup goes through a small queue.
 * Also has a demo mode that fakes wheelies, for trying the app without a Pulse.
 */
@SuppressLint("MissingPermission")  // permissions are checked in start()
class PulseBle(context: Context) {

    enum class Status { NO_PERMISSION, BLUETOOTH_OFF, SCANNING, CONNECTING, CONNECTED, DEMO }

    companion object {
        val requiredPermissions: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val manager = ctx.getSystemService(BluetoothManager::class.java)

    private val _status = MutableStateFlow(Status.SCANNING)
    val status: StateFlow<Status> = _status
    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName
    private val _live = MutableStateFlow<LivePacket?>(null)
    val live: StateFlow<LivePacket?> = _live
    private val _ride = MutableStateFlow<StatsPacket?>(null)
    val ride: StateFlow<StatsPacket?> = _ride
    private val _allTime = MutableStateFlow<StatsPacket?>(null)
    val allTime: StateFlow<StatsPacket?> = _allTime
    private val _rideExtra = MutableStateFlow<StatsExtraPacket?>(null)
    val rideExtra: StateFlow<StatsExtraPacket?> = _rideExtra
    private val _allTimeExtra = MutableStateFlow<StatsExtraPacket?>(null)
    val allTimeExtra: StateFlow<StatsExtraPacket?> = _allTimeExtra
    private val _wheelies = MutableSharedFlow<WheelieEvent>(extraBufferCapacity = 16)
    val wheelies: SharedFlow<WheelieEvent> = _wheelies
    private val _tricks = MutableSharedFlow<TrickPacket>(extraBufferCapacity = 16)
    val tricks: SharedFlow<TrickPacket> = _tricks

    val isDemo: Boolean get() = _status.value == Status.DEMO
    val isConnected: Boolean get() = _status.value == Status.CONNECTED || isDemo

    private var gatt: BluetoothGatt? = null
    private var commandChar: BluetoothGattCharacteristic? = null
    private var rideInfoChar: BluetoothGattCharacteristic? = null
    private var placesChar: BluetoothGattCharacteristic? = null
    private var routeChar: BluetoothGattCharacteristic? = null
    private var routeProgressQueued = false
    private var rideInfoQueued = false
    private var placesQueued = false
    private var scanning = false
    private var wanted = false
    private val ops = ArrayDeque<(BluetoothGatt) -> Boolean>()
    private var opBusy = false

    // ---------------------------------------------------------------- public

    fun hasPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    fun start() {
        wanted = true
        if (isDemo) return
        if (!hasPermissions()) { _status.value = Status.NO_PERMISSION; return }
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) { _status.value = Status.BLUETOOTH_OFF; return }
        if (gatt != null) return
        startScan()
    }

    fun stop() {
        wanted = false
        stopScan()
        disconnect()
        demoJob?.cancel()
    }

    fun reconnect() {
        disconnect()
        clearValues()
        start()
    }

    /** Ride overview for the Pulse's trip screen. Skipped if the last one hasn't gone out yet. */
    fun sendRideInfo(bytes: ByteArray) {
        val c = rideInfoChar ?: return
        if (rideInfoQueued) return
        rideInfoQueued = true
        enqueue { g -> rideInfoQueued = false; writeChar(g, c, bytes) }
    }

    /** Nearby spots for the Pulse's spots screen. */
    fun sendPlaces(bytes: ByteArray) {
        val c = placesChar ?: return
        if (placesQueued) return
        placesQueued = true
        enqueue { g -> placesQueued = false; writeChar(g, c, bytes) }
    }

    /** A whole route (header + points). Replaces any route still waiting to go out. */
    fun sendRoute(packets: List<ByteArray>) {
        val c = routeChar ?: return
        routeGen++
        val gen = routeGen
        for (p in packets) enqueue { g -> if (gen != routeGen) false else writeChar(g, c, p) }
    }

    /** Where you are on the route. Skipped if the last one hasn't gone out yet. */
    fun sendRouteProgress(bytes: ByteArray) {
        val c = routeChar ?: return
        if (routeProgressQueued) return
        routeProgressQueued = true
        enqueue { g -> routeProgressQueued = false; writeChar(g, c, bytes) }
    }

    private var routeGen = 0

    /** True once the connected Pulse can show routes (firmware V2.6+). */
    val supportsRoute: Boolean get() = routeChar != null

    /** True once the connected Pulse has the ride/places channels (firmware V2.0+). */
    val supportsRide: Boolean get() = rideInfoChar != null

    fun send(cmd: PulseCommand) {
        if (isDemo) { demoCommand(cmd); return }
        val c = commandChar ?: return
        enqueue { g -> writeChar(g, c, byteArrayOf(cmd.code)) }
    }

    // ------------------------------------------------------------- scanning

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post { onFound(result.device) }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post {
                scanning = false
                main.postDelayed({ if (wanted && gatt == null && !isDemo) startScan() }, 2000)
            }
        }
    }

    private fun startScan() {
        if (scanning) return
        val scanner = manager?.adapter?.bluetoothLeScanner
        if (scanner == null) { _status.value = Status.BLUETOOTH_OFF; return }
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(PulseUuid.SERVICE)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(filters, settings, scanCallback)
            scanning = true
            _status.value = Status.SCANNING
        } catch (e: SecurityException) {
            _status.value = Status.NO_PERMISSION
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { manager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
    }

    private fun onFound(device: BluetoothDevice) {
        if (gatt != null || isDemo) return
        stopScan()
        _status.value = Status.CONNECTING
        _deviceName.value = device.name ?: "Kinetic Pulse"
        gatt = device.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun disconnect() {
        gatt?.let {
            try { it.disconnect(); it.close() } catch (_: Exception) {}
        }
        gatt = null
        commandChar = null
        rideInfoChar = null
        placesChar = null
        routeChar = null
        routeProgressQueued = false
        rideInfoQueued = false
        placesQueued = false
        ops.clear()
        opBusy = false
    }

    private fun clearValues() {
        _live.value = null
        _ride.value = null
        _allTime.value = null
        _rideExtra.value = null
        _allTimeExtra.value = null
    }

    // ------------------------------------------------------------ GATT queue

    private fun enqueue(op: (BluetoothGatt) -> Boolean) {
        ops.addLast(op)
        if (!opBusy) nextOp()
    }

    private fun nextOp() {
        while (true) {
            val g = gatt
            if (g == null) { ops.clear(); opBusy = false; return }
            val op = ops.removeFirstOrNull()
            if (op == null) { opBusy = false; return }
            opBusy = true
            if (op(g)) return  // wait for its callback; otherwise skip to the next one
        }
    }

    private fun opDone() {
        opBusy = false
        nextOp()
    }

    @Suppress("DEPRECATION")
    private fun enableNotify(g: BluetoothGatt, c: BluetoothGattCharacteristic): Boolean {
        if (!g.setCharacteristicNotification(c, true)) return false
        val d = c.getDescriptor(PulseUuid.CCCD) ?: return false
        val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
        } else {
            d.value = v
            g.writeDescriptor(d)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeChar(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, v, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = v
            g.writeCharacteristic(c)
        }
    }

    private fun setup(g: BluetoothGatt) {
        val s = g.getService(PulseUuid.SERVICE)
        if (s == null) { g.disconnect(); return }
        commandChar = s.getCharacteristic(PulseUuid.COMMAND)
        rideInfoChar = s.getCharacteristic(PulseUuid.RIDE_INFO)  // null on older firmware
        placesChar = s.getCharacteristic(PulseUuid.PLACES)
        routeChar = s.getCharacteristic(PulseUuid.ROUTE)  // null before V2.6

        val notify = listOf(
            PulseUuid.LIVE, PulseUuid.WHEELIE, PulseUuid.RIDE, PulseUuid.ALL_TIME,
            PulseUuid.RIDE_EXTRA, PulseUuid.ALL_TIME_EXTRA, PulseUuid.TRICK,
        )
        for (u in notify) {
            val c = s.getCharacteristic(u) ?: continue
            enqueue { gg -> enableNotify(gg, c) }
        }
        val read = listOf(
            PulseUuid.RIDE, PulseUuid.ALL_TIME, PulseUuid.RIDE_EXTRA,
            PulseUuid.ALL_TIME_EXTRA, PulseUuid.LIVE,
        )
        for (u in read) {
            val c = s.getCharacteristic(u) ?: continue
            enqueue { gg -> gg.readCharacteristic(c) }
        }
        _status.value = Status.CONNECTED
    }

    private fun handle(u: UUID, v: ByteArray) {
        when (u) {
            PulseUuid.LIVE -> LivePacket.parse(v)?.let { _live.value = it }
            PulseUuid.WHEELIE -> WheeliePacket.parse(v)?.let {
                _wheelies.tryEmit(WheelieEvent(it, _allTime.value?.longestMs ?: 0))
            }
            PulseUuid.RIDE -> StatsPacket.parse(v)?.let { _ride.value = it }
            PulseUuid.ALL_TIME -> StatsPacket.parse(v)?.let { _allTime.value = it }
            PulseUuid.RIDE_EXTRA -> StatsExtraPacket.parse(v)?.let { _rideExtra.value = it }
            PulseUuid.ALL_TIME_EXTRA -> StatsExtraPacket.parse(v)?.let { _allTimeExtra.value = it }
            PulseUuid.TRICK -> TrickPacket.parse(v)?.let { _tricks.tryEmit(it) }
        }
    }

    // Callbacks arrive on a Bluetooth thread; everything is moved to the main thread.
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g != gatt) { try { g.close() } catch (_: Exception) {}; return@post }
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    if (!g.requestMtu(185)) g.discoverServices()
                } else {
                    disconnect()
                    _live.value = null
                    if (wanted && !isDemo) {
                        _status.value = Status.SCANNING
                        main.postDelayed({ if (wanted && gatt == null && !isDemo) start() }, 1000)
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post { if (g == gatt) g.discoverServices() }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post { if (g == gatt) setup(g) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post { opDone() }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            main.post { opDone() }
        }

        // Android 13+
        override fun onCharacteristicRead(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int,
        ) {
            val u = c.uuid
            main.post {
                if (status == BluetoothGatt.GATT_SUCCESS) handle(u, value)
                opDone()
            }
        }

        // Android 12 and older
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= 33) return
            val v = c.value?.copyOf() ?: ByteArray(0)
            val u = c.uuid
            main.post {
                if (status == BluetoothGatt.GATT_SUCCESS) handle(u, v)
                opDone()
            }
        }

        // Android 13+
        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray,
        ) {
            val u = c.uuid
            main.post { handle(u, value) }
        }

        // Android 12 and older
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            val v = c.value?.copyOf() ?: return
            val u = c.uuid
            main.post { handle(u, v) }
        }
    }

    // ------------------------------------------------------------- demo mode

    private var demoJob: Job? = null
    private var demoRide = DemoTotals()
    private var demoAll = DemoTotals()

    fun setDemo(on: Boolean) {
        if (on == isDemo) return
        if (on) {
            stopScan()
            disconnect()
            _status.value = Status.DEMO
            _deviceName.value = "Demo Pulse"
            demoRide = DemoTotals()
            demoAll = DemoTotals()
            publishDemoStats()
            demoJob = scope.launch { runDemo() }
        } else {
            demoJob?.cancel()
            demoJob = null
            clearValues()
            _deviceName.value = null
            _status.value = Status.SCANNING
            start()
        }
    }

    private fun demoCommand(cmd: PulseCommand) {
        when (cmd) {
            PulseCommand.RESET_RIDE -> demoRide = DemoTotals()
            PulseCommand.RESET_ALL_TIME -> demoAll = DemoTotals()
            else -> {}
        }
        publishDemoStats()
    }

    private fun publishDemoStats() {
        _ride.value = demoRide.stats()
        _allTime.value = demoAll.stats()
        _rideExtra.value = demoRide.extra()
        _allTimeExtra.value = demoAll.extra()
    }

    private fun demoLive(pitch: Float, inWheelie: Boolean, ms: Long, inStoppie: Boolean = false) {
        _live.value = LivePacket(
            pitch, inWheelie, calibrated = true, wheelieMs = ms, battery = 87, inStoppie = inStoppie,
        )
    }

    private var demoBestStoppieMs = 0L

    private suspend fun runDemo() {
        var t = 0f
        while (true) {
            // riding along, front wheel down
            val idleEnd = t + 3f + Random.nextFloat() * 3f
            while (t < idleEnd) {
                demoLive(sin(t * 3f) * 1.5f + Random.nextFloat() - 0.5f, false, 0)
                delay(100); t += 0.1f
            }

            // sometimes a stoppie instead of a wheelie
            if (Random.nextFloat() < 0.3f) {
                val dur = 0.5f + Random.nextFloat() * 1.8f
                val target = 10f + Random.nextFloat() * 12f
                var w = 0f; var peak = 0f; var sum = 0f; var sumSq = 0f; var n = 0
                while (w < dur) {
                    val shape = min(min(1f, w / 0.25f), min(1f, (dur - w) / 0.25f))
                    val a = target * shape + (Random.nextFloat() - 0.5f) * 2f
                    peak = max(peak, a); sum += a; sumSq += a * a; n++
                    demoLive(-a, false, if (w > 0.3f) (w * 1000).toLong() else 0, inStoppie = w > 0.3f)
                    delay(100); w += 0.1f; t += 0.1f
                }
                val avg = sum / n
                val sd = sqrt(max(0f, sumSq / n - avg * avg))
                val ms = (dur * 1000).toLong()
                _tricks.tryEmit(
                    TrickPacket(
                        TrickKind.STOPPIE, ms, peak, 0f,
                        (100f - (sd - 1f) * 12f).coerceIn(0f, 100f).toInt(), ms > demoBestStoppieMs,
                    )
                )
                demoBestStoppieMs = max(demoBestStoppieMs, ms)
                continue
            }

            // a wheelie
            val dur = 1.5f + Random.nextFloat() * 7f
            val target = 20f + Random.nextFloat() * 14f
            var w = 0f; var prev = 0f
            var peak = 0f; var sum = 0f; var sumSq = 0f; var n = 0; var pop = 0f
            while (w < dur) {
                val rise = min(1f, w / 0.5f)
                val fall = min(1f, (dur - w) / 0.4f)
                val a = target * min(rise, fall) + sin(w * 4f) * 3f * rise + (Random.nextFloat() - 0.5f) * 2f
                pop = max(pop, (a - prev) / 0.1f); prev = a
                peak = max(peak, a); sum += a; sumSq += a * a; n++
                demoLive(a, w > 0.5f, (w * 1000).toLong())
                delay(100); w += 0.1f; t += 0.1f
            }
            val avg = sum / n
            val sd = sqrt(max(0f, sumSq / n - avg * avg))
            val packet = WheeliePacket(
                ms = (dur * 1000).toLong(),
                peak = peak,
                newBest = (dur * 1000).toLong() > demoAll.bestMs,
                avg = avg,
                balance = (100f - (sd - 1f) * 12f).coerceIn(0f, 100f).toInt(),
                pop = pop.roundToInt(),
                landingG = 1.2f + Random.nextFloat() * 1.8f,
            )
            _wheelies.tryEmit(WheelieEvent(packet, demoAll.bestMs))
            // the real Pulse follows a circle's WHEELIE message with a TRICK message
            if (Random.nextFloat() < 0.25f) {
                val turns = (1f + Random.nextFloat() * 1.2f) * (if (Random.nextBoolean()) 1f else -1f)
                _tricks.tryEmit(
                    TrickPacket(TrickKind.CIRCLE, packet.ms, packet.peak, turns, packet.balance, packet.newBest)
                )
            }
            demoRide.add(packet)
            demoAll.add(packet)
            publishDemoStats()
        }
    }

    private class DemoTotals {
        var count = 0L; var bestMs = 0L; var highest = 0f; var totalMs = 0L
        var balanceSum = 0L; var bestBalance = 0; var bestPop = 0
        var softest = 0f; var over5 = 0L; var over10 = 0L

        fun add(p: WheeliePacket) {
            count++; totalMs += p.ms; balanceSum += p.balance
            bestMs = max(bestMs, p.ms); highest = max(highest, p.peak)
            bestBalance = max(bestBalance, p.balance); bestPop = max(bestPop, p.pop)
            if (softest == 0f || p.landingG < softest) softest = p.landingG
            if (p.ms >= 5000) over5++
            if (p.ms >= 10000) over10++
        }

        fun stats() = StatsPacket(count, bestMs, highest, totalMs)
        fun extra() = StatsExtraPacket(
            avgBalance = if (count > 0) (balanceSum / count).toInt() else 0,
            bestBalance = bestBalance,
            bestPop = bestPop,
            softestLandingG = if (softest == 0f) null else softest,
            over5 = over5,
            over10 = over10,
        )
    }
}
