package com.escapebranch.pinshot.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.escapebranch.pinshot.MainActivity
import com.escapebranch.pinshot.data.ScreenshotRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PIN_ALL -> pinAll(context, intent)
            ACTION_OPEN_DESTINATION -> openDestination(context, intent)
        }
    }

    private fun pinAll(context: Context, intent: Intent) {
        val uriStrings = intent.getStringArrayListExtra(EXTRA_URI_STRINGS).orEmpty()
        if (uriStrings.isEmpty()) return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, NO_NOTIFICATION_ID)
        val appContext = context.applicationContext

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ScreenshotRepository(appContext).pinAll(uriStrings)
                withContext(Dispatchers.Main.immediate) {
                    PinshotNotifications.cancel(appContext, notificationId)
                    Toast.makeText(appContext, keptMessage(uriStrings.size), Toast.LENGTH_SHORT).show()
                }
            } catch (error: Exception) {
                Log.e(TAG, "Could not keep screenshots from notification", error)
                withContext(Dispatchers.Main.immediate) {
                    Toast.makeText(appContext, "Couldn't keep screenshots. Try again.", Toast.LENGTH_SHORT).show()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun openDestination(context: Context, intent: Intent) {
        val destination = intent.getStringExtra(EXTRA_DESTINATION) ?: return
        PinshotNotifications.cancel(
            context.applicationContext,
            intent.getIntExtra(EXTRA_NOTIFICATION_ID, NO_NOTIFICATION_ID)
        )
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            putExtra(NotificationDestinations.EXTRA_DESTINATION, destination)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }

    private fun keptMessage(count: Int): String =
        if (count == 1) "Screenshot kept" else "$count screenshots kept"

    companion object {
        const val ACTION_PIN_ALL = "com.escapebranch.pinshot.action.PIN_ALL_EXPIRING"
        const val ACTION_OPEN_DESTINATION = "com.escapebranch.pinshot.action.OPEN_NOTIFICATION_DESTINATION"
        const val EXTRA_URI_STRINGS = "uri_strings"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_DESTINATION = "destination"
        const val NO_NOTIFICATION_ID = -1
        private const val TAG = "NotificationAction"
    }
}
