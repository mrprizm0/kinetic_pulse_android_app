package com.kineticlabs.pulse.sos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Someone to text when the Pulse sends an SOS. */
data class Contact(val name: String, val number: String)

/**
 * SOS text messages. They go out as normal SMS from the phone, so they work
 * anywhere there's phone signal, no internet or app needed on the other end.
 * The message has a map link to where you are.
 */
object Sos {
    private const val PREFS = "sos"
    const val MAX_CONTACTS = 3

    fun hasSmsPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------- contacts

    fun contacts(ctx: Context): List<Contact> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("contacts", "") ?: ""
        return raw.split('\n').mapNotNull { line ->
            val i = line.indexOf('\t')
            if (i <= 0) null else Contact(line.substring(0, i), line.substring(i + 1)).takeIf { it.number.isNotBlank() }
        }
    }

    fun saveContacts(ctx: Context, list: List<Contact>) {
        val raw = list.take(MAX_CONTACTS).joinToString("\n") {
            it.name.replace('\t', ' ').replace('\n', ' ') + "\t" + it.number.filter { c -> c.isDigit() || c == '+' }
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("contacts", raw).apply()
    }

    fun riderName(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("name", "") ?: ""

    fun saveRiderName(ctx: Context, name: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("name", name.trim().take(40)).apply()

    /** The last SOS event texted, so a reconnect or restart doesn't text it twice. */
    fun lastSentEvent(ctx: Context): Pair<Int, Long> {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getInt("event", -1) to p.getLong("event_at", 0L)
    }

    fun markSent(ctx: Context, event: Int) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("event", event).putLong("event_at", System.currentTimeMillis()).apply()

    // ---------------------------------------------------------- messages

    fun sosText(name: String, panic: Boolean, loc: Location?): String {
        val who = name.ifBlank { "A rider" }
        val what = if (panic) "pressed their panic button" else "may have crashed (their Pulse detected a crash)"
        val where = if (loc != null) {
            val t = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(loc.time))
            String.format(
                Locale.US, " Location: https://maps.google.com/?q=%.6f,%.6f (about %d m, at %s).",
                loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy.toInt() else 0, t,
            )
        } else " Their location isn't known."
        return "SOS from Kinetic Pulse: $who $what.$where Try calling them. If you can't reach them, call emergency services."
    }

    fun allClearText(name: String) =
        "All clear from Kinetic Pulse: ${name.ifBlank { "the rider" }} says they're OK. Sorry for the scare."

    fun testText(name: String) =
        "Test from Kinetic Pulse: ${name.ifBlank { "a rider" }} added you as an emergency contact. " +
            "If they crash or press panic, you'll get a text like this with a map link."

    /**
     * Texts [text] to every contact. Returns how many it handed to the phone
     * network, or throws [SecurityException] without the SMS permission.
     */
    fun sendToAll(ctx: Context, contacts: List<Contact>, text: String): Int {
        if (!hasSmsPermission(ctx)) throw SecurityException("no SMS permission")
        @Suppress("DEPRECATION")
        val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
                              else SmsManager.getDefault()
        var sent = 0
        for (c in contacts) {
            try {
                val parts = sms.divideMessage(text)
                sms.sendMultipartTextMessage(c.number, null, parts, null, null)
                sent++
            } catch (_: Exception) {}
        }
        return sent
    }
}
