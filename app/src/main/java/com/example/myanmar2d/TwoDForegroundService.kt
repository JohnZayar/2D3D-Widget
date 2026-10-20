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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

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
    private var lastGloAttemptMs: Long = 0L

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
                maybePollGlo()
                val nowCal = NetworkTime.now()
                val nowSeconds = MarketSchedule.secondsSinceMidnight(
                    nowCal.get(java.util.Calendar.HOUR_OF_DAY),
                    nowCal.get(java.util.Calendar.MINUTE),
                    nowCal.get(java.util.Calendar.SECOND)
                )
                if (!MarketSchedule.isMarketLiveNow(nowSeconds)) {
                    updateBreakNotification(MarketSchedule.reopenLabel(nowSeconds))
                    // The closing-capture moments (12:01:05 / 16:30:05) sit just
                    // OUTSIDE the "live" windows, so the live branch never
                    // sees them — handle closing captures from here.
                    maybeCaptureClosing()
                } else {
                    when (val result = SettradeRepository.fetchLiveSetIndex()) {
                        is SettradeRepository.FetchResult.Success -> {
                            lastKnownTwoD = result.data.twoD
                            updateNotification(lastKnownTwoD, isLive = true)
                            maybeCapture(result.data)
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

    /**
     * 3D (Thai GLO) draw-day polling, so the 3D number lands on its own —
     * no need for anyone to open the app or sit on the 3D tab. The main
     * loop ticks every 20s; on draw days (1st/16th) from 14:30 local this
     * checks the official GLO API about once a minute until this period's
     * result is frozen, then stops by itself. (Before this, nothing polled
     * GLO in the background at all: the number only updated while the app
     * was open — the 15-minute in-app loop — which is why it could sit
     * unposted until ~4 PM even though the result was out at ~3:17.)
     */
    private suspend fun maybePollGlo() {
        try {
            if (!GloRepository.isDrawPending()) return
            if (GloRepository.hasCurrentPeriodResult(applicationContext)) return
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastGloAttemptMs < GLO_POLL_INTERVAL_MS) return
            lastGloAttemptMs = nowMs
            GloRepository.fetchAndFreeze(applicationContext)
        } catch (ignored: Exception) {
        }
    }

    /**
     * Slot capture, owned by this always-running service so captures happen
     * whether or not anyone opens the app. The exact alarms
     * (CaptureAlarmReceiver) run the same rules as a backup path; whichever
     * records first wins and the other path sees the slot already stored.
     *
     * Opening slots (9:30 / 2:00) record only once the fetched numbers are
     * provably the NEW session's (see HistoryStore.isNewSessionData) — the
     * service polls every 20s, so a still-stale page just means "check again
     * on the next poll" instead of locking in the previous session's finals.
     * Closing slots record at the official close + 5s (12:01:05 / 16:30:05),
     * when the finals are frozen and the standard app confirms them.
     */
    private fun maybeCapture(data: SettradeRepository.LiveMarketData) {
        try {
            val ctx = applicationContext
            val nowCal = NetworkTime.now()
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(nowCal.time)
            val capturedAt = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(nowCal.time)
            val nowSeconds = MarketSchedule.secondsSinceMidnight(
                nowCal.get(Calendar.HOUR_OF_DAY), nowCal.get(Calendar.MINUTE), nowCal.get(Calendar.SECOND)
            )
            captureOpeningSlot(
                ctx, today, HistoryStore.SLOT_0930,
                MarketSchedule.secondsSinceMidnight(MarketSchedule.MORNING_START_H, MarketSchedule.MORNING_START_M, MarketSchedule.MORNING_START_S),
                data, capturedAt, nowSeconds
            )
            captureOpeningSlot(
                ctx, today, HistoryStore.SLOT_1400,
                MarketSchedule.secondsSinceMidnight(MarketSchedule.REOPEN_H, MarketSchedule.REOPEN_M, MarketSchedule.REOPEN_S),
                data, capturedAt, nowSeconds
            )
            captureClosingSlot(
                ctx, today, HistoryStore.SLOT_1201,
                MarketSchedule.secondsSinceMidnight(MarketSchedule.SLOT_1_H, MarketSchedule.SLOT_1_M, MarketSchedule.SLOT_1_S),
                data, capturedAt, nowSeconds
            )
            captureClosingSlot(
                ctx, today, HistoryStore.SLOT_1630,
                MarketSchedule.secondsSinceMidnight(MarketSchedule.SLOT_2_H, MarketSchedule.SLOT_2_M, MarketSchedule.SLOT_2_S),
                data, capturedAt, nowSeconds
            )
        } catch (ignored: Exception) {
        }
    }

    /** Closing slots only — called from the not-live branch around 12:03 / 16:32. */
    private suspend fun maybeCaptureClosing() {
        try {
            val ctx = applicationContext
            val nowCal = NetworkTime.now()
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(nowCal.time)
            val nowSeconds = MarketSchedule.secondsSinceMidnight(
                nowCal.get(Calendar.HOUR_OF_DAY), nowCal.get(Calendar.MINUTE), nowCal.get(Calendar.SECOND)
            )
            val slot1Target = MarketSchedule.secondsSinceMidnight(MarketSchedule.SLOT_1_H, MarketSchedule.SLOT_1_M, MarketSchedule.SLOT_1_S)
            val slot2Target = MarketSchedule.secondsSinceMidnight(MarketSchedule.SLOT_2_H, MarketSchedule.SLOT_2_M, MarketSchedule.SLOT_2_S)
            val slot1Due = nowSeconds in slot1Target..(slot1Target + CLOSE_CAPTURE_WINDOW_SECONDS) &&
                HistoryStore.getSlot(ctx, today, HistoryStore.SLOT_1201) == null
            val slot2Due = nowSeconds in slot2Target..(slot2Target + CLOSE_CAPTURE_WINDOW_SECONDS) &&
                HistoryStore.getSlot(ctx, today, HistoryStore.SLOT_1630) == null
            if (!slot1Due && !slot2Due) return
            when (val result = SettradeRepository.fetchLiveSetIndex()) {
                is SettradeRepository.FetchResult.Success -> {
                    val capturedAt = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(NetworkTime.now().time)
                    if (slot1Due) {
                        HistoryStore.recordSlotIfAbsent(ctx, today, HistoryStore.SLOT_1201, result.data.set, result.data.value, capturedAt)
                    }
                    if (slot2Due) {
                        HistoryStore.recordSlotIfAbsent(ctx, today, HistoryStore.SLOT_1630, result.data.set, result.data.value, capturedAt)
                    }
                }
                is SettradeRepository.FetchResult.Failure -> {}
            }
        } catch (ignored: Exception) {
        }
    }

    private fun captureOpeningSlot(
        context: Context, today: String, slotKey: String, targetSeconds: Int,
        data: SettradeRepository.LiveMarketData, capturedAt: String, nowSeconds: Int
    ) {
        if (nowSeconds < targetSeconds || nowSeconds > targetSeconds + OPEN_CAPTURE_WINDOW_SECONDS) return
        if (HistoryStore.getSlot(context, today, slotKey) != null) return
        val baseline = HistoryStore.previousSessionFinal(context, today, slotKey)
        if (!HistoryStore.isNewSessionData(data.set, data.value, baseline)) return
        HistoryStore.recordSlotIfAbsent(context, today, slotKey, data.set, data.value, capturedAt)
    }

    private fun captureClosingSlot(
        context: Context, today: String, slotKey: String, targetSeconds: Int,
        data: SettradeRepository.LiveMarketData, capturedAt: String, nowSeconds: Int
    ) {
        if (nowSeconds < targetSeconds || nowSeconds > targetSeconds + CLOSE_CAPTURE_WINDOW_SECONDS) return
        if (HistoryStore.getSlot(context, today, slotKey) != null) return
        HistoryStore.recordSlotIfAbsent(context, today, slotKey, data.set, data.value, capturedAt)
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

        // How long after an opening (9:30 / 2:00) the service keeps trying to
        // catch the first new-session numbers, and how long after a closing
        // target (12:03 / 16:32) a closing capture stays valid.
        const val OPEN_CAPTURE_WINDOW_SECONDS = 600
        const val CLOSE_CAPTURE_WINDOW_SECONDS = 1800

        // 3D draw-day polling cadence (see maybePollGlo).
        const val GLO_POLL_INTERVAL_MS = 60_000L

        private const val PREFS_NAME = "myanmar2d_settings"
        private const val KEY_ENABLED = "live_notification_enabled"

        fun isEnabledPref(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true)

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
