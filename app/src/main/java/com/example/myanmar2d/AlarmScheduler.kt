package com.example.myanmar2d

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

object AlarmScheduler {

    const val EXTRA_SLOT_KEY = "slot_key"
    const val EXTRA_HOUR = "hour"
    const val EXTRA_MINUTE = "minute"
    const val EXTRA_SECOND = "second"
    const val EXTRA_IS_RETRY = "is_retry"
    const val EXTRA_RETRY_ATTEMPT = "retry_attempt"

    private const val REQUEST_CODE_SLOT_1 = 1201
    private const val REQUEST_CODE_SLOT_2 = 1630

    // Retry alarms use a separate request-code range so they never collide with the
    // daily 12:01 / 4:30 alarms above.
    private const val RETRY_BASE_SLOT_1 = 91201
    private const val RETRY_BASE_SLOT_2 = 91630

    const val MAX_RETRIES = 6
    const val RETRY_INTERVAL_MINUTES = 5

    fun scheduleAll(context: Context) {
        schedule(context, HistoryStore.SLOT_1201, 12, 1, 4, REQUEST_CODE_SLOT_1)
        schedule(context, HistoryStore.SLOT_1630, 16, 30, 4, REQUEST_CODE_SLOT_2)
    }

    private fun schedule(context: Context, slotKey: String, hour: Int, minute: Int, second: Int, requestCode: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val trigger = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, 0)
        }
        if (trigger.timeInMillis <= System.currentTimeMillis()) {
            trigger.add(Calendar.DAY_OF_YEAR, 1)
        }

        val intent = Intent(context, CaptureAlarmReceiver::class.java).apply {
            putExtra(EXTRA_SLOT_KEY, slotKey)
            putExtra(EXTRA_HOUR, hour)
            putExtra(EXTRA_MINUTE, minute)
            putExtra(EXTRA_SECOND, second)
            putExtra(EXTRA_IS_RETRY, false)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.timeInMillis, pendingIntent)
        } catch (e: SecurityException) {
        }
    }

    /**
     * Schedules one retry attempt [RETRY_INTERVAL_MINUTES] minutes from now, if a capture
     * failed and we haven't exceeded [MAX_RETRIES] yet. Uses a request-code range separate
     * from the daily alarms so it never overwrites/cancels them.
     */
    fun scheduleRetry(context: Context, slotKey: String, hour: Int, minute: Int, second: Int, attempt: Int) {
        if (attempt > MAX_RETRIES) return

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAtMillis = System.currentTimeMillis() + RETRY_INTERVAL_MINUTES * 60_000L

        val intent = Intent(context, CaptureAlarmReceiver::class.java).apply {
            putExtra(EXTRA_SLOT_KEY, slotKey)
            putExtra(EXTRA_HOUR, hour)
            putExtra(EXTRA_MINUTE, minute)
            putExtra(EXTRA_SECOND, second)
            putExtra(EXTRA_IS_RETRY, true)
            putExtra(EXTRA_RETRY_ATTEMPT, attempt)
        }
        val retryBase = if (slotKey == HistoryStore.SLOT_1201) RETRY_BASE_SLOT_1 else RETRY_BASE_SLOT_2
        val requestCode = retryBase + attempt

        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        } catch (e: SecurityException) {
        }
    }
}
