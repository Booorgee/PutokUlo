package com.putokulo.app.crash

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.putokulo.app.hasPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the UI shows about the current ride. */
object RideState {
    internal val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    internal val _speedKmh = MutableStateFlow<Float?>(null)
    val speedKmh: StateFlow<Float?> = _speedKmh
}

/**
 * Foreground service that watches the accelerometer and GPS while riding, so crash detection
 * keeps working with the screen off.
 */
class RideProtectionService : Service(), SensorEventListener, LocationListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    private val detector = CrashDetector { CrashAlertManager.trigger(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) startProtection()
        return START_STICKY
    }

    private fun startProtection() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            stopSelf()
            return
        }
        try {
            ServiceCompat.startForeground(
                this,
                Notifications.ID_RIDE,
                Notifications.rideNotification(this),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (e: Exception) {
            // Android can refuse to start a location service from the background
            stopSelf()
            return
        }
        started = true

        sensorManager = getSystemService(SensorManager::class.java)
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        locationManager = getSystemService(LocationManager::class.java)
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, this, Looper.getMainLooper())
        } catch (e: SecurityException) {
            stopSelf()
            return
        }

        // Keeps the CPU awake so accelerometer readings don't pause when the screen turns off
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PutokUlo:ride")
            .apply { acquire(12 * 60 * 60 * 1_000L) }

        RideState._running.value = true
    }

    override fun onDestroy() {
        if (started) {
            sensorManager.unregisterListener(this)
            locationManager.removeUpdates(this)
            wakeLock?.takeIf { it.isHeld }?.release()
        }
        RideState._running.value = false
        RideState._speedKmh.value = null
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent) {
        detector.onAcceleration(event.values[0], event.values[1], event.values[2], SystemClock.elapsedRealtime())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onLocationChanged(location: Location) {
        CrashAlertManager.lastLocation = location
        val kmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
        detector.onSpeed(kmh, SystemClock.elapsedRealtime())
        RideState._speedKmh.value = kmh
    }

    // Overridden explicitly: these are abstract on Android 8-10
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    companion object {
        private const val ACTION_STOP = "com.putokulo.app.STOP_RIDE"

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, RideProtectionService::class.java))

        fun stop(context: Context) {
            context.stopService(Intent(context, RideProtectionService::class.java))
        }

        fun stopIntent(context: Context) =
            Intent(context, RideProtectionService::class.java).setAction(ACTION_STOP)
    }
}
