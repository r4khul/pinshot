package com.escapebranch.pinshot.notifications

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object ExpirationScheduler {
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<ExpirationWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            "pinshot-expiration-lifecycle",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
