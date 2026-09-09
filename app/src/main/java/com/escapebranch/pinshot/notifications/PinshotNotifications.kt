package com.escapebranch.pinshot.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.util.TypedValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.escapebranch.pinshot.MainActivity
import com.escapebranch.pinshot.R

object NotificationDestinations {
    const val EXTRA_DESTINATION = "pinshot.notification.destination"
    const val EXPIRING = "expiring"
    const val TRASH = "trash"
}

object PinshotNotifications {
    const val CHANNEL_ID = "screenshot_lifecycle"
    const val WARNING_ID = 4101
    const val SUMMARY_ID = 4102

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screenshot Expiration Alerts",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Reminders before temporary screenshots move to trash" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun postWarning(context: Context, uriStrings: List<String>, body: String) {
        if (!canPost(context) || uriStrings.isEmpty()) return
        ensureChannel(context)
        val count = uriStrings.size
        val title = if (count == 1) "1 screenshot expires in 1 hour" else "$count screenshots expire soon"
        val reviewIntent = destinationIntent(context, NotificationDestinations.EXPIRING)
        val pinAllIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_PIN_ALL
            putStringArrayListExtra(NotificationActionReceiver.EXTRA_URI_STRINGS, ArrayList(uriStrings))
        }
        val pinAllPendingIntent = PendingIntent.getBroadcast(
            context, WARNING_ID, pinAllIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setColor(resolveAccentColor(context))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(reviewIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(0, "Pin all", pinAllPendingIntent)
            .addAction(0, "Review", reviewIntent)
            .build()
        notifyIfAllowed(context, WARNING_ID, notification)
    }

    fun postTrashSummary(context: Context, count: Int) {
        if (!canPost(context) || count <= 0) return
        ensureChannel(context)
        val body = "$count screenshots moved to trash. They remain recoverable for 30 days."
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setColor(resolveAccentColor(context))
            .setContentTitle("Storage cleaned up")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(destinationIntent(context, NotificationDestinations.TRASH))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notifyIfAllowed(context, SUMMARY_ID, notification)
    }

    fun cancelWarning(context: Context) {
        NotificationManagerCompat.from(context).cancel(WARNING_ID)
    }

    private fun notifyIfAllowed(context: Context, id: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun destinationIntent(context: Context, destination: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(NotificationDestinations.EXTRA_DESTINATION, destination)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context, destination.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun resolveAccentColor(context: Context): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(android.R.attr.colorAccent, value, true)) value.data else Color.BLUE
    }
}
