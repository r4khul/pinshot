package com.escapebranch.pinshot.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Single source of truth for screenshot membership. MediaStore supplies files;
 * Room supplies Pinshot-owned state. A trashed item must pass both checks.
 */
class ScreenshotRepository(context: Context) {
    private val mediaStore = MediaStoreHelper(context)
    private val dao = PinshotDatabase.getInstance(context).screenshotDao()

    /**
     * Result of one MediaStore scan. [newlyTracked] contains only files that
     * acquired Pinshot state during this scan; it deliberately excludes an
     * item restored outside the app, which is not a new screenshot capture.
     */
    data class ScreenshotDiscovery(
        val screenshots: List<MediaStoreScreenshot>,
        val newlyTracked: List<MediaStoreScreenshot>
    )

    suspend fun loadScreenshots(): List<MediaStoreScreenshot> = discoverScreenshots().screenshots

    /**
     * Scans the system gallery and reports the exact screenshots that are new
     * to Pinshot. Both the foreground observer and background worker use this
     * same path so a notification cannot be based on an unverified URI.
     */
    suspend fun discoverScreenshots(): ScreenshotDiscovery {
        // The UI observer and WorkManager can run at the same time. Serializing
        // the read-and-record transaction means only the first caller observes
        // a new row, preventing duplicate capture notifications.
        return discoveryMutex.withLock {
            val screenshots = mediaStore.queryScreenshots()
            ScreenshotDiscovery(screenshots, syncVisibleScreenshots(screenshots))
        }
    }

    suspend fun loadVerifiedTrashedScreenshots(): List<MediaStoreScreenshot> {
        val knownTrashedItems = dao.getTrashed()
        // A single MATCH_ONLY cursor replaces one Binder/MediaProvider query per
        // item. The helper admits rows only if both their exact URI and original
        // DATE_ADDED match a Pinshot-owned Room record.
        return mediaStore.queryKnownTrashedScreenshots(knownTrashedItems)
    }

    suspend fun metadata(): List<ScreenshotItemEntity> = dao.getAll()

    fun observeActiveMetadata() = dao.observeActive()

    fun observeTrashedMetadata() = dao.observeTrashed()

    suspend fun setPinned(uri: Uri, pinned: Boolean) = dao.setPinned(uri.toString(), pinned)

    suspend fun pinAll(uriStrings: List<String>) = dao.pinAll(uriStrings)

    suspend fun expiringWithin(now: Long, warningEnd: Long): List<ScreenshotItemEntity> =
        dao.expiringWithin(now, warningEnd)

    suspend fun expired(now: Long): List<ScreenshotItemEntity> = dao.expired(now)

    /** Exact state check used by the targeted, one-time expiry reminder. */
    suspend fun warningCandidate(
        uriString: String,
        expectedExpiration: Long,
        now: Long
    ): ScreenshotItemEntity? = dao.get(uriString)?.takeIf {
        !it.isPinned && !it.isTrashed &&
            it.expirationTimestamp == expectedExpiration &&
            it.expirationTimestamp > now
    }

    suspend fun purgeMissing(uri: Uri) = dao.delete(uri.toString())

    /** The system batch-delete result guarantees every supplied URI is gone. */
    suspend fun purgeMissing(uris: List<Uri>) {
        val uriStrings = uris.asSequence().map(Uri::toString).distinct().toList()
        if (uriStrings.isNotEmpty()) dao.deleteAll(uriStrings)
    }

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

    /** Records the subset still owned by Pinshot after a completed batch request. */
    suspend fun recordCompletedSystemTrashRequest(uris: List<Uri>, trashed: Boolean): List<Uri> {
        val completed = ArrayList<Uri>(uris.size)
        uris.distinct().forEach { uri ->
            if (dao.get(uri.toString()) != null) {
                recordTrashState(uri, trashed)
                completed += uri
            }
        }
        return completed
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

    private suspend fun syncVisibleScreenshots(media: List<MediaStoreScreenshot>): List<MediaStoreScreenshot> {
        if (media.isEmpty()) return emptyList()
        val existing = HashMap<String, ScreenshotItemEntity>(media.size)
        media.map { it.uri.toString() }
            .chunked(MAX_DATABASE_QUERY_URIS)
            .forEach { uriStrings ->
                // Room access is suspend, so this deliberately stays outside a
                // Sequence pipeline rather than hiding a database call in a map.
                dao.getByUriStrings(uriStrings).forEach { saved ->
                    existing[saved.uriString] = saved
                }
            }
        val newlyTracked = ArrayList<MediaStoreScreenshot>()
        val upserts = media.mapNotNull { screenshot ->
            val saved = existing[screenshot.uri.toString()]
            when {
                // A reused MediaStore URI is a new file, never old Pinshot state.
                saved == null || saved.dateAdded != screenshot.dateAdded -> {
                    newlyTracked += screenshot
                    ScreenshotItemEntity(
                        uriString = screenshot.uri.toString(),
                        dateAdded = screenshot.dateAdded,
                        expirationTimestamp = screenshot.dateAdded + EXPIRATION_MILLIS
                    )
                }
                // An item restored outside Pinshot is safely reconciled once.
                saved.isTrashed -> saved.copy(
                    isPinned = true,
                    isTrashed = false,
                    trashedTimestamp = null
                )
                // No Room write/emission for unchanged gallery data.
                else -> null
            }
        }
        if (upserts.isNotEmpty()) dao.upsertAll(upserts)
        return newlyTracked
    }

    private companion object {
        val discoveryMutex = Mutex()
        // Keep every Room IN query below SQLite's bind-variable ceiling.
        const val MAX_DATABASE_QUERY_URIS = 900
        val EXPIRATION_MILLIS = TimeUnit.HOURS.toMillis(24)
    }
}
