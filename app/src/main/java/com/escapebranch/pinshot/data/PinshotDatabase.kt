package com.escapebranch.pinshot.data

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ScreenshotItemEntity::class], version = 2, exportSchema = false)
abstract class PinshotDatabase : RoomDatabase() {
    abstract fun screenshotDao(): ScreenshotDao

    companion object {
        @Volatile private var instance: PinshotDatabase? = null

        fun getInstance(context: Context): PinshotDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                PinshotDatabase::class.java,
                "pinshot.db"
            ).addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE screenshots ADD COLUMN isTrashed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE screenshots ADD COLUMN trashedTimestamp INTEGER")
            }
        }
    }
}
