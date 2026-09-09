package com.escapebranch.pinshot.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.escapebranch.pinshot.data.ScreenshotRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PIN_ALL) return
        val uriStrings = intent.getStringArrayListExtra(EXTRA_URI_STRINGS).orEmpty()
        if (uriStrings.isEmpty()) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ScreenshotRepository(context.applicationContext).pinAll(uriStrings)
                PinshotNotifications.cancelWarning(context.applicationContext)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_PIN_ALL = "com.escapebranch.pinshot.action.PIN_ALL_EXPIRING"
        const val EXTRA_URI_STRINGS = "uri_strings"
    }
}
