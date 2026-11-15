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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

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
