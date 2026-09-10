package com.escapebranch.pinshot.notifications

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.escapebranch.pinshot.data.MediaTrashManager
import com.escapebranch.pinshot.data.MediaTrashResult
import com.escapebranch.pinshot.data.ScreenshotRepository
import java.util.concurrent.TimeUnit

class ExpirationWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val repository = ScreenshotRepository(applicationContext)
        val trashManager = MediaTrashManager(applicationContext)
        val now = System.currentTimeMillis()
        // Pinshot may not have been open when a screenshot was captured. Each
        // scheduled run must discover active screenshots before evaluating the
        // 24-hour lifecycle, otherwise only screenshots seen by the UI expire.
        val discovery = try {
            repository.discoverScreenshots()
        } catch (_: SecurityException) {
            return Result.success()
        } catch (_: Exception) {
            return Result.retry()
        }
        // WorkManager is the only platform-managed path available after the UI
        // process is gone. The first scan establishes a quiet baseline, while
        // later scans can confirm screenshots captured while Pinshot was closed.
        if (CaptureNotificationTracker(applicationContext).shouldNotify(discovery.newlyTracked.size)) {
            PinshotNotifications.postCaptureDetected(
                applicationContext,
                discovery.newlyTracked.map { it.uri.toString() }
            )
        }
        // Schedule exact-item reminders after every discovery pass. This also
        // makes screenshots taken while Pinshot was closed eligible for a
        // targeted warning as soon as Android lets the worker run.
        ExpirationScheduler.scheduleWarnings(applicationContext, repository.metadata(), now)
        val warningItems = repository.expiringWithin(now, now + WARNING_WINDOW_MILLIS)
        if (warningItems.isNotEmpty()) {
            PinshotNotifications.postWarning(
                applicationContext,
                warningItems.map { it.uriString },
                warningBody(warningItems.size, repository.displayNames(warningItems.map { it.uriString }))
            )
        }

        // A worker must never attempt to surface an activity-result prompt.
        if (!trashManager.canManageMedia()) return Result.success()

        val trashedUriStrings = mutableListOf<String>()
        var shouldRetry = false
        repository.expired(now).forEach { item ->
            val uri = Uri.parse(item.uriString)
            // MediaStore row IDs may be recycled after an external deletion.
            // Never let an old Pinshot record trash a different gallery item.
            if (!repository.isVerifiedActiveScreenshot(uri)) {
                repository.purgeMissing(uri)
                return@forEach
            }
            when (trashManager.moveToTrashInBackground(uri)) {
                MediaTrashResult.Success -> {
                    if (repository.confirmAndRecordTrashState(uri, trashed = true)) {
                        trashedUriStrings += item.uriString
                    } else {
                        shouldRetry = true
                    }
                }
                MediaTrashResult.NotFound -> repository.purgeMissing(uri)
                is MediaTrashResult.Failed -> shouldRetry = true
                else -> Unit
            }
        }
        if (trashedUriStrings.isNotEmpty()) {
            PinshotNotifications.postTrashSummary(applicationContext, trashedUriStrings)
        }
        return if (shouldRetry) Result.retry() else Result.success()
    }

    private fun warningBody(count: Int, displayNames: List<String>): String = when {
        count == 1 -> "Tap to review before it moves to trash."
        count in 2..4 -> {
            val sources = displayNames.mapNotNull(::inferSourceName).distinct().take(2)
            if (sources.size == 2) {
                "Expiring within the hour: includes captures from ${sources[0]} and ${sources[1]}."
            } else {
                "Expiring within the hour. Tap to review and keep any you need."
            }
        }
        else -> "Several unpinned captures will move to your 30-day trash in 1 hour. Tap to keep any."
    }

    private fun inferSourceName(displayName: String): String? {
        val withoutExtension = displayName.substringBeforeLast('.', displayName)
        val candidate = withoutExtension
            .replace(Regex("(?i)screenshot|screen[_ -]?shot|capture|img|\\d{4}[-_]?\\d{2}[-_]?\\d{2}.*"), "")
            .trim(' ', '_', '-', '.')
        return candidate.takeIf { it.length in 2..32 && it.any(Char::isLetter) }
    }

    private companion object {
        val WARNING_WINDOW_MILLIS = TimeUnit.HOURS.toMillis(1)
    }
}
