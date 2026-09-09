package com.escapebranch.pinshot.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScreenshotDao {
    @Query("SELECT * FROM screenshots")
    fun observeAll(): Flow<List<ScreenshotItemEntity>>

    @Query("SELECT * FROM screenshots")
    suspend fun getAll(): List<ScreenshotItemEntity>

    @Query("SELECT * FROM screenshots WHERE uriString = :uriString LIMIT 1")
    suspend fun get(uriString: String): ScreenshotItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ScreenshotItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ScreenshotItemEntity>)

    @Query("UPDATE screenshots SET expirationTimestamp = :expirationTimestamp WHERE uriString = :uriString")
    suspend fun setExpiration(uriString: String, expirationTimestamp: Long)

    @Query("UPDATE screenshots SET isPinned = :isPinned WHERE uriString = :uriString")
    suspend fun setPinned(uriString: String, isPinned: Boolean)

    @Query("UPDATE screenshots SET isTrashed = :isTrashed, trashedTimestamp = :trashedTimestamp WHERE uriString = :uriString")
    suspend fun setTrashState(uriString: String, isTrashed: Boolean, trashedTimestamp: Long?)

    @Query("UPDATE screenshots SET isPinned = 1 WHERE uriString IN (:uriStrings)")
    suspend fun pinAll(uriStrings: List<String>)

    @Query("SELECT * FROM screenshots WHERE isPinned = 0 AND isTrashed = 0 AND expirationTimestamp > :now AND expirationTimestamp <= :warningEnd")
    suspend fun expiringWithin(now: Long, warningEnd: Long): List<ScreenshotItemEntity>

    @Query("SELECT * FROM screenshots WHERE isPinned = 0 AND isTrashed = 0 AND expirationTimestamp <= :now")
    suspend fun expired(now: Long): List<ScreenshotItemEntity>

    @Query("DELETE FROM screenshots WHERE uriString = :uriString")
    suspend fun delete(uriString: String)
}
