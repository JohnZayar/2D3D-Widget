package com.example.myanmar2d

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Draws a thin, full-width, always-on-top bar at the very top of the
 * screen (over whatever app is currently open) showing a live-scrolling
 * line of Set / Value / 2D / Thai lottery data - similar to
 * [LiveTickerBar] inside the app itself, but visible everywhere.
 *
 * Requires the "Display over other apps" permission, which the user must
 * grant manually from Settings (Android does not allow requesting it via
 * a normal runtime dialog) - see [MainActivity] for the permission flow.
 *
 * Android still requires a small mandatory notification for any running
 * foreground service; that notification is kept as low-priority/minimal
 * as the platform allows.
 */
class OverlayTickerService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var windowManager: WindowManager? = null
    private var tickerView: TextView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scrollRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setEnabledPref(applicationContext, false)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildServiceNotification())
        showOverlay()
        startUpdating()
        return START_STICKY
    }

    private fun showOverlay() {
        if (tickerView != null) return

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val barHeightPx = (26 * resources.displayMetrics.density).toInt()

        // System ရဲ့ တကယ့် status bar (နာရီ/battery) အမြင့်ကို တိုင်းပြီး၊
        // ကျွန်တော်တို့ bar ကို အဲဒီ အောက်ကနေမှ စတင်ပြရန် y offset ချမယ်
        val statusBarResId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarHeightPx = if (statusBarResId > 0) {
            resources.getDimensionPixelSize(statusBarResId)
        } else {
            (24 * resources.displayMetrics.density).toInt()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            barHeightPx,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = statusBarHeightPx // status bar ရဲ့ တိုက်ရိုက်အောက်ကနေ စတင်မည်
        }

        val tv = TextView(this).apply {
            setBackgroundColor(Color.parseColor("#FFFFE600")) // app ရဲ့ အဝါရောင် theme အတိုင်း
            setTextColor(Color.BLACK)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setSingleLine(true)
            setHorizontallyScrolling(true)
            ellipsize = null
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 4, 24, 4)
            text = "Myanmar 2D - Live bar စတင်နေသည်..."
        }

        tickerView = tv
        try {
            windowManager?.addView(tv, params)
            startCustomMarquee(tv)
        } catch (e: Exception) {
            // Permission not actually granted (or revoked) - stop cleanly
            // rather than crash the whole service.
            stopSelf()
        }
    }

    /**
     * Manual continuous right-to-left scroll using View.scrollTo(), which
     * (unlike Android's built-in ellipsize=MARQUEE) is never reset by a
     * setText() call - so refreshing the ticker's text every poll interval
     * no longer interrupts or restarts the scroll animation.
     */
    private fun startCustomMarquee(tv: TextView) {
        val stepPx = (1.6f * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val intervalMs = 16L // ~60fps

        tv.post {
            tv.scrollTo(-tv.width.coerceAtLeast(1), 0) // စတင်ချိန်မှာ ညာဘက်က off-screen ကနေ ဝင်လာမည်
        }

        scrollRunnable = object : Runnable {
            override fun run() {
                val textWidth = tv.layout?.getLineWidth(0)?.toInt()
                    ?: tv.paint.measureText(tv.text.toString()).toInt()
                val viewWidth = tv.width
                if (viewWidth > 0) {
                    var newScrollX = tv.scrollX + stepPx
                    if (newScrollX > textWidth) {
                        newScrollX = -viewWidth // ဘယ်ဘက်ကနေ လုံးဝထွက်သွားရင် ညာဘက်က ပြန်ဝင်လာမည်
                    }
                    tv.scrollTo(newScrollX, 0)
                }
                mainHandler.postDelayed(this, intervalMs)
            }
        }
        mainHandler.post(scrollRunnable!!)
    }

    private fun startUpdating() {
        scope.launch {
            while (true) {
                val text = buildTickerText()
                withContext(Dispatchers.Main) {
                    tickerView?.text = text
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private val greenHighlight = Color.parseColor("#4CAF50")

    /** Same highlight rule as the in-app LiveTickerBar: hundredths digit for Set, last integer digit for Value. */
    private fun appendHighlightedSet(sb: SpannableStringBuilder, setText: String) {
        val parts = setText.split('.')
        if (parts.size > 1 && parts[1].length >= 2) {
            sb.append(parts[0]).append(".").append(parts[1].substring(0, 1))
            val start = sb.length
            sb.append(parts[1].substring(1, 2))
            sb.setSpan(ForegroundColorSpan(greenHighlight), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (parts[1].length > 2) sb.append(parts[1].substring(2))
        } else {
            sb.append(setText)
        }
    }

    private fun appendHighlightedValue(sb: SpannableStringBuilder, valueText: String) {
        val parts = valueText.split('.')
        val intPart = parts.getOrNull(0).orEmpty()
        if (parts.size > 1 && intPart.isNotEmpty()) {
            sb.append(intPart.substring(0, intPart.length - 1))
            val start = sb.length
            sb.append(intPart.substring(intPart.length - 1))
            sb.setSpan(ForegroundColorSpan(greenHighlight), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(".").append(parts[1])
        } else {
            sb.append(valueText)
        }
    }

    private suspend fun buildTickerText(): CharSequence {
        val thai = GloRepository.readCached(applicationContext)
        val thaiText = thai?.firstPrize ?: "------"

        val dateFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        val nowCal = NetworkTime.now()
        val today = dateFmt.format(nowCal.time)
        val slot0930 = HistoryStore.getSlot(applicationContext, today, HistoryStore.SLOT_0930)
        val slot1400 = HistoryStore.getSlot(applicationContext, today, HistoryStore.SLOT_1400)
        val slot1201 = HistoryStore.getSlot(applicationContext, today, HistoryStore.SLOT_1201)
        val slot1630 = HistoryStore.getSlot(applicationContext, today, HistoryStore.SLOT_1630)
        val slot0930Text = slot0930?.let { calculate2D(it.set, it.value) } ?: "--"
        val slot1400Text = slot1400?.let { calculate2D(it.set, it.value) } ?: "--"
        val slot1201Text = slot1201?.let { calculate2D(it.set, it.value) } ?: "--"
        val slot1630Text = slot1630?.let { calculate2D(it.set, it.value) } ?: "--"

        val nowSeconds = MarketSchedule.secondsSinceMidnight(
            nowCal.get(java.util.Calendar.HOUR_OF_DAY),
            nowCal.get(java.util.Calendar.MINUTE),
            nowCal.get(java.util.Calendar.SECOND)
        )

        // App ရဲ့ Top Ticker Bar အတိုင်းပဲ - break period မှာ SET/Value/2D
        // live ဂဏန်းတွေ ရပ်ပြီး "Market Break" ပြပါမည် (ဒါပေမဲ့ အတည်ပြုပြီးသား
        // 12:01/4:30 ဂဏန်းတွေကတော့ Live ပျောက်သွားလည်း ဆက်ပြနေရမည်)
        if (!MarketSchedule.isMarketLiveNow(nowSeconds)) {
            val sb = SpannableStringBuilder()
            sb.append("9:30AM ").append(slot0930Text)
            sb.append("   |   12:01PM ").append(slot1201Text)
            sb.append("   |   2PM ").append(slot1400Text)
            sb.append("   |   4:30PM ").append(slot1630Text)
            sb.append("   |   Market Break \u2022 Reopens ").append(MarketSchedule.reopenLabel(nowSeconds))
            sb.append("   |   Thai ").append(thaiText)
            return sb
        }

        val sb = SpannableStringBuilder()
        when (val result = SettradeRepository.fetchLiveSetIndex()) {
            is SettradeRepository.FetchResult.Success -> {
                val d = result.data
                sb.append("9:30AM ").append(slot0930Text)
                sb.append("   |   12:01PM ").append(slot1201Text)
                sb.append("   |   SET ")
                appendHighlightedSet(sb, "%.2f".format(d.set))
                sb.append("   |   Value ")
                appendHighlightedValue(sb, "%,.2f".format(d.value))
                sb.append("   |   2D ").append(d.twoD)
                sb.append("   |   2PM ").append(slot1400Text)
                sb.append("   |   4:30PM ").append(slot1630Text)
                sb.append("   |   Thai ").append(thaiText)
                sb.append("   |   Live")
            }
            is SettradeRepository.FetchResult.Failure -> {
                sb.append("9:30AM ").append(slot0930Text)
                sb.append("   |   12:01PM ").append(slot1201Text)
                sb.append("   |   SET --   |   Value --")
                sb.append("   |   2PM ").append(slot1400Text)
                sb.append("   |   4:30PM ").append(slot1630Text)
                sb.append("   |   Thai ").append(thaiText)
                sb.append("   |   ခဏစောင့်ပါ...")
            }
        }
        return sb
    }

    private fun buildServiceNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Myanmar 2D Live Bar အလုပ်လုပ်နေသည်")
            .setContentText("ပိတ်ရန် App ထဲက Switch ကို off ပါ")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live Bar Service",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Screen ထိပ်ဆုံးက Live bar ကို run နေစေရန်"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        scrollRunnable?.let { mainHandler.removeCallbacks(it) }
        scrollRunnable = null
        tickerView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                // View was already removed / never attached - ignore.
            }
        }
        tickerView = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "overlay_ticker_channel"
        const val NOTIFICATION_ID = 1002
        const val ACTION_STOP = "com.example.myanmar2d.OVERLAY_STOP"

        // Overlay is meant to feel closer to real-time than the pull-down
        // notification, but still avoids hammering the source site.
        const val POLL_INTERVAL_MS = 10_000L

        private const val PREFS_NAME = "myanmar2d_settings"
        private const val KEY_ENABLED = "overlay_bar_enabled"

        fun isEnabledPref(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)

        fun setEnabledPref(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply()
        }

        fun start(context: Context) {
            setEnabledPref(context, true)
            val intent = Intent(context, OverlayTickerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            setEnabledPref(context, false)
            val intent = Intent(context, OverlayTickerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
