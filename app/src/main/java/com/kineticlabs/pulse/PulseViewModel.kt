package com.kineticlabs.pulse

import android.app.Application
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kineticlabs.pulse.ble.PulseBle
import com.kineticlabs.pulse.ble.WheelieEvent
import com.kineticlabs.pulse.data.HistoryStore
import com.kineticlabs.pulse.data.WheelieRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the post-wheelie summary shows. */
data class Summary(
    val rec: WheelieRecord,
    val prevBestMs: Long,
    val number: Int,        // wheelie # this session
    val rank: Int,          // 1 = longest this session
    val sessionTotalMs: Long,
    val shownAt: Long,
)

class PulseViewModel(app: Application) : AndroidViewModel(app) {
    val ble = PulseBle(app)
    private val store = HistoryStore(app)

    private val _history = MutableStateFlow(store.load())
    val history: StateFlow<List<WheelieRecord>> = _history  // oldest first

    // wheelies since the app opened (demo ones too), for rank / session total
    private val session = mutableListOf<WheelieRecord>()
    private val _sessionCount = MutableStateFlow(0)
    val sessionCount: StateFlow<Int> = _sessionCount

    private val _summary = MutableStateFlow<Summary?>(null)
    val summary: StateFlow<Summary?> = _summary

    val keepScreenOn = MutableStateFlow(true)

    init {
        viewModelScope.launch { ble.wheelies.collect { onWheelie(it) } }
    }

    private fun onWheelie(e: WheelieEvent) {
        val rec = WheelieRecord.from(e.packet)
        if (!ble.isDemo) {  // demo wheelies are not saved
            store.append(rec)
            _history.value = _history.value + rec
        }
        session += rec
        _sessionCount.value = session.size
        _summary.value = Summary(
            rec = rec,
            prevBestMs = e.prevBestMs,
            number = session.size,
            rank = 1 + session.count { it.ms > rec.ms },
            sessionTotalMs = session.sumOf { it.ms },
            shownAt = System.currentTimeMillis(),
        )
        vibrate(e.packet.newBest)
    }

    fun dismissSummary(shownAt: Long? = null) {
        val s = _summary.value ?: return
        if (shownAt == null || s.shownAt == shownAt) _summary.value = null
    }

    fun clearHistory() {
        store.clear()
        _history.value = emptyList()
    }

    private fun vibrate(best: Boolean) {
        val ctx = getApplication<Application>()
        val vib: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            ctx.getSystemService(Vibrator::class.java)
        }
        val effect = if (best) VibrationEffect.createWaveform(longArrayOf(0, 80, 70, 220), -1)
        else VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)
        try { vib?.vibrate(effect) } catch (_: Exception) {}
    }

    override fun onCleared() {
        ble.stop()
    }
}
