package com.alexalx.weatheralerts

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class MonitorWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(MonitorScheduler.PREFS, Context.MODE_PRIVATE)
        return try {
            val newActive = AlertEngine.sync(applicationContext)
            val now = System.currentTimeMillis()
            prefs.edit()
                .putLong(MonitorScheduler.KEY_LAST_RUN, now)
                .putInt(MonitorScheduler.KEY_LAST_NEW, newActive)
                .putString(MonitorScheduler.KEY_LAST_ERROR, "")
                .putString(MonitorScheduler.KEY_LAST_MODE, inputData.getString(MonitorScheduler.KEY_RUN_MODE) ?: "periodic")
                .apply()
            Result.success(
                Data.Builder()
                    .putLong("completed_at", now)
                    .putInt("new_active_alerts", newActive)
                    .build()
            )
        } catch (e: Exception) {
            prefs.edit()
                .putLong(MonitorScheduler.KEY_LAST_RUN, System.currentTimeMillis())
                .putString(MonitorScheduler.KEY_LAST_ERROR, (e.message ?: e.javaClass.simpleName).take(200))
                .putString(MonitorScheduler.KEY_LAST_MODE, inputData.getString(MonitorScheduler.KEY_RUN_MODE) ?: "periodic")
                .apply()
            Result.retry()
        }
    }
}

object MonitorScheduler {
    const val PREFS = "monitor_state"
    const val KEY_LAST_RUN = "last_run_at"
    const val KEY_LAST_NEW = "last_new_active"
    const val KEY_LAST_ERROR = "last_error"
    const val KEY_LAST_MODE = "last_mode"
    const val KEY_RUN_MODE = "run_mode"

    private const val UNIQUE = "emergency_alert_aggregator_monitor"
    const val MIN_INTERVAL_MINUTES = 15L

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val periodic = PeriodicWorkRequestBuilder<MonitorWorker>(MIN_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .addTag(UNIQUE)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("periodic_scheduled", true).apply()
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<MonitorWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder().putString(KEY_RUN_MODE, "manual_worker_test").build())
            .addTag("one_time_monitor_check")
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
}
