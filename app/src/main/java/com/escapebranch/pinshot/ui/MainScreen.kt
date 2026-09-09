package com.escapebranch.pinshot.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.CancellationSignal
import android.util.LruCache
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.escapebranch.pinshot.ui.scrubber.BallisticFastScrubber
import com.escapebranch.pinshot.ui.scrubber.TimelineSection
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import com.escapebranch.pinshot.data.ManageMediaPermissionContract
import com.escapebranch.pinshot.notifications.NotificationDestinations
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private enum class PinshotTab(val label: String) {
    Screenshots("Screenshots"),
    Expiring("Expiring"),
    Trash("Trash")
}

private data class ViewerRequest(val item: ScreenshotUiItem, val isTrash: Boolean)
private data class MonthKey(val year: Int, val month: Int)
private data class GalleryContent(
    val cells: List<GalleryCell>,
    val timelineSections: List<TimelineSection>
)

private sealed interface GalleryCell {
    val key: String

    data class YearHeader(
        val year: Int,
        override val key: String
    ) : GalleryCell

    data class MonthHeader(
        val title: String,
        val monthLabel: String,
        val year: Int,
        val month: Int,
        val itemCount: Int,
        val startsYear: Boolean,
        override val key: String
    ) : GalleryCell
    data class Photo(val item: ScreenshotUiItem) : GalleryCell {
        override val key: String = item.uri.toString()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    initialDestination: String? = null,
    viewModel: PinshotViewModel = viewModel()
) {
    val context = LocalContext.current
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
    }
    var selectedTab by rememberSaveable { mutableStateOf(PinshotTab.Screenshots) }
    var viewer by remember { mutableStateOf<ViewerRequest?>(null) }
    val selectedItems = remember { mutableStateMapOf<String, ScreenshotUiItem>() }
    var permanentDeleteCandidates by remember { mutableStateOf<List<ScreenshotUiItem>>(emptyList()) }
    val screenshotsGridState = rememberLazyGridState()
    val snackbarHostState = remember { SnackbarHostState() }
    val message by viewModel.message.collectAsStateWithLifecycle()
    val needsManageMediaPermission by viewModel.needsManageMediaPermission.collectAsStateWithLifecycle()
    val silentCleanupEnabled by viewModel.silentCleanupEnabled.collectAsStateWithLifecycle()
    val writeConsentRequest by viewModel.writeConsentRequest.collectAsStateWithLifecycle()
    val hasPendingMediaMutation by viewModel.hasPendingMediaMutation.collectAsStateWithLifecycle()
    val screenshotItems by viewModel.screenshots.collectAsStateWithLifecycle()
    val expiringItems by viewModel.expiring.collectAsStateWithLifecycle()
    val trashItems by viewModel.trash.collectAsStateWithLifecycle()
    val visibleUris = when (selectedTab) {
        PinshotTab.Screenshots -> screenshotItems
        PinshotTab.Expiring -> expiringItems
        PinshotTab.Trash -> trashItems
    }.mapTo(mutableSetOf()) { it.uri.toString() }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (granted) viewModel.refresh()
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val manageMediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.onManageMediaSettingsReturned() }
    val systemTrashLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onWriteConsentResult(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            viewModel.refresh()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(writeConsentRequest, systemTrashLauncher) {
        writeConsentRequest?.let { request ->
            systemTrashLauncher.launch(
                IntentSenderRequest.Builder(request.intentSender).build()
            )
        }
    }
    LaunchedEffect(selectedTab, visibleUris) {
        selectedItems.keys
            .filterNot { it in visibleUris }
            .forEach(selectedItems::remove)
    }
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.mediaSnackbarEvents.collect { event ->
            when (event) {
                is MediaSnackbarEvent.MovedToTrash -> {
                    val label = if (event.count == 1) {
                        "1 screenshot moved to trash"
                    } else {
                        "${event.count} screenshots moved to trash"
                    }
                    if (snackbarHostState.showBriefSnackbar(label, "Undo") == SnackbarResult.ActionPerformed) {
                        viewModel.undoTrash(event.items)
                    }
                }
                is MediaSnackbarEvent.Recovered -> {
                    val label = if (event.count == 1) "1 screenshot recovered" else "${event.count} screenshots recovered"
                    snackbarHostState.showBriefSnackbar(label)
                }
            }
        }
    }
    LaunchedEffect(initialDestination) {
        selectedTab = when (initialDestination) {
            NotificationDestinations.EXPIRING -> PinshotTab.Expiring
            NotificationDestinations.TRASH -> PinshotTab.Trash
            else -> PinshotTab.Screenshots
        }
    }
    viewer?.let { request ->
        ViewerScreen(
            item = request.item,
            isTrash = request.isTrash,
            onBack = { viewer = null },
            onMoveToExpiring = { viewModel.moveToExpiring(request.item) },
            onRestore = {
                viewModel.restore(request.item)
                viewer = null
            }
        )
        return
    }

    if (needsManageMediaPermission) {
        AlertDialog(
            onDismissRequest = viewModel::dismissManageMediaPermission,
            title = { Text("Enable silent cleanup", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "Enable silent cleanup so Pinshot can manage temporary screenshots without prompting you every time.",
                    style = MaterialTheme.typography.bodyLarge
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    manageMediaLauncher.launch(ManageMediaPermissionContract.settingsIntent(context))
                }) { Text("Enable") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissManageMediaPermission) { Text("Not now") }
            }
        )
    }
    if (permanentDeleteCandidates.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { permanentDeleteCandidates = emptyList() },
            title = {
                Text(
                    "Permanently delete ${permanentDeleteCandidates.size} screenshot(s)?",
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = {
                Text(
                    "This removes them from system trash and cannot be undone.",
                    style = MaterialTheme.typography.bodyLarge
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePermanently(permanentDeleteCandidates)
                    permanentDeleteCandidates = emptyList()
                }, enabled = !hasPendingMediaMutation) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { permanentDeleteCandidates = emptyList() }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectedItems.isEmpty()) "Pinshot" else "${selectedItems.size} selected",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                actions = {
                    if (selectedItems.isNotEmpty()) {
                        when (selectedTab) {
                            PinshotTab.Screenshots -> {
                                IconButton(enabled = !hasPendingMediaMutation, onClick = { selectedItems.values.forEach(viewModel::moveToExpiring); selectedItems.clear() }) {
                                    Icon(Icons.Filled.HourglassEmpty, "Move to Expiring")
                                }
                                IconButton(enabled = !hasPendingMediaMutation, onClick = {
                                    viewModel.moveToTrash(selectedItems.values.toList())
                                    selectedItems.clear()
                                }) {
                                    Icon(Icons.Filled.DeleteOutline, "Move to trash")
                                }
                            }
                            PinshotTab.Expiring -> {
                                IconButton(enabled = !hasPendingMediaMutation, onClick = { selectedItems.values.forEach(viewModel::pin); selectedItems.clear() }) {
                                    Icon(Icons.Filled.PushPin, "Pin selected")
                                }
                                IconButton(enabled = !hasPendingMediaMutation, onClick = {
                                    viewModel.moveToTrash(selectedItems.values.toList())
                                    selectedItems.clear()
                                }) {
                                    Icon(Icons.Filled.DeleteOutline, "Move to trash")
                                }
                            }
                            PinshotTab.Trash -> {
                                IconButton(enabled = !hasPendingMediaMutation, onClick = {
                                    viewModel.restore(selectedItems.values.toList())
                                    selectedItems.clear()
                                }) {
                                    Icon(Icons.Filled.RestoreFromTrash, "Restore selected to Screenshots")
                                }
                                IconButton(enabled = !hasPendingMediaMutation, onClick = { permanentDeleteCandidates = selectedItems.values.toList(); selectedItems.clear() }) {
                                    Icon(Icons.Filled.DeleteOutline, "Permanently delete selected")
                                }
                            }
                        }
                        IconButton(onClick = { selectedItems.clear() }) { Icon(Icons.Filled.Close, "Clear selection") }
                    } else if (selectedTab == PinshotTab.Trash) {
                        IconButton(enabled = !hasPendingMediaMutation, onClick = {
                            permanentDeleteCandidates = trashItems
                        }) { Icon(Icons.Filled.DeleteSweep, "Empty trash") }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !silentCleanupEnabled) {
                        IconButton(onClick = viewModel::requestSilentCleanup) {
                            Icon(Icons.Filled.Settings, "Enable silent cleanup")
                        }
                    }
                    IconButton(onClick = {
                        if (hasPermission) viewModel.refresh() else permissionLauncher.launch(permission)
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh screenshots")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                PinshotTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == selectedTab,
                        onClick = { selectedItems.clear(); selectedTab = tab },
                        icon = {
                            Icon(
                                imageVector = when (tab) {
                                    PinshotTab.Screenshots -> Icons.Filled.Image
                                    PinshotTab.Expiring -> Icons.Filled.HourglassEmpty
                                    PinshotTab.Trash -> Icons.Filled.DeleteOutline
                                },
                                contentDescription = null
                            )
                        },
                        label = { Text(tab.label, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.surface
    ) { padding ->
        if (!hasPermission) {
            PermissionContent(
                onGrant = { permissionLauncher.launch(permission) },
                modifier = Modifier.padding(padding)
            )
        } else {
            when (selectedTab) {
                PinshotTab.Screenshots -> GalleryTab(
                    items = screenshotItems,
                    isLoading = viewModel.isLoading.collectAsStateWithLifecycle().value,
                    gridState = screenshotsGridState,
                    onOpen = { viewer = ViewerRequest(it, isTrash = false) },
                    selectedUris = selectedItems.keys,
                    onToggleSelection = { item -> item.uri.toString().let { if (selectedItems.containsKey(it)) selectedItems.remove(it) else selectedItems[it] = item } },
                    modifier = Modifier.padding(padding)
                )
                PinshotTab.Expiring -> ExpiringTab(
                    items = expiringItems,
                    onOpen = { viewer = ViewerRequest(it, isTrash = false) },
                    onPin = viewModel::togglePin,
                    onTrash = viewModel::moveToTrash,
                    selectedUris = selectedItems.keys,
                    onToggleSelection = { item -> item.uri.toString().let { if (selectedItems.containsKey(it)) selectedItems.remove(it) else selectedItems[it] = item } },
                    modifier = Modifier.padding(padding)
                )
                PinshotTab.Trash -> TrashScreen(
                    items = trashItems,
                    isLoading = viewModel.isLoading.collectAsStateWithLifecycle().value,
                    onRestore = viewModel::restore,
                    selectedUris = selectedItems.keys,
                    onToggleSelection = { item -> item.uri.toString().let { if (selectedItems.containsKey(it)) selectedItems.remove(it) else selectedItems[it] = item } },
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }
}

@Composable
private fun GalleryTab(
    items: List<ScreenshotUiItem>,
    isLoading: Boolean,
    gridState: LazyGridState,
    onOpen: (ScreenshotUiItem) -> Unit,
    selectedUris: Set<String>,
    onToggleSelection: (ScreenshotUiItem) -> Unit,
    modifier: Modifier = Modifier,
    emptyTitle: String = "No screenshots found",
    emptyBody: String = "Screenshots in Pictures/Screenshots and DCIM/Screenshots appear here."
) {
    if (items.isEmpty()) {
        EmptyContent(
            title = if (isLoading) "Loading screenshots" else emptyTitle,
            body = emptyBody,
            loading = isLoading,
            modifier = modifier
        )
    } else {
        DatedScreenshotGrid(
            items = items,
            gridState = gridState,
            onOpen = onOpen,
            selectedUris = selectedUris,
            onToggleSelection = onToggleSelection,
            modifier = modifier
        )
    }
}

@Composable
private fun DatedScreenshotGrid(
    items: List<ScreenshotUiItem>,
    gridState: LazyGridState,
    onOpen: (ScreenshotUiItem) -> Unit,
    selectedUris: Set<String>,
    onToggleSelection: (ScreenshotUiItem) -> Unit,
    modifier: Modifier = Modifier
) {
    // This transformation happens only when MediaStore data changes, never as
    // a consequence of a scroll frame. The source query is DATE_ADDED-desc,
    // so one pass can build both the grid and the scrubber's binary-search map.
    val galleryContent = remember(items) { items.toGalleryContent() }
    val cells = galleryContent.cells

    Box(modifier = modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(start = 2.dp, top = 2.dp, end = 2.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(
                items = cells,
                key = { it.key },
                span = { cell ->
                    if (cell is GalleryCell.YearHeader || cell is GalleryCell.MonthHeader) {
                        GridItemSpan(maxLineSpan)
                    } else {
                        GridItemSpan(1)
                    }
                },
                contentType = { cell ->
                    when (cell) {
                        is GalleryCell.YearHeader -> "year-header"
                        is GalleryCell.MonthHeader -> "month-header"
                        is GalleryCell.Photo -> "screenshot-thumbnail"
                    }
                }
            ) { cell ->
                when (cell) {
                    is GalleryCell.YearHeader -> {
                        Text(
                            text = cell.year.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 2.dp)
                        )
                    }
                    is GalleryCell.MonthHeader -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = 16.dp,
                                    top = if (cell.startsYear) 8.dp else 22.dp,
                                    end = 16.dp,
                                    bottom = 8.dp
                                ),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = cell.monthLabel,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${cell.itemCount} screenshots",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    is GalleryCell.Photo -> ScreenshotThumbnail(
                        item = cell.item,
                        selected = cell.item.uri.toString() in selectedUris,
                        onClick = { if (selectedUris.isEmpty()) onOpen(cell.item) else onToggleSelection(cell.item) },
                        onLongClick = { onToggleSelection(cell.item) }
                    )
                }
            }
        }
        BallisticFastScrubber(
            gridState = gridState,
            sections = galleryContent.timelineSections,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(vertical = 16.dp)
        )
    }
}

/** Keeps transient feedback out of the scroll path and limits Undo to two seconds. */
private suspend fun SnackbarHostState.showBriefSnackbar(
    message: String,
    actionLabel: String? = null
): SnackbarResult = withTimeoutOrNull(BRIEF_SNACKBAR_MILLIS) {
    showSnackbar(
        message = message,
        actionLabel = actionLabel,
        duration = SnackbarDuration.Indefinite
    )
} ?: SnackbarResult.Dismissed

private fun List<ScreenshotUiItem>.toGalleryContent(): GalleryContent {
    val formatter = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    val monthFormatter = SimpleDateFormat("MMMM", Locale.getDefault())
    val calendar = Calendar.getInstance()
    val cells = ArrayList<GalleryCell>(size + (size / 12) + 1)
    val monthHeaderIndexes = ArrayList<Int>()
    var currentYear: Int? = null
    var currentMonth: MonthKey? = null
    var currentMonthHeaderIndex = -1
    var currentMonthItemCount = 0

    // MediaStore already supplies this list in DATE_ADDED-desc order. Avoid a
    // second O(N log N) sort, a temporary group map, and one Calendar per image.
    for (screenshot in this) {
        calendar.timeInMillis = screenshot.dateAdded
        val month = MonthKey(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH))
        if (month != currentMonth) {
            if (currentMonthHeaderIndex >= 0) {
                val previousHeader = cells[currentMonthHeaderIndex] as GalleryCell.MonthHeader
                cells[currentMonthHeaderIndex] = previousHeader.copy(itemCount = currentMonthItemCount)
            }
            currentMonth = month
            val startsYear = currentYear != month.year
            if (startsYear) {
                currentYear = month.year
                cells += GalleryCell.YearHeader(month.year, "year-${month.year}")
            }
            val monthHeader = GalleryCell.MonthHeader(
                title = formatter.format(calendar.time),
                monthLabel = monthFormatter.format(calendar.time),
                year = month.year,
                month = month.month,
                itemCount = 0,
                startsYear = startsYear,
                key = "month-${month.year}-${month.month}"
            )
            currentMonthHeaderIndex = cells.size
            monthHeaderIndexes += currentMonthHeaderIndex
            currentMonthItemCount = 0
            cells += monthHeader
        }
        cells += GalleryCell.Photo(screenshot)
        currentMonthItemCount++
    }

    if (currentMonthHeaderIndex >= 0) {
        val lastHeader = cells[currentMonthHeaderIndex] as GalleryCell.MonthHeader
        cells[currentMonthHeaderIndex] = lastHeader.copy(itemCount = currentMonthItemCount)
    }
    if (monthHeaderIndexes.isEmpty()) return GalleryContent(cells, emptyList())

    val firstHeader = cells[monthHeaderIndexes.first()] as GalleryCell.MonthHeader
    val lastHeader = cells[monthHeaderIndexes.last()] as GalleryCell.MonthHeader
    val newestMonth = firstHeader.year * 12 + firstHeader.month
    val oldestMonth = lastHeader.year * 12 + lastHeader.month
    val monthRange = (newestMonth - oldestMonth).coerceAtLeast(1)
    val timelineSections = monthHeaderIndexes.map { itemIndex ->
        val header = cells[itemIndex] as GalleryCell.MonthHeader
        val monthValue = header.year * 12 + header.month
        TimelineSection(
            firstItemIndex = itemIndex,
            monthYearLabel = header.title,
            itemCount = header.itemCount,
            normalizedPosition = ((newestMonth - monthValue).toFloat() / monthRange).coerceIn(0f, 1f),
            monthLabel = header.monthLabel,
            yearLabel = header.year.toString()
        )
    }
    return GalleryContent(cells, timelineSections)
}

@Composable
private fun ScreenshotThumbnail(
    item: ScreenshotUiItem,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        GalleryThumbnailImage(item, Modifier.fillMaxSize())
        if (selected) Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.36f),
            modifier = Modifier.fillMaxSize()
        ) {}
        if (!item.isPinned && item.remainingMillis() > 0L) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.HourglassEmpty,
                    contentDescription = "Expiring soon",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(4.dp).size(14.dp)
                )
            }
        }
    }
}

/**
 * Android's MediaProvider owns a native thumbnail store. Using it directly for
 * the scrolling grid avoids reopening and sampling each full screenshot in
 * Coil while a frame is being produced.
 */
@Composable
private fun GalleryThumbnailImage(item: ScreenshotUiItem, modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        ScreenshotImage(item, ContentScale.Crop, modifier)
        return
    }

    val context = LocalContext.current.applicationContext
    val thumbnailSizePx = remember(context) {
        (context.resources.displayMetrics.widthPixels / 3)
            .coerceIn(MIN_GALLERY_THUMBNAIL_SIZE_PX, MAX_GALLERY_THUMBNAIL_SIZE_PX)
    }
    val cacheKey = remember(item.uri, item.dateAdded, thumbnailSizePx) {
        "${item.uri}:${item.dateAdded}:$thumbnailSizePx"
    }
    val bitmap by produceState<Bitmap?>(
        initialValue = GalleryThumbnailCache.get(cacheKey),
        key1 = cacheKey
    ) {
        if (value != null) return@produceState
        val cancellation = CancellationSignal()
        try {
            val thumbnail = withContext(Dispatchers.IO) {
                context.contentResolver.loadThumbnail(
                    item.uri,
                    Size(thumbnailSizePx, thumbnailSizePx),
                    cancellation
                )
            }
            GalleryThumbnailCache.put(cacheKey, thumbnail)
            value = thumbnail
        } catch (_: Exception) {
            // A transient provider failure falls back to the neutral cell; it
            // never blocks, crashes, or starts a full-resolution decode here.
        } finally {
            cancellation.cancel()
        }
    }

    val thumbnail = bitmap
    if (thumbnail != null) {
        Image(
            bitmap = thumbnail.asImageBitmap(),
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = modifier
        ) {}
    }
}

private object GalleryThumbnailCache {
    private val cache = object : LruCache<String, Bitmap>(GALLERY_THUMBNAIL_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }
}

/**
 * Uses a stable request and cache key so tab/viewer transitions reuse the
 * decoded thumbnail rather than restarting each content-URI request.
 */
@Composable
private fun ScreenshotImage(
    item: ScreenshotUiItem,
    contentScale: ContentScale,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val model = remember(item.uri) {
        val cacheKey = "pinshot:${item.uri}"
        ImageRequest.Builder(context.applicationContext)
            .data(item.uri)
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .crossfade(false)
            .build()
    }
    AsyncImage(
        model = model,
        contentDescription = item.displayName,
        contentScale = contentScale,
        modifier = modifier
    )
}

@Composable
private fun ExpiringTab(
    items: List<ScreenshotUiItem>,
    onOpen: (ScreenshotUiItem) -> Unit,
    onPin: (ScreenshotUiItem) -> Unit,
    onTrash: (ScreenshotUiItem) -> Unit,
    selectedUris: Set<String>,
    onToggleSelection: (ScreenshotUiItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) {
        EmptyContent(
            title = "Nothing expiring",
            body = "Screenshots moved to Expiring appear here for 24 hours.",
            icon = Icons.Filled.HourglassEmpty,
            modifier = modifier
        )
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items, key = { it.uri.toString() }) { item ->
                ExpiringRow(
                    item = item,
                    onClick = { if (selectedUris.isEmpty()) onOpen(item) else onToggleSelection(item) },
                    onPin = { onPin(item) },
                    onTrash = { onTrash(item) },
                    selected = item.uri.toString() in selectedUris,
                    onLongClick = { onToggleSelection(item) }
                )
            }
        }
    }
}

@Composable
private fun ExpiringRow(
    item: ScreenshotUiItem,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onTrash: () -> Unit,
    selected: Boolean,
    onLongClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ScreenshotImage(
                item = item,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        progress = { (item.remainingMillis().toFloat() / EXPIRATION_MILLIS).coerceIn(0f, 1f) },
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("${item.remainingHours()}h left", style = MaterialTheme.typography.labelLarge)
                }
            }
            IconButton(onClick = onPin) {
                Icon(Icons.Outlined.PushPin, contentDescription = "Pin and keep")
            }
            IconButton(onClick = onTrash) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = "Move to trash")
            }
        }
        if (selected) Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)) {}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerScreen(
    item: ScreenshotUiItem,
    isTrash: Boolean,
    onBack: () -> Unit,
    onMoveToExpiring: () -> Unit,
    onRestore: () -> Unit
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        item.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (isTrash) {
                        IconButton(onClick = onRestore) {
                            Icon(
                                Icons.Filled.RestoreFromTrash,
                                contentDescription = "Restore to Screenshots"
                            )
                        }
                    } else {
                        IconButton(onClick = onMoveToExpiring) {
                        Icon(Icons.Filled.HourglassEmpty, contentDescription = "Move to Expiring")
                        }
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.surface
    ) { padding ->
        ScreenshotImage(
            item = item,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }
}

@Composable
private fun PermissionContent(onGrant: () -> Unit, modifier: Modifier = Modifier) {
    EmptyContent(
        title = "Allow photo access",
        body = "Pinshot needs access to your photos to find screenshots stored on this device.",
        action = { Button(onClick = onGrant) { Text("Allow access") } },
        modifier = modifier
    )
}

@Composable
fun EmptyContent(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    icon: ImageVector = Icons.Filled.Image,
    action: @Composable (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (loading) {
            CircularProgressIndicator()
        } else {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

private const val BRIEF_SNACKBAR_MILLIS = 2_000L
private const val MIN_GALLERY_THUMBNAIL_SIZE_PX = 160
private const val MAX_GALLERY_THUMBNAIL_SIZE_PX = 512
private const val GALLERY_THUMBNAIL_CACHE_BYTES = 32 * 1024 * 1024
private const val EXPIRATION_MILLIS = 24 * 60 * 60 * 1_000L
