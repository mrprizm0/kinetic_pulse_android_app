package com.kineticlabs.pulse.data

import android.content.Context
import com.kineticlabs.pulse.ble.WheeliePacket
import java.io.File

/** One saved wheelie. */
data class WheelieRecord(
    val time: Long,  // when it happened (epoch ms)
    val ms: Long,
    val peak: Float,
    val avg: Float,
    val balance: Int,
    val pop: Int,
    val landingG: Float,
    val newBest: Boolean,
) {
    fun toCsv() = "$time,$ms,$peak,$avg,$balance,$pop,$landingG,${if (newBest) 1 else 0}"

    companion object {
        fun from(p: WheeliePacket, time: Long = System.currentTimeMillis()) =
            WheelieRecord(time, p.ms, p.peak, p.avg, p.balance, p.pop, p.landingG, p.newBest)

        fun fromCsv(line: String): WheelieRecord? = runCatching {
            val f = line.split(',')
            WheelieRecord(
                f[0].toLong(), f[1].toLong(), f[2].toFloat(), f[3].toFloat(),
                f[4].toInt(), f[5].toInt(), f[6].toFloat(), f[7] == "1",
            )
        }.getOrNull()
    }
}

/** Every wheelie, one CSV line each, in the app's private storage. */
class HistoryStore(context: Context) {
    private val file = File(context.filesDir, "wheelies.csv")

    fun load(): List<WheelieRecord> =
        if (!file.exists()) emptyList() else file.readLines().mapNotNull { WheelieRecord.fromCsv(it) }

    fun append(r: WheelieRecord) = file.appendText(r.toCsv() + "\n")

    fun clear() { file.delete() }
}
