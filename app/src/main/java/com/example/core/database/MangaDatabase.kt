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
        SourceConfigEntity::class,
        MangaEntity::class,
        ChapterEntity::class,
        PageEntity::class,
        FavoriteEntity::class,
        ReadingHistoryEntity::class,
        DownloadTaskEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class MangaDatabase : RoomDatabase() {
    abstract fun mangaDao(): MangaDao

    companion object {
        @Volatile
        private var INSTANCE: MangaDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chapters_mangaId` ON `chapters` (`mangaId`)"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sources` ADD COLUMN `engineType` TEXT NOT NULL DEFAULT 'MADARA'")
                db.execSQL("ALTER TABLE `sources` ADD COLUMN `contentRating` TEXT NOT NULL DEFAULT 'GENERAL'")
                db.execSQL("ALTER TABLE `sources` ADD COLUMN `extractedMangaCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `sources` ADD COLUMN `extractedChaptersCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `sources` ADD COLUMN `extractedPagesCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `sources` ADD COLUMN `lastError` TEXT")
                db.execSQL("ALTER TABLE `manga` ADD COLUMN `contentRating` TEXT NOT NULL DEFAULT 'GENERAL'")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `source_configurations` (`sourceId` TEXT NOT NULL, `domain` TEXT NOT NULL, `configJson` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`sourceId`))"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_source_configurations_domain` ON `source_configurations` (`domain`)"
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
