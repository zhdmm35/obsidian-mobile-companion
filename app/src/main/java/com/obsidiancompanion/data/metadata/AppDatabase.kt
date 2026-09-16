package com.obsidiancompanion.data.metadata

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.obsidiancompanion.data.metadata.dao.NoteMetadataDao
import com.obsidiancompanion.data.metadata.dao.PendingEditDao
import com.obsidiancompanion.data.metadata.dao.RecentSearchDao
import com.obsidiancompanion.data.metadata.dao.RepoEntryDao
import com.obsidiancompanion.data.metadata.dao.RepositoryStateDao
import com.obsidiancompanion.data.metadata.entities.NoteUserMetadataEntity
import com.obsidiancompanion.data.metadata.entities.PendingEditEntity
import com.obsidiancompanion.data.metadata.entities.RecentSearchEntity
import com.obsidiancompanion.data.metadata.entities.RepoEntryEntity
import com.obsidiancompanion.data.metadata.entities.RepositoryStateEntity

/**
 * App Metadata 库（§2/§40）：只存 repository tree/index、cache metadata、收藏、最近阅读、最近搜索。
 * Markdown 正文一律不进 Room（按 blob SHA 存 ContentCache）。
 * v2（Phase 5 §20）：新增 pending_edits —— 唯一的正文暂存例外（保存失败/冲突/崩溃恢复，成功即清）。
 */
@Database(
    entities = [
        RepoEntryEntity::class,
        NoteUserMetadataEntity::class,
        RecentSearchEntity::class,
        RepositoryStateEntity::class,
        PendingEditEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun repoEntryDao(): RepoEntryDao
    abstract fun repositoryStateDao(): RepositoryStateDao
    abstract fun noteMetadataDao(): NoteMetadataDao
    abstract fun recentSearchDao(): RecentSearchDao
    abstract fun pendingEditDao(): PendingEditDao

    companion object {

        /** v1 → v2：仅新增 pending_edits 表；既有 Tree / 收藏 / 最近阅读 / 最近搜索全部保留。 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_edits` (" +
                        "`repoId` TEXT NOT NULL, `path` TEXT NOT NULL, `baseSha` TEXT NOT NULL, " +
                        "`content` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`repoId`, `path`))",
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "app_metadata.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
