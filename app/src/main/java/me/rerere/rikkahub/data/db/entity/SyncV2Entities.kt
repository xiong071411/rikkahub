package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Sync V2 本地已对齐的实体修订基线表。
 * 记录每个同步实体最后一次与云端确认一致的 revision 与语义哈希（last_synced_hash），
 * 用于精确计算增量差异及向服务端提交乐观并发基线（base_revision）。
 */
@Entity(
    tableName = "sync_entity_state",
    primaryKeys = ["entity_type", "entity_id"],
)
data class SyncEntityStateEntity(
    @ColumnInfo("entity_type")
    val entityType: String,
    @ColumnInfo("entity_id")
    val entityId: String,
    @ColumnInfo("last_synced_revision")
    val lastSyncedRevision: Long = 0L,
    @ColumnInfo("last_synced_hash")
    val lastSyncedHash: String = "",
    @ColumnInfo("deleted")
    val deleted: Boolean = false,
    @ColumnInfo("updated_at")
    val updatedAt: Long = 0L,
)

/**
 * Sync V2 本地离线待推送队列（Outbox）。
 * 同一 (entity_type, entity_id) 在尚未成功推送到云端前多次修改时，会合并最新 payload，
 * 但严格保留最初记录时的 baseRevision，从而保证并发冲突检测不漏报。
 */
@Entity(
    tableName = "sync_outbox",
    indices = [
        Index(value = ["entity_type", "entity_id"], unique = true),
    ],
)
data class SyncOutboxEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo("id")
    val id: Long = 0L,
    @ColumnInfo("mutation_id")
    val mutationId: String,
    @ColumnInfo("entity_type")
    val entityType: String,
    @ColumnInfo("entity_id")
    val entityId: String,
    @ColumnInfo("operation")
    val operation: String,
    @ColumnInfo("base_revision")
    val baseRevision: Long = 0L,
    @ColumnInfo("payload")
    val payload: String = "",
    @ColumnInfo("content_hash")
    val contentHash: String = "",
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("retry_count")
    val retryCount: Int = 0,
    @ColumnInfo("last_error")
    val lastError: String? = null,
)

/**
 * Sync V2 远端待延迟应用缓冲表（Inbox Pending）。
 * 当某个本地会话正在流式生成（ConversationSession.isGenerating == true）时，
 * 远端针对该会话的下发变更先暂存于此，待生成结束后或下次空闲同步时再安全合并，避免打断本地流式回复。
 */
@Entity(
    tableName = "sync_inbox_pending",
    primaryKeys = ["entity_type", "entity_id"],
)
data class SyncInboxPendingEntity(
    @ColumnInfo("entity_type")
    val entityType: String,
    @ColumnInfo("entity_id")
    val entityId: String,
    @ColumnInfo("revision")
    val revision: Long,
    @ColumnInfo("operation")
    val operation: String,
    @ColumnInfo("payload")
    val payload: String = "",
    @ColumnInfo("content_hash")
    val contentHash: String = "",
    @ColumnInfo("received_at")
    val receivedAt: Long,
)

/**
 * Sync V2 双副本冲突留存表。
 * 当多端基于同一旧 revision 并发修改同一实体且语义哈希不一致时，云端权威版本照常应用，
 * 同时将本地未覆盖的修改完整保存在 localPayload 中，供用户在冲突中心恢复为新会话、覆盖重推或忽略。
 */
@Entity(
    tableName = "sync_conflicts",
    indices = [
        Index(value = ["resolved", "created_at"]),
    ],
)
data class SyncConflictEntity(
    @PrimaryKey
    @ColumnInfo("id")
    val id: String,
    @ColumnInfo("entity_type")
    val entityType: String,
    @ColumnInfo("entity_id")
    val entityId: String,
    @ColumnInfo("title")
    val title: String = "",
    @ColumnInfo("operation")
    val operation: String,
    @ColumnInfo("base_revision")
    val baseRevision: Long = 0L,
    @ColumnInfo("server_revision")
    val serverRevision: Long = 0L,
    @ColumnInfo("local_payload")
    val localPayload: String = "",
    @ColumnInfo("server_payload")
    val serverPayload: String = "",
    @ColumnInfo("resolved")
    val resolved: Boolean = false,
    @ColumnInfo("created_at")
    val createdAt: Long,
)

/**
 * Sync V2 附件断点续传/待重试队列表。
 * 记录上传或下载失败（以及 syncFiles 暂时关闭时延后拉取）的受管附件路径，不阻塞文本同步，网络恢复后自动补齐。
 */
@Entity(
    tableName = "sync_file_queue",
    primaryKeys = ["direction", "relative_path"],
)
data class SyncFileQueueEntity(
    @ColumnInfo("direction")
    val direction: String,
    @ColumnInfo("relative_path")
    val relativePath: String,
    @ColumnInfo("sha256")
    val sha256: String = "",
    @ColumnInfo("size")
    val size: Long = 0L,
    @ColumnInfo("retry_count")
    val retryCount: Int = 0,
    @ColumnInfo("last_error")
    val lastError: String? = null,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)

/**
 * Sync V2 客户端持久元数据键值表（保存 server_id、cursor、client_device_id、v2_reconciled 等状态）。
 */
@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    @PrimaryKey
    @ColumnInfo("key")
    val key: String,
    @ColumnInfo("value")
    val value: String,
)
