package com.bettergecko.app.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val BASE_URL = "https://geckows.co.za"
// Static app token, captured from the official app's live traffic. The server rotates it
// periodically; if /auth/signin starts failing, re-capture it. Keep in sync with iOS APIClient.swift.
private const val STATIC_APP_TOKEN = "1f31271a71c05ca1b9c6c52a14086bb9"
// Must be a plain integer; the official app sends "17".
const val APP_VERSION = "17"
// The nginx gateway returns a bare 403 to any User-Agent containing "bettergecko".
// Send the official app's UA so requests reach the API.
const val USER_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"

class ApiException(val code: Int, message: String) : Exception(message) {
    companion object {
        const val UNAUTHORIZED = 401
        fun unauthorized() = ApiException(UNAUTHORIZED, "Session expired. Please log in again.")
    }
}

/** Keeps the xsrf-token cookie signin sets, like URLSession's shared cookie storage on iOS. */
private object MemoryCookieJar : CookieJar {
    private val cookies = mutableMapOf<String, Cookie>()
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { this.cookies[it.name] = it }
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> =
        cookies.values.filter { it.matches(url) }
    @Synchronized fun clear() = cookies.clear()
}

object ApiClient {
    val http: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(MemoryCookieJar)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    private val JSON_TYPE = "application/json".toMediaType()
    private val reloginLock = Mutex()

    /**
     * Performs a request and returns the parsed JSON body. On 401 it silently re-logs in with
     * stored credentials and replays once. Only 401 does this: a 403 (version rejected, account
     * locked) would just loop the same rejected signin and risk a server-side lockout.
     */
    suspend fun request(
        method: String,
        path: String,
        body: Map<String, Any?>? = null,
        params: Map<String, String>? = null,
        overrideToken: String? = null,
    ): JsonElement {
        val (code, text) = execute(method, path, body, params, overrideToken ?: Store.token)

        if (code == 401) {
            if (overrideToken != null) throw ApiException.unauthorized()   // don't retry auth calls
            val fresh = runCatching {
                reloginLock.withLock { AuthApi.reloginWithStoredCredentials() }
                Store.token
            }.getOrNull() ?: throw ApiException.unauthorized()
            return request(method, path, body, params, overrideToken = fresh)
        }

        if (code !in 200..299) {
            val msg = runCatching {
                val obj = Store.json.parseToJsonElement(text).jsonObject
                listOf("customMsg", "message", "error").firstNotNullOfOrNull { obj[it]?.jsonPrimitive?.contentOrNull }
            }.getOrNull() ?: "HTTP $code"
            throw ApiException(code, msg)
        }

        return if (text.isBlank()) JsonObject(emptyMap())
        else runCatching { Store.json.parseToJsonElement(text) }.getOrElse { JsonObject(emptyMap()) }
    }

    private suspend fun execute(
        method: String, path: String, body: Map<String, Any?>?, params: Map<String, String>?, token: String?,
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val url = (BASE_URL + path).toHttpUrl().newBuilder().apply {
            params?.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()

        val reqBody = body?.let { toJson(it).toString().toRequestBody(JSON_TYPE) }
            ?: if (method == "POST") "{}".toRequestBody(JSON_TYPE) else null

        val req = Request.Builder().url(url).method(method, reqBody)
            .header("Content-Type", "application/json")
            .header("User-Agent", USER_AGENT)
            .header("X-App-Version", APP_VERSION)
            .header("X-Android-App-Version", APP_VERSION)
            .header("X-IOS-App-Version", APP_VERSION)
            .header("Origin", BASE_URL)
            .header("Referer", "$BASE_URL/")
            .header("Cache-Control", "no-cache")
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .build()

        Log.d("API", "$method $url")
        http.newCall(req).execute().use { resp ->
            Log.d("API", "${resp.code} $path")
            resp.code to (resp.body?.string() ?: "")
        }
    }

    private fun toJson(map: Map<String, Any?>): JsonObject = JsonObject(map.mapValues { (_, v) ->
        when (v) {
            null -> kotlinx.serialization.json.JsonNull
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
    })

    fun clearCookies() = MemoryCookieJar.clear()

    internal const val staticToken = STATIC_APP_TOKEN
}

// MARK: - Auth

@Serializable private data class SignInResponse(val token: String, val role: String? = null)
@Serializable private data class DeviceIDResponse(val deviceID: String, val role: String? = null)

object AuthApi {
    private const val KEY_EMAIL = "gg_email"
    private const val KEY_PASSWORD = "gg_password"

    suspend fun login(email: String, password: String): SessionInfo {
        val signin = Store.json.decodeFromJsonElement<SignInResponse>(
            ApiClient.request("POST", "/auth/signin", mapOf("email" to email, "pw" to password),
                overrideToken = ApiClient.staticToken)
        )
        Store.token = signin.token

        // /app/deviceid works with the per-user session token from signin.
        val dev = Store.json.decodeFromJsonElement<DeviceIDResponse>(
            ApiClient.request("POST", "/app/deviceid", emptyMap())
        )
        Store.deviceID = dev.deviceID

        SecureStore.save(KEY_EMAIL, email)
        SecureStore.save(KEY_PASSWORD, password)

        return SessionInfo(email, signin.token, dev.role ?: signin.role ?: "user", dev.deviceID)
    }

    /** On launch: try the stored session token; if it 401s, re-login with saved credentials. */
    suspend fun restoreSession(): SessionInfo {
        if (Store.token != null) {
            try {
                return fetchSessionInfo()
            } catch (e: ApiException) {
                if (e.code != ApiException.UNAUTHORIZED) throw e
            }
        }
        return reloginWithStoredCredentials()
    }

    suspend fun reloginWithStoredCredentials(): SessionInfo {
        val email = SecureStore.load(KEY_EMAIL)
        val pw = SecureStore.load(KEY_PASSWORD)
        if (email == null || pw == null) throw ApiException.unauthorized()
        return login(email, pw)
    }

    suspend fun logout() {
        runCatching { ApiClient.request("POST", "/auth/logout", emptyMap()) }
        ApiClient.clearCookies()
        Store.clearAll()
        SecureStore.clear()
    }

    private suspend fun fetchSessionInfo(): SessionInfo {
        val dev = Store.json.decodeFromJsonElement<DeviceIDResponse>(
            ApiClient.request("POST", "/app/deviceid", emptyMap())
        )
        Store.deviceID = dev.deviceID
        val email = SecureStore.load(KEY_EMAIL) ?: dev.deviceID.dropLast(32)
        return SessionInfo(email, Store.token ?: "", dev.role ?: "user", dev.deviceID)
    }
}

// MARK: - Device

object DeviceApi {
    suspend fun performanceHistory(gsn: String): PerformanceHistoryResponse =
        Store.json.decodeFromJsonElement(
            ApiClient.request("GET", "/app/getPerformanceHistory", params = mapOf("geckoSerialNumber" to gsn))
        )

    suspend fun reading(gsn: String): DeviceReading {
        val h = performanceHistory(gsn)
        return DeviceReading(h.latestTemperature, GeckoState.from(h.latestState), h.latestPVVoltage)
    }

    suspend fun hasControl(gsn: String): Boolean {
        val obj = ApiClient.request("GET", "/app/hasControl", params = mapOf("geckoSerialNumber" to gsn)).jsonObject
        val v = (obj["hasControl"] ?: obj["control"])?.jsonPrimitive?.contentOrNull
        return v == "true"
    }

    /** Sets both AC and PV setpoints in one call. */
    suspend fun setTemperature(gsn: String, acMax: Double, pvMax: Double) {
        ApiClient.request("POST", "/app/setTemperature",
            mapOf("geckoSerialNumber" to gsn, "acMax" to acMax, "pvMax" to pvMax))
    }

    /** Mode: ALL_OFF | PV_ONLY | AC_AND_PV | AC_ONLY. */
    suspend fun setMode(gsn: String, mode: String) {
        ApiClient.request("POST", "/app/setMode", mapOf("geckoSerialNumber" to gsn, "geckoMode" to mode))
    }

    /** setMode, ignoring the server's known intermittent 500 (the change still takes effect). */
    suspend fun setModeTolerant(gsn: String, mode: String) {
        try { setMode(gsn, mode) } catch (e: ApiException) { if (e.code != 500) throw e }
    }
}
