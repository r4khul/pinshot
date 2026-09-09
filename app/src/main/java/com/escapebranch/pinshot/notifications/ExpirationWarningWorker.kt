package com.escapebranch.pinshot.notifications

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.escapebranch.pinshot.data.ScreenshotRepository

/** Posts one specific reminder close to its screenshot's 24-hour expiry. */
class ExpirationWarningWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val uriString = inputData.getString(KEY_URI) ?: return Result.success()
        val expectedExpiration = inputData.getLong(KEY_EXPIRATION, NO_EXPIRATION)
        if (expectedExpiration == NO_EXPIRATION) return Result.success()

        val repository = ScreenshotRepository(applicationContext)
        val candidate = repository.warningCandidate(uriString, expectedExpiration, System.currentTimeMillis())
            ?: return Result.success()
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return Result.success()
        // The item may have been deleted or changed outside Pinshot since the
        // reminder was enqueued. Never notify about a reused MediaStore ID.
        if (!repository.isVerifiedActiveScreenshot(uri)) return Result.success()

        val name = repository.displayNames(listOf(uriString)).firstOrNull()
        PinshotNotifications.postWarning(
            applicationContext,
            uriStrings = listOf(uriString),
            body = if (name == null) {
                "Tap to review before it moves to trash."
            } else {
                "$name expires soon. Tap to review."
            }
        )
        return Result.success()
    }

    companion object {
        const val KEY_URI = "uri"
        const val KEY_EXPIRATION = "expiration"
        const val NO_EXPIRATION = Long.MIN_VALUE
    }
}
