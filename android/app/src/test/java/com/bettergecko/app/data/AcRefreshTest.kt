package com.bettergecko.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class AcRefreshTest {
    // One hour of element time on 2026-11-16 (SAST), in 5-minute samples.
    private val start = Instant.parse("2026-11-16T08:00:00Z")
    private fun hour(on: Int = 12) = (0..12).map {
        HistoryPoint(start.plusSeconds(it * 300L), null, null, null, null, null, null, if (it < on) "ac_out" else "idle")
    }

    @Test fun mergesEveryDeviceAndSkipsFailuresWithoutStopping() = runBlocking {
        val store = mutableMapOf<String, ACUsageLedger>()
        val errors = mutableListOf<String>()
        val updated = AcRefresh.refreshLedgers(
            listOf("aaa", "bad", "ccc"),
            fetch = { if (it == "bad") throw ApiException(500, "boom") else hour() },
            load = { store[it] ?: ACUsageLedger() },
            save = { gsn, l -> store[gsn] = l },
            onError = { gsn, _ -> errors += gsn },
        )
        assertEquals(2, updated)
        assertEquals(listOf("bad"), errors)
        assertEquals(3600.0, store["aaa"]!!.days["2026-11-16"]!!, 0.0)
        assertEquals(3600.0, store["ccc"]!!.days["2026-11-16"]!!, 0.0)
        assertNull(store["bad"])
    }

    @Test fun laterSmallerWindowNeverLowersADay() = runBlocking {
        val store = mutableMapOf<String, ACUsageLedger>()
        val load = { gsn: String -> store[gsn] ?: ACUsageLedger() }
        val save = { gsn: String, l: ACUsageLedger -> store[gsn] = l }
        AcRefresh.refreshLedgers(listOf("aaa"), { hour(12) }, load, save)
        AcRefresh.refreshLedgers(listOf("aaa"), { hour(6) }, load, save)
        assertEquals(3600.0, store["aaa"]!!.days["2026-11-16"]!!, 0.0)
    }

    // Shape returned by the worker's GET /usage.
    private val sample = """{"gsn":"fce8c0de16e0","lastRun":{"ran_at":"2026-11-17T20:10:15.590Z","ok":1,"error":null},"months":[{"month":"2026-11","hours":0.67,"days":[{"day":"2026-11-15","hours":0.08,"seconds":300},{"day":"2026-11-16","hours":0.42,"seconds":1500}]},{"month":"2026-10","hours":0,"days":[]}]}"""

    @Test fun parsesTrackerDays() {
        assertEquals(mapOf("2026-11-15" to 300.0, "2026-11-16" to 1500.0), Tracker.parseDays(sample))
    }

    @Test fun mergeRemoteKeepsTheLargerDay() {
        val l = ACUsageLedger(mapOf("2026-11-15" to 600.0, "2026-11-16" to 900.0))
            .mergeRemote(mapOf("2026-11-15" to 300.0, "2026-11-16" to 1500.0, "2026-11-01" to 60.0))
        assertEquals(mapOf("2026-11-15" to 600.0, "2026-11-16" to 1500.0, "2026-11-01" to 60.0), l.days)
    }

    @Test fun trackerStillUpdatesWhenHistoryFails() = runBlocking {
        val store = mutableMapOf<String, ACUsageLedger>()
        val updated = AcRefresh.refreshLedgers(
            listOf("aaa"), fetch = { throw ApiException(500, "down") },
            load = { store[it] ?: ACUsageLedger() }, save = { g, l -> store[g] = l },
            remote = { mapOf("2026-11-01" to 7200.0) },
        )
        assertEquals(1, updated)
        assertEquals(7200.0, store["aaa"]!!.days["2026-11-01"]!!, 0.0)
    }

    @Test fun trackerFailureDoesNotBlockHistory() = runBlocking {
        val store = mutableMapOf<String, ACUsageLedger>()
        val updated = AcRefresh.refreshLedgers(
            listOf("aaa"), fetch = { hour() },
            load = { store[it] ?: ACUsageLedger() }, save = { g, l -> store[g] = l },
            remote = { throw ApiException(401, "Wrong API key") },
        )
        assertEquals(1, updated)
        assertEquals(3600.0, store["aaa"]!!.days["2026-11-16"]!!, 0.0)
    }
}
