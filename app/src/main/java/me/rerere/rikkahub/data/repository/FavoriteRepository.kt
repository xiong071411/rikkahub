package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.entity.FavoriteEntity
import me.rerere.rikkahub.data.model.FavoriteType
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import me.rerere.rikkahub.data.sync.v2.FavoriteSyncV2Payload
import me.rerere.rikkahub.data.sync.v2.SyncMutationRecorder
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

class FavoriteRepository(
    private val dao: FavoriteDAO,
    private val syncMutationRecorder: SyncMutationRecorder? = null,
) {
    private suspend fun recordFavoriteUpsert(entity: FavoriteEntity) {
        syncMutationRecorder?.recordUpsert(
            entityType = "favorite",
            entityId = entity.id,
            rawPayloadJson = JsonInstant.encodeToString(FavoriteSyncV2Payload.fromEntity(entity)),
        )
    }

    fun listAll(): Flow<List<FavoriteEntity>> = dao.listAll()

    fun listByType(type: FavoriteType): Flow<List<FavoriteEntity>> = dao.listByType(type.value)

    suspend fun getByRefKey(refKey: String): FavoriteEntity? = dao.getByRefKey(refKey)

    suspend fun existsByRefKey(refKey: String): Boolean = dao.existsByRefKey(refKey)

    suspend fun deleteByRefKey(refKey: String): Int {
        val existing = dao.getByRefKey(refKey)
        val removed = dao.deleteByRefKey(refKey)
        if (existing != null && removed > 0) {
            syncMutationRecorder?.recordDelete("favorite", existing.id)
        }
        return removed
    }

    suspend fun deleteById(id: String): Int {
        val removed = dao.deleteById(id)
        if (removed > 0) {
            syncMutationRecorder?.recordDelete("favorite", id)
        }
        return removed
    }

    suspend fun upsert(entity: FavoriteEntity) {
        dao.upsert(entity)
        recordFavoriteUpsert(entity)
    }

    suspend fun addNodeFavorite(target: NodeFavoriteTarget): FavoriteEntity {
        val refKey = NodeFavoriteAdapter.buildRefKey(target)
        val existing = dao.getByRefKey(refKey)
        val favorite = NodeFavoriteAdapter.buildFavoriteEntity(
            target = target,
            existing = existing,
        )
        dao.upsert(favorite)
        recordFavoriteUpsert(favorite)
        return favorite
    }

    suspend fun removeNodeFavorite(conversationId: Uuid, nodeId: Uuid): Int {
        return deleteByRefKey(NodeFavoriteAdapter.buildRefKey(conversationId.toString(), nodeId.toString()))
    }

    suspend fun isNodeFavorited(conversationId: Uuid, nodeId: Uuid): Boolean {
        return dao.existsByRefKey(NodeFavoriteAdapter.buildRefKey(conversationId.toString(), nodeId.toString()))
    }
}
