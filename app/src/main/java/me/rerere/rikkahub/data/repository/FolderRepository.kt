package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FolderDAO
import me.rerere.rikkahub.data.db.entity.FolderEntity
import me.rerere.rikkahub.data.model.Folder
import me.rerere.rikkahub.data.sync.v2.FolderSyncV2Payload
import me.rerere.rikkahub.data.sync.v2.SyncMutationRecorder
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant
import kotlin.uuid.Uuid

class FolderRepository(
    private val folderDAO: FolderDAO,
    private val conversationDAO: ConversationDAO,
    private val syncTombstoneManager: me.rerere.rikkahub.data.sync.engine.SyncTombstoneManager,
    private val syncMutationRecorder: SyncMutationRecorder? = null,
) {
    private suspend fun recordFolderUpsert(entity: FolderEntity) {
        syncMutationRecorder?.recordUpsert(
            entityType = "folder",
            entityId = entity.id,
            rawPayloadJson = JsonInstant.encodeToString(FolderSyncV2Payload.fromEntity(entity)),
        )
    }

    fun getFoldersOfAssistant(assistantId: Uuid): Flow<List<Folder>> {
        return folderDAO.getFoldersOfAssistant(assistantId.toString())
            .map { list -> list.map { it.toFolder() } }
    }

    suspend fun getFolderById(id: Uuid): Folder? {
        return folderDAO.getFolderById(id.toString())?.toFolder()
    }

    suspend fun createFolder(assistantId: Uuid, name: String): Folder {
        val folder = Folder(
            assistantId = assistantId,
            name = name,
            createAt = Instant.now(),
        )
        val entity = folder.toEntity()
        folderDAO.insert(entity)
        syncTombstoneManager.removeTombstone(folder.id.toString())
        recordFolderUpsert(entity)
        return folder
    }

    suspend fun renameFolder(id: Uuid, name: String) {
        folderDAO.rename(id.toString(), name)
        folderDAO.getFolderById(id.toString())?.let { recordFolderUpsert(it) }
    }

    /**
     * 删除文件夹，先把归属该文件夹的会话 folder_id 清空，再删除文件夹本身（不影响会话）。
     */
    suspend fun deleteFolder(id: Uuid) {
        conversationDAO.clearFolder(id.toString())
        folderDAO.deleteById(id.toString())
        syncTombstoneManager.recordTombstone(id.toString(), "folder")
        syncMutationRecorder?.recordDelete("folder", id.toString())
    }
}

private fun FolderEntity.toFolder(): Folder = Folder(
    id = Uuid.parse(id),
    assistantId = Uuid.parse(assistantId),
    name = name,
    sortIndex = sortIndex,
    createAt = Instant.ofEpochMilli(createAt),
)

private fun Folder.toEntity(): FolderEntity = FolderEntity(
    id = id.toString(),
    assistantId = assistantId.toString(),
    name = name,
    sortIndex = sortIndex,
    createAt = createAt.toEpochMilli(),
)
