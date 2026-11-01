package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.db.dao.ManagedFileDAO
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.sync.v2.ExtendedSyncTypes
import me.rerere.rikkahub.data.sync.v2.SyncCoverageStore
import me.rerere.rikkahub.data.sync.v2.SyncMutationRecorder
import me.rerere.rikkahub.utils.JsonInstant

class FilesRepository(
    private val dao: ManagedFileDAO,
    private val recorder: SyncMutationRecorder? = null,
) {
    suspend fun insert(file: ManagedFileEntity): ManagedFileEntity {
        val id = dao.insert(file)
        val saved = file.copy(id = id)
        record(saved)
        return saved
    }

    suspend fun update(file: ManagedFileEntity) {
        dao.update(file)
        record(file)
    }

    suspend fun getById(id: Long): ManagedFileEntity? = dao.getById(id)

    suspend fun getByPath(relativePath: String): ManagedFileEntity? = dao.getByPath(relativePath)

    fun listByFolder(folder: String): Flow<List<ManagedFileEntity>> = dao.listByFolder(folder)

    private suspend fun record(file: ManagedFileEntity) {
        if (!ExtendedSyncTypes.isResourcePath(file.relativePath)) return
        recorder?.recordUpsert(
            "managed_file",
            SyncCoverageStore.pathId(file.relativePath),
            JsonObject(JsonInstant.encodeToJsonElement(file).jsonObject - "id").toString()
        )
    }

    private suspend fun recordDelete(file: ManagedFileEntity) {
        if (!ExtendedSyncTypes.isResourcePath(file.relativePath)) return
        val key = SyncCoverageStore.pathId(file.relativePath)
        recorder?.recordDelete("managed_file", key)
        recorder?.recordDelete("file_asset", key)
    }

    suspend fun deleteById(id: Long): Int {
        dao.getById(id)?.let { recordDelete(it) }
        return dao.deleteById(id)
    }

    suspend fun deleteByPath(relativePath: String, syncDelete: Boolean = true): Int {
        if (syncDelete) dao.getByPath(relativePath)?.let { recordDelete(it) }
        return dao.deleteByPath(relativePath)
    }

    suspend fun deleteByFolder(folder: String): Int {
        dao.listByFolder(folder).first().forEach { recordDelete(it) }
        return dao.deleteByFolder(folder)
    }
}
