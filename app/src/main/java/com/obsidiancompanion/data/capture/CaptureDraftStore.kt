package com.obsidiancompanion.data.capture

import android.content.Context
import android.content.SharedPreferences
import com.obsidiancompanion.data.metadata.dao.CaptureDraftDao
import com.obsidiancompanion.data.metadata.entities.CaptureDraftEntity
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Room 逐条保存分享内容；只有打开/复制/上传时读取全文，旧 SharedPreferences 在后台一次性迁移。 */
class CaptureDraftStore(
    private val prefs: SharedPreferences,
    private val dao: CaptureDraftDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(context: Context, dao: CaptureDraftDao) :
        this(context.getSharedPreferences("capture_draft", Context.MODE_PRIVATE), dao)

    @Serializable
    data class Draft(
        val text: String,
        val name: String,
        val folder: String,
        val id: String = UUID.randomUUID().toString(),
        val updatedAt: Long = System.currentTimeMillis(),
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val initialization = Mutex()
    private val writes = Mutex()
    private var initialized = false

    val drafts = flow { initialize(); emitAll(dao.observeSummaries()) }
    val count = flow { initialize(); emitAll(dao.observeCount()) }

    suspend fun initialize() = initialization.withLock {
        if (!initialized) {
            withContext(ioDispatcher) {
                val encoded = prefs.getString("drafts", null)
                val legacy = if (encoded != null) {
                    json.decodeFromString<List<Draft>>(encoded)
                } else {
                    prefs.getString("text", null)?.let { text ->
                        listOf(Draft(text, prefs.getString("name", "").orEmpty(), prefs.getString("folder", "").orEmpty(),
                            id = UUID.nameUUIDFromBytes(("capture-legacy:" + text).toByteArray(Charsets.UTF_8)).toString()))
                    }.orEmpty()
                }
                if (legacy.isNotEmpty()) dao.importLegacy(legacy.map { it.toEntity() })
                // 数据库插入成功后才删旧副本；中途失败重试时 IGNORE 保持 ID 与用户后续修改。
                if (encoded != null || prefs.contains("text")) {
                    check(prefs.edit().remove("drafts").remove("text").remove("name").remove("folder").commit()) {
                        "Cannot finish capture draft migration"
                    }
                }
            }
            initialized = true
        }
    }

    suspend fun create(text: String, name: String, folder: String = ""): Draft {
        initialize()
        val draft = Draft(text, name, folder)
        dao.insert(draft.toEntity())
        return draft
    }

    suspend fun update(id: String, name: String, folder: String): Boolean {
        initialize()
        return writes.withLock { dao.update(id, name, folder, System.currentTimeMillis()) > 0 }
    }

    suspend fun find(id: String): Draft? { initialize(); return dao.get(id)?.toDraft() }

    suspend fun clearIfUnchanged(expected: Draft): Boolean {
        initialize()
        return writes.withLock {
            dao.deleteIfUnchanged(expected.id, expected.text, expected.name, expected.folder, expected.updatedAt) > 0
        }
    }

    private fun Draft.toEntity() = CaptureDraftEntity(id, text, name, folder, updatedAt)
    private fun CaptureDraftEntity.toDraft() = Draft(text, name, folder, id, updatedAt)
}
