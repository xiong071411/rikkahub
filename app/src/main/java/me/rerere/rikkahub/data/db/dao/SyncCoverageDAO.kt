package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.db.entity.MemoryConsolidationEntity
import me.rerere.rikkahub.data.db.entity.SyncResourceEntity
import me.rerere.rikkahub.data.db.entity.TranslationHistoryEntity
import me.rerere.rikkahub.data.db.entity.UsageRecordEntity

@Dao
interface SyncCoverageDAO {
    @Query("SELECT * FROM usage_record ORDER BY id")
    suspend fun usage(): List<UsageRecordEntity>

    @Query("SELECT * FROM usage_record WHERE sync_id = :id")
    suspend fun usageById(id: String): UsageRecordEntity?

    @Upsert
    suspend fun upsertUsage(value: UsageRecordEntity)

    @Query("DELETE FROM usage_record WHERE sync_id = :id")
    suspend fun deleteUsage(id: String)

    @Query("SELECT * FROM translation_history ORDER BY create_at, id")
    suspend fun translations(): List<TranslationHistoryEntity>

    @Upsert
    suspend fun upsertTranslation(value: TranslationHistoryEntity)

    @Query("SELECT * FROM genmediaentity ORDER BY id")
    suspend fun generatedMedia(): List<GenMediaEntity>

    @Query("SELECT * FROM genmediaentity WHERE sync_id = :id")
    suspend fun generatedMediaById(id: String): GenMediaEntity?

    @Upsert
    suspend fun upsertGeneratedMedia(value: GenMediaEntity)

    @Query("DELETE FROM genmediaentity WHERE sync_id = :id")
    suspend fun deleteGeneratedMedia(id: String)

    @Query("SELECT * FROM managed_files ORDER BY relative_path")
    suspend fun managedFiles(): List<ManagedFileEntity>

    @Upsert
    suspend fun upsertManagedFile(value: ManagedFileEntity)

    @Query("SELECT * FROM memory_consolidation ORDER BY conversation_id")
    suspend fun memoryProgress(): List<MemoryConsolidationEntity>

    @Query("DELETE FROM memory_consolidation WHERE conversation_id = :id")
    suspend fun deleteMemoryProgress(id: String)

    @Query("SELECT * FROM sync_resources ORDER BY entityType, entityId")
    suspend fun resources(): List<SyncResourceEntity>

    @Query("SELECT * FROM sync_resources WHERE entityType = :type AND entityId = :id")
    suspend fun resource(type: String, id: String): SyncResourceEntity?

    @Upsert
    suspend fun putResource(value: SyncResourceEntity)
}
