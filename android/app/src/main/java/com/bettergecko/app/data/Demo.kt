package com.bettergecko.app.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Demo mode: a simulated GeyserGecko so the app can be explored without an account or device
 * (Play testers, reviewers). When [Store.demoMode] is on, [ApiClient.request] and
 * [GeckoRealtime.fetchSettings] answer from here and nothing goes to the network.
 */
object Demo {
    const val GSN = "DEMO00000001"
    const val DEVICE_ID = "demo"
    val device = GeckoDevice(GSN, "Demo Geyser")
    val session = SessionInfo(email = "Demo mode (sample data)", token = "demo", role = "demo", deviceID = DEVICE_ID)

    private const val STEP_SECS = 300L                 // 5-minute samples, like the real device
    private const val SPAN_SECS = 3 * 24 * 3600L       // the history endpoint covers ~3 days
    private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

    /** Canned REST responses, keyed on path. */
    fun respond(path: String): JsonElement = when (path) {
        "/app/getPerformanceHistory" -> AppJson.encodeToJsonElement(history(Instant.now(), settings()))
        "/app/hasControl" -> obj("hasControl" to "true")
        "/app/deviceid" -> obj("deviceID" to DEVICE_ID, "role" to "demo")
        else -> JsonObject(emptyMap())                 // setTemperature, setMode, logout: accepted
    }

    /** What the socket.io snapshot would report; setpoints/mode follow whatever the user saved. */
    fun live(): LiveSnapshot {
        val s = settings()
        val latest = history(Instant.now(), s).latestTemperature
        return LiveSnapshot(
            acMax = s.targetTemp.roundToInt(), pvMax = s.pvTargetTemp.roundToInt(), geckoMode = s.mode,
            temperature = latest, fwVersion = "1.0.46 (demo)", mcuFwVersion = 12,
            connectedAP = "Demo-WiFi", rssi = -58,
        )
    }

    private fun settings() = Store.settings(GSN) ?: PersistedSettings(targetTemp = 45.0, pvTargetTemp = 65.0)

    /**
     * Simulates the last three days, newest-first like the server: the panels heat the water
     * through the day up to the PV setpoint, the element tops it up to the AC setpoint when it
     * cools below, and morning/evening showers draw it down. Deterministic for a given [now].
     */
    fun history(now: Instant, s: PersistedSettings): PerformanceHistoryResponse {
        val end = Instant.ofEpochSecond(now.epochSecond - now.epochSecond % STEP_SECS)
        val start = end.minusSeconds(SPAN_SECS)
        val solarAllowed = s.mode == "PV_ONLY" || s.mode == "AC_AND_PV"
        val acAllowed = s.mode == "AC_ONLY" || s.mode == "AC_AND_PV"

        val ts = mutableListOf<String>(); val temps = mutableListOf<Double?>(); val states = mutableListOf<String?>()
        val pv = mutableListOf<Int?>(); val mppt = mutableListOf<Int?>()
        val pvOut = mutableListOf<Int?>(); val acOut = mutableListOf<Int?>()

        var temp = 48.0
        var heatingAc = false
        var t = start
        while (!t.isAfter(end)) {
            val local = t.atZone(SAST)
            val hour = local.hour + local.minute / 60.0
            val cloud = 0.65 + 0.35 * dayFactor(local.toLocalDate().toEpochDay())
            val sun = max(0.0, sin(PI * (hour - 6.5) / 12.5)) * cloud   // 06:30–19:00

            // Hot-water draws: a morning and an evening shower.
            if (hour in 6.0..6.5) temp -= 1.6
            if (hour in 19.0..19.5) temp -= 1.3
            temp -= 0.035                                               // standing loss

            val state = when {
                solarAllowed && sun > 0.2 && temp < s.pvTargetTemp -> { temp += 0.45 * sun; "pv_out" }
                acAllowed && (heatingAc || temp < s.targetTemp - 3) && temp < s.targetTemp -> {
                    heatingAc = true; temp += 0.55; "ac_out"
                }
                !solarAllowed && !acAllowed -> { heatingAc = false; "off" }
                else -> { heatingAc = false; "idle" }
            }
            temp = temp.coerceIn(18.0, 75.0)

            ts += ISO.format(t)
            temps += (temp * 10).roundToInt() / 10.0
            states += state
            pv += if (sun > 0) ((110 + 95 * sun) * 3).roundToInt() else 0        // raw, server reports ×3
            mppt += if (sun > 0) (205 + 35 * sun).roundToInt() else 0
            pvOut += if (state == "pv_out") (1900 * sun).roundToInt() else 0
            acOut += if (state == "ac_out") 3900 else 0
            t = t.plusSeconds(STEP_SECS)
        }

        val stepMin = (STEP_SECS / 60).toInt()
        val pvMin = states.count { it == "pv_out" } * stepMin
        val acMin = states.count { it == "ac_out" } * stepMin
        return PerformanceHistoryResponse(
            timestamps = ts.reversed(), temperatures = temps.reversed(), state = states.reversed(),
            pvVoltage = pv.reversed(), mpptVoltage = mppt.reversed(),
            pvOutPower = pvOut.reversed(), acOutPower = acOut.reversed(),
            pvUsageWeek = pvMin * 7 / 3, acUsageWeek = acMin * 7 / 3,
            savingsWeek = (pvMin * 7 / 3) / 60.0 * Store.DEFAULT_ELEMENT_KW * Store.DEFAULT_RATE,
        )
    }

    /** Stable 0..1 "how sunny" value per day, so a given day always looks the same. */
    private fun dayFactor(epochDay: Long): Double {
        val x = sin(epochDay * 12.9898) * 43758.5453
        return x - kotlin.math.floor(x)
    }

    private fun obj(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })
}
