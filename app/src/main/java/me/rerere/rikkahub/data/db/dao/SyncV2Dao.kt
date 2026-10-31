package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.SyncConflictEntity
import me.rerere.rikkahub.data.db.entity.SyncEntityStateEntity
import me.rerere.rikkahub.data.db.entity.SyncFileQueueEntity
import me.rerere.rikkahub.data.db.entity.SyncInboxPendingEntity
import me.rerere.rikkahub.data.db.entity.SyncMetaEntity
import me.rerere.rikkahub.data.db.entity.SyncOutboxEntity

/**
 * Sync V2 状态、离线队列、延迟收件箱、双副本冲突与附件重试队列 DAO。
 */
@Dao
interface SyncV2Dao {
    // ---- sync_meta ----

    @Query("SELECT value FROM sync_meta WHERE `key` = :key LIMIT 1")
    suspend fun getMetaValue(key: String): String?

    @Query("SELECT value FROM sync_meta WHERE `key` = :key LIMIT 1")
    fun observeMetaValue(key: String): Flow<String?>

    @Upsert
    suspend fun upsertMeta(meta: SyncMetaEntity)

    @Query("DELETE FROM sync_meta WHERE `key` = :key")
    suspend fun deleteMeta(key: String)

    // ---- sync_entity_state ----

    @Query("SELECT * FROM sync_entity_state WHERE entity_type = :entityType AND entity_id = :entityId LIMIT 1")
    suspend fun getEntityState(entityType: String, entityId: String): SyncEntityStateEntity?

    @Query("SELECT * FROM sync_entity_state WHERE entity_type = :entityType")
    suspend fun getEntityStatesByType(entityType: String): List<SyncEntityStateEntity>

    @Query("SELECT * FROM sync_entity_state")
    suspend fun getAllEntityStates(): List<SyncEntityStateEntity>

    @Upsert
    suspend fun upsertEntityState(state: SyncEntityStateEntity)

    @Upsert
    suspend fun upsertEntityStates(states: List<SyncEntityStateEntity>)

    @Query("DELETE FROM sync_entity_state")
    suspend fun clearAllEntityStates()

    // ---- sync_outbox ----

    @Query("SELECT * FROM sync_outbox ORDER BY id ASC")
    suspend fun getAllOutboxOrdered(): List<SyncOutboxEntity>

    @Query("SELECT COUNT(*) FROM sync_outbox")
    fun observeOutboxCount(): Flow<Int>

    @Query("SELECT * FROM sync_outbox WHERE entity_type = :entityType AND entity_id = :entityId LIMIT 1")
    suspend fun getOutboxEntry(entityType: String, entityId: String): SyncOutboxEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplaceOutbox(entry: SyncOutboxEntity): Long

    @Query("DELETE FROM sync_outbox WHERE id = :id")
    suspend fun deleteOutboxById(id: Long)

    @Query("DELETE FROM sync_outbox WHERE mutation_id = :mutationId")
    suspend fun deleteOutboxByMutationId(mutationId: String)

    @Query("DELETE FROM sync_outbox WHERE entity_type = :entityType AND entity_id = :entityId")
    suspend fun deleteOutboxByEntity(entityType: String, entityId: String)

    @Query("UPDATE sync_outbox SET retry_count = retry_count + 1, last_error = :error WHERE id = :id")
    suspend fun markOutboxRetry(id: Long, error: String?)

    // ---- sync_inbox_pending ----

    @Query("SELECT * FROM sync_inbox_pending ORDER BY revision ASC")
    suspend fun getAllInboxPending(): List<SyncInboxPendingEntity>

    @Upsert
    suspend fun upsertInboxPending(item: SyncInboxPendingEntity)

    @Query("DELETE FROM sync_inbox_pending WHERE entity_type = :entityType AND entity_id = :entityId")
    suspend fun deleteInboxPending(entityType: String, entityId: String)

    // ---- sync_conflicts ----

    @Query("SELECT * FROM sync_conflicts WHERE resolved = 0 ORDER BY created_at DESC")
    fun observeUnresolvedConflicts(): Flow<List<SyncConflictEntity>>

    @Query("SELECT COUNT(*) FROM sync_conflicts WHERE resolved = 0")
    fun observeUnresolvedConflictCount(): Flow<Int>

    @Query("SELECT * FROM sync_conflicts WHERE resolved = 0 ORDER BY created_at DESC")
    suspend fun getUnresolvedConflicts(): List<SyncConflictEntity>

    @Query("SELECT * FROM sync_conflicts WHERE id = :id LIMIT 1")
    suspend fun getConflictById(id: String): SyncConflictEntity?

    @Upsert
    suspend fun upsertConflict(conflict: SyncConflictEntity)

    @Query("UPDATE sync_conflicts SET resolved = 1 WHERE id = :id")
    suspend fun markConflictResolved(id: String)

    // ---- sync_file_queue ----

    @Query("SELECT * FROM sync_file_queue ORDER BY updated_at ASC")
    suspend fun getAllFileQueue(): List<SyncFileQueueEntity>

    @Query("SELECT * FROM sync_file_queue WHERE direction = :direction ORDER BY updated_at ASC")
    suspend fun getFileQueueByDirection(direction: String): List<SyncFileQueueEntity>

    @Query("SELECT COUNT(*) FROM sync_file_queue")
    fun observePendingFileCount(): Flow<Int>

    @Upsert
    suspend fun upsertFileQueue(item: SyncFileQueueEntity)

    @Upsert
    suspend fun upsertFileQueueAll(items: List<SyncFileQueueEntity>)

    @Query("DELETE FROM sync_file_queue WHERE direction = :direction AND relative_path = :relativePath")
    suspend fun deleteFileQueue(direction: String, relativePath: String)

    @Query(
        "UPDATE sync_file_queue SET retry_count = retry_count + 1, last_error = :error, updated_at = :updatedAt " +
            "WHERE direction = :direction AND relative_path = :relativePath"
    )
    suspend fun markFileQueueRetry(direction: String, relativePath: String, error: String?, updatedAt: Long)
}
