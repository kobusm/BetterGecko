package com.bettergecko.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import kotlin.math.roundToInt

/** Live values only available over the socket.io channel. */
data class LiveSnapshot(
    val acMax: Int? = null,
    val pvMax: Int? = null,
    val geckoMode: String? = null,
    val temperature: Double? = null,
    val fwVersion: String? = null,
    val mcuFwVersion: Int? = null,
    val connectedAP: String? = null,
    val rssi: Int? = null,
) {
    val hasSetpoints get() = acMax != null && pvMax != null
    val isComplete get() = hasSetpoints && geckoMode != null
}

/** Engine.IO v3 framing/parsing — pure functions so they can be unit tested. */
object EngineIO {
    /** Splits "<len>:<packet><len>:<packet>…" into packets. Lengths count UTF-16 chars. */
    fun parsePackets(s: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < s.length) {
            val start = i
            while (i < s.length && s[i].isDigit()) i++
            if (i >= s.length || s[i] != ':' || i == start) break
            val len = s.substring(start, i).toInt()
            i++
            if (i + len > s.length) break
            out += s.substring(i, i + len)
            i += len
        }
        return out
    }

    fun frame(packet: String) = "${packet.length}:$packet"

    fun parseSid(payload: String): String? = parsePackets(payload).firstNotNullOfOrNull { p ->
        if (!p.startsWith("0")) null
        else runCatching { AppJson.parseToJsonElement(p.drop(1)).jsonObject["sid"]?.jsonPrimitive?.contentOrNull }.getOrNull()
    }

    /** Applies one "42[event,data]" packet into the snapshot. */
    fun apply(packet: String, gsn: String, live: LiveSnapshot): LiveSnapshot {
        if (!packet.startsWith("42")) return live
        val arr = runCatching { AppJson.parseToJsonElement(packet.drop(2)) as? JsonArray }.getOrNull() ?: return live
        if (arr.size < 2) return live
        val event = (arr[0] as? JsonPrimitive)?.contentOrNull ?: return live
        val data = arr[1] as? JsonObject ?: return live

        return when (event) {
            "heartbeatUpdate" -> live.copy(
                acMax = data.int("AC_MAX") ?: live.acMax,
                pvMax = data.int("PV_MAX") ?: live.pvMax,
                temperature = data.dbl("temperature") ?: live.temperature,
                fwVersion = data.str("FW_VERSION") ?: live.fwVersion,
                mcuFwVersion = data.int("MCU_FW_VERSION") ?: live.mcuFwVersion,
                connectedAP = data.str("CONNECTED_AP_NAME") ?: live.connectedAP,
                rssi = data.int("rssi") ?: live.rssi,
            )
            "userGeckos", "onlineGeckos", "userDevices" -> {
                // The list sits under a key matching the event name.
                val list = (data[event] as? JsonArray) ?: data.values.firstOrNull { it is JsonArray } as? JsonArray
                val match = list?.mapNotNull { it as? JsonObject }?.firstOrNull { it.str("geckoSerialNumber") == gsn }
                match?.str("geckoMode")?.let { live.copy(geckoMode = it) } ?: live
            }
            else -> live
        }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.dbl(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    private fun JsonObject.int(k: String) = dbl(k)?.roundToInt()
}

/**
 * Minimal Engine.IO v3 (socket.io v2) long-polling client. Fetch-once: open a polling session,
 * subscribe to the device, read the queued heartbeatUpdate / userGeckos events, then close.
 */
object GeckoRealtime {
    private const val BASE = "https://geckows.co.za/socket.io/"
    private val TEXT = "text/plain;charset=UTF-8".toMediaType()
    private val client = ApiClient.http.newBuilder().cookieJar(okhttp3.CookieJar.NO_COOKIES).build()

    suspend fun fetchSettings(gsn: String, deviceID: String, timeoutMs: Long = 12_000): LiveSnapshot =
        if (Store.demoMode) Demo.live() else withContext(Dispatchers.IO) {
            val query = linkedMapOf(
                "token" to "u$deviceID",
                "ANDROID_APP_VERSION" to APP_VERSION,
                "IOS_APP_VERSION" to APP_VERSION,
                "EIO" to "3",
                "transport" to "polling",
            )
            val sid = EngineIO.parseSid(get(query)) ?: throw ApiException(0, "socket.io handshake failed")
            query["sid"] = sid

            runCatching { post(query, """42["watchGecko",{"geckoSerialNumber":"$gsn","deviceID":"$deviceID"}]""") }
            runCatching { post(query, """42["getUserGeckos",{"deviceID":"$deviceID","ANDROID_APP_VERSION":17,"IOS_APP_VERSION":17}]""") }

            var live = LiveSnapshot()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline && !live.isComplete) {
                val body = runCatching { get(query) }.getOrNull() ?: break
                EngineIO.parsePackets(body).forEach { live = EngineIO.apply(it, gsn, live) }
                if (body.isEmpty()) delay(300)   // avoid a hot loop on empty polls
            }
            runCatching { post(query, "1") }   // best-effort close
            live
        }

    private fun url(q: Map<String, String>): String =
        BASE + "?" + q.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }

    private fun get(q: Map<String, String>): String {
        val req = Request.Builder().url(url(q)).header("User-Agent", USER_AGENT).header("Accept", "*/*").build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun post(q: Map<String, String>, packet: String) {
        val req = Request.Builder().url(url(q)).header("User-Agent", USER_AGENT)
            .post(EngineIO.frame(packet).toRequestBody(TEXT)).build()
        client.newCall(req).execute().close()
    }
}
