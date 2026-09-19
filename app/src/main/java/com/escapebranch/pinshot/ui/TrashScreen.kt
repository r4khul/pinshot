package com.escapebranch.pinshot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest

/** Displays only MediaStore-trashed entries which the repository has verified in Room. */
@Composable
fun TrashScreen(
    items: List<ScreenshotUiItem>,
    isLoading: Boolean,
    onOpen: (ScreenshotUiItem) -> Unit,
    onRestore: (ScreenshotUiItem) -> Unit,
    selectedUris: Set<String>,
    onToggleSelection: (ScreenshotUiItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) {
        EmptyContent(
            title = if (isLoading) "Loading trash" else "Trash is empty",
            body = "Only screenshots moved here by Pinshot appear in this view.",
            loading = isLoading,
            icon = Icons.Filled.DeleteOutline,
            modifier = modifier
        )
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(
            items = items,
            key = { it.uri.toString() },
            contentType = { "trash-thumbnail" }
        ) { item ->
            TrashThumbnail(
                item = item,
                selected = item.uri.toString() in selectedUris,
                selectionMode = selectedUris.isNotEmpty(),
                onOpen = { if (selectedUris.isEmpty()) onOpen(item) else onToggleSelection(item) },
                onRestore = { onRestore(item) },
                onLongClick = { onToggleSelection(item) }
            )
        }
    }
}

@Composable
private fun TrashThumbnail(
    item: ScreenshotUiItem,
    selected: Boolean,
    selectionMode: Boolean,
    onOpen: () -> Unit,
    onRestore: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    val model = remember(item.uri) {
        val cacheKey = "pinshot:${item.uri}"
        ImageRequest.Builder(context.applicationContext)
            .data(item.uri)
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .crossfade(false)
            .build()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .combinedClickable(onClick = onOpen, onLongClick = onLongClick)
    ) {
        AsyncImage(
            model = model,
            contentDescription = "Restore ${item.displayName}",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (selected) Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.36f),
            modifier = Modifier.fillMaxSize()
        ) {}
        if (selected) {
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(5.dp)
                )
            }
        }
        if (!selectionMode) {
            FilledTonalIconButton(
                onClick = onRestore,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.RestoreFromTrash,
                    contentDescription = "Restore ${item.displayName} to Screenshots",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
        ) {
            Text(
                text = "${item.trashDaysRemaining()}d left",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }
    }
}
