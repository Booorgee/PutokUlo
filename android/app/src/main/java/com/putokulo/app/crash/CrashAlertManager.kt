package com.putokulo.app.crash

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telephony.SmsManager
import com.putokulo.app.data.EmergencyContact
import com.putokulo.app.data.SettingsRepository
import com.putokulo.app.hasPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Runs the crash countdown and sends the SMS. Lives outside any screen so the alert still goes out
 * when the phone is locked or the app is in the background.
 */
object CrashAlertManager {
    const val COUNTDOWN_SECONDS = 15

    sealed interface State {
        data object Idle : State
        data class Countdown(val secondsLeft: Int, val contacts: List<EmergencyContact>, val isTest: Boolean) : State
        /** [sentTo] is empty if the SMS could not be sent. In a test it lists who would have been alerted. */
        data class Sent(val sentTo: List<EmergencyContact>, val message: String, val isTest: Boolean) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** Updated by the ride service on every GPS fix. */
    @Volatile
    var lastLocation: Location? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var countdown: Job? = null
    private var ringtone: Ringtone? = null

    fun trigger(context: Context, isTest: Boolean = false) {
        if (_state.value != State.Idle) return
        val app = context.applicationContext
        val contacts = SettingsRepository.contacts.value

        _state.value = State.Countdown(COUNTDOWN_SECONDS, contacts, isTest)
        startAlarm(app)
        Notifications.showCrashCountdown(app, COUNTDOWN_SECONDS)

        // Count down to a fixed deadline so a busy phone can't stretch the countdown
        val deadline = SystemClock.elapsedRealtime() + COUNTDOWN_SECONDS * 1_000L
        countdown = scope.launch {
            while (true) {
                val msLeft = deadline - SystemClock.elapsedRealtime()
                if (msLeft <= 0) break
                _state.value = State.Countdown(((msLeft + 999) / 1_000).toInt(), contacts, isTest)
                delay((msLeft - 1) % 1_000 + 1)
            }
            stopAlarm(app)
            val message = buildMessage(app)
            val sentTo = if (isTest) contacts else sendSms(app, contacts, message)
            _state.value = State.Sent(sentTo, message, isTest)
            Notifications.showAlertSent(app, sentTo.size, isTest)
        }
    }

    /** "I'm OK": stop the countdown before anything is sent. */
    fun cancel(context: Context) {
        countdown?.cancel()
        stopAlarm(context.applicationContext)
        Notifications.cancelCrashAlert(context)
        _state.value = State.Idle
    }

    /** Close the "alert sent" screen. */
    fun dismiss(context: Context) {
        Notifications.cancelCrashAlert(context)
        _state.value = State.Idle
    }

    private fun buildMessage(context: Context): String {
        val rider = SettingsRepository.riderName.value.ifBlank { "the rider" }
        val location = lastLocation ?: lastKnownLocation(context)
        val where = location?.let {
            String.format(Locale.US, "Location: https://maps.google.com/?q=%.6f,%.6f", it.latitude, it.longitude)
        } ?: "Location unavailable."
        return "PutokUlo ALERT: A possible crash was detected for $rider. $where (sent automatically)"
    }

    private fun lastKnownLocation(context: Context): Location? {
        if (!context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return null
        val locationManager = context.getSystemService(LocationManager::class.java)
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    private fun sendSms(context: Context, contacts: List<EmergencyContact>, message: String): List<EmergencyContact> {
        if (!context.hasPermission(Manifest.permission.SEND_SMS)) return emptyList()
        val sms = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        val parts = sms.divideMessage(message)
        return contacts.filter { contact ->
            runCatching { sms.sendMultipartTextMessage(contact.phone, null, parts, null, null) }.isSuccess
        }
    }

    private fun startAlarm(context: Context) {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
            audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            if (Build.VERSION.SDK_INT >= 28) isLooping = true
            play()
        }
        vibrator(context).vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 300), 0))
    }

    private fun stopAlarm(context: Context) {
        ringtone?.stop()
        ringtone = null
        vibrator(context).cancel()
    }

    private fun vibrator(context: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
}
