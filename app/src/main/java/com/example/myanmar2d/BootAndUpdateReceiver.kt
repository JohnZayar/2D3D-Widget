package com.example.myanmar2d

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Android automatically cancels all scheduled alarms whenever:
 *  - the device reboots, or
 *  - this app is updated/reinstalled (a new APK is installed over the old one)
 *
 * Without this receiver, the daily capture alarms (9:30/12:01/2:00/4:30)
 * would silently stop firing after any of those events, UNTIL the user
 * happened to open the app again (which is exactly what should NOT be
 * required - the whole point is that capture works on its own schedule
 * regardless of whether the app is ever opened).
 *
 * This receiver re-schedules everything automatically, the moment the
 * device finishes booting or the app finishes updating - no need to open
 * the app at all.
 */
class BootAndUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                AlarmScheduler.scheduleAll(context.applicationContext)
            }
        }
    }
}
