package com.putokulo.app.crash

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.putokulo.app.MainActivity
import com.putokulo.app.R
import com.putokulo.app.hasPermission
import com.putokulo.app.ui.AlertActivity

object Notifications {
    private const val CHANNEL_RIDE = "ride_protection"
    private const val CHANNEL_ALERT = "crash_alert"
    const val ID_RIDE = 1
    private const val ID_ALERT = 2

    fun createChannels(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_RIDE, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName("Ride protection")
                    .setDescription("Shown while crash detection is running")
                    .build(),
                // The alarm sound and vibration are played by CrashAlertManager, not the channel
                NotificationChannelCompat.Builder(CHANNEL_ALERT, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName("Crash alerts")
                    .setDescription("Countdown before your emergency contacts are alerted")
                    .setSound(null, null)
                    .setVibrationEnabled(false)
                    .build(),
            )
        )
    }

    fun rideNotification(context: Context): Notification {
        val openApp = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            context, 0, RideProtectionService.stopIntent(context), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_RIDE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Ride protection is on")
            .setContentText("Watching for crashes. Ride safe!")
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, "Stop", stop)
            .build()
    }

    fun showCrashCountdown(context: Context, seconds: Int) {
        val alertScreen = alertScreenIntent(context)
        val cancel = PendingIntent.getBroadcast(
            context, 0, Intent(context, CancelAlertReceiver::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        notify(
            context,
            NotificationCompat.Builder(context, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Possible crash detected")
                .setContentText("Your emergency contacts will be alerted unless you cancel")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(alertScreen, true)
                .setContentIntent(alertScreen)
                .setOngoing(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setWhen(System.currentTimeMillis() + seconds * 1_000L)
                .addAction(0, "I'm OK, cancel", cancel)
                .build()
        )
    }

    fun showAlertSent(context: Context, sentCount: Int, isTest: Boolean) {
        val text = when {
            isTest -> "Test finished. No SMS was sent."
            sentCount == 0 -> "The alert could not be sent. Call for help."
            else -> "Your location was sent to $sentCount contact(s)."
        }
        notify(
            context,
            NotificationCompat.Builder(context, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(if (isTest) "Crash alert test" else "Crash alert sent")
                .setContentText(text)
                .setContentIntent(alertScreenIntent(context))
                .setAutoCancel(true)
                .build()
        )
    }

    fun cancelCrashAlert(context: Context) = NotificationManagerCompat.from(context).cancel(ID_ALERT)

    private fun alertScreenIntent(context: Context) = PendingIntent.getActivity(
        context,
        1,
        Intent(context, AlertActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @SuppressLint("MissingPermission") // checked below
    private fun notify(context: Context, notification: Notification) {
        if (context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            NotificationManagerCompat.from(context).notify(ID_ALERT, notification)
        }
    }
}

/** "I'm OK, cancel" button on the countdown notification. */
class CancelAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = CrashAlertManager.cancel(context)
}
