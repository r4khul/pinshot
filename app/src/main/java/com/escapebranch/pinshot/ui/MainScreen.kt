package com.escapebranch.pinshot.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Restore
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.escapebranch.pinshot.ui.scrubber.BallisticFastScrubber
import com.escapebranch.pinshot.ui.scrubber.TimelineSection
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.escapebranch.pinshot.data.ManageMediaPermissionContract
import com.escapebranch.pinshot.notifications.NotificationDestinations
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

private const val PREFETCH_AHEAD_CELLS = 12
private const val PREFETCH_THUMBNAIL_SIZE_PX = 256

private enum class PinshotTab(val label: String) {
    Screenshots("Screenshots"),
    Expiring("Expiring"),
    Trash("Trash")
}

private data class ViewerRequest(val item: ScreenshotUiItem, val isTrash: Boolean)
private data class MonthKey(val year: Int, val month: Int)

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
        viewModel.trashEvents.collect { item ->
            if (snackbarHostState.showSnackbar("Moved to trash", "Undo") == SnackbarResult.ActionPerformed) {
                viewModel.undoTrash(item)
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
                                IconButton(enabled = !hasPendingMediaMutation, onClick = { selectedItems.values.forEach(viewModel::moveToTrash); selectedItems.clear() }) {
                                    Icon(Icons.Filled.DeleteOutline, "Move to trash")
                                }
                            }
                            PinshotTab.Expiring -> {
                                IconButton(enabled = !hasPendingMediaMutation, onClick = { selectedItems.values.forEach(viewModel::pin); selectedItems.clear() }) {
                                    Icon(Icons.Filled.PushPin, "Pin selected")
                                }
                                IconButton(enabled = !hasPendingMediaMutation, onClick = { selectedItems.values.forEach(viewModel::moveToTrash); selectedItems.clear() }) {
                                    Icon(Icons.Filled.DeleteOutline, "Move to trash")
                                }
                            }
                            PinshotTab.Trash -> {
                                IconButton(enabled = !hasPendingMediaMutation, onClick = { selectedItems.values.forEach(viewModel::restore); selectedItems.clear() }) {
                                    Icon(Icons.Filled.Restore, "Restore selected")
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
    val cells = remember(items) { items.toGalleryCells() }
    val timelineSections = remember(cells) { cells.toTimelineSections() }
    PrefetchGalleryThumbnails(cells, gridState)

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
                contentType = { it::class }
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
                                .padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 6.dp),
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
            sections = timelineSections,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(vertical = 16.dp)
        )
    }
}

/**
 * Direction-aware look-ahead for a lazy grid. It intentionally warms only a
 * dozen upcoming thumbnails on disk: prefetching hundreds of decoded images
 * competes with the visible cells for CPU, I/O, and memory during a fling.
 */
@Composable
private fun PrefetchGalleryThumbnails(cells: List<GalleryCell>, gridState: LazyGridState) {
    val context = LocalContext.current
    val imageLoader = remember(context) { context.imageLoader }
    val prefetchedUris = remember(cells) { mutableSetOf<String>() }

    LaunchedEffect(cells, gridState, imageLoader) {
        var previousFirstVisible = 0
        snapshotFlow {
            val visible = gridState.layoutInfo.visibleItemsInfo
            val first = visible.minOfOrNull { it.index }
            val last = visible.maxOfOrNull { it.index }
            if (first == null || last == null) null else first to last
        }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { (firstVisible, lastVisible) ->
                val movingForward = firstVisible >= previousFirstVisible
                previousFirstVisible = firstVisible
                val start: Int
                val end: Int
                if (movingForward) {
                    start = (lastVisible + 1).coerceAtMost(cells.lastIndex)
                    end = (lastVisible + PREFETCH_AHEAD_CELLS).coerceAtMost(cells.lastIndex)
                } else {
                    start = (firstVisible - PREFETCH_AHEAD_CELLS).coerceAtLeast(0)
                    end = (firstVisible - 1).coerceAtLeast(0)
                }
                if (start > end) return@collect
                for (index in start..end) {
                    val item = (cells[index] as? GalleryCell.Photo)?.item ?: continue
                    if (prefetchedUris.add(item.uri.toString())) {
                        imageLoader.enqueue(prefetchRequest(context, item))
                    }
                }
            }
    }
}

private fun prefetchRequest(context: android.content.Context, item: ScreenshotUiItem): ImageRequest {
    val cacheKey = "pinshot:${item.uri}"
    return ImageRequest.Builder(context.applicationContext)
        .data(item.uri)
        .size(PREFETCH_THUMBNAIL_SIZE_PX)
        .diskCacheKey(cacheKey)
        // Visible cells own the memory cache. Look-ahead only warms the disk
        // cache so a fast fling cannot evict the thumbnails on screen.
        .memoryCachePolicy(CachePolicy.DISABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .build()
}

private fun List<ScreenshotUiItem>.toGalleryCells(): List<GalleryCell> {
    val formatter = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    val monthFormatter = SimpleDateFormat("MMMM", Locale.getDefault())
    val cells = mutableListOf<GalleryCell>()
    var currentYear: Int? = null
    sortedByDescending { it.dateAdded }
        .groupBy { it.monthKey() }
        .forEach { (month, screenshots) ->
            if (currentYear != month.year) {
                currentYear = month.year
                cells += GalleryCell.YearHeader(month.year, "year-${month.year}")
            }
            cells += GalleryCell.MonthHeader(
                title = formatter.format(Date(screenshots.first().dateAdded)),
                monthLabel = monthFormatter.format(Date(screenshots.first().dateAdded)),
                year = month.year,
                month = month.month,
                itemCount = screenshots.size,
                key = "month-${month.year}-${month.month}"
            )
            cells += screenshots.map(GalleryCell::Photo)
        }
    return cells
}

private fun ScreenshotUiItem.monthKey(): MonthKey {
    val calendar = Calendar.getInstance().apply { timeInMillis = dateAdded }
    return MonthKey(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH))
}

private fun List<GalleryCell>.toTimelineSections(): List<TimelineSection> {
    val headers = mapIndexedNotNull { index, cell ->
        (cell as? GalleryCell.MonthHeader)?.let { index to it }
    }
    if (headers.isEmpty()) return emptyList()

    val newestMonth = headers.first().second.year * 12 + headers.first().second.month
    val oldestMonth = headers.last().second.year * 12 + headers.last().second.month
    val monthRange = (newestMonth - oldestMonth).coerceAtLeast(1)

    return headers.mapIndexed { headerIndex, (itemIndex, header) ->
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
        ScreenshotImage(item, ContentScale.Crop, Modifier.fillMaxSize())
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
                            Icon(Icons.Filled.Restore, contentDescription = "Restore")
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
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (loading) {
            CircularProgressIndicator()
        } else {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.size(72.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(20.dp)
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

private const val EXPIRATION_MILLIS = 24 * 60 * 60 * 1_000L
