package com.escapebranch.pinshot.data

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.provider.MediaStore
import android.net.Uri
import androidx.annotation.RequiresApi

sealed interface MediaTrashResult {
    data object Success : MediaTrashResult
    data class ConsentRequired(val intentSender: IntentSender) : MediaTrashResult
    data class SystemTrashRequest(val intentSender: IntentSender) : MediaTrashResult
    data class SystemDeleteRequest(val intentSender: IntentSender) : MediaTrashResult
    data object PermissionRequiredForBackgroundWork : MediaTrashResult
    data object NotFound : MediaTrashResult
    data object Unsupported : MediaTrashResult
    data class Failed(val cause: Throwable) : MediaTrashResult
}

/** Scoped-storage transitions for Pinshot-owned screenshots. */
class MediaTrashManager(context: Context) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    fun canManageMedia(): Boolean = ManageMediaPermissionContract.isGranted(appContext)

    fun moveToTrash(uri: Uri): MediaTrashResult = userInitiatedTransition(uri, trashed = true)

    fun restore(uri: Uri): MediaTrashResult = userInitiatedTransition(uri, trashed = false)

    /**
     * MANAGE_MEDIA suppresses Android's confirmation UI for this request; it
     * does not grant direct ContentResolver.delete access by itself.
     */
    fun deletePermanently(uri: Uri): MediaTrashResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return MediaTrashResult.Unsupported
        if (canManageMedia()) return createDeleteRequest(listOf(uri))
        return try {
            when (resolver.delete(uri, null, null)) {
                0 -> MediaTrashResult.NotFound
                else -> MediaTrashResult.Success
            }
        } catch (exception: RecoverableSecurityException) {
            MediaTrashResult.ConsentRequired(exception.userAction.actionIntent.intentSender)
        } catch (exception: SecurityException) {
            MediaTrashResult.Failed(exception)
        } catch (exception: java.io.FileNotFoundException) {
            MediaTrashResult.NotFound
        } catch (throwable: Throwable) {
            MediaTrashResult.Failed(throwable)
        }
    }

    /**
     * Headless work must never create consent UI. It only runs when the special
     * media-management app-op is active.
     */
    fun moveToTrashInBackground(uri: Uri): MediaTrashResult {
        if (!canManageMedia()) return MediaTrashResult.PermissionRequiredForBackgroundWork
        return setTrashed(uri, trashed = true)
    }

    private fun userInitiatedTransition(uri: Uri, trashed: Boolean): MediaTrashResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return MediaTrashResult.Unsupported
        // The request must still be launched, but MANAGE_MEDIA means Android
        // performs it without displaying a per-item confirmation dialog.
        if (canManageMedia()) return createTrashRequest(uri, trashed)
        return setTrashed(uri, trashed)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun createTrashRequest(uri: Uri, trashed: Boolean): MediaTrashResult = try {
        MediaTrashResult.SystemTrashRequest(
            MediaStore.createTrashRequest(resolver, listOf(uri), trashed).intentSender
        )
    } catch (throwable: Throwable) {
        MediaTrashResult.Failed(throwable)
    }

    /**
     * One platform request can atomically delete every selected item. The
     * activity result is delivered only after Android finishes the whole batch.
     */
    fun createPermanentDeleteRequest(uris: List<Uri>): MediaTrashResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return MediaTrashResult.Unsupported
        if (uris.isEmpty()) return MediaTrashResult.NotFound
        return createDeleteRequest(uris)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun createDeleteRequest(uris: List<Uri>): MediaTrashResult {
        return try {
            MediaTrashResult.SystemDeleteRequest(
                MediaStore.createDeleteRequest(resolver, uris).intentSender
            )
        } catch (throwable: Throwable) {
            MediaTrashResult.Failed(throwable)
        }
    }

    private fun setTrashed(uri: Uri, trashed: Boolean): MediaTrashResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return MediaTrashResult.Unsupported
        return try {
            val values = ContentValues(1).apply {
                put(MediaStore.MediaColumns.IS_TRASHED, if (trashed) 1 else 0)
            }
            when (resolver.update(uri, values, null, null)) {
                0 -> MediaTrashResult.NotFound
                else -> MediaTrashResult.Success
            }
        } catch (exception: RecoverableSecurityException) {
            MediaTrashResult.ConsentRequired(exception.userAction.actionIntent.intentSender)
        } catch (exception: SecurityException) {
            MediaTrashResult.Failed(exception)
        } catch (exception: java.io.FileNotFoundException) {
            MediaTrashResult.NotFound
        } catch (throwable: Throwable) {
            MediaTrashResult.Failed(throwable)
        }
    }
}
