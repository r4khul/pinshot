package com.escapebranch.pinshot.notifications

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.escapebranch.pinshot.data.ScreenshotItemEntity
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

    /**
     * A dedicated work request is more reliable for the one-hour reminder than
     * waiting for the next periodic discovery pass. It remains inexact by
     * Android design, but is not tied to the 15-minute worker cadence.
     */
    fun scheduleWarnings(context: Context, items: List<ScreenshotItemEntity>, now: Long) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        items.asSequence()
            .filter { !it.isPinned && !it.isTrashed && it.expirationTimestamp > now }
            .forEach { item ->
                val delayMillis = (item.expirationTimestamp - now - WARNING_LEAD_MILLIS).coerceAtLeast(0L)
                val request = OneTimeWorkRequestBuilder<ExpirationWarningWorker>()
                    .setInputData(
                        workDataOf(
                            ExpirationWarningWorker.KEY_URI to item.uriString,
                            ExpirationWarningWorker.KEY_EXPIRATION to item.expirationTimestamp
                        )
                    )
                    .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                    .build()
                workManager.enqueueUniqueWork(
                    "pinshot-expiry-warning-${item.uriString.hashCode()}-${item.expirationTimestamp}",
                    ExistingWorkPolicy.REPLACE,
                    request
                )
            }
    }

    private val WARNING_LEAD_MILLIS = TimeUnit.HOURS.toMillis(1)
}
