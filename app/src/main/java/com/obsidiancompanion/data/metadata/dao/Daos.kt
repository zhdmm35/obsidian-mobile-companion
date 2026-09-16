package com.obsidiancompanion.data.metadata.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.obsidiancompanion.data.metadata.entities.NoteUserMetadataEntity
import com.obsidiancompanion.data.metadata.entities.PendingEditEntity
import com.obsidiancompanion.data.metadata.entities.RecentSearchEntity
import com.obsidiancompanion.data.metadata.entities.RepoEntryEntity
import com.obsidiancompanion.data.metadata.entities.RepositoryStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RepoEntryDao {

    @Query("SELECT * FROM repo_entries WHERE repoId = :repoId")
    fun observeAll(repoId: String): Flow<List<RepoEntryEntity>>

    @Query("SELECT * FROM repo_entries WHERE repoId = :repoId AND path = :path")
    fun observeOne(repoId: String, path: String): Flow<RepoEntryEntity?>

    @Query("SELECT * FROM repo_entries WHERE repoId = :repoId AND path = :path")
    suspend fun get(repoId: String, path: String): RepoEntryEntity?

    @Query("SELECT * FROM repo_entries WHERE repoId = :repoId")
    suspend fun getAll(repoId: String): List<RepoEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<RepoEntryEntity>)

    @Query("DELETE FROM repo_entries WHERE repoId = :repoId")
    suspend fun deleteByRepo(repoId: String)

    /**
     * Phase 5 §10/§50：保存成功后立即把 entry 指向新 blob（不必等整树 refresh），
     * observedChangedAt = now → Home 最近修改立即出现（不等下一次远程 Tree refresh）。
     */
    @Query(
        "UPDATE repo_entries SET blobSha = :newSha, size = :size, observedChangedAt = :changedAt " +
            "WHERE repoId = :repoId AND path = :path",
    )
    suspend fun updateBlobAfterSave(repoId: String, path: String, newSha: String, size: Long?, changedAt: Long)
}

@Dao
interface RepositoryStateDao {

    @Query("SELECT * FROM repository_state WHERE repoId = :repoId")
    suspend fun get(repoId: String): RepositoryStateEntity?

    @Query("SELECT * FROM repository_state WHERE repoId = :repoId")
    fun observe(repoId: String): Flow<RepositoryStateEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: RepositoryStateEntity)
}

@Dao
interface NoteMetadataDao {

    @Query("SELECT * FROM note_user_metadata WHERE repoId = :repoId AND isFavorite = 1")
    fun observeFavorites(repoId: String): Flow<List<NoteUserMetadataEntity>>

    @Query(
        "SELECT * FROM note_user_metadata WHERE repoId = :repoId AND lastReadAt IS NOT NULL " +
            "ORDER BY lastReadAt DESC LIMIT :limit",
    )
    fun observeRecentRead(repoId: String, limit: Int): Flow<List<NoteUserMetadataEntity>>

    @Query("SELECT * FROM note_user_metadata WHERE repoId = :repoId AND path = :path")
    fun observeOne(repoId: String, path: String): Flow<NoteUserMetadataEntity?>

    /** upsert 只动 isFavorite，保留 lastReadAt。 */
    @Query(
        "INSERT INTO note_user_metadata(repoId, path, lastReadAt, isFavorite) VALUES (:repoId, :path, NULL, :favorite) " +
            "ON CONFLICT(repoId, path) DO UPDATE SET isFavorite = :favorite",
    )
    suspend fun setFavorite(repoId: String, path: String, favorite: Boolean)

    /** upsert 只动 lastReadAt，保留 isFavorite。 */
    @Query(
        "INSERT INTO note_user_metadata(repoId, path, lastReadAt, isFavorite) VALUES (:repoId, :path, :readAt, 0) " +
            "ON CONFLICT(repoId, path) DO UPDATE SET lastReadAt = :readAt",
    )
    suspend fun markRead(repoId: String, path: String, readAt: Long)
}

@Dao
interface RecentSearchDao {

    /** 同 query 重复提交 → 只更新时间（§28）。 */
    @Query(
        "INSERT INTO recent_searches(repoId, query, searchedAt) VALUES (:repoId, :query, :searchedAt) " +
            "ON CONFLICT(repoId, query) DO UPDATE SET searchedAt = :searchedAt",
    )
    suspend fun record(repoId: String, query: String, searchedAt: Long)

    @Query("SELECT * FROM recent_searches WHERE repoId = :repoId ORDER BY searchedAt DESC LIMIT :limit")
    fun observeRecent(repoId: String, limit: Int): Flow<List<RecentSearchEntity>>
}

/** Phase 5 §20-§21：PendingEdit（保存前暂存 / 成功清除）。 */
@Dao
interface PendingEditDao {

    @Query("SELECT * FROM pending_edits WHERE repoId = :repoId AND path = :path")
    suspend fun get(repoId: String, path: String): PendingEditEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(edit: PendingEditEntity)

    @Query("DELETE FROM pending_edits WHERE repoId = :repoId AND path = :path")
    suspend fun delete(repoId: String, path: String)
}
