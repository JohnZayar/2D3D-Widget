package com.example.myanmar2d

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import java.util.Calendar

object AlarmScheduler {

    const val EXTRA_SLOT_KEY = "slot_key"
    const val EXTRA_HOUR = "hour"
    const val EXTRA_MINUTE = "minute"
    const val EXTRA_SECOND = "second"
    const val EXTRA_ATTEMPT = "attempt"

    private const val REQUEST_CODE_SLOT_1 = 1201
    private const val REQUEST_CODE_SLOT_2 = 1630
    private const val REQUEST_CODE_SLOT_0930 = 930
    private const val REQUEST_CODE_SLOT_1400 = 1400
    private const val RETRY_CODE_OFFSET = 5000

    fun scheduleAll(context: Context) {
        // Single source of truth: MarketSchedule. (Previously these were
        // hardcoded here with slightly different seconds, drifting out of
        // sync with the in-app schedule.)
        schedule(context, HistoryStore.SLOT_1201, MarketSchedule.SLOT_1_H, MarketSchedule.SLOT_1_M, MarketSchedule.SLOT_1_S, REQUEST_CODE_SLOT_1)
        schedule(context, HistoryStore.SLOT_1630, MarketSchedule.SLOT_2_H, MarketSchedule.SLOT_2_M, MarketSchedule.SLOT_2_S, REQUEST_CODE_SLOT_2)
        schedule(context, HistoryStore.SLOT_0930, MarketSchedule.MORNING_START_H, MarketSchedule.MORNING_START_M, MarketSchedule.MORNING_START_S, REQUEST_CODE_SLOT_0930)
        schedule(context, HistoryStore.SLOT_1400, MarketSchedule.REOPEN_H, MarketSchedule.REOPEN_M, MarketSchedule.REOPEN_S, REQUEST_CODE_SLOT_1400)
    }

    private fun requestCodeFor(slotKey: String): Int = when (slotKey) {
        HistoryStore.SLOT_1201 -> REQUEST_CODE_SLOT_1
        HistoryStore.SLOT_1630 -> REQUEST_CODE_SLOT_2
        HistoryStore.SLOT_0930 -> REQUEST_CODE_SLOT_0930
        HistoryStore.SLOT_1400 -> REQUEST_CODE_SLOT_1400
        else -> slotKey.hashCode()
    }

    /**
     * Sets an elapsed-realtime alarm, preferring exact delivery.
     *
     * The exact-alarm permissions (USE_EXACT_ALARM / SCHEDULE_EXACT_ALARM)
     * are now declared in the manifest, so exact alarms normally work.
     * But if exact delivery is still unavailable on this device (permission
     * revoked, OEM quirk), fall back to an inexact alarm instead of the old
     * behaviour — a silently swallowed SecurityException that left NO alarm
     * scheduled at all, which is why captures only ever happened while the
     * app happened to be open.
     */
    private fun setAlarm(context: Context, triggerAtElapsedMs: Long, pendingIntent: PendingIntent) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()
        try {
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsedMs, pendingIntent
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsedMs, pendingIntent
                )
            }
        } catch (e: SecurityException) {
            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsedMs, pendingIntent
                )
            } catch (ignored: Exception) {
            }
        } catch (ignored: Exception) {
        }
    }

    private fun schedule(context: Context, slotKey: String, hour: Int, minute: Int, second: Int, requestCode: Int) {
        // ဖုန်းရဲ့ wall-clock/timezone မှန်မမှန် မငဲ့ဘဲ (ဖုန်းတလုံးနဲ့တလုံး
        // clock မတူလို့ capture အချိန်ကွဲတာကို ဖြေရှင်းရန်) - NetworkTime ရဲ့
        // sync ထားတဲ့ true time ကို အခြေခံပြီး "ဘယ်နှစ်စက္ကန့်ကြာမှ" ဆိုတာသာ
        // တွက်ပြီး elapsed-realtime alarm (device clock ပြောင်းနေလည်း မထိခိုက်) နဲ့ schedule မယ်
        val trueNow = NetworkTime.now()
        val trigger = (trueNow.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, 0)
        }
        if (trigger.timeInMillis <= trueNow.timeInMillis) {
            trigger.add(Calendar.DAY_OF_YEAR, 1)
        }
        val msUntilTrigger = trigger.timeInMillis - trueNow.timeInMillis

        val intent = Intent(context, CaptureAlarmReceiver::class.java).apply {
            putExtra(EXTRA_SLOT_KEY, slotKey)
            putExtra(EXTRA_HOUR, hour)
            putExtra(EXTRA_MINUTE, minute)
            putExtra(EXTRA_SECOND, second)
            putExtra(EXTRA_ATTEMPT, 0)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        setAlarm(context, SystemClock.elapsedRealtime() + msUntilTrigger, pendingIntent)
    }

    /**
     * One-shot retry for a slot whose capture just failed (network) or saw
     * only the previous session's stale numbers (opening slots). Uses a
     * separate request code so it never disturbs the daily schedule, which
     * [scheduleAll] re-arms after every receiver run anyway.
     */
    fun scheduleRetry(context: Context, slotKey: String, delaySeconds: Long, attempt: Int) {
        val intent = Intent(context, CaptureAlarmReceiver::class.java).apply {
            putExtra(EXTRA_SLOT_KEY, slotKey)
            putExtra(EXTRA_ATTEMPT, attempt)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCodeFor(slotKey) + RETRY_CODE_OFFSET, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        setAlarm(context, SystemClock.elapsedRealtime() + delaySeconds * 1000L, pendingIntent)
    }
}
