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
    val wheelieMs: Long,
    val battery: Int?,
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
