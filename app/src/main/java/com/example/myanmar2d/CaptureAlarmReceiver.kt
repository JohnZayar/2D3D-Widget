package com.example.myanmar2d

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class CaptureAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val slotKey = intent.getStringExtra(AlarmScheduler.EXTRA_SLOT_KEY) ?: return
        val hour = intent.getIntExtra(AlarmScheduler.EXTRA_HOUR, 0)
        val minute = intent.getIntExtra(AlarmScheduler.EXTRA_MINUTE, 0)
        val second = intent.getIntExtra(AlarmScheduler.EXTRA_SECOND, 0)
        val isRetry = intent.getBooleanExtra(AlarmScheduler.EXTRA_IS_RETRY, false)
        val retryAttempt = intent.getIntExtra(AlarmScheduler.EXTRA_RETRY_ATTEMPT, 0)

        val appContext = context.applicationContext
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                val today = dateFmt.format(Calendar.getInstance().time)

                val alreadyCaptured = HistoryStore.getSlot(appContext, today, slotKey) != null

                if (!alreadyCaptured) {
                    when (val result = SettradeRepository.fetchLiveSetIndex()) {
                        is SettradeRepository.FetchResult.Success -> {
                            val capturedAt = timeFmt.format(Calendar.getInstance().time)
                            HistoryStore.recordSlot(
                                appContext, today, slotKey,
                                result.data.set, result.data.value, capturedAt
                            )
                        }
                        is SettradeRepository.FetchResult.Failure -> {
                            // Still not captured - try again in a few minutes, up to MAX_RETRIES,
                            // so a single network hiccup can never permanently skip a draw.
                            AlarmScheduler.scheduleRetry(
                                appContext, slotKey, hour, minute, second, retryAttempt + 1
                            )
                        }
                    }
                }

                // Only push tomorrow's alarm on the original (non-retry) fire, so in-flight
                // retries for today don't keep getting overwritten.
                if (!isRetry) {
                    AlarmScheduler.scheduleAll(appContext)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
