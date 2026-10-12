package com.bettergecko.app.data

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The server is hosted in South Africa; timestamps and day boundaries are SAST (UTC+2, no DST). */
val SAST: ZoneId = ZoneId.of("Africa/Johannesburg")

data class SessionInfo(
    val email: String,
    val token: String,
    val role: String,
    val deviceID: String,
)

@Serializable
data class GeckoDevice(
    val geckoSerialNumber: String,
    val friendlyName: String? = null,
) {
    val displayName: String get() = friendlyName?.takeIf { it.isNotEmpty() } ?: geckoSerialNumber
}

// MARK: - Modes

data class GeckoMode(val id: String, val label: String)

object Modes {
    const val DEFAULT = "AC_AND_PV"

    val all = listOf(
        GeckoMode("ALL_OFF", "Off"),
        GeckoMode("PV_ONLY", "Solar Only"),
        GeckoMode("AC_AND_PV", "Solar + AC"),
        GeckoMode("AC_ONLY", "AC Only"),
    )

    fun label(mode: String): String = all.firstOrNull { it.id == mode }?.label ?: mode
}

// MARK: - State

enum class GeckoState(val raw: String?, val label: String) {
    SOLAR_HEATING("pv_out", "Solar Heating"),
    ELEMENT_HEATING("ac_out", "AC Heating"),
    OFF("off", "Off"),
    IDLE("idle", "Idle"),
    UNKNOWN(null, "Unknown");

    companion object {
        fun from(raw: String?): GeckoState = entries.firstOrNull { it.raw != null && it.raw == raw } ?: UNKNOWN
    }
}

// MARK: - History

@Serializable
data class PerformanceHistoryResponse(
    val timestamps: List<String>? = null,
    val temperatures: List<Double?>? = null,
    val pvVoltage: List<Int?>? = null,
    val mpptVoltage: List<Int?>? = null,
    val pvInPower: List<Int?>? = null,
    val pvOutPower: List<Int?>? = null,
    val acOutPower: List<Int?>? = null,
    val state: List<String?>? = null,
    val pvUsageWeek: Int? = null,   // minutes solar was active this week
    val acUsageWeek: Int? = null,   // minutes element was active this week
    val savingsWeek: Double? = null,
) {
    /** Server returns newest-first; this returns oldest-first for charts. */
    fun toHistoryPoints(): List<HistoryPoint> {
        val ts = timestamps ?: return emptyList()
        return ts.mapIndexedNotNull { i, s ->
            val instant = runCatching { Instant.parse(s) }.getOrNull() ?: return@mapIndexedNotNull null
            HistoryPoint(
                timestamp = instant,
                temperature = temperatures?.getOrNull(i),
                pvVoltage = pvVoltage?.getOrNull(i)?.div(3),   // scale reported PV voltage ÷3
                mpptVoltage = mpptVoltage?.getOrNull(i),
                pvInPower = pvInPower?.getOrNull(i),
                pvOutPower = pvOutPower?.getOrNull(i),
                acOutPower = acOutPower?.getOrNull(i),
                state = state?.getOrNull(i),
            )
        }.reversed()
    }

    val latestTemperature: Double? get() = temperatures?.firstOrNull()
    val latestState: String? get() = state?.firstOrNull()
    val latestPVVoltage: Int? get() = pvVoltage?.firstOrNull()?.div(3)
}

data class HistoryPoint(
    val timestamp: Instant,
    val temperature: Double?,
    val pvVoltage: Int?,
    val mpptVoltage: Int?,
    val pvInPower: Int?,
    val pvOutPower: Int?,
    val acOutPower: Int?,
    val state: String?,
) {
    val pvOn: Int get() = if (state == "pv_out") 1 else 0
    val acOn: Int get() = if (state == "ac_out") 1 else 0
}

data class DeviceReading(
    val temperature: Double?,
    val state: GeckoState,
    val pvVoltage: Int?,
)

// MARK: - Time ranges

enum class TimeRange(val label: String, val seconds: Long) {
    ONE_HOUR("1H", 3600),
    SIX_HOURS("6H", 6 * 3600),
    ONE_DAY("24H", 24 * 3600),
    THREE_DAYS("3D", 3 * 24 * 3600);

    fun filter(history: List<HistoryPoint>, now: Instant = Instant.now()): List<HistoryPoint> {
        val cutoff = now.minusSeconds(seconds)
        return history.filter { it.timestamp >= cutoff }
    }
}

/** Seconds spent in each state across consecutive points. */
data class StateTotals(val solarSecs: Double, val elementSecs: Double, val idleSecs: Double)

fun stateTotals(points: List<HistoryPoint>): StateTotals {
    var solar = 0.0; var element = 0.0; var idle = 0.0
    for (i in 0 until points.size - 1) {
        val dt = (points[i + 1].timestamp.toEpochMilli() - points[i].timestamp.toEpochMilli()) / 1000.0
        when (GeckoState.from(points[i].state)) {
            GeckoState.SOLAR_HEATING -> solar += dt
            GeckoState.ELEMENT_HEATING -> element += dt
            else -> idle += dt
        }
    }
    return StateTotals(solar, element, idle)
}

fun formatDuration(seconds: Double): String {
    val s = seconds.toLong()
    val h = s / 3600
    val m = (s % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

fun buildCsv(points: List<HistoryPoint>): String = buildString {
    append("Timestamp,Temperature (°C),PV Voltage (V),PV On,AC On,State\n")
    for (p in points) {
        val temp = p.temperature?.let { "%.4f".format(java.util.Locale.US, it) } ?: ""
        append("${p.timestamp},$temp,${p.pvVoltage ?: ""},${p.pvOn},${p.acOn},${p.state ?: ""}\n")
    }
}

// MARK: - AC usage ledger

/**
 * Locally-accumulated AC (element) on-time per SAST calendar day. The performance-history
 * endpoint only covers the last few days, so monthly totals are built up by merging each
 * fetched window into this ledger.
 */
@Serializable
data class ACUsageLedger(val days: Map<String, Double> = emptyMap()) {

    /** Merges AC on-time from an oldest-first window. Each day keeps the larger of old and new totals,
     *  because the window's oldest day may be only partially covered. */
    fun merge(history: List<HistoryPoint>): ACUsageLedger {
        if (history.size < 2) return this
        val fresh = mutableMapOf<String, Double>()
        for (i in 0 until history.size - 1) {
            if (GeckoState.from(history[i].state) != GeckoState.ELEMENT_HEATING) continue
            // Cap gaps so missing data isn't billed as an hour-long element run.
            val dt = minOf((history[i + 1].timestamp.toEpochMilli() - history[i].timestamp.toEpochMilli()) / 1000.0, 3600.0)
            if (dt <= 0) continue
            val day = DAY_FMT.format(history[i].timestamp)
            fresh[day] = (fresh[day] ?: 0.0) + dt
        }
        val merged = days.toMutableMap()
        for ((day, secs) in fresh) merged[day] = maxOf(merged[day] ?: 0.0, secs)
        return ACUsageLedger(merged)
    }

    /** AC on-hours for the SAST calendar month containing [instant]. */
    fun hoursInMonthOf(instant: Instant): Double {
        val prefix = DAY_FMT.format(instant).take(7)
        return days.filterKeys { it.startsWith(prefix) }.values.sum() / 3600
    }

    companion object {
        private val DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(SAST)

        fun previousMonth(instant: Instant): Instant =
            instant.atZone(SAST).minusMonths(1).toInstant()
    }
}

// MARK: - Settings cache

/** Last-known setpoints/mode; the REST API has no endpoint that returns them. */
@Serializable
data class PersistedSettings(
    val targetTemp: Double = 50.0,
    val pvTargetTemp: Double = 60.0,
    val mode: String = Modes.DEFAULT,
)

val AppJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; coerceInputValues = true }
