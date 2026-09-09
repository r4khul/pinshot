package com.escapebranch.pinshot.notifications

import android.content.Context
import androidx.core.content.edit

/**
 * Avoids treating every pre-existing gallery image as a fresh capture on the
 * first scan after installation or after photo access is granted. Once that
 * baseline exists, every newly tracked MediaStore row is a real candidate for
 * the capture notification, regardless of whether a worker or the open UI
 * discovered it first.
 */
class CaptureNotificationTracker(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun shouldNotify(newlyTrackedCount: Int): Boolean {
        synchronized(lock) {
            if (!preferences.getBoolean(KEY_BASELINE_ESTABLISHED, false)) {
                preferences.edit { putBoolean(KEY_BASELINE_ESTABLISHED, true) }
                return false
            }
            return newlyTrackedCount > 0
        }
    }

    private companion object {
        val lock = Any()
        const val PREFERENCES_NAME = "pinshot.capture-notifications"
        const val KEY_BASELINE_ESTABLISHED = "baseline-established"
    }
}
