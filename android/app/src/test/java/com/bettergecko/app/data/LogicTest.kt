package com.bettergecko.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class HistoryTest {
    @Test fun parsesNewestFirstIntoOldestFirstAndScalesPvVoltage() {
        val r = PerformanceHistoryResponse(
            timestamps = listOf("2026-10-11T10:05:00.000Z", "2026-10-11T10:00:00.000Z", "garbage"),
            temperatures = listOf(55.5, 54.0, 1.0),
            pvVoltage = listOf(300, 150, 0),
            state = listOf("pv_out", "ac_out", "idle"),
        )
        val pts = r.toHistoryPoints()
        assertEquals(2, pts.size)                       // bad timestamp dropped
        assertEquals(54.0, pts[0].temperature!!, 0.0)   // oldest first
        assertEquals(50, pts[0].pvVoltage)              // 150 / 3
        assertEquals(1, pts[0].acOn)
        assertEquals(1, pts[1].pvOn)
        assertEquals(100, r.latestPVVoltage)
        assertEquals(55.5, r.latestTemperature!!, 0.0)
    }

    @Test fun stateTotalsAttributeIntervalToStartingState() {
        val t0 = Instant.parse("2026-10-11T10:00:00Z")
        val pts = listOf(
            point(t0, "pv_out"), point(t0.plusSeconds(600), "ac_out"),
            point(t0.plusSeconds(900), "idle"), point(t0.plusSeconds(1000), "idle"),
        )
        val s = stateTotals(pts)
        assertEquals(600.0, s.solarSecs, 0.0)
        assertEquals(300.0, s.elementSecs, 0.0)
        assertEquals(100.0, s.idleSecs, 0.0)
    }

    @Test fun formatsDurations() {
        assertEquals("0m", formatDuration(59.0))
        assertEquals("5m", formatDuration(300.0))
        assertEquals("2h 3m", formatDuration(2 * 3600 + 180.0))
    }

    @Test fun unknownStateFallsBack() {
        assertEquals(GeckoState.UNKNOWN, GeckoState.from(null))
        assertEquals(GeckoState.UNKNOWN, GeckoState.from("weird"))
        assertEquals(GeckoState.ELEMENT_HEATING, GeckoState.from("ac_out"))
    }
}

class LedgerTest {
    @Test fun mergesPerSastDayCapsGapsAndKeepsMax() {
        // 21:30 UTC = 23:30 SAST on the 10th; 22:30 UTC = 00:30 SAST on the 11th.
        val a = Instant.parse("2026-10-10T21:30:00Z")
        val hist = listOf(
            point(a, "ac_out"),
            point(a.plusSeconds(1800), "ac_out"),        // 30 min on the 10th
            point(a.plusSeconds(3600), "idle"),          // 30 min on the 11th (starts 00:00 SAST)
            point(a.plusSeconds(3600 + 7200), "ac_out"), // idle gap not billed
            point(a.plusSeconds(3600 + 7200 + 3 * 3600), "idle"), // 3 h gap capped to 1 h
        )
        val l = ACUsageLedger().merge(hist)
        assertEquals(1800.0, l.days["2026-10-10"]!!, 0.0)
        assertEquals(1800.0 + 3600.0, l.days["2026-10-11"]!!, 0.0)

        // Re-merging a partial window keeps the larger total.
        val partial = ACUsageLedger().merge(hist.drop(3))
        assertEquals(1800.0 + 3600.0, l.merge(hist.drop(3)).days["2026-10-11"]!!, 0.0)
        assertEquals(3600.0, partial.days["2026-10-11"]!!, 0.0)

        assertEquals((1800.0 + 1800 + 3600) / 3600, l.hoursInMonthOf(Instant.parse("2026-10-20T00:00:00Z")), 1e-9)
        assertEquals(0.0, l.hoursInMonthOf(Instant.parse("2026-09-20T00:00:00Z")), 0.0)
    }
}

class ScheduleTest {
    private val zone = ZoneId.of("Africa/Johannesburg")
    private fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 10, 11, h, m, 0, 0, zone)

    @Test fun activeOccurrenceIsMostRecentPastRule() {
        val list = listOf(
            ModeSchedule(hour = 6, minute = 0, mode = "PV_ONLY"),
            ModeSchedule(hour = 18, minute = 0, mode = "AC_AND_PV"),
            ModeSchedule(hour = 12, minute = 0, mode = "ALL_OFF", enabled = false),
        )
        assertEquals("PV_ONLY", ScheduleMath.activeOccurrence(list, at(13, 0))!!.first)
        // Before 06:00 the 18:00 rule from yesterday is active.
        val early = ScheduleMath.activeOccurrence(list, at(5, 0))!!
        assertEquals("AC_AND_PV", early.first)
        assertEquals(10, early.second.dayOfMonth)
        assertNull(ScheduleMath.activeOccurrence(emptyList(), at(5, 0)))
    }

    @Test fun nextOccurrenceRollsToTomorrow() {
        val s = ModeSchedule(hour = 6, minute = 0, mode = "PV_ONLY")
        assertEquals(12, ScheduleMath.nextOccurrence(s, at(6, 0)).dayOfMonth)
        assertEquals(11, ScheduleMath.nextOccurrence(s, at(5, 59)).dayOfMonth)
    }
}

class EngineIOTest {
    @Test fun splitsFramedPackets() {
        val payload = "${EngineIO.frame("0{\"sid\":\"abc\"}")}${EngineIO.frame("40")}"
        assertEquals(listOf("0{\"sid\":\"abc\"}", "40"), EngineIO.parsePackets(payload))
        assertEquals("abc", EngineIO.parseSid(payload))
        assertEquals(emptyList<String>(), EngineIO.parsePackets("garbage"))
    }

    @Test fun appliesHeartbeatAndModeEvents() {
        var live = LiveSnapshot()
        live = EngineIO.apply("""42["heartbeatUpdate",{"AC_MAX":52.0,"PV_MAX":65,"temperature":48.5,"FW_VERSION":"1.2","rssi":-61}]""", "g1", live)
        live = EngineIO.apply("""42["userGeckos",{"userGeckos":[{"geckoSerialNumber":"g0","geckoMode":"ALL_OFF"},{"geckoSerialNumber":"g1","geckoMode":"PV_ONLY"}]}]""", "g1", live)
        live = EngineIO.apply("3", "g1", live)   // pong ignored
        assertEquals(52, live.acMax)
        assertEquals(65, live.pvMax)
        assertEquals(48.5, live.temperature!!, 0.0)
        assertEquals("1.2", live.fwVersion)
        assertEquals(-61, live.rssi)
        assertEquals("PV_ONLY", live.geckoMode)
        assertEquals(true, live.isComplete)
    }
}

private fun point(t: Instant, state: String) = HistoryPoint(t, null, null, null, null, null, null, state)
