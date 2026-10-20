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

    /**
     * Writes only if nothing is recorded yet for this slot/date. The
     * background alarm (CaptureAlarmReceiver) and the in-app foreground
     * loop can both try to capture around the same real-world moment;
     * without this guard, whichever one's network fetch happens to finish
     * LAST would silently overwrite the other's (often more accurate,
     * closer-to-the-moment) result. This makes the FIRST successful
     * capture win, and returns whichever slot actually ended up stored so
     * the caller can stay in sync with it.
     */
    @Synchronized
    fun recordSlotIfAbsent(context: Context, date: String, slotKey: String, set: Double, value: Double, capturedAt: String): StoredSlot {
        val existing = getSlot(context, date, slotKey)
        if (existing != null) return existing
        recordSlot(context, date, slotKey, set, value, capturedAt)
        return StoredSlot(set, value, capturedAt)
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

    /**
     * The recorded finals of the session that ran immediately BEFORE the
     * session [slotKey] opens: for the 2:00 PM opening slot that is this
     * morning's 12:01 PM record; for the 9:30 AM opening slot it is
     * yesterday's 4:30 PM record. Null for closing slots and when no
     * earlier record exists.
     */
    fun previousSessionFinal(context: Context, date: String, slotKey: String): StoredSlot? {
        return try {
            when (slotKey) {
                SLOT_1400 -> getSlot(context, date, SLOT_1201)
                SLOT_0930 -> {
                    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                    val parsed = fmt.parse(date) ?: return null
                    val cal = java.util.Calendar.getInstance()
                    cal.time = parsed
                    cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
                    getSlot(context, fmt.format(cal.time), SLOT_1630)
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * True when [set]/[value] can only be NEW-session data. Right at the
     * 9:30 / 2:00 openings the source page keeps showing the previous
     * session's frozen finals for a while (its update lags the open, and
     * a captured-to-the-second snapshot of those stale numbers is exactly
     * the "never correct" bug). The trading Value restarts from near
     * zero every session, so once the page starts publishing the new
     * session the pair cannot equal the previous finals — that change,
     * not the wall-clock second, is the reliable capture signal.
     */
    fun isNewSessionData(set: Double, value: Double, baseline: StoredSlot?): Boolean {
        if (baseline == null) return true
        return set != baseline.set || value != baseline.value
    }

    private fun slotRecordKey(date: String, slotKey: String) = "slot|$date|$slotKey"
}
