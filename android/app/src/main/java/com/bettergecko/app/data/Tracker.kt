package com.bettergecko.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * Optional self-hosted AC-hours tracker (the Cloudflare Worker in `worker/`).
 *
 * The worker logs in on its own schedule and keeps every day's AC on-time, so it fills the gaps
 * the on-phone ledger gets when the app isn't opened for 3+ days. It's per-user: the URL and API
 * key are entered in Profile, never built into the app. The key is kept in [SecureStore].
 */
object Tracker {
    private const val URL_KEY = "ac_tracker_url"
    private const val SECRET_KEY = "ac_tracker_key"

    val url: String? get() = Store.string(URL_KEY)
    val apiKey: String? get() = SecureStore.load(SECRET_KEY)
    val isConfigured: Boolean get() = !Store.demoMode && validUrl(url) != null && !apiKey.isNullOrEmpty()

    fun save(url: String, apiKey: String) {
        Store.setString(URL_KEY, url.trim())
        SecureStore.save(SECRET_KEY, apiKey.trim())
    }

    fun clear() {
        Store.setString(URL_KEY, null)
        SecureStore.remove(SECRET_KEY)
    }

    /** Only https URLs are accepted: the API key travels in a header. */
    fun validUrl(s: String?): HttpUrl? =
        s?.trim()?.takeIf { it.isNotEmpty() }?.toHttpUrlOrNull()?.takeIf { it.isHttps && it.host.isNotEmpty() }

    /** The tracker's per-day AC seconds for this month and last month ("yyyy-MM-dd" → seconds). */
    suspend fun fetchDays(gsn: String): Map<String, Double> = withContext(Dispatchers.IO) {
        val base = validUrl(url) ?: throw ApiException(0, "Tracker not set up")
        val key = apiKey?.takeIf { it.isNotEmpty() } ?: throw ApiException(0, "Tracker not set up")
        val reqUrl = base.newBuilder().addPathSegment("usage").addQueryParameter("gsn", gsn).build()
        val req = Request.Builder().url(reqUrl).header("Authorization", "Bearer $key").build()
        ApiClient.http.newCall(req).execute().use { resp ->
            when (resp.code) {
                200 -> parseDays(resp.body?.string() ?: "")
                401 -> throw ApiException(401, "Wrong API key")
                404 -> throw ApiException(404, "Tracker doesn't track this device")
                else -> throw ApiException(resp.code, "HTTP ${resp.code}")
            }
        }
    }

    @Serializable private data class Day(val day: String, val seconds: Double)
    @Serializable private data class Month(val month: String, val days: List<Day>)
    @Serializable private data class Usage(val months: List<Month>)

    fun parseDays(json: String): Map<String, Double> {
        val days = mutableMapOf<String, Double>()
        for (d in AppJson.decodeFromString<Usage>(json).months.flatMap { it.days }) {
            days[d.day] = maxOf(days[d.day] ?: 0.0, d.seconds)
        }
        return days
    }
}
