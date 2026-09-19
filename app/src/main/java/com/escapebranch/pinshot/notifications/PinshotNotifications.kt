package com.escapebranch.pinshot.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.util.Size
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.escapebranch.pinshot.MainActivity
import com.escapebranch.pinshot.R
import kotlin.math.max
import kotlin.math.roundToInt

object NotificationDestinations {
    const val EXTRA_DESTINATION = "pinshot.notification.destination"
    const val EXPIRING = "expiring"
    const val TRASH = "trash"
}

object PinshotNotifications {
    // A new ID is intentional: Android freezes a channel's importance after
    // creation, so existing installs need a fresh high-visibility channel.
    const val CHANNEL_ID = "screenshot_expiry_alerts_v2"
    const val WARNING_ID = 4101
    const val SUMMARY_ID = 4102
    private const val CAPTURE_CHANNEL_ID = "screenshot_capture_updates_v1"
    private const val CAPTURE_ID = 4103
    private const val MAX_THUMBNAILS = 3

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val expiryChannel = NotificationChannel(
            CHANNEL_ID,
            "Screenshot expiry alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Reminders before temporary screenshots move to trash" }
        val captureChannel = NotificationChannel(
            CAPTURE_CHANNEL_ID,
            "Screenshot capture updates",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Updates when Pinshot finds a new screenshot" }
        context.getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(expiryChannel)
            createNotificationChannel(captureChannel)
        }
    }

    fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun postWarning(context: Context, uriStrings: List<String>, body: String) {
        if (!canPost(context) || uriStrings.isEmpty()) return
        ensureChannel(context)
        val count = uriStrings.size
        val title = if (count == 1) "A screenshot expires in 1 hour" else "$count screenshots expire soon"
        val reviewIntent = destinationIntent(context, NotificationDestinations.EXPIRING)
        val pinAllIntent = pinAllIntent(context, WARNING_ID, uriStrings)
        val notification = expressiveNotification(
            context = context,
            channelId = CHANNEL_ID,
            title = title,
            body = body,
            uriStrings = uriStrings,
            primaryAction = NotificationAction("Pin & keep", R.drawable.ic_notification_pin, pinAllIntent),
            secondaryAction = NotificationAction("Review", R.drawable.ic_notification_review, reviewIntent),
            contentIntent = reviewIntent,
            category = NotificationCompat.CATEGORY_REMINDER,
            priority = NotificationCompat.PRIORITY_HIGH
        )
        notifyIfAllowed(context, WARNING_ID, notification)
    }

    fun postTrashSummary(context: Context, uriStrings: List<String>) {
        if (!canPost(context) || uriStrings.isEmpty()) return
        ensureChannel(context)
        val count = uriStrings.size
        val body = if (count == 1) {
            "It is safely recoverable from trash for 30 days."
        } else {
            "$count screenshots are safely recoverable from trash for 30 days."
        }
        val trashIntent = destinationIntent(context, NotificationDestinations.TRASH)
        val notification = expressiveNotification(
            context = context,
            channelId = CHANNEL_ID,
            title = if (count == 1) "Screenshot moved to trash" else "Screenshots moved to trash",
            body = body,
            uriStrings = uriStrings,
            primaryAction = NotificationAction("Open trash", R.drawable.ic_notification_review, trashIntent),
            secondaryAction = null,
            contentIntent = trashIntent,
            category = NotificationCompat.CATEGORY_REMINDER,
            priority = NotificationCompat.PRIORITY_HIGH
        )
        notifyIfAllowed(context, SUMMARY_ID, notification)
    }

    /** A visual confirmation for screenshots discovered after the initial scan. */
    fun postCaptureDetected(context: Context, uriStrings: List<String>) {
        if (!canPost(context) || uriStrings.isEmpty()) return
        ensureChannel(context)
        val count = uriStrings.size
        val reviewIntent = destinationIntent(context, NotificationDestinations.EXPIRING)
        val notification = expressiveNotification(
            context = context,
            channelId = CAPTURE_CHANNEL_ID,
            title = if (count == 1) "New screenshot, ready when you are" else "$count new screenshots, ready when you are",
            body = if (count == 1) "Pin it to keep it, or leave it for automatic cleanup tomorrow." else "Pin your keepers, or let Pinshot clear the rest tomorrow.",
            uriStrings = uriStrings,
            primaryAction = NotificationAction("Pin & keep", R.drawable.ic_notification_pin, pinAllIntent(context, CAPTURE_ID, uriStrings)),
            secondaryAction = NotificationAction("Review", R.drawable.ic_notification_review, reviewIntent),
            contentIntent = reviewIntent,
            category = NotificationCompat.CATEGORY_STATUS,
            priority = NotificationCompat.PRIORITY_DEFAULT
        )
        notifyIfAllowed(context, CAPTURE_ID, notification)
    }

    fun cancelWarning(context: Context) {
        NotificationManagerCompat.from(context).cancel(WARNING_ID)
    }

    private fun expressiveNotification(
        context: Context,
        channelId: String,
        title: String,
        body: String,
        uriStrings: List<String>,
        primaryAction: NotificationAction,
        secondaryAction: NotificationAction?,
        contentIntent: PendingIntent,
        category: String,
        priority: Int
    ): android.app.Notification {
        val thumbnails = uriStrings.take(MAX_THUMBNAILS).mapNotNull { loadThumbnail(context, it) }
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_pinshot_mark)
            .setColor(resolveAccentColor(context))
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setCategory(category)
            .setPriority(priority)
            // DecoratedCustomViewStyle preserves the system notification header,
            // while our content provides the screenshot story and controls.
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(notificationView(
                context, R.layout.notification_pinshot_compact, title, body, uriStrings.size,
                thumbnails, primaryAction, secondaryAction, showActions = false
            ))
            .setCustomBigContentView(notificationView(
                context, R.layout.notification_pinshot_expanded, title, body, uriStrings.size,
                thumbnails, primaryAction, secondaryAction, showActions = true
            ))
            .build()
    }

    private fun notificationView(
        context: Context,
        layoutId: Int,
        title: String,
        body: String,
        count: Int,
        thumbnails: List<Bitmap>,
        primaryAction: NotificationAction,
        secondaryAction: NotificationAction?,
        showActions: Boolean
    ): RemoteViews = RemoteViews(context.packageName, layoutId).apply {
        setTextViewText(R.id.notification_title, title)
        setTextViewText(R.id.notification_body, body)
        bindPreviews(if (showActions) thumbnails else thumbnails.take(1), count)
        if (showActions) {
            bindAction(
                containerId = R.id.notification_primary_action_container,
                labelId = R.id.notification_primary_action,
                iconId = R.id.notification_primary_icon,
                action = primaryAction
            )
            if (secondaryAction == null) {
                setViewVisibility(R.id.notification_secondary_action_container, View.GONE)
            } else {
                bindAction(
                    containerId = R.id.notification_secondary_action_container,
                    labelId = R.id.notification_secondary_action,
                    iconId = R.id.notification_secondary_icon,
                    action = secondaryAction
                )
            }
        }
    }

    private fun RemoteViews.bindPreviews(thumbnails: List<Bitmap>, count: Int) {
        val previewIds = intArrayOf(
            R.id.notification_preview_one,
            R.id.notification_preview_two,
            R.id.notification_preview_three
        )
        previewIds.forEachIndexed { index, id ->
            val bitmap = thumbnails.getOrNull(index)
            setViewVisibility(id, if (bitmap == null) View.GONE else View.VISIBLE)
            bitmap?.let { setImageViewBitmap(id, it) }
        }
        setViewVisibility(
            R.id.notification_preview_three_container,
            if (thumbnails.size >= MAX_THUMBNAILS) View.VISIBLE else View.GONE
        )
        setViewVisibility(R.id.notification_preview_strip, if (thumbnails.isEmpty()) View.GONE else View.VISIBLE)
        if (count > MAX_THUMBNAILS) {
            setTextViewText(R.id.notification_more_count, "+${count - MAX_THUMBNAILS}")
            setViewVisibility(R.id.notification_more_count, View.VISIBLE)
        } else {
            setViewVisibility(R.id.notification_more_count, View.GONE)
        }
    }

    private fun RemoteViews.bindAction(
        containerId: Int,
        labelId: Int,
        iconId: Int,
        action: NotificationAction
    ) {
        setTextViewText(labelId, action.label)
        setImageViewResource(iconId, action.iconRes)
        setOnClickPendingIntent(containerId, action.pendingIntent)
    }

    private fun pinAllIntent(context: Context, requestCode: Int, uriStrings: List<String>): PendingIntent {
        val pinAllIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_PIN_ALL
            putStringArrayListExtra(NotificationActionReceiver.EXTRA_URI_STRINGS, ArrayList(uriStrings))
        }
        return PendingIntent.getBroadcast(
            context, requestCode, pinAllIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun loadThumbnail(context: Context, uriString: String): Bitmap? = runCatching {
        val uri = Uri.parse(uriString)
        val edge = max(96, (context.resources.displayMetrics.density * 88).roundToInt())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(uri, Size(edge, edge), null)
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            while (max(bounds.outWidth / sample, bounds.outHeight / sample) > edge * 2) sample *= 2
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    }.getOrNull()

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

    private data class NotificationAction(
        val label: String,
        val iconRes: Int,
        val pendingIntent: PendingIntent
    )
}
