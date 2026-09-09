package com.escapebranch.pinshot.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings

/** Entry point for Android's special media-management permission. */
object ManageMediaPermissionContract {
    fun isGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && MediaStore.canManageMedia(context)

    fun settingsIntent(context: Context): Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    }.apply {
        data = Uri.parse("package:${context.packageName}")
    }
}
