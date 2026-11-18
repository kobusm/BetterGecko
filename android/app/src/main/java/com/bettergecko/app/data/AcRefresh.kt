package com.bettergecko.app.data

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Daily background refresh of AC on-hours.
 *
 * The history endpoint only covers ~3 days, so the on-phone [ACUsageLedger] has gaps whenever the
 * app isn't opened for a while. WorkManager runs this about once a day even if the app is never
 * opened, and the schedule survives reboots. Doze can delay it by hours, which doesn't matter
 * with a 3-day window. It stops if the user force-stops the app, and some manufacturers'
 * battery savers kill it too.
 */
object AcRefresh {
    private const val WORK_NAME = "ac_refresh"
    private const val LAST_RUN_KEY = "ac_refresh_last_run"

    /** Registers the daily job. KEEP leaves an existing schedule alone, so this is safe on every launch. */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<AcRefreshWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * Fetches each device's history and merges it into that device's ledger. One device failing
     * doesn't stop the rest. Returns how many devices were updated.
     */
    suspend fun refreshLedgers(
        gsns: List<String>,
        fetch: suspend (String) -> List<HistoryPoint>,
        load: (String) -> ACUsageLedger,
        save: (String, ACUsageLedger) -> Unit,
        onError: (String, Exception) -> Unit = { _, _ -> },
    ): Int {
        var updated = 0
        for (gsn in gsns) {
            try {
                save(gsn, load(gsn).merge(fetch(gsn)))
                updated++
            } catch (e: Exception) {
                onError(gsn, e)
            }
        }
        return updated
    }

    internal const val lastRunKey = LAST_RUN_KEY
}

class AcRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Store/SecureStore are initialised in BetterGeckoApp.onCreate, which runs before any worker.
        if (Store.demoMode || Store.token == null) return Result.success()
        val gsns = Store.devices.map { it.geckoSerialNumber }
        if (gsns.isEmpty()) return Result.success()
        val updated = AcRefresh.refreshLedgers(
            gsns,
            fetch = { DeviceApi.performanceHistory(it).toHistoryPoints() },
            load = Store::ledger,
            save = Store::saveLedger,
            onError = { gsn, e -> Log.w("AcRefresh", "$gsn: ${e.message}") },
        )
        if (updated == 0) return Result.retry()   // e.g. server unreachable: WorkManager backs off and retries
        Store.acRefreshLastRun = System.currentTimeMillis()
        return Result.success()
    }
}
