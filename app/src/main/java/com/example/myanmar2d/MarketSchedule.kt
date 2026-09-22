package com.example.myanmar2d

/**
 * Single source of truth for the daily live/break schedule, so the in-app
 * ticker, the status-bar notification, and the overlay bar all agree on
 * exactly when the market is "live" vs "on break".
 */
object MarketSchedule {
    const val MORNING_START_H = 9
    const val MORNING_START_M = 30
    const val MORNING_START_S = 1

    const val SLOT_1_H = 12
    const val SLOT_1_M = 1
    const val SLOT_1_S = 5

    const val REOPEN_H = 14
    const val REOPEN_M = 0
    const val REOPEN_S = 1

    const val SLOT_2_H = 16
    const val SLOT_2_M = 30
    const val SLOT_2_S = 5

    fun secondsSinceMidnight(h: Int, m: Int, s: Int): Int = h * 3600 + m * 60 + s

    /** True only during the two live-trading windows: 9:30-12:01 and 14:00-16:30. */
    fun isMarketLiveNow(nowSeconds: Int): Boolean {
        val morningStart = secondsSinceMidnight(MORNING_START_H, MORNING_START_M, MORNING_START_S)
        val slot1Target = secondsSinceMidnight(SLOT_1_H, SLOT_1_M, SLOT_1_S)
        val reopen = secondsSinceMidnight(REOPEN_H, REOPEN_M, REOPEN_S)
        val slot2Target = secondsSinceMidnight(SLOT_2_H, SLOT_2_M, SLOT_2_S)
        return (nowSeconds in morningStart until slot1Target) || (nowSeconds in reopen until slot2Target)
    }

    /** What to show while paused/closed. */
    fun reopenLabel(nowSeconds: Int): String {
        val morningStart = secondsSinceMidnight(MORNING_START_H, MORNING_START_M, MORNING_START_S)
        val reopen = secondsSinceMidnight(REOPEN_H, REOPEN_M, REOPEN_S)
        return when {
            nowSeconds < morningStart -> "9:30 AM"
            nowSeconds < reopen -> "2:00 PM"
            else -> "9:30 AM"
        }
    }
}
