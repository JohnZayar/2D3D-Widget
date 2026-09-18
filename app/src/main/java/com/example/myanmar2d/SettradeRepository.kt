package com.example.myanmar2d

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object SettradeRepository {

    private const val SET_OR_TH_URL = "https://www.set.or.th/en/market/index/set/overview"
    private const val SETTRADE_URL = "https://www.settrade.com/th/equities/market-summary/overview"
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
        val encodedSetOrTh = java.net.URLEncoder.encode(SET_OR_TH_URL, "UTF-8")
        val encodedSettrade = java.net.URLEncoder.encode(SETTRADE_URL, "UTF-8")

        // VPN မရှိလည်း အလုပ်ဖြစ်ဖို့ - direct URL အရင်စမ်းပြီး၊ block/fail ဖြစ်ရင်
        // proxy ၃ မျိုး အဆင့်ဆင့် ထပ်စမ်းမယ်. တစ်ခုမှ အောင်ရင် ရပ်မယ်.
        val attempts = listOf(
            SET_OR_TH_URL to ::parseSetOrTh,
            SETTRADE_URL to ::parseSettrade,
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

    private fun fetchHtml(urlString: String): Pair<Int, String?> {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
            )
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            connection.setRequestProperty("Accept-Language", "th-TH,th;q=0.9,en;q=0.8")
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
