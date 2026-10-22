package com.example.myanmar2d

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object SettradeRepository {

    private const val SET_OR_TH_URL = "https://www.set.or.th/en/market/index/set/overview"
    private const val SETTRADE_URL = "https://www.settrade.com/th/equities/market-summary/overview"

    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    // set.or.th sits behind Incapsula, which serves a ~950-byte bot-wall
    // shell to most mobile-UA requests; a desktop UA passes far more often
    // (proven by the set_logger dual-feed runs, 2026-10-21/22).
    private const val WIN_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    private const val PROXY_ALLORIGINS = "https://api.allorigins.win/raw?url="
    private const val PROXY_CODETABS = "https://api.codetabs.com/v1/proxy?quest="
    private const val PROXY_CORSPROXY = "https://corsproxy.io/?url="

    data class LiveMarketData(val set: Double, val value: Double) {
        val twoD: String get() = calculate2D(set, value)
    }

    sealed interface FetchResult {
        data class Success(val data: LiveMarketData) : FetchResult
        data class Failure(val reason: String) : FetchResult
    }

    suspend fun fetchLiveSetIndex(): FetchResult = withContext(Dispatchers.IO) {
        // Cache-buster: the scraped pages (and the proxies in front of them)
        // cache aggressively, and a cached page means the previous session's
        // numbers — fatal at capture moments ("Ref ကြောင့်မှားတယ်"). A unique
        // query on every fetch, plus no-cache headers in fetchHtml, forces
        // the freshest page the source will serve.
        val bust = System.currentTimeMillis()
        val setOrThUrl = "$SET_OR_TH_URL?_cb=$bust"
        val settradeUrl = "$SETTRADE_URL?_cb=$bust"
        val encodedSetOrTh = java.net.URLEncoder.encode(setOrThUrl, "UTF-8")
        val encodedSettrade = java.net.URLEncoder.encode(settradeUrl, "UTF-8")

        // v1.3.8 speed pass (Po, 2026-10-22: the app visibly trailed other
        // apps while the numbers were moving). Fetch BOTH direct sources in
        // PARALLEL and keep the freshest print: set.or.th leads settrade by
        // seconds during churn, but its bot-wall (and occasional stale cache
        // pages) used to push the app onto the slower source. Freshness is
        // judged by the page's own "Last Update" stamp (Bangkok time).
        val (orth, trade) = coroutineScope {
            val o = async { fetchSetOrThDirect(setOrThUrl) }
            val t = async { tryFetch(settradeUrl, ::parseSettrade) }
            o.await() to t.await()
        }
        if (orth != null && orth.fresh) return@withContext FetchResult.Success(orth.data)
        if (trade is FetchResult.Success) return@withContext trade
        if (orth != null) return@withContext FetchResult.Success(orth.data)

        // VPN မရှိလည်း အလုပ်ဖြစ်ဖို့ - direct URL အရင်စမ်းပြီး၊ block/fail ဖြစ်ရင်
        // proxy ၃ မျိုး အဆင့်ဆင့် ထပ်စမ်းမယ်. တစ်ခုမှ အောင်ရင် ရပ်မယ်.
        // Both direct routes failed: walk the proxy chain as before, so
        // phones whose network blocks the Thai sites still get numbers.
        val attempts = listOf(
            setOrThUrl to ::parseSetOrTh,
            settradeUrl to ::parseSettrade,
            PROXY_ALLORIGINS + encodedSetOrTh to ::parseSetOrTh,
            PROXY_ALLORIGINS + encodedSettrade to ::parseSettrade,
            PROXY_CODETABS + encodedSetOrTh to ::parseSetOrTh,
            PROXY_CODETABS + encodedSettrade to ::parseSettrade,
            PROXY_CORSPROXY + encodedSetOrTh to ::parseSetOrTh,
            PROXY_CORSPROXY + encodedSettrade to ::parseSettrade
        )

        var firstFailure: FetchResult.Failure? = null
        for ((url, parser) in attempts) {
            val result = tryFetch(url, parser)
            if (result is FetchResult.Success) return@withContext result
            if (firstFailure == null) firstFailure = result as FetchResult.Failure
        }
        firstFailure ?: FetchResult.Failure("All sources failed")
    }

    private data class StampedResult(val data: LiveMarketData, val fresh: Boolean)

    /**
     * Direct set.or.th fetch with the desktop UA. A bot-wall shell (or any
     * unparseable response) gets ONE quick retry after a short pause - in
     * the logger runs a retry landed the full ~447 KB page almost every
     * time. Returns null when both attempts fail.
     */
    private fun fetchSetOrThDirect(url: String): StampedResult? {
        repeat(2) { attempt ->
            try {
                val (code, html) = fetchHtml(url, WIN_UA)
                if (code in 200..299 && !html.isNullOrBlank() && html.length >= 5000) {
                    val data = parseSetOrTh(html)
                    if (data != null) return StampedResult(data, isFreshPrint(html))
                }
            } catch (e: Exception) {
                // fall through to the retry / caller fallback
            }
            if (attempt == 0) {
                try { Thread.sleep(1200) } catch (ignored: InterruptedException) {}
            }
        }
        return null
    }

    /**
     * The set.or.th page prints its own "Last Update" (Bangkok time). A
     * print counts as fresh when that stamp is within 2 minutes of network
     * time; during live hours the prints change constantly, so an older
     * stamp means a stale cached page. Unknown stamp = treat as fresh
     * (set.or.th is the leading source whenever it answers at all).
     */
    private fun isFreshPrint(html: String): Boolean {
        val m = Regex("""Last Update\s*([0-9]{1,2} [A-Za-z]{3} [0-9]{4} [0-9:]{8})""").find(html)
            ?: return true
        return try {
            val fmt = SimpleDateFormat("d MMM yyyy HH:mm:ss", Locale.ENGLISH).apply {
                timeZone = TimeZone.getTimeZone("Asia/Bangkok")
            }
            val stampMs = fmt.parse(m.groupValues[1])?.time ?: return true
            (NetworkTime.now().timeInMillis - stampMs) / 1000 <= 120
        } catch (e: Exception) {
            true
        }
    }

    private inline fun tryFetch(url: String, parse: (String) -> LiveMarketData?): FetchResult {
        return try {
            val (code, html) = fetchHtml(url)
            if (code !in 200..299) {
                return FetchResult.Failure("HTTP $code from $url")
            }
            if (html.isNullOrBlank()) {
                return FetchResult.Failure("Empty response from $url")
            }
            val data = parse(html)
                ?: return FetchResult.Failure("Fetched $url (${html.length} chars) but couldn't parse SET data")
            FetchResult.Success(data)
        } catch (e: Exception) {
            FetchResult.Failure("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun fetchHtml(urlString: String, ua: String = MOBILE_UA): Pair<Int, String?> {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", ua)
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            connection.setRequestProperty("Accept-Language", "th-TH,th;q=0.9,en;q=0.8")
            connection.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0")
            connection.setRequestProperty("Pragma", "no-cache")
            connection.useCaches = false
            val code = connection.responseCode
            // ဖုန်းရဲ့ clock မှန်မမှန် မငဲ့ဘဲ server ရဲ့ တကယ့်အချိန်ကို sync လုပ်မယ်
            NetworkTime.updateFromHeader(connection.getHeaderField("Date"))
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }
            code to body
        } finally {
            connection.disconnect()
        }
    }

    private fun stripTags(html: String): String {
        val withoutScripts = html
            .replace(Regex("(?is)<script.*?</script>"), " ")
            .replace(Regex("(?is)<style.*?</style>"), " ")
        return withoutScripts
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("&nbsp;", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ")
    }

    private fun parseSetOrTh(html: String): LiveMarketData? {
        val text = stripTags(html)

        val setToken = Regex("""\bSET\b""").find(text) ?: return null
        val window = text.substring(setToken.range.last + 1, (setToken.range.last + 1 + 300).coerceAtMost(text.length))
        val numberRegex = Regex("""-?[0-9][0-9,]*\.[0-9]+""")
        val indexNumber = numberRegex.find(window)?.value?.replace(",", "")?.toDoubleOrNull() ?: return null
        if (indexNumber < 500 || indexNumber > 5000) return null

        val valueMatch = Regex("""Value\s*\([^)]*Baht\)\D{0,10}([0-9][0-9,]*\.[0-9]+)""").find(text)
            ?: return null
        val valueNumber = valueMatch.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null

        return LiveMarketData(set = indexNumber, value = valueNumber)
    }

    private fun parseSettrade(html: String): LiveMarketData? {
        val text = stripTags(html)
        val numberRegex = Regex("""-?[0-9][0-9,]*\.[0-9]+""")
        val setTokenRegex = Regex("""\bSET\b""")

        for (match in setTokenRegex.findAll(text)) {
            val windowStart = match.range.last + 1
            val windowEnd = (windowStart + 700).coerceAtMost(text.length)
            val window = text.substring(windowStart, windowEnd)

            val numbers = numberRegex.findAll(window)
                .map { it.value.replace(",", "") }
                .mapNotNull { it.toDoubleOrNull() }
                .toList()

            if (numbers.size < 2) continue
            val candidateIndex = numbers.first()
            if (candidateIndex < 500 || candidateIndex > 5000) continue

            val candidateValue = numbers.max()
            return LiveMarketData(set = candidateIndex, value = candidateValue)
        }
        return null
    }
}
