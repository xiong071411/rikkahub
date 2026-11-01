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
    private suspend fun <T> mutate(block: suspend () -> T): T =
        if (recorder == null) block() else recorder.runInTransaction(block)

    suspend fun insert(file: ManagedFileEntity): ManagedFileEntity = mutate {
        val id = dao.insert(file)
        val saved = file.copy(id = id)
        record(saved)
        saved
    }

    suspend fun update(file: ManagedFileEntity) = mutate {
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

    suspend fun deleteById(id: Long): Int = mutate {
        dao.getById(id)?.let { recordDelete(it) }
        dao.deleteById(id)
    }

    suspend fun deleteByPath(relativePath: String, syncDelete: Boolean = true): Int = mutate {
        if (syncDelete) dao.getByPath(relativePath)?.let { recordDelete(it) }
        dao.deleteByPath(relativePath)
    }

    suspend fun deleteByFolder(folder: String): Int = mutate {
        dao.listByFolder(folder).first().forEach { recordDelete(it) }
        dao.deleteByFolder(folder)
    }
}
