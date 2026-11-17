package com.kineticlabs.pulse.ride

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.kineticlabs.pulse.MainActivity
import com.kineticlabs.pulse.R

/**
 * Keeps recording the ride while the phone is in a pocket with the screen off.
 * Android requires this to be a visible "foreground service" with a notification.
 */
class RideService : Service() {

    companion object {
        private const val CHANNEL = "ride"
        private const val NOTE_ID = 7

        fun hasLocationPermission(ctx: Context) =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        fun start(ctx: Context) {
            if (!hasLocationPermission(ctx)) return
            RideRecorder.begin()
            ContextCompat.startForegroundService(ctx, Intent(ctx, RideService::class.java))
        }

        fun stop(ctx: Context): RideState? {
            ctx.stopService(Intent(ctx, RideService::class.java))
            return RideRecorder.end()
        }
    }

    private var lm: LocationManager? = null
    private val listener = LocationListener { loc: Location -> RideRecorder.onLocation(loc) }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")  // checked in start()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val note: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Ride in progress")
            .setContentText("Kinetic Pulse is recording your ride.")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        try {
            ServiceCompat.startForeground(
                this, NOTE_ID, note,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (_: Exception) {  // e.g. location permission taken away: don't crash, just stop
            stopSelf(); return START_NOT_STICKY
        }
        // Android restarted us after killing the app: there's no ride in memory any
        // more, so don't sit there with a "ride in progress" notice recording nothing.
        if (!hasLocationPermission(this) || !RideRecorder.state.value.active) { stopSelf(); return START_NOT_STICKY }
        if (lm == null) {
            lm = getSystemService(LocationManager::class.java)
            try {
                lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            } catch (_: Exception) {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { lm?.removeUpdates(listener) } catch (_: Exception) {}
        lm = null
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Ride tracking", NotificationManager.IMPORTANCE_LOW))
        }
    }
}
