package com.example.myanmar2d

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class CaptureAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val slotKey = intent.getStringExtra(AlarmScheduler.EXTRA_SLOT_KEY) ?: return
        val attempt = intent.getIntExtra(AlarmScheduler.EXTRA_ATTEMPT, 0)
        val appContext = context.applicationContext
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                val today = dateFmt.format(NetworkTime.now().time)

                if (HistoryStore.getSlot(appContext, today, slotKey) == null) {
                    when (val result = SettradeRepository.fetchLiveSetIndex()) {
                        is SettradeRepository.FetchResult.Success -> {
                            val isOpenSlot = slotKey == HistoryStore.SLOT_0930 ||
                                slotKey == HistoryStore.SLOT_1400
                            // Opening slots: the page still shows the previous
                            // session's frozen finals for a while after the open
                            // (that lag is why 9:30 / 2:00 were "never correct").
                            // Don't record stale numbers — wait and retry until
                            // the page starts publishing the new session, which
                            // the changed trading Value reliably signals.
                            val baseline = if (isOpenSlot) {
                                HistoryStore.previousSessionFinal(appContext, today, slotKey)
                            } else {
                                null
                            }
                            if (isOpenSlot && !HistoryStore.isNewSessionData(result.data.set, result.data.value, baseline)) {
                                if (attempt < MAX_ATTEMPTS) {
                                    AlarmScheduler.scheduleRetry(
                                        appContext, slotKey, RETRY_DELAY_SECONDS, attempt + 1
                                    )
                                }
                            } else {
                                // fetchLiveSetIndex() ရဲ့ HTTP fetch က NetworkTime ကို
                                // sync ထားပြီးသားမို့ ဒီအချိန်ဟာ true time ဖြစ်ပါတယ်
                                val capturedAt = timeFmt.format(NetworkTime.now().time)
                                // recordSlotIfAbsent - app ကိုဖွင့်ထားနေချိန် foreground loop
                                // ကလည်း တစ်ပြိုင်နက် capture ဖြစ်နိုင်လို့ ဘယ်ဟာအရင်ရောက်ရောက်
                                // အနိုင်ရအောင် atomic check လုပ်ပါတယ်
                                HistoryStore.recordSlotIfAbsent(
                                    appContext, today, slotKey,
                                    result.data.set, result.data.value, capturedAt
                                )
                            }
                        }
                        is SettradeRepository.FetchResult.Failure -> {
                            // Network hiccup at the capture moment shouldn't lose
                            // the slot for the whole day — retry a few times.
                            if (attempt < MAX_ATTEMPTS) {
                                AlarmScheduler.scheduleRetry(
                                    appContext, slotKey, RETRY_DELAY_SECONDS, attempt + 1
                                )
                            }
                        }
                    }
                }

                AlarmScheduler.scheduleAll(appContext)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        // 8 retries x 45s ≈ 6 minutes of patience — long enough for the
        // source page to start publishing a new session / for a network
        // hiccup to clear, short enough to stay near the slot moment.
        private const val MAX_ATTEMPTS = 8
        private const val RETRY_DELAY_SECONDS = 45L
    }
}
