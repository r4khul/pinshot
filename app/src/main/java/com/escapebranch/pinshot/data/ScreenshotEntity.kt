package com.escapebranch.pinshot.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Persistent metadata owned by Pinshot; image bytes remain in MediaStore. */
@Entity(tableName = "screenshots")
data class ScreenshotItemEntity(
    @PrimaryKey val uriString: String,
    val dateAdded: Long,
    val expirationTimestamp: Long,
    val isPinned: Boolean = false,
    /** Pinshot metadata only; the source of truth for the bytes remains MediaStore. */
    val isTrashed: Boolean = false,
    val trashedTimestamp: Long? = null
)
