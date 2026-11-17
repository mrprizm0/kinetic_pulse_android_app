package com.kineticlabs.pulse.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

// Matches the firmware's Ble.h exactly. All numbers are little-endian.

object PulseUuid {
    private fun make(n: Int): UUID =
        UUID.fromString(String.format("6b1e%04x-4b49-4e45-5449-43204c414253", n))

    val SERVICE: UUID = make(1)
    val LIVE: UUID = make(2)
    val WHEELIE: UUID = make(3)
    val RIDE: UUID = make(4)
    val ALL_TIME: UUID = make(5)
    val COMMAND: UUID = make(6)
    val RIDE_EXTRA: UUID = make(7)
    val ALL_TIME_EXTRA: UUID = make(8)
    val TRICK: UUID = make(9)
    /** phone -> Pulse: ride overview (written every couple of seconds while riding) */
    val RIDE_INFO: UUID = make(10)
    /** phone -> Pulse: up to 4 nearby spots or rideouts */
    val PLACES: UUID = make(11)
    /** phone -> Pulse: a route to follow (beta, firmware V2.6+) */
    val ROUTE: UUID = make(12)
    /** phone -> Pulse: plate-camera (ALPR) warning (firmware V2.9+) */
    val ALERT: UUID = make(13)
    /** Pulse -> phone: SOS (panic button / crash detected), firmware V3.0+ */
    val SOS: UUID = make(14)

    /** Standard "turn notifications on" descriptor. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

enum class PulseCommand(val code: Byte) {
    RESET_RIDE(1), RESET_ALL_TIME(2), CALIBRATE(3), SOUND_ON(4), SOUND_OFF(5)
}

private fun le(b: ByteArray): ByteBuffer = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
private fun u32(bb: ByteBuffer, i: Int) = bb.getInt(i).toLong() and 0xFFFFFFFFL
private fun u16(bb: ByteBuffer, i: Int) = bb.getShort(i).toInt() and 0xFFFF

/** 0002 LIVE, 10 times a second. */
data class LivePacket(
    val pitch: Float,
    val inWheelie: Boolean,
    val calibrated: Boolean,
    val wheelieMs: Long,     // current wheelie OR stoppie time
    val battery: Int?,
    val inStoppie: Boolean = false,
) {
    companion object {
        fun parse(b: ByteArray): LivePacket? {
            if (b.size < 8) return null
            val bb = le(b)
            val flags = u8(b, 2)
            val bat = u8(b, 7)
            return LivePacket(
                pitch = bb.getShort(0) / 10f,
                inWheelie = (flags and 1) != 0,
                calibrated = (flags and 2) != 0,
                wheelieMs = u32(bb, 3),
                battery = if (bat == 255) null else bat,
                inStoppie = (flags and 4) != 0,
            )
        }
    }
}

enum class TrickKind(val code: Int) {
    WHEELIE(0), STOPPIE(1), CIRCLE(2);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: WHEELIE
    }
}

/**
 * 000E SOS: state 0 none, 1 counting down, 2 SENT (text the contacts),
 * 3 all clear after a sent one; reason 1 panic, 2 crash; event id per SOS.
 */
data class SosPacket(val state: Int, val reason: Int, val secondsLeft: Int, val eventId: Int) {
    val isPanic: Boolean get() = reason == 1
    companion object {
        const val NONE = 0; const val COUNTING = 1; const val SENT = 2; const val ALL_CLEAR = 3
        fun parse(b: ByteArray): SosPacket? {
            if (b.size < 5) return null
            return SosPacket(u8(b, 0), u8(b, 1), u8(b, 2), u8(b, 3) or (u8(b, 4) shl 8))
        }
    }
}

/** 0009 TRICK, sent after every wheelie, stoppie or wheelie circle (firmware V1.5+). */
data class TrickPacket(
    val kind: TrickKind,
    val ms: Long,
    val peak: Float,      // stoppie: how far the nose dipped
    val turns: Float,     // circles: + = left
    val balance: Int,
    val newBest: Boolean,
) {
    companion object {
        fun parse(b: ByteArray): TrickPacket? {
            if (b.size < 10) return null
            val bb = le(b)
            return TrickPacket(
                kind = TrickKind.of(u8(b, 0)),
                ms = u32(bb, 1),
                peak = bb.getShort(5) / 10f,
                turns = bb.getShort(7) / 100f,
                balance = u8(b, 9),
                newBest = b.size > 10 && u8(b, 10) == 1,
            )
        }
    }
}

/** 0003 WHEELIE, sent ~0.4 s after each wheelie lands. */
data class WheeliePacket(
    val ms: Long,
    val peak: Float,
    val newBest: Boolean,
    val avg: Float,
    val balance: Int,
    val pop: Int,
    val landingG: Float,
) {
    companion object {
        fun parse(b: ByteArray): WheeliePacket? {
            if (b.size < 7) return null
            val bb = le(b)
            return WheeliePacket(
                ms = u32(bb, 0),
                peak = bb.getShort(4) / 10f,
                newBest = u8(b, 6) == 1,
                avg = if (b.size >= 10) bb.getShort(7) / 10f else 0f,
                balance = if (b.size >= 10) u8(b, 9) else 0,
                pop = if (b.size >= 14) bb.getShort(10).toInt() else 0,
                landingG = if (b.size >= 14) u16(bb, 12) / 100f else 0f,
            )
        }
    }
}

/** 0004 RIDE / 0005 ALLTIME. */
data class StatsPacket(
    val count: Long,
    val longestMs: Long,
    val highest: Float,
    val totalMs: Long,
) {
    val avgMs: Long get() = if (count > 0) totalMs / count else 0

    companion object {
        fun parse(b: ByteArray): StatsPacket? {
            if (b.size < 14) return null
            val bb = le(b)
            return StatsPacket(u32(bb, 0), u32(bb, 4), bb.getShort(8) / 10f, u32(bb, 10))
        }
    }
}

/** 0007 RIDE_EXTRA / 0008 ALLTIME_EXTRA. */
data class StatsExtraPacket(
    val avgBalance: Int,
    val bestBalance: Int,
    val bestPop: Int,
    val softestLandingG: Float?,
    val over5: Long,
    val over10: Long,
) {
    companion object {
        fun parse(b: ByteArray): StatsExtraPacket? {
            if (b.size < 14) return null
            val bb = le(b)
            val soft = u16(bb, 4)
            return StatsExtraPacket(
                avgBalance = u8(b, 0),
                bestBalance = u8(b, 1),
                bestPop = bb.getShort(2).toInt(),
                softestLandingG = if (soft == 0) null else soft / 100f,
                over5 = u32(bb, 6),
                over10 = u32(bb, 10),
            )
        }
    }
}

/** Landing softness 0..100 from the touchdown hit (same formula as the device). */
fun landingScore(g: Float): Int = (100f - (g - 1f) * 25f).coerceIn(0f, 100f).toInt()

/**
 * 000A RIDE_INFO, phone -> Pulse, 20 bytes:
 *   u8 flags (bit0 ride active, bit1 GPS fix), u32 elapsed s, u32 distance m,
 *   u16 speed km/h x10, u16 top speed km/h x10, u16 wheelies, u32 best wheelie ms, u8 reserved
 */
fun rideInfoBytes(active: Boolean, hasFix: Boolean, elapsedS: Long, distanceM: Long, speedKmh: Float,
                  topKmh: Float, wheelies: Int, bestMs: Long,
                  batteryPct: Int? = null, rangeKm: Float? = null): ByteArray {
    // 24 bytes since V3.0: + u8 flags (bit0 battery estimate valid), u8 battery %, u16 range km x10.
    // Older firmware reads the first 19 and ignores the rest.
    val bb = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
    bb.put(((if (active) 1 else 0) or (if (hasFix) 2 else 0)).toByte())
    bb.putInt(elapsedS.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
    bb.putInt(distanceM.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
    bb.putShort((speedKmh * 10).toInt().coerceIn(0, 65535).toShort())
    bb.putShort((topKmh * 10).toInt().coerceIn(0, 65535).toShort())
    bb.putShort(wheelies.coerceIn(0, 65535).toShort())
    bb.putInt(bestMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
    bb.put(0)
    val hasBatt = batteryPct != null && rangeKm != null
    bb.put((if (hasBatt) 1 else 0).toByte())
    bb.put((batteryPct ?: 0).coerceIn(0, 100).toByte())
    bb.putShort(((rangeKm ?: 0f) * 10).toInt().coerceIn(0, 65535).toShort())
    return bb.array()
}

/** Spot kinds as the Pulse knows them. */
object PlaceKind {
    const val MOTOCROSS = 0; const val MOTORSPORT = 1; const val OFFROAD = 2
    const val PUMP = 3; const val BMX = 4; const val SKATEPARK = 5; const val RIDEOUT = 9
    fun of(kind: String): Int = when (kind) {
        "motocross track" -> MOTOCROSS; "motorsport" -> MOTORSPORT; "off-road area" -> OFFROAD
        "pump track" -> PUMP; "bmx track" -> BMX; "skatepark" -> SKATEPARK; else -> MOTORSPORT
    }
}

data class PlaceForPulse(val kind: Int, val motorsOk: Boolean, val distanceM: Int, val bearingDeg: Int, val name: String)

/**
 * 000B PLACES, phone -> Pulse: u8 count (0..4), then per place 26 bytes:
 *   u8 kind, u8 motors ok, u16 distance in 10 m steps, u16 bearing deg, char[20] name (ASCII, 0-padded)
 */
fun placesBytes(places: List<PlaceForPulse>): ByteArray {
    val list = places.take(4)
    val bb = ByteBuffer.allocate(1 + 26 * list.size).order(ByteOrder.LITTLE_ENDIAN)
    bb.put(list.size.toByte())
    for (p in list) {
        bb.put(p.kind.toByte())
        bb.put((if (p.motorsOk) 1 else 0).toByte())
        bb.putShort((p.distanceM / 10).coerceIn(0, 65535).toShort())
        bb.putShort(((p.bearingDeg % 360 + 360) % 360).toShort())
        // plain ASCII only: the Pulse font has no accents or emoji
        val ascii = p.name.map { if (it.code in 32..126) it else '?' }.joinToString("").take(20)
        val name = ByteArray(20); ascii.toByteArray(Charsets.US_ASCII).copyInto(name)
        bb.put(name)
    }
    return bb.array()
}