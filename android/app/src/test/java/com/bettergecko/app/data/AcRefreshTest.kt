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
}
