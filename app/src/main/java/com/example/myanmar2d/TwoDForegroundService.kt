package com.example.myanmar2d

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps a persistent (ongoing) notification in the status bar showing the
 * live 2D number, refreshed periodically from [SettradeRepository]. The
 * notification survives the app being closed/backgrounded; it only goes
 * away when the user taps "ပိတ်မည်" (stop) or disables it from the app's
 * toggle in [MainActivity].
 *
 * On each poll:
 *  - Success -> shows the fresh 2D number, marked "Live"
 *  - Failure -> keeps showing the last known number, marked as stale, so
 *    the notification never goes blank on a transient network hiccup.
 */
class TwoDForegroundService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var lastKnownTwoD: String = "--"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setEnabledPref(applicationContext, false)
            stopSelf()
            return START_NOT_STICKY
        }

        // Must call startForeground within a few seconds of the service starting.
        startForeground(NOTIFICATION_ID, buildNotification(lastKnownTwoD, isLive = false))
        startUpdating()
        return START_STICKY
    }

    private fun startUpdating() {
        scope.launch {
            while (true) {
                val nowCal = NetworkTime.now()
                val nowSeconds = MarketSchedule.secondsSinceMidnight(
                    nowCal.get(java.util.Calendar.HOUR_OF_DAY),
                    nowCal.get(java.util.Calendar.MINUTE),
                    nowCal.get(java.util.Calendar.SECOND)
                )
                if (!MarketSchedule.isMarketLiveNow(nowSeconds)) {
                    updateBreakNotification(MarketSchedule.reopenLabel(nowSeconds))
                } else {
                    when (val result = SettradeRepository.fetchLiveSetIndex()) {
                        is SettradeRepository.FetchResult.Success -> {
                            lastKnownTwoD = result.data.twoD
                            updateNotification(lastKnownTwoD, isLive = true)
                        }
                        is SettradeRepository.FetchResult.Failure -> {
                            updateNotification(lastKnownTwoD, isLive = false)
                        }
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun updateBreakNotification(reopenLabel: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildBreakNotification(reopenLabel))
    }

    private fun updateNotification(value: String, isLive: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(value, isLive))
    }

    private fun buildBreakNotification(reopenLabel: String): Notification {
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, TwoDForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Market Break")
            .setContentText("Reopens $reopenLabel")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPendingIntent)
            .addAction(0, "ပိတ်မည်", stopPendingIntent)
            .build()
    }

    private fun buildNotification(value: String, isLive: Boolean): Notification {
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, TwoDForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("2D: $value")
            .setContentText(if (isLive) "Live" else "Live \u2022 waiting for update")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPendingIntent)
            .addAction(0, "ပိတ်မည်", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "2D Live",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Live 2D number in the status bar"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "two_d_live_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.example.myanmar2d.ACTION_STOP"

        // How often the service re-scrapes the live SET index while running
        // in the background. Kept longer than the in-app 4s ticker on
        // purpose, to avoid hammering the source site continuously all day.
        const val POLL_INTERVAL_MS = 20_000L

        private const val PREFS_NAME = "myanmar2d_settings"
        private const val KEY_ENABLED = "live_notification_enabled"

        fun isEnabledPref(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)

        fun setEnabledPref(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply()
        }

        fun start(context: Context) {
            setEnabledPref(context, true)
            val intent = Intent(context, TwoDForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            setEnabledPref(context, false)
            val intent = Intent(context, TwoDForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
