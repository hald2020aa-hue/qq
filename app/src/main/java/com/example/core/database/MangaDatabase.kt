package com.example.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SourceEntity::class,
        MangaEntity::class,
        ChapterEntity::class,
        PageEntity::class,
        FavoriteEntity::class,
        ReadingHistoryEntity::class,
        DownloadTaskEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class MangaDatabase : RoomDatabase() {
    abstract fun mangaDao(): MangaDao

    companion object {
        @Volatile
        private var INSTANCE: MangaDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Safe schema evolution example preserving user data (Rule 18)
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chapters_mangaId` ON `chapters` (`mangaId`)"
                )
            }
        }

        fun getInstance(context: Context): MangaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MangaDatabase::class.java,
                    "manga_reader.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
