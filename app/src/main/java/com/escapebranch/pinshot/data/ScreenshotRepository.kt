package com.escapebranch.pinshot.data

import android.content.Context
import android.net.Uri
import java.util.concurrent.TimeUnit

/**
 * Single source of truth for screenshot membership. MediaStore supplies files;
 * Room supplies Pinshot-owned state. A trashed item must pass both checks.
 */
class ScreenshotRepository(context: Context) {
    private val mediaStore = MediaStoreHelper(context)
    private val dao = PinshotDatabase.getInstance(context).screenshotDao()

    suspend fun loadScreenshots(): List<MediaStoreScreenshot> {
        val screenshots = mediaStore.queryScreenshots()
        syncVisibleScreenshots(screenshots)
        return screenshots
    }

    suspend fun loadVerifiedTrashedScreenshots(): List<MediaStoreScreenshot> {
        val knownTrashedItems = dao.getAll()
            .asSequence()
            .filter { it.isTrashed }
            .toList()

        if (knownTrashedItems.isEmpty()) return emptyList()

        // Query each known screenshot URI with MATCH_ONLY. Unlike a broad system
        // trash query, this cannot leak camera, download, or chat-app media.
        return knownTrashedItems.mapNotNull { saved ->
            mediaStore.queryKnownTrashedScreenshot(Uri.parse(saved.uriString))
                ?.takeIf { it.dateAdded == saved.dateAdded }
        }.sortedByDescending { it.dateModified }
    }

    suspend fun metadata(): List<ScreenshotItemEntity> = dao.getAll()

    fun observeMetadata() = dao.observeAll()

    suspend fun setPinned(uri: Uri, pinned: Boolean) = dao.setPinned(uri.toString(), pinned)

    suspend fun pinAll(uriStrings: List<String>) = dao.pinAll(uriStrings)

    suspend fun expiringWithin(now: Long, warningEnd: Long): List<ScreenshotItemEntity> =
        dao.expiringWithin(now, warningEnd)

    suspend fun expired(now: Long): List<ScreenshotItemEntity> = dao.expired(now)

    suspend fun purgeMissing(uri: Uri) = dao.delete(uri.toString())

    /**
     * The destructive action may only target a file that is both known to
     * Pinshot and currently in Android's trash. This protects against stale UI
     * cells, URI reuse, and a file that was restored outside the app.
     */
    suspend fun isVerifiedTrashedScreenshot(uri: Uri): Boolean {
        val saved = dao.get(uri.toString()) ?: return false
        if (!saved.isTrashed) return false
        return mediaStore.queryKnownTrashedScreenshot(uri)?.dateAdded == saved.dateAdded
    }

    /** Confirms a saved URI has not become a different MediaStore item. */
    suspend fun isVerifiedActiveScreenshot(uri: Uri): Boolean {
        val saved = dao.get(uri.toString()) ?: return false
        if (saved.isTrashed) return false
        return mediaStore.queryKnownActiveScreenshot(uri)?.dateAdded == saved.dateAdded
    }

    /** See [MediaStoreHelper.exists]; null is deliberately not a success. */
    suspend fun isMissing(uri: Uri): Boolean = mediaStore.exists(uri) == false

    suspend fun displayNames(uriStrings: List<String>): List<String> = uriStrings.mapNotNull { uriString ->
        mediaStore.displayName(Uri.parse(uriString))
    }

    suspend fun moveToExpiring(uri: Uri) {
        dao.setPinned(uri.toString(), false)
        dao.setExpiration(uri.toString(), System.currentTimeMillis() + EXPIRATION_MILLIS)
    }

    suspend fun recordTrashState(uri: Uri, trashed: Boolean, timestamp: Long = System.currentTimeMillis()) {
        dao.setTrashState(uri.toString(), trashed, if (trashed) timestamp else null)
        // Restore means the user chose to keep this screenshot. Keeping it
        // pinned prevents the next expiration pass from immediately trashing it.
        if (!trashed) dao.setPinned(uri.toString(), true)
    }

    /**
     * A completed MediaStore PendingIntent is an atomic system operation. Do
     * not reject it solely because an OEM delays exposing the row to a follow-up
     * query; the next refresh will reconcile the visible list.
     */
    suspend fun recordCompletedSystemTrashRequest(uri: Uri, trashed: Boolean): Boolean {
        if (dao.get(uri.toString()) == null) return false
        recordTrashState(uri, trashed)
        return true
    }

    /**
     * Records a transition only after MediaProvider exposes the matching state.
     * This handles providers that return a non-OK activity result even though a
     * MANAGE_MEDIA trash request has already completed successfully.
     */
    suspend fun confirmAndRecordTrashState(uri: Uri, trashed: Boolean): Boolean {
        val saved = dao.get(uri.toString()) ?: return false
        val verified = if (trashed) {
            mediaStore.queryKnownTrashedScreenshot(uri)
        } else {
            mediaStore.queryKnownActiveScreenshot(uri)
        }
        if (verified?.dateAdded != saved.dateAdded) return false
        recordTrashState(uri, trashed)
        return true
    }

    private suspend fun syncVisibleScreenshots(media: List<MediaStoreScreenshot>) {
        if (media.isEmpty()) return
        val existing = dao.getAll().associateBy { it.uriString }
        val upserts = media.map { screenshot ->
            val saved = existing[screenshot.uri.toString()]
            ScreenshotItemEntity(
                uriString = screenshot.uri.toString(),
                dateAdded = screenshot.dateAdded,
                expirationTimestamp = saved?.expirationTimestamp
                    ?: screenshot.dateAdded + EXPIRATION_MILLIS,
                isPinned = saved?.isPinned ?: false,
                // Presence in the normal query proves it is not system-trashed.
                isTrashed = false,
                trashedTimestamp = null
            )
        }
        dao.upsertAll(upserts)
    }

    private companion object {
        val EXPIRATION_MILLIS = TimeUnit.HOURS.toMillis(24)
    }
}
