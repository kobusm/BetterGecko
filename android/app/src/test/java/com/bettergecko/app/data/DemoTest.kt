package com.bettergecko.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DemoTest {
    private val now = Instant.parse("2026-10-14T12:03:20Z")   // 14:03 SAST
    private val defaults = PersistedSettings(targetTemp = 45.0, pvTargetTemp = 65.0, mode = "AC_AND_PV")

    @Test fun coversThreeDaysAtFiveMinutesNewestFirst() {
        val h = Demo.history(now, defaults)
        assertEquals(3 * 24 * 12 + 1, h.timestamps!!.size)
        assertEquals("2026-10-14T12:00:00Z", h.timestamps!!.first())
        val pts = h.toHistoryPoints()
        assertTrue(pts.zipWithNext().all { (a, b) -> a.timestamp < b.timestamp })   // parses oldest-first
    }

    @Test fun solarAndElementBothRunAndTemperatureStaysPlausible() {
        val pts = Demo.history(now, defaults).toHistoryPoints()
        assertTrue(pts.any { it.pvOn == 1 })
        assertTrue(pts.any { it.acOn == 1 })
        assertTrue(pts.all { it.temperature!! in 18.0..75.0 })
        assertTrue(pts.filter { it.pvOn == 1 }.all { it.pvVoltage!! > 0 })
    }

    @Test fun elementRunsWithinTheLastDay() {
        // The 24H view should show AC activity, not just the weekly total.
        val day = TimeRange.ONE_DAY.filter(Demo.history(now, defaults).toHistoryPoints(), now)
        assertTrue(day.any { it.acOn == 1 })
    }

    @Test fun respectsMode() {
        val solarOnly = Demo.history(now, defaults.copy(mode = "PV_ONLY")).toHistoryPoints()
        assertTrue(solarOnly.none { it.acOn == 1 })
        val off = Demo.history(now, defaults.copy(mode = "ALL_OFF")).toHistoryPoints()
        assertTrue(off.none { it.pvOn == 1 || it.acOn == 1 })
    }

    @Test fun isDeterministic() {
        assertEquals(Demo.history(now, defaults), Demo.history(now, defaults))
    }
}
