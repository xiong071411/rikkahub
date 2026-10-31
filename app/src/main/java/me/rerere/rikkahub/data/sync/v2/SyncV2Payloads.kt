package me.rerere.rikkahub.data.sync.v2

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.db.entity.FavoriteEntity
import me.rerere.rikkahub.data.db.entity.FolderEntity
import me.rerere.rikkahub.data.db.entity.MediaCreationNodeEntity
import me.rerere.rikkahub.data.db.entity.MediaCreationRecordEntity
import me.rerere.rikkahub.data.db.entity.MediaCreationSessionEntity

/**
 * Sync V2 跨平台（Android <-> Unified Web Server）实体 Payload 序列化契约。
 * 保证 Repository Outbox 记录器与 IncrementalSyncEngine 对同一实体产出完全一致的 JSON 结构。
 */

@Serializable
data class FolderSyncV2Payload(
    val id: String,
    val assistantId: String = "",
    val name: String,
    val sortIndex: Int = 0,
    val createAt: Long = 0L,
    val updateAt: Long = 0L,
) {
    fun toEntity(): FolderEntity = FolderEntity(
        id = id,
        assistantId = assistantId,
        name = name,
        sortIndex = sortIndex,
        createAt = createAt,
    )

    companion object {
        fun fromEntity(entity: FolderEntity, updateAt: Long = System.currentTimeMillis()): FolderSyncV2Payload =
            FolderSyncV2Payload(
                id = entity.id,
                assistantId = entity.assistantId,
                name = entity.name,
                sortIndex = entity.sortIndex,
                createAt = entity.createAt,
                updateAt = updateAt,
            )
    }
}

@Serializable
data class FavoriteSyncV2Payload(
    val id: String,
    val refKey: String,
    val type: String,
    val refJson: String,
    val snapshotJson: String,
    val metaJson: String? = null,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
) {
    fun toEntity(): FavoriteEntity = FavoriteEntity(
        id = id,
        refKey = refKey,
        type = type,
        refJson = refJson,
        snapshotJson = snapshotJson,
        metaJson = metaJson,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    companion object {
        fun fromEntity(entity: FavoriteEntity): FavoriteSyncV2Payload = FavoriteSyncV2Payload(
            id = entity.id,
            refKey = entity.refKey,
            type = entity.type,
            refJson = entity.refJson,
            snapshotJson = entity.snapshotJson,
            metaJson = entity.metaJson,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
        )
    }
}

@Serializable
data class MediaSessionSyncV2Payload(
    val id: String,
    val title: String,
    val draft: String,
    val createAt: Long,
    val updateAt: Long,
) {
    fun toEntity(): MediaCreationSessionEntity = MediaCreationSessionEntity(
        id = id,
        title = title,
        draft = draft,
        createAt = createAt,
        updateAt = updateAt,
    )

    companion object {
        fun fromEntity(entity: MediaCreationSessionEntity): MediaSessionSyncV2Payload = MediaSessionSyncV2Payload(
            id = entity.id,
            title = entity.title,
            draft = entity.draft,
            createAt = entity.createAt,
            updateAt = entity.updateAt,
        )
    }
}

@Serializable
data class MediaNodeSyncV2Payload(
    val id: String,
    val sessionId: String,
    val selectedRecordId: String,
    val createAt: Long,
) {
    fun toEntity(): MediaCreationNodeEntity = MediaCreationNodeEntity(
        id = id,
        sessionId = sessionId,
        selectedRecordId = selectedRecordId,
        createAt = createAt,
    )

    companion object {
        fun fromEntity(entity: MediaCreationNodeEntity): MediaNodeSyncV2Payload = MediaNodeSyncV2Payload(
            id = entity.id,
            sessionId = entity.sessionId,
            selectedRecordId = entity.selectedRecordId,
            createAt = entity.createAt,
        )
    }
}

@Serializable
data class MediaRecordSyncV2Payload(
    val id: String,
    val sessionId: String,
    val nodeId: String,
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val kind: String,
    val prompt: String,
    val params: String,
    val inputs: String,
    val status: String,
    val taskId: String? = null,
    val error: String? = null,
    val outputs: String,
    val createAt: Long,
    val updateAt: Long,
) {
    fun toEntity(): MediaCreationRecordEntity = MediaCreationRecordEntity(
        id = id,
        sessionId = sessionId,
        nodeId = nodeId,
        providerId = providerId,
        providerName = providerName,
        modelId = modelId,
        kind = kind,
        prompt = prompt,
        params = params,
        inputs = inputs,
        status = status,
        taskId = taskId,
        error = error,
        outputs = outputs,
        createAt = createAt,
        updateAt = updateAt,
    )

    companion object {
        fun fromEntity(entity: MediaCreationRecordEntity): MediaRecordSyncV2Payload = MediaRecordSyncV2Payload(
            id = entity.id,
            sessionId = entity.sessionId,
            nodeId = entity.nodeId,
            providerId = entity.providerId,
            providerName = entity.providerName,
            modelId = entity.modelId,
            kind = entity.kind,
            prompt = entity.prompt,
            params = entity.params,
            inputs = entity.inputs,
            status = entity.status,
            taskId = entity.taskId,
            error = entity.error,
            outputs = entity.outputs,
            createAt = entity.createAt,
            updateAt = entity.updateAt,
        )
    }
}
