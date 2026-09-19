package com.example.myanmar2d

import android.content.Context

object HistoryStore {

    private const val PREFS_NAME = "myanmar2d_history"
    private const val KEY_DATES = "dates"

    const val SLOT_1201 = "1201"
    const val SLOT_1630 = "1630"
    const val SLOT_0930 = "0930"
    const val SLOT_1400 = "1400"

    data class StoredSlot(val set: Double, val value: Double, val capturedAt: String) {
        val twoD: String get() = calculate2D(set, value)
    }

    fun recordSlot(context: Context, date: String, slotKey: String, set: Double, value: Double, capturedAt: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dates = (prefs.getStringSet(KEY_DATES, emptySet()) ?: emptySet()).toMutableSet()
        dates.add(date)
        prefs.edit()
            .putStringSet(KEY_DATES, dates)
            .putString(slotRecordKey(date, slotKey), "$set|$value|$capturedAt")
            .apply()
    }

    fun getSlot(context: Context, date: String, slotKey: String): StoredSlot? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(slotRecordKey(date, slotKey), null) ?: return null
        val parts = raw.split("|")
        if (parts.size != 3) return null
        val set = parts[0].toDoubleOrNull() ?: return null
        val value = parts[1].toDoubleOrNull() ?: return null
        return StoredSlot(set, value, parts[2])
    }

    fun getAllDates(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dates = prefs.getStringSet(KEY_DATES, emptySet()) ?: emptySet()
        return dates.sortedDescending()
    }

    private fun slotRecordKey(date: String, slotKey: String) = "slot|$date|$slotKey"
}
