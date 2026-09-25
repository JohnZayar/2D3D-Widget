package com.example.myanmar2d

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.util.Calendar

object AlarmScheduler {

    const val EXTRA_SLOT_KEY = "slot_key"
    const val EXTRA_HOUR = "hour"
    const val EXTRA_MINUTE = "minute"
    const val EXTRA_SECOND = "second"

    private const val REQUEST_CODE_SLOT_1 = 1201
    private const val REQUEST_CODE_SLOT_2 = 1630
    private const val REQUEST_CODE_SLOT_0930 = 930
    private const val REQUEST_CODE_SLOT_1400 = 1400

    fun scheduleAll(context: Context) {
        schedule(context, HistoryStore.SLOT_1201, 12, 1, 6, REQUEST_CODE_SLOT_1)
        schedule(context, HistoryStore.SLOT_1630, 16, 30, 6, REQUEST_CODE_SLOT_2)
        schedule(context, HistoryStore.SLOT_0930, 9, 30, 6, REQUEST_CODE_SLOT_0930)
        schedule(context, HistoryStore.SLOT_1400, 14, 0, 6, REQUEST_CODE_SLOT_1400)
    }

    private fun schedule(context: Context, slotKey: String, hour: Int, minute: Int, second: Int, requestCode: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

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
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + msUntilTrigger,
                pendingIntent
            )
        } catch (e: SecurityException) {
        }
    }
}
