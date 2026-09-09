package com.escapebranch.pinshot.ui

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.escapebranch.pinshot.data.ManageMediaPermissionContract
import com.escapebranch.pinshot.data.MediaStoreScreenshot
import com.escapebranch.pinshot.data.MediaTrashManager
import com.escapebranch.pinshot.data.MediaTrashResult
import com.escapebranch.pinshot.data.ScreenshotItemEntity
import com.escapebranch.pinshot.data.ScreenshotRepository
import com.escapebranch.pinshot.notifications.PinshotNotifications
import com.escapebranch.pinshot.notifications.ExpirationScheduler
import com.escapebranch.pinshot.notifications.CaptureNotificationTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ScreenshotUiItem(
    val uri: Uri,
    val displayName: String,
    val dateAdded: Long,
    val relativePath: String,
    val expirationTimestamp: Long,
    val isPinned: Boolean,
    val trashedTimestamp: Long?
) {
    fun remainingMillis(now: Long = System.currentTimeMillis()) =
        (expirationTimestamp - now).coerceAtLeast(0L)

    fun remainingHours(now: Long = System.currentTimeMillis()) =
        TimeUnit.MILLISECONDS.toHours(remainingMillis(now)).coerceAtLeast(1L)

    fun trashDaysRemaining(now: Long = System.currentTimeMillis()) =
        (30L - TimeUnit.MILLISECONDS.toDays(now - (trashedTimestamp ?: now))).coerceAtLeast(1L)
}

data class MediaWriteConsentRequest(
    val intentSender: IntentSender,
    val item: ScreenshotUiItem,
    val trashed: Boolean,
    val retryDirectUpdateAfterResult: Boolean = false,
    val isPermanentDelete: Boolean = false,
    val items: List<ScreenshotUiItem> = listOf(item)
)

sealed interface MediaSnackbarEvent {
    /** Every item here completed the Android trash transition successfully. */
    data class MovedToTrash(val items: List<ScreenshotUiItem>) : MediaSnackbarEvent {
        val count: Int get() = items.size
    }
    data class Recovered(val count: Int) : MediaSnackbarEvent
}

class PinshotViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ScreenshotRepository(application)
    private val trashManager = MediaTrashManager(application)
    private val captureNotificationTracker = CaptureNotificationTracker(application)
    private val activeMedia = MutableStateFlow<List<MediaStoreScreenshot>>(emptyList())
    private val trashedMedia = MutableStateFlow<List<MediaStoreScreenshot>>(emptyList())
    private val now = MutableStateFlow(System.currentTimeMillis())
    private val _isLoading = MutableStateFlow(false)
    private val _message = MutableStateFlow<String?>(null)
    private val _needsManageMediaPermission = MutableStateFlow(false)
    private val _silentCleanupEnabled = MutableStateFlow(trashManager.canManageMedia())
    private val _writeConsentRequest = MutableStateFlow<MediaWriteConsentRequest?>(null)
    private val _mediaSnackbarEvents = MutableSharedFlow<MediaSnackbarEvent>(extraBufferCapacity = 1)
    private val _hasPendingMediaMutation = MutableStateFlow(false)
    private val consentQueue = ArrayDeque<MediaWriteConsentRequest>()
    private val inFlightUris = mutableSetOf<String>()
    private val refreshMutex = Mutex()
    private val pendingTrashFeedback = mutableListOf<ScreenshotUiItem>()
    private var pendingRecoveryCount = 0
    private var trashFeedbackJob: Job? = null
    private var recoveryFeedbackJob: Job? = null
    private var mediaStoreRefreshJob: Job? = null

    val isLoading: StateFlow<Boolean> = _isLoading
    val message: StateFlow<String?> = _message
    val needsManageMediaPermission: StateFlow<Boolean> = _needsManageMediaPermission
    val silentCleanupEnabled: StateFlow<Boolean> = _silentCleanupEnabled
    val writeConsentRequest: StateFlow<MediaWriteConsentRequest?> = _writeConsentRequest
    val hasPendingMediaMutation: StateFlow<Boolean> = _hasPendingMediaMutation
    val mediaSnackbarEvents = _mediaSnackbarEvents.asSharedFlow()

    private val activeItems = combine(activeMedia, repository.observeActiveMetadata()) { media, entities ->
        media.toUiItems(entities)
    }
    val screenshots = activeItems.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val expiring = combine(activeItems, now) { items, currentTime ->
        items.filter { !it.isPinned && currentTime < it.expirationTimestamp }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val trash = combine(trashedMedia, repository.observeTrashedMetadata()) { media, entities ->
        media.toUiItems(entities)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            while (true) {
                delay(60_000)
                now.value = System.currentTimeMillis()
            }
        }
    }

    fun refresh() = viewModelScope.launch { refreshNow() }

    /** Collapses bursty MediaStore observer callbacks into one refresh. */
    fun onMediaStoreChanged() {
        mediaStoreRefreshJob?.cancel()
        mediaStoreRefreshJob = viewModelScope.launch {
            delay(MEDIASTORE_REFRESH_DEBOUNCE_MILLIS)
            refreshNow()
        }
    }

    private suspend fun refreshNow() = refreshMutex.withLock {
        _isLoading.value = true
        try {
            val (active, trashed) = withContext(Dispatchers.IO) {
                val discovery = repository.discoverScreenshots()
                val current = discovery.screenshots
                // A screenshot found while the app is open gets its targeted
                // one-hour warning immediately, not only on the next worker run.
                ExpirationScheduler.scheduleWarnings(
                    getApplication(),
                    repository.metadata(),
                    System.currentTimeMillis()
                )
                if (captureNotificationTracker.shouldNotify(discovery.newlyTracked.size)) {
                    PinshotNotifications.postCaptureDetected(getApplication(), discovery.newlyTracked.size)
                }
                current to repository.loadVerifiedTrashedScreenshots()
            }
            activeMedia.value = active
            trashedMedia.value = trashed
            now.value = System.currentTimeMillis()
        } catch (_: SecurityException) {
            _message.value = "Photo access is required to show screenshots"
        } catch (_: Exception) {
            _message.value = "Could not load screenshots"
        } finally {
            _isLoading.value = false
        }
    }

    fun moveToExpiring(item: ScreenshotUiItem) = viewModelScope.launch(Dispatchers.IO) {
        repository.moveToExpiring(item.uri)
    }

    fun togglePin(item: ScreenshotUiItem) = viewModelScope.launch(Dispatchers.IO) {
        repository.setPinned(item.uri, !item.isPinned)
        if (!item.isPinned) PinshotNotifications.cancelWarning(getApplication())
    }

    fun pin(item: ScreenshotUiItem) = viewModelScope.launch(Dispatchers.IO) {
        repository.setPinned(item.uri, true)
        PinshotNotifications.cancelWarning(getApplication())
    }

    fun moveToTrash(item: ScreenshotUiItem) = beginTrashTransition(item, trashed = true)

    fun restore(item: ScreenshotUiItem) = beginTrashTransition(item, trashed = false)

    fun moveToTrash(items: List<ScreenshotUiItem>) = beginBatchTrashTransition(items, trashed = true)

    fun restore(items: List<ScreenshotUiItem>) = beginBatchTrashTransition(items, trashed = false)

    fun undoTrash(item: ScreenshotUiItem) = restore(item)

    /** Undo is offered only for completed trash moves, never for recovery. */
    fun undoTrash(items: List<ScreenshotUiItem>) {
        restore(items)
    }

    fun deletePermanently(item: ScreenshotUiItem) {
        if (!startMutation(item)) return
        viewModelScope.launch {
            if (!withContext(Dispatchers.IO) { repository.isVerifiedTrashedScreenshot(item.uri) }) {
                _message.value = "This screenshot is no longer in Pinshot trash"
                refreshNow()
                finishMutation(item)
                return@launch
            }
            when (val result = withContext(Dispatchers.IO) { trashManager.deletePermanently(item.uri) }) {
                is MediaTrashResult.ConsentRequired -> enqueueConsent(
                    MediaWriteConsentRequest(
                        intentSender = result.intentSender,
                        item = item,
                        trashed = false,
                        retryDirectUpdateAfterResult = true,
                        isPermanentDelete = true
                    )
                )
                is MediaTrashResult.SystemDeleteRequest -> enqueueConsent(
                    MediaWriteConsentRequest(
                        intentSender = result.intentSender,
                        item = item,
                        trashed = false,
                        isPermanentDelete = true
                    )
                )
                MediaTrashResult.Success -> {
                    confirmPermanentDeletion(item)
                    finishMutation(item)
                }
                MediaTrashResult.NotFound -> {
                    withContext(Dispatchers.IO) { repository.purgeMissing(item.uri) }
                    refreshNow()
                    finishMutation(item)
                }
                else -> {
                    _message.value = "Android could not permanently delete this screenshot"
                    finishMutation(item)
                }
            }
        }
    }

    /** Deletes a selection through one Android request instead of one per row. */
    fun deletePermanently(items: List<ScreenshotUiItem>) {
        val candidates = items.distinctBy { it.uri.toString() }
        if (candidates.size <= 1) {
            candidates.singleOrNull()?.let(::deletePermanently)
            return
        }
        val started = candidates.filter(::startMutation)
        if (started.isEmpty()) return

        viewModelScope.launch {
            val verified = withContext(Dispatchers.IO) {
                started.filter { repository.isVerifiedTrashedScreenshot(it.uri) }
            }
            val rejected = started - verified.toSet()
            finishMutations(rejected)
            if (verified.isEmpty()) {
                _message.value = "None of the selected screenshots are still in Pinshot trash"
                refreshNow()
                return@launch
            }

            when (val result = withContext(Dispatchers.IO) {
                trashManager.createPermanentDeleteRequest(verified.map { it.uri })
            }) {
                is MediaTrashResult.SystemDeleteRequest -> enqueueConsent(
                    MediaWriteConsentRequest(
                        intentSender = result.intentSender,
                        item = verified.first(),
                        items = verified,
                        trashed = false,
                        isPermanentDelete = true
                    )
                )
                else -> {
                    _message.value = "Android could not permanently delete the selected screenshots"
                    finishMutations(verified)
                }
            }
        }
    }

    fun onWriteConsentResult(granted: Boolean) {
        val request = _writeConsentRequest.value ?: return
        _writeConsentRequest.value = null
        viewModelScope.launch {
            try {
                if (!granted) {
                    reconcileCancelledSystemRequest(request)
                } else if (request.retryDirectUpdateAfterResult) {
                    // RecoverableSecurityException grants write access; Android
                    // does not perform the original update/delete automatically.
                    finishMutation(request.item)
                    if (request.isPermanentDelete) {
                        deletePermanently(request.item)
                    } else {
                        beginTrashTransition(request.item, request.trashed)
                    }
                } else if (request.isPermanentDelete) {
                    confirmCompletedPermanentDeletion(request.items)
                } else {
                    val completedUris = withContext(Dispatchers.IO) {
                        repository.recordCompletedSystemTrashRequest(
                            request.items.map { it.uri },
                            request.trashed
                        )
                    }
                    if (completedUris.isNotEmpty()) {
                        refreshNow()
                        val completedItems = request.items.filter { it.uri in completedUris }
                        completedItems.forEach { emitTransitionEvent(it, request.trashed) }
                        if (completedItems.size != request.items.size) {
                            _message.value = "Some screenshots changed before Android completed the action"
                        }
                    } else {
                        _message.value = "The screenshot changed before Android completed the trash action"
                    }
                }
            } finally {
                if (!request.retryDirectUpdateAfterResult || !granted) {
                    finishMutations(request.items)
                }
                showNextConsentRequest()
            }
        }
    }

    fun onManageMediaSettingsReturned() {
        _needsManageMediaPermission.value = false
        _silentCleanupEnabled.value = ManageMediaPermissionContract.isGranted(getApplication())
        if (_silentCleanupEnabled.value) {
            _message.value = "Silent cleanup is enabled"
        } else {
            _message.value = "Silent cleanup was not enabled"
        }
    }

    fun dismissManageMediaPermission() {
        _needsManageMediaPermission.value = false
    }

    fun requestSilentCleanup() {
        if (!ManageMediaPermissionContract.isGranted(getApplication())) {
            _needsManageMediaPermission.value = true
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun beginTrashTransition(item: ScreenshotUiItem, trashed: Boolean) {
        if (!startMutation(item)) return
        viewModelScope.launch {
            val isVerified = withContext(Dispatchers.IO) {
                if (trashed) {
                    repository.isVerifiedActiveScreenshot(item.uri)
                } else {
                    repository.isVerifiedTrashedScreenshot(item.uri)
                }
            }
            if (!isVerified) {
                _message.value = "This screenshot changed outside Pinshot; no action was taken"
                refreshNow()
                finishMutation(item)
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                if (trashed) trashManager.moveToTrash(item.uri) else trashManager.restore(item.uri)
            }
            when (result) {
                MediaTrashResult.Success -> commitTrashTransition(item, trashed)
                is MediaTrashResult.ConsentRequired -> {
                    enqueueConsent(
                        MediaWriteConsentRequest(
                            intentSender = result.intentSender,
                            item = item,
                            trashed = trashed,
                            retryDirectUpdateAfterResult = true
                        )
                    )
                }
                is MediaTrashResult.SystemTrashRequest -> {
                    enqueueConsent(MediaWriteConsentRequest(result.intentSender, item, trashed))
                }
                MediaTrashResult.NotFound -> {
                    withContext(Dispatchers.IO) { repository.purgeMissing(item.uri) }
                    refreshNow()
                    finishMutation(item)
                }
                MediaTrashResult.PermissionRequiredForBackgroundWork -> {
                    _needsManageMediaPermission.value = true
                    finishMutation(item)
                }
                MediaTrashResult.Unsupported -> {
                    _message.value = "System trash requires Android 11 or later"
                    finishMutation(item)
                }
                is MediaTrashResult.Failed -> {
                    _message.value = "Android could not update this item in system trash"
                    finishMutation(item)
                }
                is MediaTrashResult.SystemDeleteRequest -> finishMutation(item)
            }
        }
    }

    /** Validates every selected URI, then sends Android one atomic batch action. */
    private fun beginBatchTrashTransition(items: List<ScreenshotUiItem>, trashed: Boolean) {
        val candidates = items.distinctBy { it.uri.toString() }
        if (candidates.size <= 1) {
            candidates.singleOrNull()?.let { beginTrashTransition(it, trashed) }
            return
        }
        val started = candidates.filter(::startMutation)
        if (started.isEmpty()) return

        viewModelScope.launch {
            val verified = withContext(Dispatchers.IO) {
                started.filter { item ->
                    if (trashed) {
                        repository.isVerifiedActiveScreenshot(item.uri)
                    } else {
                        repository.isVerifiedTrashedScreenshot(item.uri)
                    }
                }
            }
            finishMutations(started - verified.toSet())
            if (verified.isEmpty()) {
                _message.value = "None of the selected screenshots can be changed"
                refreshNow()
                return@launch
            }

            when (val result = withContext(Dispatchers.IO) {
                trashManager.createTrashRequest(verified.map { it.uri }, trashed)
            }) {
                is MediaTrashResult.SystemTrashRequest -> enqueueConsent(
                    MediaWriteConsentRequest(
                        intentSender = result.intentSender,
                        item = verified.first(),
                        items = verified,
                        trashed = trashed
                    )
                )
                MediaTrashResult.Unsupported -> {
                    _message.value = "System trash requires Android 11 or later"
                    finishMutations(verified)
                }
                else -> {
                    _message.value = "Android could not update the selected screenshots in system trash"
                    finishMutations(verified)
                }
            }
        }
    }

    private suspend fun commitTrashTransition(item: ScreenshotUiItem, trashed: Boolean) {
        val confirmed = withContext(Dispatchers.IO) {
            repository.confirmAndRecordTrashState(item.uri, trashed)
        }
        if (confirmed) {
            refreshNow()
            emitTransitionEvent(item, trashed)
        } else {
            _message.value = "Android did not complete the system trash action"
            refreshNow()
        }
        finishMutation(item)
    }

    private suspend fun enqueueConsent(request: MediaWriteConsentRequest) {
        if (_writeConsentRequest.value == null) {
            _writeConsentRequest.value = request
        } else {
            consentQueue.addLast(request)
        }
    }

    private suspend fun confirmPermanentDeletion(item: ScreenshotUiItem) {
        if (withContext(Dispatchers.IO) { repository.isMissing(item.uri) }) {
            withContext(Dispatchers.IO) { repository.purgeMissing(item.uri) }
            refreshNow()
        } else {
            _message.value = "Android did not confirm permanent deletion; the screenshot was kept"
            refreshNow()
        }
    }

    private suspend fun confirmCompletedPermanentDeletion(items: List<ScreenshotUiItem>) {
        // Android guarantees a createDeleteRequest result is delivered only
        // after every URI in that request has been processed successfully.
        withContext(Dispatchers.IO) { repository.purgeMissing(items.map { it.uri }) }
        refreshNow()
    }

    /**
     * A few MediaProvider implementations return RESULT_CANCELED even after a
     * request has changed media. Reconcile only exact, verified outcomes so a
     * misleading result code cannot leave the UI or Room state stale.
     */
    private suspend fun reconcileCancelledSystemRequest(request: MediaWriteConsentRequest) {
        if (request.retryDirectUpdateAfterResult) {
            _message.value = "The system action was cancelled"
            return
        }

        if (request.isPermanentDelete) {
            val deletedItems = withContext(Dispatchers.IO) {
                request.items.filter { repository.isMissing(it.uri) }
            }
            if (deletedItems.isEmpty()) {
                _message.value = "The system action was cancelled"
                return
            }
            withContext(Dispatchers.IO) { repository.purgeMissing(deletedItems.map { it.uri }) }
            refreshNow()
            if (deletedItems.size != request.items.size) {
                _message.value = "Android completed permanent deletion for ${deletedItems.size} of ${request.items.size} screenshots"
            }
            return
        }

        val completedItems = withContext(Dispatchers.IO) {
            request.items.filter { item ->
                repository.confirmAndRecordTrashState(item.uri, request.trashed)
            }
        }
        if (completedItems.isEmpty()) {
            _message.value = "The system action was cancelled"
            return
        }
        refreshNow()
        completedItems.forEach { emitTransitionEvent(it, request.trashed) }
        if (completedItems.size != request.items.size) {
            _message.value = "Android completed the action for ${completedItems.size} of ${request.items.size} screenshots"
        }
    }

    private fun startMutation(item: ScreenshotUiItem): Boolean {
        val started = inFlightUris.add(item.uri.toString())
        if (started) {
            _hasPendingMediaMutation.value = true
        } else {
            _message.value = "An action for this screenshot is already in progress"
        }
        return started
    }

    private fun finishMutation(item: ScreenshotUiItem) {
        inFlightUris.remove(item.uri.toString())
        _hasPendingMediaMutation.value = inFlightUris.isNotEmpty()
    }

    private fun finishMutations(items: List<ScreenshotUiItem>) {
        items.forEach { inFlightUris.remove(it.uri.toString()) }
        _hasPendingMediaMutation.value = inFlightUris.isNotEmpty()
    }

    private fun showNextConsentRequest() {
        _writeConsentRequest.value = if (consentQueue.isEmpty()) null else consentQueue.removeFirst()
    }

    private suspend fun emitTransitionEvent(item: ScreenshotUiItem, trashed: Boolean) {
        if (trashed) {
            pendingTrashFeedback += item
            trashFeedbackJob?.cancel()
            trashFeedbackJob = viewModelScope.launch {
                delay(FEEDBACK_COALESCE_MILLIS)
                val completedItems = pendingTrashFeedback.toList()
                pendingTrashFeedback.clear()
                trashFeedbackJob = null
                if (completedItems.isNotEmpty()) {
                    _mediaSnackbarEvents.emit(MediaSnackbarEvent.MovedToTrash(completedItems))
                }
            }
        } else {
            pendingRecoveryCount++
            recoveryFeedbackJob?.cancel()
            recoveryFeedbackJob = viewModelScope.launch {
                delay(FEEDBACK_COALESCE_MILLIS)
                val recoveredCount = pendingRecoveryCount
                pendingRecoveryCount = 0
                recoveryFeedbackJob = null
                if (recoveredCount > 0) {
                    _mediaSnackbarEvents.emit(MediaSnackbarEvent.Recovered(recoveredCount))
                }
            }
        }
    }

    private fun List<MediaStoreScreenshot>.toUiItems(entities: List<ScreenshotItemEntity>): List<ScreenshotUiItem> {
        val metadata = entities.associateBy { it.uriString }
        return map { image ->
            val entity = metadata[image.uri.toString()]
            ScreenshotUiItem(
                uri = image.uri,
                displayName = image.displayName,
                dateAdded = image.dateAdded,
                relativePath = image.relativePath,
                expirationTimestamp = entity?.expirationTimestamp ?: image.dateAdded + EXPIRATION_MILLIS,
                isPinned = entity?.isPinned ?: false,
                trashedTimestamp = entity?.trashedTimestamp ?: image.dateModified
            )
        }
    }

    private companion object {
        const val MEDIASTORE_REFRESH_DEBOUNCE_MILLIS = 500L
        const val FEEDBACK_COALESCE_MILLIS = 200L
        val EXPIRATION_MILLIS = TimeUnit.HOURS.toMillis(24)
    }
}
