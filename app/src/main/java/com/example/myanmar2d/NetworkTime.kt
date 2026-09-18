package com.example.myanmar2d

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Provides "now" corrected against a trusted server clock instead of the
 * phone's own (sometimes wrong, or wrong-timezone) system clock.
 *
 * Every successful HTTP fetch in [SettradeRepository] reads the standard
 * HTTP "Date" response header (which every web server sends, in UTC) and
 * calls [updateFromHeader] with it. From that we compute how far the
 * phone's own clock is off (`offsetMs`), and apply that same correction
 * whenever [now] is asked for - always expressed in Myanmar time
 * (Asia/Yangon, UTC+6:30) regardless of what timezone the phone itself is
 * set to.
 *
 * Until the first successful fetch happens (e.g. right after app launch,
 * or if the device has no network at all), [now] simply falls back to the
 * phone's own clock/timezone converted as best-effort - see [isSynced].
 */
object NetworkTime {

    @Volatile private var offsetMs: Long = 0L
    @Volatile private var lastSyncAtMs: Long = 0L

    private val yangon = TimeZone.getTimeZone("Asia/Yangon")

    private val httpDateFormat: SimpleDateFormat by lazy {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
    }

    /** Call this with the raw "Date" HTTP response header after any network fetch. */
    @Synchronized
    fun updateFromHeader(dateHeader: String?) {
        if (dateHeader.isNullOrBlank()) return
        try {
            val serverMillis = httpDateFormat.parse(dateHeader)?.time ?: return
            offsetMs = serverMillis - System.currentTimeMillis()
            lastSyncAtMs = System.currentTimeMillis()
        } catch (e: Exception) {
            // Unparseable header - ignore, keep the previous offset (or 0).
        }
    }

    /** True once at least one successful network time-sync has happened. */
    fun isSynced(): Boolean = lastSyncAtMs != 0L

    /** Current time, corrected to the server clock, always in Myanmar time. */
    fun now(): Calendar {
        val cal = Calendar.getInstance(yangon)
        cal.timeInMillis = System.currentTimeMillis() + offsetMs
        return cal
    }
}
