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
                        is SettradeRepository.FetchResult.Failure -> {}
                    }
                }

                AlarmScheduler.scheduleAll(appContext)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
