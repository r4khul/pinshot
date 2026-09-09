package com.escapebranch.pinshot.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.util.concurrent.TimeUnit

data class MediaStoreScreenshot(
    val uri: Uri,
    val displayName: String,
    val dateAdded: Long,
    val relativePath: String,
    val dateModified: Long
)

/** The only image source for Pinshot. It never fabricates or copies screenshot data. */
class MediaStoreHelper(context: Context) {
    private val resolver: ContentResolver = context.contentResolver

    fun queryScreenshots(): List<MediaStoreScreenshot> = query(trashOnly = false)

    fun queryTrashedScreenshots(): List<MediaStoreScreenshot> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return query(trashOnly = true)
    }

    /** Reads one Room-known screenshot that MediaStore currently marks trashed. */
    fun queryKnownTrashedScreenshot(uri: Uri): MediaStoreScreenshot? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        // Android may move the physical file into its own trash directory, so
        // RELATIVE_PATH is no longer a screenshots path after this transition.
        // Room ownership plus the exact URI and date are the scope boundary.
        return queryKnownScreenshot(uri, isTrashed = true, requireScreenshotPath = false)
    }

    /** Reads one exact, non-trashed screenshot URI before a destructive action. */
    fun queryKnownActiveScreenshot(uri: Uri): MediaStoreScreenshot? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return queryKnownScreenshot(uri, isTrashed = false, requireScreenshotPath = true)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun queryKnownScreenshot(
        uri: Uri,
        isTrashed: Boolean,
        requireScreenshotPath: Boolean
    ): MediaStoreScreenshot? {
        return try {
            resolver.query(
                uri,
                screenshotProjection(),
                Bundle().apply {
                    // Individual-URI MATCH_ONLY queries are unreliable on some
                    // providers. Include the row, then check IS_TRASHED from the
                    // row itself so the same code works for both states.
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                },
                null
            )?.use { rows ->
                if (!rows.moveToFirst()) return@use null
                val rowIsTrashed = rows.getInt(
                    rows.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_TRASHED)
                ) != 0
                if (rowIsTrashed != isTrashed) return@use null
                val name = rows.getString(rows.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                    .orEmpty()
                    .ifBlank { "Screenshot" }
                val relativePath = rows.getString(
                    rows.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                ).orEmpty()
                if (requireScreenshotPath && !isScreenshotPath(relativePath, name)) return@use null
                MediaStoreScreenshot(
                    uri = uri,
                    displayName = name,
                    dateAdded = TimeUnit.SECONDS.toMillis(
                        rows.getLong(rows.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED))
                    ),
                    relativePath = relativePath,
                    dateModified = TimeUnit.SECONDS.toMillis(
                        rows.getLong(rows.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED))
                    )
                )
            }
        } catch (_: SecurityException) {
            null
        }
    }

    fun displayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)
            ?.use { rows ->
                if (rows.moveToFirst()) rows.getString(0)?.takeIf { it.isNotBlank() } else null
            }
    } catch (_: SecurityException) {
        null
    }

    /**
     * Returns false only when MediaProvider confirms that this exact row is
     * gone. A null result means the provider could not be queried, which must
     * never be treated as a successful permanent deletion.
     */
    fun exists(uri: Uri): Boolean? = try {
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val queryArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bundle().apply {
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                }
            } else {
                Bundle.EMPTY
            }
            resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), queryArgs, null)
        } else {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
        }
        cursor?.use { it.moveToFirst() }
            ?: false
    } catch (_: SecurityException) {
        null
    } catch (_: java.io.FileNotFoundException) {
        false
    }

    private fun query(trashOnly: Boolean): List<MediaStoreScreenshot> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = screenshotProjection()

        val screenshots = mutableListOf<MediaStoreScreenshot>()
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            resolver.query(
                collection,
                projection,
                Bundle().apply {
                    // Explicitly exclude trashed rows from the gallery. Some OEM
                    // providers do not reliably honour MATCH_DEFAULT here.
                    putInt(
                        MediaStore.QUERY_ARG_MATCH_TRASHED,
                        if (trashOnly) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE
                    )
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                        "(${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?)")
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                        arrayOf("Pictures/Screenshots%", "DCIM/Screenshots%"))
                    putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_MODIFIED))
                    putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                },
                null
            )
        } else {
            val (selection, selectionArgs) = screenshotSelection()
            resolver.query(collection, projection, selection, selectionArgs, sortOrder)
        }

        cursor?.use { rows ->
            val idColumn = rows.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = rows.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateColumn = rows.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val modifiedColumn = rows.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                rows.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            } else {
                -1
            }

            while (rows.moveToNext()) {
                val relativePath = if (pathColumn >= 0) rows.getString(pathColumn).orEmpty() else ""
                if (isScreenshotPath(relativePath, rows.getString(nameColumn).orEmpty())) {
                    screenshots += MediaStoreScreenshot(
                        uri = ContentUris.withAppendedId(collection, rows.getLong(idColumn)),
                        displayName = rows.getString(nameColumn).orEmpty().ifBlank { "Screenshot" },
                        dateAdded = TimeUnit.SECONDS.toMillis(rows.getLong(dateColumn)),
                        relativePath = relativePath,
                        dateModified = TimeUnit.SECONDS.toMillis(rows.getLong(modifiedColumn))
                    )
                }
            }
        }
        return screenshots
    }

    private fun screenshotSelection(): Pair<String, Array<String>> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "(${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?)" to
                arrayOf("Pictures/Screenshots%", "DCIM/Screenshots%")
        } else {
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?" to arrayOf("%Screenshot%")
        }

    private fun screenshotProjection(): Array<String> = buildList {
        add(MediaStore.Images.Media._ID)
        add(MediaStore.Images.Media.DISPLAY_NAME)
        add(MediaStore.Images.Media.DATE_ADDED)
        add(MediaStore.Images.Media.DATE_MODIFIED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(MediaStore.Images.Media.RELATIVE_PATH)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(MediaStore.MediaColumns.IS_TRASHED)
        }
    }.toTypedArray()

    private fun isScreenshotPath(relativePath: String, displayName: String): Boolean =
        relativePath.startsWith("Pictures/Screenshots") ||
            relativePath.startsWith("DCIM/Screenshots") ||
            (relativePath.isEmpty() && displayName.contains("screenshot", ignoreCase = true))
}
