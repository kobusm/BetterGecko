package com.bettergecko.app.data

import kotlinx.serialization.Serializable
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/**
 * A local rule: at hour:minute every day, switch the device to [mode].
 *
 * There is no server-side scheduling API, so schedules live on-device. Unlike iOS, Android can
 * run code at an exact time (AlarmManager), so the mode is applied in the background; the app
 * also catches up to whichever rule should be active whenever it comes to the foreground.
 */
@Serializable
data class ModeSchedule(
    val id: String = UUID.randomUUID().toString(),
    val hour: Int,
    val minute: Int,
    val mode: String,
    val enabled: Boolean = true,
) {
    val timeLabel: String get() = "%02d:%02d".format(hour, minute)
    val modeLabel: String get() = Modes.label(mode)
}

object ScheduleMath {
    /** Most recent past occurrence of any enabled schedule, relative to [now]. */
    fun activeOccurrence(schedules: List<ModeSchedule>, now: ZonedDateTime): Pair<String, ZonedDateTime>? {
        var best: Pair<String, ZonedDateTime>? = null
        for (s in schedules.filter { it.enabled }) {
            var occ = now.withHour(s.hour).withMinute(s.minute).withSecond(0).withNano(0)
            if (occ.isAfter(now)) occ = occ.minusDays(1)   // fired yesterday
            if (best == null || occ.isAfter(best.second)) best = s.mode to occ
        }
        return best
    }

    /** Next time [s] fires strictly after [now]. */
    fun nextOccurrence(s: ModeSchedule, now: ZonedDateTime): ZonedDateTime {
        var occ = now.withHour(s.hour).withMinute(s.minute).withSecond(0).withNano(0)
        if (!occ.isAfter(now)) occ = occ.plusDays(1)
        return occ
    }

    fun sorted(list: List<ModeSchedule>) = list.sortedWith(compareBy({ it.hour }, { it.minute }))

    fun now(): ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())
}
