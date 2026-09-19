package com.example.myanmar2d

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private val YellowTop = Color(0xFFFFE600)
private val RedCard = Color(0xFFF44336)
private val GreenPill = Color(0xFF4CAF50)
private val GoldGreen = Color(0xFF43A047)
private val TickerBg = Color(0xFF111111)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        AlarmScheduler.scheduleAll(this)
        // Notification permission (if needed) would already have been granted
        // when the user first turned this on, so it's safe to just restart it.
        if (TwoDForegroundService.isEnabledPref(this)) {
            TwoDForegroundService.start(this)
        }
        if (OverlayTickerService.isEnabledPref(this) && Settings.canDrawOverlays(this)) {
            OverlayTickerService.start(this)
        }
        setContent {
            MaterialTheme {
                AppRoot()
            }
        }
    }
}

private enum class Tab { HOME, RESULTS_2D, RESULTS_3D }

@Composable
private fun AppRoot() {
    var tab by remember { mutableStateOf(Tab.HOME) }
    var livePreview by remember { mutableStateOf<LivePreview?>(null) }
    var thaiLottery by remember { mutableStateOf<GloRepository.GloDrawResult?>(null) }
    val context = LocalContext.current
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    // Status bar ထဲ Live 2D notification ကို ပြ/မပြ toggle
    var notificationEnabled by remember { mutableStateOf(TwoDForegroundService.isEnabledPref(context)) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            TwoDForegroundService.start(context)
            notificationEnabled = true
        }
    }
    val onToggleNotification: (Boolean) -> Unit = { wantEnabled ->
        if (wantEnabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                TwoDForegroundService.start(context)
                notificationEnabled = true
            }
        } else {
            TwoDForegroundService.stop(context)
            notificationEnabled = false
        }
    }

    // Screen ထိပ်ဆုံးက Live Bar (overlay) ကို ပြ/မပြ toggle
    var overlayBarEnabled by remember { mutableStateOf(OverlayTickerService.isEnabledPref(context)) }
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // ပြန်ရောက်လာရင် permission တကယ်ရလား ပြန်စစ်မယ်
        if (Settings.canDrawOverlays(context)) {
            OverlayTickerService.start(context)
            overlayBarEnabled = true
        }
    }
    val onToggleOverlayBar: (Boolean) -> Unit = { wantEnabled ->
        if (wantEnabled) {
            if (Settings.canDrawOverlays(context)) {
                OverlayTickerService.start(context)
                overlayBarEnabled = true
            } else {
                val intent = android.content.Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                overlayPermissionLauncher.launch(intent)
            }
        } else {
            OverlayTickerService.stop(context)
            overlayBarEnabled = false
        }
    }

    val coroutineScope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }

    suspend fun refreshLiveDataNow() {
        when (val result = SettradeRepository.fetchLiveSetIndex()) {
            is SettradeRepository.FetchResult.Success -> {
                val now = NetworkTime.now()
                livePreview = LivePreview(
                    result.data.set, result.data.value,
                    dateFmt.format(now.time), timeFmt.format(now.time)
                )
            }
            is SettradeRepository.FetchResult.Failure -> {}
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            when (val result = SettradeRepository.fetchLiveSetIndex()) {
                is SettradeRepository.FetchResult.Success -> {
                    val now = NetworkTime.now()
                    livePreview = LivePreview(
                        result.data.set, result.data.value,
                        dateFmt.format(now.time), timeFmt.format(now.time)
                    )
                }
                is SettradeRepository.FetchResult.Failure -> {}
            }
            delay(4000)
        }
    }

    // Thai (GLO) lottery: show whatever is cached immediately (never blank on cold start),
    // then try to fetch/freeze this period's (1st/16th, phone's local clock) result.
    LaunchedEffect(Unit) {
        thaiLottery = GloRepository.readCached(context)

        while (true) {
            when (val result = GloRepository.fetchAndFreeze(context)) {
                is GloRepository.FetchResult.Success -> thaiLottery = result.data
                is GloRepository.FetchResult.Failure -> {} // keep showing whatever's cached
            }
            delay(15 * 60 * 1000L) // recheck every 15 minutes
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopBar(
                    current = tab,
                    onTabSelected = { tab = it },
                    notificationEnabled = notificationEnabled,
                    onToggleNotification = onToggleNotification,
                    overlayBarEnabled = overlayBarEnabled,
                    onToggleOverlayBar = onToggleOverlayBar
                )
                LiveTickerBar(
                    live = livePreview,
                    thai = thaiLottery,
                    marketLive = isMarketLiveNow(
                        secondsSinceMidnight(
                            NetworkTime.now().get(Calendar.HOUR_OF_DAY),
                            NetworkTime.now().get(Calendar.MINUTE),
                            NetworkTime.now().get(Calendar.SECOND)
                        )
                    )
                )
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.HOME -> HomeScreen(
                    livePreviewShared = livePreview,
                    isRefreshing = isRefreshing,
                    onManualRefresh = {
                        coroutineScope.launch {
                            isRefreshing = true
                            refreshLiveDataNow()
                            isRefreshing = false
                        }
                    }
                )
                Tab.RESULTS_2D -> ResultsList2D()
                Tab.RESULTS_3D -> ThaiHistoryScreen()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveTickerBar(live: LivePreview?, thai: GloRepository.GloDrawResult?, marketLive: Boolean) {
    val thaiLottery = thai?.firstPrize ?: "------"

    if (!marketLive) {
        val nowSecondsForLabel = secondsSinceMidnight(
            NetworkTime.now().get(Calendar.HOUR_OF_DAY),
            NetworkTime.now().get(Calendar.MINUTE),
            NetworkTime.now().get(Calendar.SECOND)
        )
        Surface(modifier = Modifier.fillMaxWidth(), color = TickerBg, shadowElevation = 4.dp) {
            Text(
                "Market Break  \u2022  ${marketReopenLabel(nowSecondsForLabel)}  |  Thai: $thaiLottery",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 12.sp,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).padding(horizontal = 12.dp)
            )
        }
        return
    }

    val setText = live?.let { "%.2f".format(it.set) } ?: "1569.49"
    val valueText = live?.let { "%,.2f".format(it.value) } ?: "54,381.15"

    val tickerText = buildAnnotatedString {
        append("Set ")
        // highlight the 2nd digit after Set's decimal point
        val setParts = setText.split('.')
        if (setParts.size > 1 && setParts[1].length >= 2) {
            append(setParts[0] + "." + setParts[1].substring(0, 1))
            withStyle(SpanStyle(color = GoldGreen, fontWeight = FontWeight.Bold)) {
                append(setParts[1].substring(1, 2))
            }
            if (setParts[1].length > 2) append(setParts[1].substring(2))
        } else {
            append(setText)
        }

        append("     |     Val ")
        // highlight the 1 digit right before Val's decimal point
        val valParts = valueText.split('.')
        val valInt = valParts.getOrNull(0).orEmpty()
        if (valParts.size > 1 && valInt.isNotEmpty()) {
            append(valInt.substring(0, valInt.length - 1))
            withStyle(SpanStyle(color = GoldGreen, fontWeight = FontWeight.Bold)) {
                append(valInt.substring(valInt.length - 1))
            }
            append("." + valParts[1])
        } else {
            append(valueText)
        }

        append("     |     Thai: $thaiLottery     |     ")
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = TickerBg,
        shadowElevation = 4.dp
    ) {
        Text(
            text = tickerText,
            color = Color.White,
            fontSize = 12.sp,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .basicMarquee(
                    iterations = Int.MAX_VALUE,
                    velocity = 40.dp
                )
        )
    }
}

@Composable
private fun ThaiHistoryScreen() {
    val context = LocalContext.current
    var history by remember { mutableStateOf(GloRepository.getHistory(context)) }
    var cached by remember { mutableStateOf(GloRepository.readCached(context)) }
    var isDrawDayLive by remember { mutableStateOf(false) }
    val keyFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }

    LaunchedEffect(Unit) {
        while (true) {
            val now = NetworkTime.now()
            val dayOfMonth = now.get(Calendar.DAY_OF_MONTH)
            val nowSeconds = secondsSinceMidnight(
                now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.SECOND)
            )
            val noon = secondsSinceMidnight(12, 0, 0)
            val isDrawDay = dayOfMonth == 1 || dayOfMonth == 16
            isDrawDayLive = isDrawDay && nowSeconds >= noon

            if (isDrawDayLive) {
                val todayKey = keyFmt.format(now.time)
                if (cached == null || cached?.drawDateKey != todayKey) {
                    when (val result = GloRepository.fetchAndFreeze(context)) {
                        is GloRepository.FetchResult.Success -> {
                            cached = result.data
                            history = GloRepository.getHistory(context)
                        }
                        is GloRepository.FetchResult.Failure -> {}
                    }
                }
            }
            delay(30_000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (isDrawDayLive) {
            val now = NetworkTime.now()
            val todayKey = keyFmt.format(now.time)
            val confirmedToday = cached?.takeIf { it.drawDateKey == todayKey }

            Surface(color = RedCard, modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(20.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (confirmedToday != null) {
                        Text(confirmedToday.last3, fontSize = 64.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFEB3B))
                        Spacer(Modifier.height(8.dp))
                        Text("Confirmed \u2022 $todayKey", fontSize = 13.sp, color = Color.White)
                    } else {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("Live \u2022 ရလဒ်ကို ခဏစောင့်ပါ", fontSize = 14.sp, color = Color.White)
                    }
                }
            }
        }

        if (history.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(40.dp))
                Text(
                    "Thai lottery ရလဒ် မရှိသေးပါ။ ၁ ရက် သို့မဟုတ် ၁၆ ရက်နေ့ ရလဒ်ထွက်တာနဲ့ ဒီနေရာမှာ ပေါ်လာပါလိမ့်မယ်။",
                    fontSize = 14.sp,
                    color = Color.DarkGray
                )
            }
            return@Column
        }

        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(history) { entry ->
                Surface(shape = RoundedCornerShape(16.dp), color = GreenPill, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(20.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LabeledValue("Date", entry.drawDateKey, valueColor = Color.White)
                        LabeledValue("3D", entry.last3, valueColor = Color(0xFFFFEB3B), big = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    current: Tab,
    onTabSelected: (Tab) -> Unit,
    notificationEnabled: Boolean,
    onToggleNotification: (Boolean) -> Unit,
    overlayBarEnabled: Boolean,
    onToggleOverlayBar: (Boolean) -> Unit
) {
    var showLiveSettings by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().background(YellowTop).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("Myanmar 2D", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                Text("v1.0", fontSize = 11.sp, color = Color.DarkGray)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onTabSelected(Tab.RESULTS_2D) }) { Text("2D", fontWeight = FontWeight.Bold) }
                TextButton(onClick = { onTabSelected(Tab.RESULTS_3D) }) { Text("3D", fontWeight = FontWeight.Bold, color = Color(0xFF1565C0)) }
                TextButton(onClick = { onTabSelected(Tab.HOME) }) { Text("Home") }

                Box {
                    IconButton(onClick = { showLiveSettings = true }) {
                        Text("\u2699", fontSize = 20.sp) // ⚙ gear
                    }
                    DropdownMenu(expanded = showLiveSettings, onDismissRequest = { showLiveSettings = false }) {
                        DropdownMenuItem(
                            text = { Text("Status bar Live (notification)", fontSize = 13.sp) },
                            onClick = { onToggleNotification(!notificationEnabled) },
                            trailingIcon = {
                                Switch(checked = notificationEnabled, onCheckedChange = onToggleNotification)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Screen ထိပ် Live Bar (overlay)", fontSize = 13.sp) },
                            onClick = { onToggleOverlayBar(!overlayBarEnabled) },
                            trailingIcon = {
                                Switch(checked = overlayBarEnabled, onCheckedChange = onToggleOverlayBar)
                            }
                        )
                    }
                }
            }
        }
    }
}

private data class CapturedSlot(
    val set: Double,
    val value: Double,
    val date: String,
    val capturedAt: String
) {
    val twoD: String get() = calculate2D(set, value)
}

private data class LivePreview(
    val set: Double,
    val value: Double,
    val date: String,
    val time: String
) {
    val twoD: String get() = calculate2D(set, value)
}

private const val MORNING_START_H = 9
private const val MORNING_START_M = 30
private const val MORNING_START_S = 0
private const val SLOT_1_H = 12
private const val SLOT_1_M = 1
private const val SLOT_1_S = 4
private const val REOPEN_H = 14
private const val REOPEN_M = 0
private const val REOPEN_S = 0
private const val SLOT_2_H = 16
private const val SLOT_2_M = 30
private const val SLOT_2_S = 4
private const val CAPTURE_GRACE_SECONDS = 1800

private fun secondsSinceMidnight(h: Int, m: Int, s: Int) = h * 3600 + m * 60 + s

/**
 * A gentle, continuous alpha pulse (not tied to the 4-second data refresh) used to signal
 * "still watching, live" on values that haven't changed - without any jarring jump/flash
 * when the underlying number is actually the same.
 */
@Composable
private fun rememberPulseAlpha(): Float {
    val infiniteTransition = rememberInfiniteTransition(label = "livePulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.88f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    return alpha
}

private enum class HeadlinePhase { IDLE_BEFORE_START, LIVE, FROZEN_MORNING, FROZEN_EVENING }

/** True only during the two live-trading windows: 9:30-12:01 and 14:00-16:30. */
private fun isMarketLiveNow(nowSeconds: Int): Boolean {
    val morningStart = secondsSinceMidnight(MORNING_START_H, MORNING_START_M, MORNING_START_S)
    val slot1Target = secondsSinceMidnight(SLOT_1_H, SLOT_1_M, SLOT_1_S)
    val reopen = secondsSinceMidnight(REOPEN_H, REOPEN_M, REOPEN_S)
    val slot2Target = secondsSinceMidnight(SLOT_2_H, SLOT_2_M, SLOT_2_S)
    return (nowSeconds in morningStart until slot1Target) || (nowSeconds in reopen until slot2Target)
}

/** What to tell the user while the ticker bar is paused/closed. */
private fun marketReopenLabel(nowSeconds: Int): String {
    val morningStart = secondsSinceMidnight(MORNING_START_H, MORNING_START_M, MORNING_START_S)
    val slot1Target = secondsSinceMidnight(SLOT_1_H, SLOT_1_M, SLOT_1_S)
    val reopen = secondsSinceMidnight(REOPEN_H, REOPEN_M, REOPEN_S)
    return when {
        nowSeconds < morningStart -> "Reopens 9:30 AM"
        nowSeconds < reopen -> "Reopens 2:00 PM"
        else -> "Reopens 9:30 AM"
    }
}

private fun currentHeadlinePhase(nowSeconds: Int, slot1: CapturedSlot?, slot2: CapturedSlot?): HeadlinePhase {
    val morningStart = secondsSinceMidnight(MORNING_START_H, MORNING_START_M, MORNING_START_S)
    val slot1Target = secondsSinceMidnight(SLOT_1_H, SLOT_1_M, SLOT_1_S)
    val reopen = secondsSinceMidnight(REOPEN_H, REOPEN_M, REOPEN_S)
    val slot2Target = secondsSinceMidnight(SLOT_2_H, SLOT_2_M, SLOT_2_S)
    return when {
        nowSeconds < morningStart -> HeadlinePhase.IDLE_BEFORE_START
        nowSeconds < slot1Target -> HeadlinePhase.LIVE
        nowSeconds < reopen -> if (slot1 != null) HeadlinePhase.FROZEN_MORNING else HeadlinePhase.LIVE
        nowSeconds < slot2Target -> HeadlinePhase.LIVE
        else -> if (slot2 != null) HeadlinePhase.FROZEN_EVENING else HeadlinePhase.LIVE
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun HomeScreen(
    livePreviewShared: LivePreview?,
    isRefreshing: Boolean = false,
    onManualRefresh: () -> Unit = {}
) {
    val context = LocalContext.current

    var slot1 by remember { mutableStateOf<CapturedSlot?>(null) }
    var slot2 by remember { mutableStateOf<CapturedSlot?>(null) }
    var slot0930 by remember { mutableStateOf<CapturedSlot?>(null) }
    var slot1400 by remember { mutableStateOf<CapturedSlot?>(null) }
    var lastResetDate by remember { mutableStateOf("") }
    var lastError by remember { mutableStateOf<String?>(null) }

    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LaunchedEffect(Unit) {
        val today = dateFmt.format(NetworkTime.now().time)
        HistoryStore.getSlot(context, today, HistoryStore.SLOT_1201)?.let {
            slot1 = CapturedSlot(it.set, it.value, today, it.capturedAt)
        }
        HistoryStore.getSlot(context, today, HistoryStore.SLOT_1630)?.let {
            slot2 = CapturedSlot(it.set, it.value, today, it.capturedAt)
        }
        HistoryStore.getSlot(context, today, HistoryStore.SLOT_0930)?.let {
            slot0930 = CapturedSlot(it.set, it.value, today, it.capturedAt)
        }
        HistoryStore.getSlot(context, today, HistoryStore.SLOT_1400)?.let {
            slot1400 = CapturedSlot(it.set, it.value, today, it.capturedAt)
        }
    }

    LaunchedEffect(Unit) {
        val slot1Target = secondsSinceMidnight(SLOT_1_H, SLOT_1_M, SLOT_1_S)
        val slot2Target = secondsSinceMidnight(SLOT_2_H, SLOT_2_M, SLOT_2_S)
        val slot0930Target = secondsSinceMidnight(MORNING_START_H, MORNING_START_M, MORNING_START_S)
        val slot1400Target = secondsSinceMidnight(REOPEN_H, REOPEN_M, REOPEN_S)

        while (true) {
            val now = NetworkTime.now()
            val today = dateFmt.format(now.time)

            if (today != lastResetDate) {
                lastResetDate = today
                if (HistoryStore.getSlot(context, today, HistoryStore.SLOT_1201) == null) slot1 = null
                if (HistoryStore.getSlot(context, today, HistoryStore.SLOT_1630) == null) slot2 = null
                if (HistoryStore.getSlot(context, today, HistoryStore.SLOT_0930) == null) slot0930 = null
                if (HistoryStore.getSlot(context, today, HistoryStore.SLOT_1400) == null) slot1400 = null
            }

            val nowSeconds = secondsSinceMidnight(
                now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.SECOND)
            )

            if (slot0930 == null && nowSeconds in slot0930Target..(slot0930Target + CAPTURE_GRACE_SECONDS)) {
                when (val result = SettradeRepository.fetchLiveSetIndex()) {
                    is SettradeRepository.FetchResult.Success -> {
                        val capturedAt = timeFmt.format(now.time)
                        slot0930 = CapturedSlot(result.data.set, result.data.value, today, capturedAt)
                        HistoryStore.recordSlot(context, today, HistoryStore.SLOT_0930, result.data.set, result.data.value, capturedAt)
                    }
                    is SettradeRepository.FetchResult.Failure -> lastError = result.reason
                }
            }
            if (slot1400 == null && nowSeconds in slot1400Target..(slot1400Target + CAPTURE_GRACE_SECONDS)) {
                when (val result = SettradeRepository.fetchLiveSetIndex()) {
                    is SettradeRepository.FetchResult.Success -> {
                        val capturedAt = timeFmt.format(now.time)
                        slot1400 = CapturedSlot(result.data.set, result.data.value, today, capturedAt)
                        HistoryStore.recordSlot(context, today, HistoryStore.SLOT_1400, result.data.set, result.data.value, capturedAt)
                    }
                    is SettradeRepository.FetchResult.Failure -> lastError = result.reason
                }
            }

            if (slot1 == null && nowSeconds in slot1Target..(slot1Target + CAPTURE_GRACE_SECONDS)) {
                when (val result = SettradeRepository.fetchLiveSetIndex()) {
                    is SettradeRepository.FetchResult.Success -> {
                        val capturedAt = timeFmt.format(now.time)
                        slot1 = CapturedSlot(result.data.set, result.data.value, today, capturedAt)
                        HistoryStore.recordSlot(context, today, HistoryStore.SLOT_1201, result.data.set, result.data.value, capturedAt)
                    }
                    is SettradeRepository.FetchResult.Failure -> lastError = result.reason
                }
            }
            if (slot2 == null && nowSeconds in slot2Target..(slot2Target + CAPTURE_GRACE_SECONDS)) {
                when (val result = SettradeRepository.fetchLiveSetIndex()) {
                    is SettradeRepository.FetchResult.Success -> {
                        val capturedAt = timeFmt.format(now.time)
                        slot2 = CapturedSlot(result.data.set, result.data.value, today, capturedAt)
                        HistoryStore.recordSlot(context, today, HistoryStore.SLOT_1630, result.data.set, result.data.value, capturedAt)
                    }
                    is SettradeRepository.FetchResult.Failure -> lastError = result.reason
                }
            }

            delay(1000)
        }
    }

    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = onManualRefresh
    )

    Box(Modifier.fillMaxSize().pullRefresh(pullRefreshState)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        Spacer(Modifier.height(8.dp))

        val nowCalForPhase = NetworkTime.now()
        val nowSecondsForPhase = secondsSinceMidnight(
            nowCalForPhase.get(Calendar.HOUR_OF_DAY),
            nowCalForPhase.get(Calendar.MINUTE),
            nowCalForPhase.get(Calendar.SECOND)
        )
        val phase = currentHeadlinePhase(nowSecondsForPhase, slot1, slot2)

        when (phase) {
            HeadlinePhase.IDLE_BEFORE_START -> {
                Text(
                    text = "--",
                    fontSize = 110.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.LightGray
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Starts at 9:00 AM",
                    fontSize = 14.sp,
                    color = Color.DarkGray
                )
            }
            HeadlinePhase.FROZEN_MORNING -> {
                Text(
                    text = slot1!!.twoD,
                    fontSize = 110.sp,
                    fontWeight = FontWeight.Bold,
                    color = GoldGreen
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Confirmed 12:01 PM \u2022 ${slot1!!.date} ${slot1!!.capturedAt}",
                    fontSize = 14.sp,
                    color = Color.DarkGray
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Reopens live at 2:00 PM",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
            HeadlinePhase.FROZEN_EVENING -> {
                Text(
                    text = slot2!!.twoD,
                    fontSize = 110.sp,
                    fontWeight = FontWeight.Bold,
                    color = GoldGreen
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Confirmed 4:30 PM \u2022 ${slot2!!.date} ${slot2!!.capturedAt}",
                    fontSize = 14.sp,
                    color = Color.DarkGray
                )
            }
            HeadlinePhase.LIVE -> {
                if (livePreviewShared != null) {
                    Text(
                        text = livePreviewShared.twoD,
                        fontSize = 110.sp,
                        fontWeight = FontWeight.Bold,
                        color = GoldGreen,
                        modifier = Modifier.alpha(rememberPulseAlpha())
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Live \u2022 ${livePreviewShared.date} ${livePreviewShared.time}",
                        fontSize = 14.sp,
                        color = Color.DarkGray
                    )
                } else {
                    CircularProgressIndicator(color = GoldGreen)
                    Spacer(Modifier.height(8.dp))
                    Text("Fetching live SET Index...", fontSize = 14.sp, color = Color.DarkGray)
                }
            }
        }

        lastError?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                "Last fetch issue: $it",
                fontSize = 11.sp,
                color = Color(0xFFD32F2F),
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }

        Spacer(Modifier.height(16.dp))

        SlotCard(
            label = "12:01 PM",
            slot = slot1,
            live = livePreviewShared,
            liveWindowActive = nowSecondsForPhase >= secondsSinceMidnight(MORNING_START_H, MORNING_START_M, MORNING_START_S) &&
                nowSecondsForPhase < secondsSinceMidnight(SLOT_1_H, SLOT_1_M, SLOT_1_S)
        )
        Spacer(Modifier.height(16.dp))
        SlotCard(
            label = "4:30 PM",
            slot = slot2,
            live = livePreviewShared,
            liveWindowActive = nowSecondsForPhase >= secondsSinceMidnight(REOPEN_H, REOPEN_M, REOPEN_S) &&
                nowSecondsForPhase < secondsSinceMidnight(SLOT_2_H, SLOT_2_M, SLOT_2_S)
        )
        Spacer(Modifier.height(16.dp))
        SlotCard(
            label = "9:30 AM",
            slot = slot0930,
            live = null,
            liveWindowActive = false,
            compact = true
        )
        Spacer(Modifier.height(16.dp))
        SlotCard(
            label = "2:00 PM",
            slot = slot1400,
            live = null,
            liveWindowActive = false,
            compact = true
        )
        }

        PullRefreshIndicator(
            refreshing = isRefreshing,
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

@Composable
private fun SlotCard(
    label: String,
    slot: CapturedSlot?,
    live: LivePreview?,
    liveWindowActive: Boolean,
    compact: Boolean = false
) {
    Surface(shape = RoundedCornerShape(16.dp), color = RedCard, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(4.dp))
            Spacer(Modifier.height(8.dp))

            val twoDText = slot?.twoD ?: "--"

            if (compact) {
                Text(
                    text = twoDText,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFFEB3B)
                )
            } else {
                val useLive = liveWindowActive && slot == null
                val rowModifier = if (useLive) {
                    Modifier.fillMaxWidth().alpha(rememberPulseAlpha())
                } else {
                    Modifier.fillMaxWidth()
                }

                Row(rowModifier, horizontalArrangement = Arrangement.SpaceBetween) {
                    val setText = slot?.let { "%.2f".format(it.set) } ?: (if (useLive) live?.let { "%.2f".format(it.set) } else null) ?: "--"
                    val valueText = slot?.let { "%,.2f".format(it.value) } ?: (if (useLive) live?.let { "%,.2f".format(it.value) } else null) ?: "--"
                    LabeledValue("SET", setText)
                    LabeledValue("Value", valueText)
                    LabeledValue("2D", twoDText, valueColor = Color(0xFFFFEB3B), big = true)
                }
            }
        }
    }
}

@Composable
private fun ResultsList2D() {
    val context = LocalContext.current
    val dates = remember { HistoryStore.getAllDates(context) }

    if (dates.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(40.dp))
            Text(
                "No confirmed results yet. Once the app captures 12:01 PM or 4:30 PM, they'll show up here.",
                fontSize = 14.sp,
                color = Color.DarkGray
            )
        }
        return
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(dates) { date ->
            Column {
                DatePill(text = date)
                Spacer(Modifier.height(12.dp))
                HistoryStore.getSlot(context, date, HistoryStore.SLOT_1201)?.let { slot ->
                    ResultCard(TwoDResult(time = "12:01 PM", set = slot.set, value = slot.value, date = date))
                    Spacer(Modifier.height(12.dp))
                }
                HistoryStore.getSlot(context, date, HistoryStore.SLOT_1630)?.let { slot ->
                    ResultCard(TwoDResult(time = "4:30 PM", set = slot.set, value = slot.value, date = date))
                }
            }
        }
    }
}

@Composable
private fun ResultsList3D() {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(SampleData.threeDHistory) { result ->
            ThreeDCard(result)
        }
    }
}

@Composable
private fun DatePill(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(50), color = GreenPill) {
            Text(text, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
        }
    }
}

@Composable
private fun ResultCard(result: TwoDResult) {
    Surface(shape = RoundedCornerShape(16.dp), color = RedCard, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(result.time, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LabeledValue("SET", "%.2f".format(result.set))
                LabeledValue("Value", "%,.2f".format(result.value))
                LabeledValue("2D", result.twoD, valueColor = Color(0xFFFFEB3B), big = true)
            }
        }
    }
}

@Composable
private fun ThreeDCard(result: ThreeDResult) {
    Surface(shape = RoundedCornerShape(16.dp), color = GreenPill, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(20.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            LabeledValue("Date", result.date, valueColor = Color.White)
            LabeledValue("3D", result.threeD, valueColor = Color(0xFFFFEB3B), big = true)
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String, valueColor: Color = Color.White, big: Boolean = false, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 13.sp, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            fontSize = if (big) 30.sp else 20.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
    }
}
