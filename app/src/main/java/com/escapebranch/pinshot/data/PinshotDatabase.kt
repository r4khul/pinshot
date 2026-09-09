package com.escapebranch.pinshot.data

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ScreenshotItemEntity::class], version = 4, exportSchema = false)
abstract class PinshotDatabase : RoomDatabase() {
    abstract fun screenshotDao(): ScreenshotDao

    companion object {
        @Volatile private var instance: PinshotDatabase? = null

        fun getInstance(context: Context): PinshotDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                PinshotDatabase::class.java,
                "pinshot.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
                .also { instance = it }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE screenshots ADD COLUMN isTrashed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE screenshots ADD COLUMN trashedTimestamp INTEGER")
            }
        }

        /** Keeps the Pinshot-trash lookup O(log N + T) as history grows. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_screenshots_isTrashed ON screenshots (isTrashed)")
            }
        }

        /** Serves the 24-hour lifecycle range queries without a table scan. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_screenshots_isPinned_isTrashed_expirationTimestamp " +
                        "ON screenshots (isPinned, isTrashed, expirationTimestamp)"
                )
            }
        }
    }
}
