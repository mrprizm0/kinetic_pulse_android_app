package com.kineticlabs.pulse.data

import android.content.Context
import com.kineticlabs.pulse.ble.TrickKind
import com.kineticlabs.pulse.ble.TrickPacket
import com.kineticlabs.pulse.ble.WheeliePacket
import java.io.File

/** One saved trick: a wheelie, a stoppie or a wheelie circle. */
data class WheelieRecord(
    val time: Long,  // when it happened (epoch ms)
    val ms: Long,
    val peak: Float,  // stoppie: how far the nose dipped
    val avg: Float,
    val balance: Int,
    val pop: Int,
    val landingG: Float,
    val newBest: Boolean,
    val kind: TrickKind = TrickKind.WHEELIE,
    val turns: Float = 0f,  // circles: + = left
) {
    val isWheelie: Boolean get() = kind != TrickKind.STOPPIE  // circles are wheelies too

    fun toCsv() =
        "$time,$ms,$peak,$avg,$balance,$pop,$landingG,${if (newBest) 1 else 0},${kind.code},$turns"

    companion object {
        fun from(p: WheeliePacket, time: Long = System.currentTimeMillis()) =
            WheelieRecord(time, p.ms, p.peak, p.avg, p.balance, p.pop, p.landingG, p.newBest)

        fun fromStoppie(t: TrickPacket, time: Long = System.currentTimeMillis()) =
            WheelieRecord(time, t.ms, t.peak, 0f, t.balance, 0, 0f, t.newBest, TrickKind.STOPPIE, 0f)

        // older files have 8 columns (no kind / turns): those were all wheelies
        fun fromCsv(line: String): WheelieRecord? = runCatching {
            val f = line.split(',')
            WheelieRecord(
                f[0].toLong(), f[1].toLong(), f[2].toFloat(), f[3].toFloat(),
                f[4].toInt(), f[5].toInt(), f[6].toFloat(), f[7] == "1",
                TrickKind.of(f.getOrNull(8)?.toIntOrNull() ?: 0),
                f.getOrNull(9)?.toFloatOrNull() ?: 0f,
            )
        }.getOrNull()
    }
}

/** Every trick, one CSV line each, in the app's private storage. */
class HistoryStore(context: Context) {
    private val file = File(context.filesDir, "wheelies.csv")

    fun load(): List<WheelieRecord> =
        if (!file.exists()) emptyList() else file.readLines().mapNotNull { WheelieRecord.fromCsv(it) }

    fun append(r: WheelieRecord) = file.appendText(r.toCsv() + "\n")

    /** Rewrite everything (used when a wheelie turns out to have been a circle). */
    fun saveAll(list: List<WheelieRecord>) =
        file.writeText(list.joinToString("") { it.toCsv() + "\n" })

    fun clear() { file.delete() }
}
