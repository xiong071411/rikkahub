package me.rerere.rikkahub.data.sync.v2

import androidx.room.withTransaction
import kotlinx.coroutines.currentCoroutineContext
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.SyncV2Dao
import me.rerere.rikkahub.data.db.entity.SyncOutboxEntity
import java.io.File
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.Uuid

/**
 * 标记当前协程正在应用来自云端的增量同步变更（Pull 写入或冲突应用）。
 * Repository 层检测到该上下文时，不会把云端回放的写库操作重复塞入本地 [SyncOutboxEntity]，
 * 同时不影响其他协程上并发发生的用户手动编辑（零竞态隔离）。
 */
object RemoteSyncApplyContextKey : CoroutineContext.Key<RemoteSyncApplyContext>

object RemoteSyncApplyContext : AbstractCoroutineContextElement(RemoteSyncApplyContextKey)

suspend fun isApplyingRemoteSync(): Boolean =
    currentCoroutineContext()[RemoteSyncApplyContextKey] != null

/**
 * Sync V2 本地离线变更记录器（Outbox Recorder）。
 *
 * 核心不变量：
 * 1. 当同一 `(entityType, entityId)` 尚未成功推送到服务端、在本地被连续修改多次时，
 *    覆盖更新最新的 `payload` 与 `contentHash`，但**严格保留最初分叉时的 `baseRevision`**，
 *    确保重连推送时服务端能够准确检测这期间其他客户端是否改过该实体。
 * 2. 若实体尚未与云端同步过（`entityState == null && existingOutbox.baseRevision == 0`），
 *    在本地新建后又立即删除，直接移除未发出的本地 `upsert` 即可，避免制造无意义的云端空墓碑。
 */
class SyncMutationRecorder(
    private val database: AppDatabase?,
    private val syncV2Dao: SyncV2Dao,
    private val filesDirProvider: () -> File? = { null },
) {
    suspend fun <R> runInTransaction(block: suspend () -> R): R {
        return if (database != null) {
            database.withTransaction { block() }
        } else {
            block()
        }
    }

    suspend fun hasPendingMutation(entityType: String, entityId: String): Boolean =
        syncV2Dao.getOutboxEntry(entityType, entityId) != null

    suspend fun recordUpsert(
        entityType: String,
        entityId: String,
        rawPayloadJson: String,
    ) {
        if (isApplyingRemoteSync()) return
        val filesDir = filesDirProvider()
        val normPayload = CanonicalSyncHash.normalizeEntityPayload(entityType, rawPayloadJson, filesDir)
        val contentHash = CanonicalSyncHash.computeSemanticHash(entityType, normPayload, filesDir)
        val now = System.currentTimeMillis()

        runInTransaction {
            val existingOutbox = syncV2Dao.getOutboxEntry(entityType, entityId)
            val entityState = syncV2Dao.getEntityState(entityType, entityId)

            if (existingOutbox != null) {
                // 幂等关键：内容与操作均未变化时必须保留原 mutationId。
                // 每轮同步前 enqueueLocalDiffIntoOutbox 会重新扫描并调用本方法，若此处无条件换新 mutationId，
                // 当“服务端已提交但响应在途中丢失”后重推时将无法命中服务端 mutation_id 幂等表，
                // 反而因 baseRevision 落后被误判为与自己的提交冲突。
                if (existingOutbox.operation == "upsert" && existingOutbox.contentHash == contentHash) {
                    return@runInTransaction
                }
                syncV2Dao.insertOrReplaceOutbox(
                    existingOutbox.copy(
                        mutationId = Uuid.random().toString(),
                        operation = "upsert",
                        payload = normPayload,
                        contentHash = contentHash,
                        createdAt = now,
                    )
                )
                return@runInTransaction
            }

            // 如果与上次云端对齐基线完全一致（语义哈希未变且非删除态），无需产生无意义推送
            if (entityState != null && !entityState.deleted && entityState.lastSyncedHash == contentHash) {
                return@runInTransaction
            }

            syncV2Dao.insertOrReplaceOutbox(
                SyncOutboxEntity(
                    mutationId = Uuid.random().toString(),
                    entityType = entityType,
                    entityId = entityId,
                    operation = "upsert",
                    baseRevision = entityState?.lastSyncedRevision ?: 0L,
                    payload = normPayload,
                    contentHash = contentHash,
                    createdAt = now,
                )
            )
        }
    }

    suspend fun recordDelete(
        entityType: String,
        entityId: String,
    ) {
        if (isApplyingRemoteSync()) return
        val delHash = CanonicalSyncHash.computeSemanticHash(entityType, "")
        val now = System.currentTimeMillis()

        runInTransaction {
            val existingOutbox = syncV2Dao.getOutboxEntry(entityType, entityId)
            val entityState = syncV2Dao.getEntityState(entityType, entityId)

            if (existingOutbox != null) {
                // 本地离线新建、从未同步过云端的实体在离线期间即被删除 -> 直接抵消出队
                if (entityState == null && existingOutbox.baseRevision == 0L && existingOutbox.operation == "upsert") {
                    syncV2Dao.deleteOutboxById(existingOutbox.id)
                    return@runInTransaction
                }
                // 已排队的删除再次被扫描到时保持原 mutationId 不变，保证断网重推幂等
                if (existingOutbox.operation == "delete") {
                    return@runInTransaction
                }
                syncV2Dao.insertOrReplaceOutbox(
                    existingOutbox.copy(
                        mutationId = Uuid.random().toString(),
                        operation = "delete",
                        payload = "",
                        contentHash = delHash,
                        createdAt = now,
                    )
                )
                return@runInTransaction
            }

            if (entityState != null && entityState.deleted) {
                return@runInTransaction
            }

            syncV2Dao.insertOrReplaceOutbox(
                SyncOutboxEntity(
                    mutationId = Uuid.random().toString(),
                    entityType = entityType,
                    entityId = entityId,
                    operation = "delete",
                    baseRevision = entityState?.lastSyncedRevision ?: 0L,
                    payload = "",
                    contentHash = delHash,
                    createdAt = now,
                )
            )
        }
    }
}
