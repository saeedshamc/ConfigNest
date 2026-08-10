package com.example.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.ConfigFetcherRepository
import com.example.data.SourceRepository
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class ConfigRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting periodic background config refresh worker...")
        return try {
            val sourceRepository = SourceRepository(applicationContext)
            val fetcherRepository = ConfigFetcherRepository()

            val sources = sourceRepository.sourcesFlow.first()
            val proxySettings = sourceRepository.proxySettingsFlow.first()

            val result = fetcherRepository.fetchAllSources(sources, proxySettings)

            val (existingList, _) = sourceRepository.cachedConfigsFlow.first()
            val existingSet = existingList.map { it.rawConfig.trim() }.toSet()

            val newlyFetchedUnique = result.configs.filterNot { existingSet.contains(it.rawConfig.trim()) }
            val mergedList = existingList + newlyFetchedUnique

            val now = System.currentTimeMillis()
            sourceRepository.saveCachedConfigs(mergedList, now)

            Log.i(TAG, "Background config refresh successful. Added ${newlyFetchedUnique.size} new configs. Total: ${mergedList.size}")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error in background config refresh worker: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ConfigRefreshWorker"
        const val WORK_NAME = "periodic_config_refresh_work"

        fun schedule(context: Context, intervalHours: Long = 6) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<ConfigRefreshWorker>(
                intervalHours, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
            Log.i(TAG, "Scheduled periodic background config refresh every $intervalHours hours.")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Cancelled periodic background config refresh.")
        }
    }
}
