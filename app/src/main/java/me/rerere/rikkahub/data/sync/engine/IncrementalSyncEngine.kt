package me.rerere.rikkahub.data.sync.engine

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.ProviderSetting
import me.rerere.asr.ASRProviderSetting
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.SyncServerConfig
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.dao.FolderDAO
import me.rerere.rikkahub.data.db.dao.MediaCreationDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.SyncV2Dao
import me.rerere.rikkahub.data.db.entity.FolderEntity
import me.rerere.rikkahub.data.db.entity.SyncConflictEntity
import me.rerere.rikkahub.data.db.entity.SyncEntityStateEntity
import me.rerere.rikkahub.data.db.entity.SyncFileQueueEntity
import me.rerere.rikkahub.data.db.entity.SyncInboxPendingEntity
import me.rerere.rikkahub.data.db.entity.SyncMetaEntity
import me.rerere.rikkahub.data.db.entity.SyncOutboxEntity
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.sync.v2.AttachmentPathNormalizer
import me.rerere.rikkahub.data.sync.v2.AttachmentPathNormalizer.toLocalizedAttachmentPaths
import me.rerere.rikkahub.data.sync.v2.AttachmentPathNormalizer.toPortableAttachmentPaths
import me.rerere.rikkahub.data.sync.v2.CanonicalSyncHash
import me.rerere.rikkahub.data.sync.v2.FavoriteSyncV2Payload
import me.rerere.rikkahub.data.sync.v2.FolderSyncV2Payload
import me.rerere.rikkahub.data.sync.v2.MediaNodeSyncV2Payload
import me.rerere.rikkahub.data.sync.v2.MediaRecordSyncV2Payload
import me.rerere.rikkahub.data.sync.v2.MediaSessionSyncV2Payload
import me.rerere.rikkahub.data.sync.v2.MemorySyncPayload
import me.rerere.rikkahub.data.sync.v2.RemoteSyncApplyContext
import me.rerere.rikkahub.data.sync.v2.SyncMutationRecorder
import me.rerere.rikkahub.data.sync.v2.SyncSettingsContract
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlin.uuid.Uuid

private const val TAG = "IncrementalSyncEngine"
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

private const val META_SERVER_ID = "server_id"
private const val META_CURSOR = "cursor"
private const val META_CLIENT_DEVICE_ID = "client_device_id"
private const val META_V2_RECONCILED = "v2_reconciled"
private const val META_COVERAGE = "sync_coverage_version"

private const val PULL_PAGE_LIMIT = 200
private const val PUSH_BATCH_LIMIT = 150
private const val FILE_CHECK_BATCH_LIMIT = 500

// ==================== Sync V2 传输 DTO ====================

@Serializable
data class SyncV2InfoResponseDto(
    val protocol_version: Int = 2,
    val supported_entity_types: List<String> = emptyList(),
    val server_id: String = "",
    val current_revision: Long = 0L,
    val server_time: Long = 0L,
    val unresolved_conflicts: Int = 0,
)

@Serializable
data class SyncV2PullRequestDto(
    val device_id: String,
    val device_name: String,
    val server_id: String = "",
    val cursor: Long = 0L,
    val upper_bound_revision: Long? = null,
    val limit: Int = PULL_PAGE_LIMIT,
)

@Serializable
data class SyncV2ChangeItemDto(
    val revision: Long,
    val entity_type: String,
    val entity_id: String,
    val operation: String,
    val payload: String = "",
    val content_hash: String = "",
    val updated_at: Long = 0L,
)

@Serializable
data class SyncV2PullResponseDto(
    val server_id: String = "",
    val current_revision: Long = 0L,
    val upper_bound_revision: Long = 0L,
    val next_cursor: Long = 0L,
    val has_more: Boolean = false,
    val reset_required: Boolean = false,
    val server_time: Long = 0L,
    val changes: List<SyncV2ChangeItemDto> = emptyList(),
)

@Serializable
data class SyncV2MutationItemDto(
    val mutation_id: String,
    val entity_type: String,
    val entity_id: String,
    val operation: String,
    val base_revision: Long = 0L,
    val payload: String = "",
    val content_hash: String = "",
)

@Serializable
data class SyncV2PushRequestDto(
    val device_id: String,
    val device_name: String,
    val server_id: String = "",
    val mutations: List<SyncV2MutationItemDto> = emptyList(),
)

@Serializable
data class SyncV2MutationResultDto(
    val mutation_id: String,
    val entity_type: String,
    val entity_id: String,
    val status: String,
    val revision: Long = 0L,
    val conflict_id: String? = null,
    val server_operation: String? = null,
    val server_payload: String? = null,
    val server_hash: String? = null,
    val message: String? = null,
)

@Serializable
data class SyncV2PushResponseDto(
    val server_id: String = "",
    val current_revision: Long = 0L,
    val server_time: Long = 0L,
    val reset_required: Boolean = false,
    val results: List<SyncV2MutationResultDto> = emptyList(),
)

@Serializable
data class AiPreferencesSyncDto(
    val favoriteModels: List<String> = emptyList(),
    val chatModelId: String = "",
    val fastModelId: String = "",
    val enableAutoTitle: Boolean = true,
    val titlePrompt: String = "",
    val translatePrompt: String = "",
    val translateTargetLanguage: String = "zh-CN",
    val enableSuggestion: Boolean = true,
    val suggestionPrompt: String = "",
    val ocrPrompt: String = "",
    val defaultTTSPlaybackSpeed: Float = 1.0f,
)

@Serializable
data class FileCheckItemDto(
    val path: String,
    val hash: String = "",
    val size: Long = 0L,
)

@Serializable
data class FilesCheckRequestDto(
    val files: List<FileCheckItemDto>,
)

@Serializable
data class FilesCheckResponseDto(
    val missing_paths: List<String> = emptyList(),
)

data class SyncSummary(
    val pulledConversations: Int,
    val pushedConversations: Int,
    val syncedAssistants: Int,
    val syncedProviders: Int,
    val syncedFiles: Int,
    val conflictsDetected: Int = 0,
    val currentRevision: Long = 0L,
    val serverTimestamp: Long,
)

/**
 * 会话与文件夹同步本地访问桥接接口。
 * 生产环境默认委托给 `ChatService`（`ConversationSession` 单一事实源）与 Repository；
 * JVM 单元测试可直接注入内存实现而无需启动 Android `Service` 或 Robolectric。
 */
interface SyncConversationBridge {
    fun isConversationBusyForSync(convId: Uuid): Boolean
    suspend fun getAllConversationIds(): List<String>
    suspend fun loadConversation(convId: Uuid): Conversation?
    suspend fun applyRemoteSyncedConversation(conversation: Conversation)
    suspend fun applyRemoteDeletedConversation(convId: Uuid)
    suspend fun insertForkedConversation(conversation: Conversation)
    suspend fun deleteFolder(folderId: Uuid)
    suspend fun purgeLegacyTombstones(olderThanMs: Long)
}

private class DefaultSyncConversationBridge(
    private val conversationRepository: ConversationRepository,
    private val folderRepository: FolderRepository,
    private val conversationDAO: ConversationDAO,
    private val syncTombstoneManager: SyncTombstoneManager,
    private val lazyChatService: Lazy<ChatService>?,
) : SyncConversationBridge {
    override fun isConversationBusyForSync(convId: Uuid): Boolean =
        lazyChatService?.value?.isConversationBusyForSync(convId) == true

    override suspend fun getAllConversationIds(): List<String> =
        conversationDAO.getAllIds()

    override suspend fun loadConversation(convId: Uuid): Conversation? =
        lazyChatService?.value?.getConversation(convId) ?: conversationRepository.getConversationById(convId)

    override suspend fun applyRemoteSyncedConversation(conversation: Conversation) {
        val chatService = lazyChatService?.value
        if (chatService != null) {
            chatService.applyRemoteSyncedConversation(conversation)
        } else {
            if (conversationDAO.existsById(conversation.id.toString())) {
                conversationRepository.updateConversation(conversation)
            } else {
                conversationRepository.insertConversation(conversation)
            }
        }
    }

    override suspend fun applyRemoteDeletedConversation(convId: Uuid) {
        val chatService = lazyChatService?.value
        if (chatService != null) {
            chatService.applyRemoteDeletedConversation(convId)
        } else {
            conversationRepository.getConversationById(convId)?.let {
                conversationRepository.deleteConversation(it)
            }
        }
    }

    override suspend fun insertForkedConversation(conversation: Conversation) {
        conversationRepository.insertConversation(conversation)
    }

    override suspend fun deleteFolder(folderId: Uuid) {
        val chatService = lazyChatService?.value
        if (chatService != null) {
            chatService.deleteFolder(folderId)
        } else {
            folderRepository.deleteFolder(folderId)
        }
    }

    override suspend fun purgeLegacyTombstones(olderThanMs: Long) {
        syncTombstoneManager.purgeOlderThan(olderThanMs)
    }
}

/**
 * Sync V2 全量业务实体修订增量同步引擎（防丢消息、双副本冲突保护、无损生图同步、低功耗串行版）：
 *
 * 1. 基于服务端单调递增 `revision` + `upper_bound_revision` 快照分页拉取，彻底消除设备时钟漂移与同毫秒漏同步。
 * 2. 本地变更通过 `sync_outbox` + `sync_entity_state` 跟踪乐观并发基线 `base_revision`；
 *    遇到多端并发修改同一实体时，绝不静默丢弃任一端内容，双副本落入 `sync_conflicts` 供冲突中心一键恢复或分叉为新会话。
 * 3. 会话写入与删除严格通过 `ChatService`（`ConversationSession` 单一事实源）执行；若目标会话正在流式生成，
 *    远端更新自动暂存至 `sync_inbox_pending`，生成结束后再无感合并。
 * 4. 覆盖全量 9 类实体：`conversation`、`folder`、`assistant`、`provider`、`favorite`、
 *    `media_session`、`media_node`、`media_record`、`extra_setting`，配合 `@Upsert` 彻底修复生图历史误清空问题。
 * 5. 附件采用 `.tmp` 临时文件 + `fd.sync()` + SHA-256 校验 + 原子重命名，失败进入 `sync_file_queue` 断点续传，不阻塞文本同步。
 */
class IncrementalSyncEngine(
    private val localFilesDir: File,
    private val database: AppDatabase?,
    private val syncV2Dao: SyncV2Dao,
    private val syncMutationRecorder: SyncMutationRecorder,
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val conversationBridge: SyncConversationBridge,
    private val folderDAO: FolderDAO,
    private val favoriteDAO: FavoriteDAO,
    private val mediaCreationDAO: MediaCreationDAO,
    private val okHttpClient: OkHttpClient,
    private val pullPageLimit: Int = PULL_PAGE_LIMIT,
    private val json: Json = JsonInstant,
) {
    constructor(
        context: Context,
        database: AppDatabase,
        syncV2Dao: SyncV2Dao,
        syncMutationRecorder: SyncMutationRecorder,
        settingsStore: SettingsStore,
        memoryRepository: MemoryRepository,
        conversationRepository: ConversationRepository,
        folderRepository: FolderRepository,
        conversationDAO: ConversationDAO,
        folderDAO: FolderDAO,
        @Suppress("UNUSED_PARAMETER") messageNodeDAO: MessageNodeDAO,
        favoriteDAO: FavoriteDAO,
        mediaCreationDAO: MediaCreationDAO,
        syncTombstoneManager: SyncTombstoneManager,
        okHttpClient: OkHttpClient,
        lazyChatService: Lazy<ChatService>? = null,
        json: Json = JsonInstant,
    ) : this(
        localFilesDir = context.filesDir,
        database = database,
        syncV2Dao = syncV2Dao,
        syncMutationRecorder = syncMutationRecorder,
        settingsStore = settingsStore,
        memoryRepository = memoryRepository,
        conversationBridge = DefaultSyncConversationBridge(
            conversationRepository = conversationRepository,
            folderRepository = folderRepository,
            conversationDAO = conversationDAO,
            syncTombstoneManager = syncTombstoneManager,
            lazyChatService = lazyChatService,
        ),
        folderDAO = folderDAO,
        favoriteDAO = favoriteDAO,
        mediaCreationDAO = mediaCreationDAO,
        okHttpClient = okHttpClient,
        pullPageLimit = PULL_PAGE_LIMIT,
        json = json,
    )

    private val syncMutex = Mutex()

    private val defaultDeviceName: String by lazy {
        val mfr = runCatching { Build.MANUFACTURER }.getOrNull().orEmpty()
        val mdl = runCatching { Build.MODEL }.getOrNull().orEmpty()
        "$mfr $mdl".trim().ifEmpty { "Android Device" }
    }

    private suspend fun <R> runInTransaction(block: suspend () -> R): R {
        return if (database != null) {
            database.withTransaction { block() }
        } else {
            block()
        }
    }

    private fun logWarn(msg: String, tr: Throwable? = null) {
        runCatching {
            if (tr != null) Log.w(TAG, msg, tr) else Log.w(TAG, msg)
        }
    }

    // Push 时遵循外键拓扑顺序，保证 media_session 先于 media_node、media_node 先于 media_record 到达服务端
    private val entityPushPriority: Map<String, Int> = mapOf(
        "folder" to 1,
        "assistant" to 2,
        "provider" to 3,
        "extra_setting" to 4,
        "memory_file" to 5,
        "conversation" to 5,
        "favorite" to 6,
        "media_session" to 7,
        "media_node" to 8,
        "media_record" to 9,
    )

    // ---- 响应式状态查询（供 SyncTab 与冲突中心低开销订阅） ----

    fun observeUnresolvedConflicts(): Flow<List<SyncConflictEntity>> =
        syncV2Dao.observeUnresolvedConflicts()

    fun observeUnresolvedConflictCount(): Flow<Int> =
        syncV2Dao.observeUnresolvedConflictCount()

    fun observePendingFileCount(): Flow<Int> =
        syncV2Dao.observePendingFileCount()

    fun observeOutboxCount(): Flow<Int> =
        syncV2Dao.observeOutboxCount()

    fun observeCurrentRevision(): Flow<Long> =
        syncV2Dao.observeMetaValue(META_CURSOR).map { it?.toLongOrNull() ?: 0L }

    private fun buildUrl(baseUrl: String, path: String): String =
        baseUrl.trim().trimEnd('/') + "/" + path.trimStart('/')

    private suspend fun getOrCreateClientDeviceId(config: SyncServerConfig): String {
        if (config.deviceName.isNotBlank()) return config.deviceName.trim()
        val stored = syncV2Dao.getMetaValue(META_CLIENT_DEVICE_ID)
        if (!stored.isNullOrBlank()) return stored
        val rawModel = runCatching { Build.MODEL }.getOrNull() ?: "Android"
        val modelPrefix = rawModel.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "Android" }
        val generated = "${modelPrefix}_${Uuid.random().toString().take(8)}"
        syncV2Dao.upsertMeta(SyncMetaEntity(META_CLIENT_DEVICE_ID, generated))
        return generated
    }

    private fun resolveDeviceName(config: SyncServerConfig): String =
        config.deviceName.trim().ifEmpty { defaultDeviceName }

    private fun validateConfigOrThrow(config: SyncServerConfig): Pair<String, String> {
        val baseUrl = config.serverUrl.trim().trimEnd('/')
        require(baseUrl.isNotBlank()) { "同步服务器地址不能为空" }
        require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) {
            "服务器地址必须以 http:// 或 https:// 开头"
        }
        val token = config.authToken.trim()
        require(token.isNotBlank()) {
            "同步令牌 (Sync Token) 未配置，请在服务器运行 manage_secrets.py 获取独立令牌后填入"
        }
        return baseUrl to token
    }

    private fun parseHttpError(code: Int, rawBody: String, fallbackLabel: String): Nothing {
        val detail = runCatching {
            val obj = json.parseToJsonElement(rawBody) as? JsonObject
            ((obj?.get("detail") ?: obj?.get("error")) as? JsonPrimitive)?.takeIf { it.isString }?.content
        }.getOrNull()

        // 始终保留 HTTP 状态码与中文操作引导，服务端 detail 仅作为补充附在末尾，
        // 避免服务端返回英文简短提示（如 "Invalid sync token"）时用户无法判断错误类别。
        val suffix = detail?.let { "（服务端：$it）" }.orEmpty()
        val msg = when (code) {
            401, 403 -> "同步令牌鉴权失败 (HTTP $code)，请核对 Sync Token 是否与服务端一致$suffix"
            426 -> "同步协议版本不匹配 (HTTP 426)，请升级客户端与服务端至 Sync V2$suffix"
            404 -> "服务端未暴露 Sync V2 接口 (HTTP 404)，请确认服务端已更新至统一版 2.0+"
            else -> detail?.let { "$fallbackLabel (HTTP $code): $it" } ?: "$fallbackLabel: HTTP $code"
        }
        error(msg)
    }

    /**
     * 测试与 Sync V2 服务端的连通性及 Bearer Token 合法性。
     */
    suspend fun testConnection(config: SyncServerConfig): Result<SyncV2InfoResponseDto> = withContext(Dispatchers.IO) {
        runCatching {
            val (baseUrl, token) = validateConfigOrThrow(config)
            fetchServerInfo(baseUrl, token)
        }
    }

    private fun fetchServerInfo(baseUrl: String, token: String): SyncV2InfoResponseDto {
        val req = Request.Builder()
            .url(buildUrl(baseUrl, "api/sync/v2/info"))
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        return okHttpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                parseHttpError(resp.code, bodyStr, "连接同步服务器失败")
            }
            val info = json.decodeFromString<SyncV2InfoResponseDto>(bodyStr)
            if (info.protocol_version < 2) {
                error("远端协议版本过低 (v${info.protocol_version})，需要 Sync V2")
            }
            info
        }
    }

    /**
     * 执行完整的 Sync V2 增量同步闭环。
     */
    suspend fun sync(
        config: SyncServerConfig,
        onProgress: (String) -> Unit = {},
    ): Result<SyncSummary> = withContext(Dispatchers.IO) {
        runCatching {
            syncMutex.withLock {
                val (baseUrl, token) = validateConfigOrThrow(config)
                val clientDeviceId = getOrCreateClientDeviceId(config)
                val clientDeviceName = resolveDeviceName(config)

                onProgress("正在验证 Sync V2 服务器与修订基线...")
                val serverInfo = fetchServerInfo(baseUrl, token)
                require("memory_file" in serverInfo.supported_entity_types) {
                    "服务端尚未支持记忆与完整偏好同步，请先升级服务端（客户端至少需要记忆同步能力）"
                }

                // 0. 检查 server_id 是否变化（如切换到新服务器或服务端重建了数据库）
                val storedServerId = syncV2Dao.getMetaValue(META_SERVER_ID)
                var cursor = syncV2Dao.getMetaValue(META_CURSOR)?.toLongOrNull() ?: 0L
                var isReconciled = syncV2Dao.getMetaValue(META_V2_RECONCILED) == "1"

                if (!storedServerId.isNullOrBlank() && storedServerId != serverInfo.server_id) {
                    logWarn("Server ID changed ($storedServerId -> ${serverInfo.server_id}), resetting local sync baseline")
                    runInTransaction {
                        syncV2Dao.clearAllEntityStates()
                        syncV2Dao.upsertMeta(SyncMetaEntity(META_SERVER_ID, serverInfo.server_id))
                        syncV2Dao.upsertMeta(SyncMetaEntity(META_CURSOR, "0"))
                        syncV2Dao.upsertMeta(SyncMetaEntity(META_V2_RECONCILED, "0"))
                    }
                    cursor = 0L
                    isReconciled = false
                } else if (storedServerId.isNullOrBlank()) {
                    syncV2Dao.upsertMeta(SyncMetaEntity(META_SERVER_ID, serverInfo.server_id))
                }

                // 197 可能已忽略新实体但推进游标；升级后重新读取一次云端完整基线，不清空本地队列或内容。
                if (syncV2Dao.getMetaValue(META_COVERAGE) != SyncSettingsContract.COVERAGE_VERSION) {
                    cursor = 0L
                    isReconciled = false
                }

                // Step 0a: 先尝试冲刷此前因流式生成而暂存的 sync_inbox_pending
                drainReadyInboxPending()

                var pulledConvCount = 0
                var syncedAssistantsCount = 0
                var syncedProvidersCount = 0
                var conflictsCount = 0
                val referencedRemoteFiles = LinkedHashSet<String>()

                if (!isReconciled || cursor == 0L) {
                    // ---- 首次 V2 基线对账模式（兼容 V1 老用户无损平滑升级与新设备首次全量拉取） ----
                    onProgress("正在执行 Sync V2 首次基线对账...")
                    val reconcileStats = pullAndReconcileInitialBaseline(
                        baseUrl = baseUrl,
                        token = token,
                        deviceId = clientDeviceId,
                        deviceName = clientDeviceName,
                        serverId = serverInfo.server_id,
                        targetRevision = serverInfo.current_revision,
                        onProgress = onProgress,
                    )
                    pulledConvCount += reconcileStats.pulledConversations
                    syncedAssistantsCount += reconcileStats.syncedAssistants
                    syncedProvidersCount += reconcileStats.syncedProviders
                    conflictsCount += reconcileStats.conflicts
                    referencedRemoteFiles.addAll(reconcileStats.referencedFiles)
                    cursor = reconcileStats.newCursor

                    // 对账完成后，扫描本地独有的实体（如服务端尚不存在的新会话、生图节点与生图记录）入队待推
                    onProgress("正在扫描本地独有对话与创作记录...")
                    enqueueLocalDiffIntoOutbox(includeRoomEntities = true)
                } else {
                    // ---- 常规 Sync V2 增量同步模式 ----
                    // Step 0b: 在拉取前先把 Settings 修改/删除及任何未入队的本地修改固化进 sync_outbox，保留各自的旧 baseRevision
                    onProgress("正在检查本地未同步变更...")
                    enqueueLocalDiffIntoOutbox(includeRoomEntities = true)

                    // Step 1: 锁定 upper_bound_revision 分页拉取云端增量
                    if (serverInfo.current_revision > cursor) {
                        onProgress("正在分页拉取云端增量修订...")
                        val pullStats = pullIncrementalChanges(
                            baseUrl = baseUrl,
                            token = token,
                            deviceId = clientDeviceId,
                            deviceName = clientDeviceName,
                            serverId = serverInfo.server_id,
                            startCursor = cursor,
                            upperBoundRevision = serverInfo.current_revision,
                            onProgress = onProgress,
                        )
                        if (pullStats.resetRequired) {
                            // 服务端修订日志已被裁剪，自动回退做一次无损全量对账
                            logWarn("Server requested cursor reset, running baseline reconciliation")
                            val rec = pullAndReconcileInitialBaseline(
                                baseUrl = baseUrl,
                                token = token,
                                deviceId = clientDeviceId,
                                deviceName = clientDeviceName,
                                serverId = serverInfo.server_id,
                                targetRevision = serverInfo.current_revision,
                                onProgress = onProgress,
                            )
                            pulledConvCount += rec.pulledConversations
                            syncedAssistantsCount += rec.syncedAssistants
                            syncedProvidersCount += rec.syncedProviders
                            conflictsCount += rec.conflicts
                            referencedRemoteFiles.addAll(rec.referencedFiles)
                            cursor = rec.newCursor
                            enqueueLocalDiffIntoOutbox(includeRoomEntities = true)
                        } else {
                            pulledConvCount += pullStats.pulledConversations
                            syncedAssistantsCount += pullStats.syncedAssistants
                            syncedProvidersCount += pullStats.syncedProviders
                            referencedRemoteFiles.addAll(pullStats.referencedFiles)
                            cursor = pullStats.newCursor
                        }
                    }
                }

                // Step 2: 按外键拓扑顺序分批推送本地 sync_outbox
                val outboxEntries = syncV2Dao.getAllOutboxOrdered()
                    .sortedWith(compareBy({ entityPushPriority[it.entityType] ?: 99 }, { it.id }))

                var pushedConvCount = 0
                var latestKnownRevision = cursor

                if (outboxEntries.isNotEmpty()) {
                    val batches = outboxEntries.chunked(PUSH_BATCH_LIMIT)
                    batches.forEachIndexed { batchIdx, batch ->
                        onProgress("正在推送本地变更 (${batchIdx + 1}/${batches.size})...")
                        val pushOutcome = pushOutboxBatch(
                            baseUrl = baseUrl,
                            token = token,
                            deviceId = clientDeviceId,
                            deviceName = clientDeviceName,
                            serverId = serverInfo.server_id,
                            batch = batch,
                        )
                        pushedConvCount += pushOutcome.pushedConversations
                        syncedAssistantsCount += pushOutcome.pushedAssistants
                        syncedProvidersCount += pushOutcome.pushedProviders
                        conflictsCount += pushOutcome.conflicts
                        referencedRemoteFiles.addAll(pushOutcome.conflictServerFiles)
                        if (pushOutcome.currentRevision > latestKnownRevision) {
                            latestKnownRevision = pushOutcome.currentRevision
                        }
                    }

                    // Push 后如果 cursor 和最新 revision 之间没有穿插其他设备的变更，顺带对齐一次增量游标
                    val finalPull = pullIncrementalChanges(
                        baseUrl = baseUrl,
                        token = token,
                        deviceId = clientDeviceId,
                        deviceName = clientDeviceName,
                        serverId = serverInfo.server_id,
                        startCursor = cursor,
                        upperBoundRevision = latestKnownRevision,
                        onProgress = {},
                    )
                    if (!finalPull.resetRequired) {
                        pulledConvCount += finalPull.pulledConversations
                        referencedRemoteFiles.addAll(finalPull.referencedFiles)
                        cursor = finalPull.newCursor
                    }
                }

                // Step 3: 附件校验、上传与原子下载（含断点重试队列 sync_file_queue）
                val syncedFilesCount = syncAttachments(
                    baseUrl = baseUrl,
                    token = token,
                    syncFilesEnabled = config.syncFiles,
                    newlyReferencedRelPaths = referencedRemoteFiles,
                    scanAllLocalMissing = !isReconciled,
                    onProgress = onProgress,
                )

                syncV2Dao.upsertMeta(SyncMetaEntity(META_COVERAGE, SyncSettingsContract.COVERAGE_VERSION))
                // Step 4: 更新最后同步时间并清理旧版遗留墓碑表
                val nowTime = System.currentTimeMillis()
                settingsStore.update { current ->
                    current.copy(
                        syncServerConfig = current.syncServerConfig.copy(lastSyncTime = nowTime)
                    )
                }
                conversationBridge.purgeLegacyTombstones(nowTime - 30L * 24 * 3600 * 1000)

                onProgress(
                    if (conflictsCount > 0) {
                        "同步完成（检测到 $conflictsCount 项并发冲突，已完整保护至冲突中心）"
                    } else {
                        "Sync V2 增量同步完成！"
                    }
                )

                SyncSummary(
                    pulledConversations = pulledConvCount,
                    pushedConversations = pushedConvCount,
                    syncedAssistants = syncedAssistantsCount,
                    syncedProviders = syncedProvidersCount,
                    syncedFiles = syncedFilesCount,
                    conflictsDetected = conflictsCount,
                    currentRevision = cursor,
                    serverTimestamp = nowTime,
                )
            }
        }
    }

    // ==================== 冲突中心恢复操作 ====================

    /**
     * 将会话冲突中的本地未覆盖副本另存为一条全新的独立会话，确保两端消息均不丢失。
     */
    suspend fun resolveConflictForkConversation(
        conflictId: String,
        config: SyncServerConfig,
    ): Result<Conversation> = withContext(Dispatchers.IO) {
        runCatching {
            val conflict = syncV2Dao.getConflictById(conflictId)
                ?: error("未找到对应的冲突记录")
            require(conflict.entityType == "conversation" && conflict.localPayload.isNotBlank()) {
                "仅非空的会话冲突支持另存为新会话"
            }
            val parsed = json.decodeFromString<Conversation>(conflict.localPayload)
            val now = Instant.now()
            val forked = parsed.toLocalizedAttachmentPaths(localFilesDir).copy(
                id = Uuid.random(),
                title = "${parsed.title.ifBlank { "未命名对话" }} (冲突恢复副本)",
                isPinned = false,
                createAt = now,
                updateAt = now,
            )
            // 不带 RemoteSyncApplyContext 写入，使其自动进入 sync_outbox 同步至云端
            conversationBridge.insertForkedConversation(forked)
            syncV2Dao.markConflictResolved(conflictId)
            ackRemoteConflictQuietly(conflictId, config)
            forked
        }
    }

    /**
     * 采用本地冲突副本覆盖当前版本（重新排队推送至云端）。
     */
    suspend fun resolveConflictApplyLocal(
        conflictId: String,
        config: SyncServerConfig,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val conflict = syncV2Dao.getConflictById(conflictId)
                ?: error("未找到对应的冲突记录")

            val currentState = syncV2Dao.getEntityState(conflict.entityType, conflict.entityId)
            val baseRev = maxOf(currentState?.lastSyncedRevision ?: 0L, conflict.serverRevision)

            if (conflict.operation == "delete") {
                // 先在本地应用删除，再以最新 serverRevision 作为 baseRevision 入队推送
                withContext(RemoteSyncApplyContext) {
                    applyRemoteDeleteLocally(conflict.entityType, conflict.entityId)
                }
                val delHash = CanonicalSyncHash.computeSemanticHash(conflict.entityType, "")
                syncV2Dao.insertOrReplaceOutbox(
                    SyncOutboxEntity(
                        mutationId = Uuid.random().toString(),
                        entityType = conflict.entityType,
                        entityId = conflict.entityId,
                        operation = "delete",
                        baseRevision = baseRev,
                        payload = "",
                        contentHash = delHash,
                        createdAt = System.currentTimeMillis(),
                    )
                )
            } else {
                require(conflict.localPayload.isNotBlank()) { "本地冲突副本为空，无法覆盖应用" }
                withContext(RemoteSyncApplyContext) {
                    applyRemoteUpsertLocally(conflict.entityType, conflict.entityId, conflict.localPayload)
                }
                val norm = CanonicalSyncHash.normalizeEntityPayload(conflict.entityType, conflict.localPayload, localFilesDir)
                val hash = CanonicalSyncHash.computeSemanticHash(conflict.entityType, norm, localFilesDir)
                syncV2Dao.insertOrReplaceOutbox(
                    SyncOutboxEntity(
                        mutationId = Uuid.random().toString(),
                        entityType = conflict.entityType,
                        entityId = conflict.entityId,
                        operation = "upsert",
                        baseRevision = baseRev,
                        payload = norm,
                        contentHash = hash,
                        createdAt = System.currentTimeMillis(),
                    )
                )
            }

            syncV2Dao.markConflictResolved(conflictId)
            ackRemoteConflictQuietly(conflictId, config)
        }
    }

    /**
     * 保留云端版本并将冲突标记为已处理。
     */
    suspend fun resolveConflictDismiss(
        conflictId: String,
        config: SyncServerConfig,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            syncV2Dao.markConflictResolved(conflictId)
            ackRemoteConflictQuietly(conflictId, config)
        }
    }

    private fun ackRemoteConflictQuietly(conflictId: String, config: SyncServerConfig) {
        val baseUrl = config.serverUrl.trim().trimEnd('/')
        val token = config.authToken.trim()
        if (baseUrl.isBlank() || token.isBlank()) return
        runCatching {
            val req = Request.Builder()
                .url(buildUrl(baseUrl, "api/sync/v2/conflicts/$conflictId/ack"))
                .addHeader("Authorization", "Bearer $token")
                .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                .build()
            okHttpClient.newCall(req).execute().close()
        }
    }

    // ==================== 内部阶段实现 ====================

    private data class PullOutcome(
        val pulledConversations: Int,
        val syncedAssistants: Int,
        val syncedProviders: Int,
        val conflicts: Int = 0,
        val newCursor: Long,
        val resetRequired: Boolean = false,
        val referencedFiles: Set<String> = emptySet(),
    )

    private data class PushOutcome(
        val pushedConversations: Int,
        val pushedAssistants: Int,
        val pushedProviders: Int,
        val conflicts: Int,
        val currentRevision: Long,
        val conflictServerFiles: Set<String>,
    )

    /**
     * 冲刷 `sync_inbox_pending` 中此前因为流式生成而延后的会话更新。
     */
    private suspend fun drainReadyInboxPending() {
        val pendingList = syncV2Dao.getAllInboxPending()
        if (pendingList.isEmpty()) return
        val now = System.currentTimeMillis()
        for (pending in pendingList) {
            if (pending.entityType == "conversation") {
                val convId = runCatching { Uuid.parse(pending.entityId) }.getOrNull()
                if (convId != null && conversationBridge.isConversationBusyForSync(convId)) {
                    continue
                }
            }
            val existingOutbox = syncV2Dao.getOutboxEntry(pending.entityType, pending.entityId)
            if (existingOutbox != null) continue
            applyOrStageRemoteChange(
                SyncV2ChangeItemDto(
                    revision = pending.revision,
                    entity_type = pending.entityType,
                    entity_id = pending.entityId,
                    operation = pending.operation,
                    payload = pending.payload,
                    content_hash = pending.contentHash,
                    updated_at = pending.receivedAt,
                ),
                pending.contentHash,
                now,
            )
        }
    }

    /**
     * 首次对齐或重置对齐：从 cursor=0 拉取全部远端快照，利用语义哈希无损匹配已一致数据，
     * 对两边内容确有差异的记录在本地 `sync_conflicts` 保存本地副本后再应用云端权威版本。
     */
    private suspend fun pullAndReconcileInitialBaseline(
        baseUrl: String,
        token: String,
        deviceId: String,
        deviceName: String,
        serverId: String,
        targetRevision: Long,
        onProgress: (String) -> Unit,
    ): PullOutcome {
        val allChanges = mutableListOf<SyncV2ChangeItemDto>()
        var cursor = 0L
        var upperBound = targetRevision
        var page = 0

        if (targetRevision > 0L) {
            while (true) {
                page++
                onProgress("正在拉取云端基线数据 (第 $page 页)...")
                val reqBody = SyncV2PullRequestDto(
                    device_id = deviceId,
                    device_name = deviceName,
                    server_id = serverId,
                    cursor = cursor,
                    upper_bound_revision = upperBound,
                    limit = pullPageLimit,
                )
                val httpReq = Request.Builder()
                    .url(buildUrl(baseUrl, "api/sync/v2/pull"))
                    .addHeader("Authorization", "Bearer $token")
                    .post(json.encodeToString(reqBody).toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val respDto: SyncV2PullResponseDto = okHttpClient.newCall(httpReq).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) parseHttpError(resp.code, raw, "拉取基线失败")
                    json.decodeFromString(raw)
                }
                upperBound = respDto.upper_bound_revision
                allChanges.addAll(respDto.changes)
                cursor = respDto.next_cursor
                if (!respDto.has_more) break
            }
        }

        // 读取本地当前快照映射 (entityType, entityId) -> portableJson
        val localSnapshotMap = collectLocalEntitySnapshotMap()
        val now = System.currentTimeMillis()
        var pulledConvs = 0
        var syncedAssts = 0
        var syncedProvs = 0
        var conflicts = 0
        val refFiles = LinkedHashSet<String>()

        // 按外键依赖顺序排列，防止 media_node / media_record 早于 media_session 落库触发外键失败
        val orderedChanges = allChanges.sortedWith(
            compareBy<SyncV2ChangeItemDto> {
                if (it.operation == "delete") {
                    // 删除时子表优先于父表
                    100 - (entityPushPriority[it.entity_type] ?: 50)
                } else {
                    entityPushPriority[it.entity_type] ?: 50
                }
            }.thenBy { it.revision }
        )

        val appliedSettings = applySettingsBatch(orderedChanges, initial = true)

        for (change in orderedChanges) {
            val key = change.entity_type to change.entity_id
            val remoteHash = change.content_hash.ifBlank {
                CanonicalSyncHash.computeSemanticHash(change.entity_type, change.payload, localFilesDir)
            }
            if (change.operation == "upsert" && change.payload.isNotBlank()) {
                refFiles.addAll(CanonicalSyncHash.extractReferencedFilesFromEntity(change.entity_type, change.payload, localFilesDir))
            }

            val localJson = localSnapshotMap[key]
            val existingOutbox = syncV2Dao.getOutboxEntry(change.entity_type, change.entity_id)

            if (change.operation == "upsert") {
                if (localJson != null) {
                    val localHash = CanonicalSyncHash.computeSemanticHash(change.entity_type, localJson, localFilesDir)
                    if (localHash == remoteHash) {
                        // 两端内容语义完全一致，无需重写本地数据库，直接建立基线！
                        syncV2Dao.deleteOutboxByEntity(change.entity_type, change.entity_id)
                        syncV2Dao.upsertEntityState(
                            SyncEntityStateEntity(
                                entityType = change.entity_type,
                                entityId = change.entity_id,
                                lastSyncedRevision = change.revision,
                                lastSyncedHash = remoteHash,
                                deleted = false,
                                updatedAt = now,
                            )
                        )
                        continue
                    }
                    // 对会话、收藏、生图记录等重要用户创作，若本地与云端升级前已有差异，先保存本地副本至冲突中心防丢
                    if (change.entity_type in setOf("conversation", "memory_file", "media_session", "media_record") || existingOutbox != null) {
                        saveLocalConflictRecord(
                            conflictId = "reconcile-${change.entity_type}-${change.entity_id}-${change.revision}",
                            entityType = change.entity_type,
                            entityId = change.entity_id,
                            operation = "upsert",
                            baseRevision = 0L,
                            serverRevision = change.revision,
                            localPayload = localJson,
                            serverPayload = change.payload,
                        )
                        conflicts++
                    }
                }

                val appliedNow = applyOrStageRemoteChange(change, remoteHash, now, settingsAlreadyApplied = (change.entity_type to change.entity_id) in appliedSettings)
                if (appliedNow) {
                    when (change.entity_type) {
                        "conversation" -> pulledConvs++
                        "assistant" -> syncedAssts++
                        "provider" -> syncedProvs++
                    }
                }
                existingOutbox?.let { syncV2Dao.deleteOutboxByMutationId(it.mutationId) }
            } else {
                // remote delete
                if (localJson != null && existingOutbox?.operation == "upsert") {
                    saveLocalConflictRecord(
                        conflictId = "reconcile-del-${change.entity_type}-${change.entity_id}-${change.revision}",
                        entityType = change.entity_type,
                        entityId = change.entity_id,
                        operation = "upsert",
                        baseRevision = 0L,
                        serverRevision = change.revision,
                        localPayload = localJson,
                        serverPayload = "",
                    )
                    conflicts++
                }
                applyOrStageRemoteChange(change, remoteHash, now, settingsAlreadyApplied = (change.entity_type to change.entity_id) in appliedSettings)
                syncV2Dao.deleteOutboxByEntity(change.entity_type, change.entity_id)
            }
        }

        runInTransaction {
            syncV2Dao.upsertMeta(SyncMetaEntity(META_SERVER_ID, serverId))
            syncV2Dao.upsertMeta(SyncMetaEntity(META_CURSOR, cursor.toString()))
            syncV2Dao.upsertMeta(SyncMetaEntity(META_V2_RECONCILED, "1"))
        }

        return PullOutcome(
            pulledConversations = pulledConvs,
            syncedAssistants = syncedAssts,
            syncedProviders = syncedProvs,
            conflicts = conflicts,
            newCursor = cursor,
            referencedFiles = refFiles,
        )
    }

    /**
     * 常规增量分页拉取（锁定 `upperBoundRevision` 快照上界）。
     * 若某实体在本地 `sync_outbox` 中有未推送修改，Pull 阶段保留本地修改及其原始 `baseRevision`，交由后续 Push 阶段统一判定并发冲突。
     */
    private suspend fun pullIncrementalChanges(
        baseUrl: String,
        token: String,
        deviceId: String,
        deviceName: String,
        serverId: String,
        startCursor: Long,
        upperBoundRevision: Long,
        onProgress: (String) -> Unit,
    ): PullOutcome {
        if (upperBoundRevision <= startCursor) {
            return PullOutcome(0, 0, 0, newCursor = startCursor)
        }

        val collectedChanges = mutableListOf<SyncV2ChangeItemDto>()
        var cursor = startCursor
        var lockedUpper = upperBoundRevision
        var page = 0

        while (true) {
            page++
            onProgress("正在拉取云端变更 (第 $page 页)...")
            val reqBody = SyncV2PullRequestDto(
                device_id = deviceId,
                device_name = deviceName,
                server_id = serverId,
                cursor = cursor,
                upper_bound_revision = lockedUpper,
                limit = pullPageLimit,
            )
            val httpReq = Request.Builder()
                .url(buildUrl(baseUrl, "api/sync/v2/pull"))
                .addHeader("Authorization", "Bearer $token")
                .post(json.encodeToString(reqBody).toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val respDto: SyncV2PullResponseDto = okHttpClient.newCall(httpReq).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) parseHttpError(resp.code, raw, "增量拉取失败")
                json.decodeFromString(raw)
            }
            if (respDto.reset_required) {
                return PullOutcome(0, 0, 0, newCursor = startCursor, resetRequired = true)
            }
            lockedUpper = respDto.upper_bound_revision
            collectedChanges.addAll(respDto.changes)
            cursor = respDto.next_cursor
            if (!respDto.has_more) break
        }

        val now = System.currentTimeMillis()
        var pulledConvs = 0
        var syncedAssts = 0
        var syncedProvs = 0
        val refFiles = LinkedHashSet<String>()

        val orderedChanges = collectedChanges.sortedWith(
            compareBy<SyncV2ChangeItemDto> {
                if (it.operation == "delete") {
                    100 - (entityPushPriority[it.entity_type] ?: 50)
                } else {
                    entityPushPriority[it.entity_type] ?: 50
                }
            }.thenBy { it.revision }
        )

        val appliedSettings = applySettingsBatch(orderedChanges, initial = false)

        for (change in orderedChanges) {
            val remoteHash = change.content_hash.ifBlank {
                CanonicalSyncHash.computeSemanticHash(change.entity_type, change.payload, localFilesDir)
            }
            if (change.operation == "upsert" && change.payload.isNotBlank()) {
                refFiles.addAll(CanonicalSyncHash.extractReferencedFilesFromEntity(change.entity_type, change.payload, localFilesDir))
            }

            // 关键保护：如果本地已有未推送修改，绝不在此处静默覆盖，留给 Step 2 Push 走服务端乐观并发校验与冲突保护！
            val pendingOutbox = syncV2Dao.getOutboxEntry(change.entity_type, change.entity_id)
            if (pendingOutbox != null) {
                continue
            }

            val appliedNow = applyOrStageRemoteChange(change, remoteHash, now, settingsAlreadyApplied = (change.entity_type to change.entity_id) in appliedSettings)
            if (appliedNow && change.operation == "upsert") {
                when (change.entity_type) {
                    "conversation" -> pulledConvs++
                    "assistant" -> syncedAssts++
                    "provider" -> syncedProvs++
                }
            }
        }

        syncV2Dao.upsertMeta(SyncMetaEntity(META_CURSOR, cursor.toString()))
        return PullOutcome(
            pulledConversations = pulledConvs,
            syncedAssistants = syncedAssts,
            syncedProviders = syncedProvs,
            newCursor = cursor,
            referencedFiles = refFiles,
        )
    }

    /**
     * 应用单条云端变更；若目标会话正在流式生成，则安全暂存至 `sync_inbox_pending`。
     */
    private suspend fun applyOrStageRemoteChange(
        change: SyncV2ChangeItemDto,
        remoteHash: String,
        now: Long,
        settingsAlreadyApplied: Boolean = false,
        forceMemory: Boolean = false,
    ): Boolean {
        if (change.entity_type == "conversation") {
            val convId = runCatching { Uuid.parse(change.entity_id) }.getOrNull()
            if (convId != null && conversationBridge.isConversationBusyForSync(convId)) {
                syncV2Dao.upsertInboxPending(
                    SyncInboxPendingEntity(
                        entityType = change.entity_type,
                        entityId = change.entity_id,
                        revision = change.revision,
                        operation = change.operation,
                        payload = change.payload,
                        contentHash = remoteHash,
                        receivedAt = now,
                    )
                )
                return false
            }
        }

        val currentState = syncV2Dao.getEntityState(change.entity_type, change.entity_id)
        val alreadyIdentical = currentState != null &&
            currentState.deleted == (change.operation == "delete") &&
            currentState.lastSyncedHash == remoteHash

        if (!alreadyIdentical || forceMemory) {
            if (change.entity_type == "memory_file") {
                val applied = if (change.operation == "delete") {
                    memoryRepository.deleteFromSync(change.entity_id, forceMemory)
                } else {
                    val payload = json.decodeFromString<MemorySyncPayload>(change.payload).validate()
                    require(payload.entityId == change.entity_id) { "记忆同步实体 ID 不匹配" }
                    memoryRepository.upsertFromSync(payload.toEntity(), forceMemory)
                }
                if (!applied) {
                    syncV2Dao.upsertInboxPending(SyncInboxPendingEntity(
                        entityType = change.entity_type,
                        entityId = change.entity_id,
                        revision = change.revision,
                        operation = change.operation,
                        payload = change.payload,
                        contentHash = remoteHash,
                        receivedAt = now,
                    ))
                    return false
                }
            } else if (!settingsAlreadyApplied) {
                withContext(RemoteSyncApplyContext) {
                    if (change.operation == "delete") {
                        applyRemoteDeleteLocally(change.entity_type, change.entity_id)
                    } else {
                        applyRemoteUpsertLocally(change.entity_type, change.entity_id, change.payload)
                    }
                }
            }
        }

        syncV2Dao.upsertEntityState(
            SyncEntityStateEntity(
                entityType = change.entity_type,
                entityId = change.entity_id,
                lastSyncedRevision = change.revision,
                lastSyncedHash = remoteHash,
                deleted = change.operation == "delete",
                updatedAt = now,
            )
        )
        syncV2Dao.deleteInboxPending(change.entity_type, change.entity_id)
        return !alreadyIdentical
    }

    /**
     * 分批向 `/api/sync/v2/push` 提交本地 `sync_outbox` 队列，处理 `applied` / `noop` / `busy` / `conflict`。
     */
    private suspend fun pushOutboxBatch(
        baseUrl: String,
        token: String,
        deviceId: String,
        deviceName: String,
        serverId: String,
        batch: List<SyncOutboxEntity>,
    ): PushOutcome {
        val reqDto = SyncV2PushRequestDto(
            device_id = deviceId,
            device_name = deviceName,
            server_id = serverId,
            mutations = batch.map { item ->
                SyncV2MutationItemDto(
                    mutation_id = item.mutationId,
                    entity_type = item.entityType,
                    entity_id = item.entityId,
                    operation = item.operation,
                    base_revision = item.baseRevision,
                    payload = item.payload,
                    content_hash = item.contentHash,
                )
            },
        )
        val httpReq = Request.Builder()
            .url(buildUrl(baseUrl, "api/sync/v2/push"))
            .addHeader("Authorization", "Bearer $token")
            .post(json.encodeToString(reqDto).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val respDto: SyncV2PushResponseDto = okHttpClient.newCall(httpReq).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) parseHttpError(resp.code, raw, "推送变更失败")
            json.decodeFromString(raw)
        }

        val byMutationId = batch.associateBy { it.mutationId }
        val now = System.currentTimeMillis()
        var pushedConvs = 0
        var pushedAssts = 0
        var pushedProvs = 0
        var conflicts = 0
        val conflictFiles = LinkedHashSet<String>()

        for (res in respDto.results) {
            val outbox = byMutationId[res.mutation_id] ?: continue
            when (res.status) {
                "applied", "noop" -> {
                    runInTransaction {
                        syncV2Dao.upsertEntityState(
                            SyncEntityStateEntity(
                                entityType = outbox.entityType,
                                entityId = outbox.entityId,
                                lastSyncedRevision = res.revision,
                                lastSyncedHash = outbox.contentHash,
                                deleted = outbox.operation == "delete",
                                updatedAt = now,
                            )
                        )
                        val latest = syncV2Dao.getOutboxEntry(outbox.entityType, outbox.entityId)
                        if (latest?.mutationId == outbox.mutationId) {
                            syncV2Dao.deleteOutboxByMutationId(outbox.mutationId)
                        } else if (latest != null && latest.baseRevision == outbox.baseRevision) {
                            // 请求发出后又编辑了记忆：只确认已发送版本，新内容保留并接续刚确认的基线。
                            syncV2Dao.insertOrReplaceOutbox(latest.copy(baseRevision = res.revision))
                        }
                    }
                    if (res.status == "applied" && outbox.operation == "upsert") {
                        when (outbox.entityType) {
                            "conversation" -> pushedConvs++
                            "assistant" -> pushedAssts++
                            "provider" -> pushedProvs++
                        }
                    }
                }
                "busy" -> {
                    // Web 端当前正在流式生成此会话，保留在本地 outbox 待下次空闲自动重推
                    syncV2Dao.markOutboxRetry(outbox.id, res.message ?: "远端会话正在生成回复，稍后重试")
                }
                "conflict" -> {
                    conflicts++
                    val cid = res.conflict_id ?: Uuid.random().toString()
                    val serverOp = res.server_operation ?: "upsert"
                    val serverPayload = res.server_payload.orEmpty()
                    val serverHash = res.server_hash?.takeIf { it.isNotBlank() }
                        ?: CanonicalSyncHash.computeSemanticHash(outbox.entityType, serverPayload, localFilesDir)

                    saveLocalConflictRecord(
                        conflictId = cid,
                        entityType = outbox.entityType,
                        entityId = outbox.entityId,
                        operation = outbox.operation,
                        baseRevision = outbox.baseRevision,
                        serverRevision = res.revision,
                        localPayload = outbox.payload,
                        serverPayload = serverPayload,
                    )

                    if (serverOp != "delete" && serverPayload.isNotBlank()) {
                        conflictFiles.addAll(
                            CanonicalSyncHash.extractReferencedFilesFromEntity(outbox.entityType, serverPayload, localFilesDir)
                        )
                    }

                    if (syncV2Dao.getOutboxEntry(outbox.entityType, outbox.entityId)?.mutationId != outbox.mutationId) continue

                    // 将本地主记录对齐至服务端当前权威版本，本地差异副本已在双端 sync_conflicts 永久保护
                    applyOrStageRemoteChange(
                        change = SyncV2ChangeItemDto(
                            revision = res.revision,
                            entity_type = outbox.entityType,
                            entity_id = outbox.entityId,
                            operation = if (serverOp == "delete") "delete" else "upsert",
                            payload = serverPayload,
                            content_hash = serverHash,
                            updated_at = now,
                        ),
                        remoteHash = serverHash,
                        now = now,
                        forceMemory = true,
                    )
                    syncV2Dao.deleteOutboxByMutationId(outbox.mutationId)
                }
            }
        }

        return PushOutcome(
            pushedConversations = pushedConvs,
            pushedAssistants = pushedAssts,
            pushedProviders = pushedProvs,
            conflicts = conflicts,
            currentRevision = respDto.current_revision,
            conflictServerFiles = conflictFiles,
        )
    }

    private suspend fun saveLocalConflictRecord(
        conflictId: String,
        entityType: String,
        entityId: String,
        operation: String,
        baseRevision: Long,
        serverRevision: Long,
        localPayload: String,
        serverPayload: String,
    ) {
        val title = extractDisplayTitle(entityType, localPayload.ifBlank { serverPayload }, entityId)
        syncV2Dao.upsertConflict(
            SyncConflictEntity(
                id = conflictId,
                entityType = entityType,
                entityId = entityId,
                title = title,
                operation = operation,
                baseRevision = baseRevision,
                serverRevision = serverRevision,
                localPayload = localPayload,
                serverPayload = serverPayload,
                resolved = false,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    private fun extractDisplayTitle(entityType: String, payloadJson: String, fallbackId: String): String {
        if (payloadJson.isNotBlank()) {
            val obj = runCatching { json.parseToJsonElement(payloadJson) as? JsonObject }.getOrNull()
            val candidate = ((obj?.get("title") ?: obj?.get("name")) as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.trim()
            if (!candidate.isNullOrEmpty()) return candidate
        }
        if (entityType == "memory_file") {
            return runCatching { json.decodeFromString<MemorySyncPayload>(payloadJson).path }.getOrDefault(fallbackId)
        }
        return "$entityType:$fallbackId"
    }

    /**
     * 采集当前本地全部 9 类同步实体的可移植 JSON 快照映射 `(entityType, entityId) -> portableJson`。
     */
    private suspend fun collectLocalEntitySnapshotMap(): Map<Pair<String, String>, String> {
        val currentSettings = settingsStore.awaitLoaded()
        val result = LinkedHashMap<Pair<String, String>, String>()

        // 1. Assistants
        currentSettings.assistants.forEach { asst ->
            val portable = asst.toPortableAttachmentPaths(localFilesDir)
            result["assistant" to asst.id.toString()] = json.encodeToString(portable)
        }

        // 2. Providers
        currentSettings.providers.forEach { prov ->
            result["provider" to prov.id.toString()] = json.encodeToString(prov)
        }

        // 3. Extra Settings (11 keys)
        buildExtraSettingsMap(currentSettings).forEach { (k, payloadStr) ->
            result["extra_setting" to k] = payloadStr
        }

        // 4. Folders
        folderDAO.getAllFolders().forEach { f ->
            result["folder" to f.id] = json.encodeToString(FolderSyncV2Payload.fromEntity(f, f.createAt))
        }

        // 5. Conversations
        conversationBridge.getAllConversationIds().forEach { idStr ->
            val conv = runCatching { Uuid.parse(idStr) }.getOrNull()?.let { uuid ->
                conversationBridge.loadConversation(uuid)
            } ?: return@forEach
            // 尚未产生实际内容的空白草稿会话不参与云端同步
            if (conv.title.isBlank() && conv.messageNodes.isEmpty()) return@forEach
            val portable = conv.toPortableAttachmentPaths(localFilesDir)
            result["conversation" to idStr] = json.encodeToString(portable)
        }

        // 6. Favorites
        favoriteDAO.getAllFavorites().forEach { fav ->
            result["favorite" to fav.id] = json.encodeToString(FavoriteSyncV2Payload.fromEntity(fav))
        }

        // 7. Media Creation Sessions / Nodes / Records
        mediaCreationDAO.getAllSessions().forEach { s ->
            result["media_session" to s.id] = json.encodeToString(MediaSessionSyncV2Payload.fromEntity(s))
        }
        mediaCreationDAO.getAllNodes().forEach { n ->
            result["media_node" to n.id] = json.encodeToString(MediaNodeSyncV2Payload.fromEntity(n))
        }
        mediaCreationDAO.getAllRecords().forEach { r ->
            result["media_record" to r.id] = json.encodeToString(MediaRecordSyncV2Payload.fromEntity(r))
        }

        memoryRepository.getAllFiles().forEach { file ->
            val payload = MemorySyncPayload.fromEntity(file).validate()
            result["memory_file" to payload.entityId] = json.encodeToString(payload)
        }
        return result
    }

    /**
     * 对比本地当前快照与 `sync_entity_state` 基线，把所有未入队的新增、修改与删除补入 `sync_outbox`。
     * 尤其负责捕获 `Settings` 中助手、提供商及扩展配置的增删改。
     */
    private suspend fun enqueueLocalDiffIntoOutbox(includeRoomEntities: Boolean) {
        val currentSettings = settingsStore.awaitLoaded()
        val statesByKey = syncV2Dao.getAllEntityStates().associateBy { it.entityType to it.entityId }

        val snapshotMap = if (includeRoomEntities) {
            collectLocalEntitySnapshotMap()
        } else {
            val map = LinkedHashMap<Pair<String, String>, String>()
            currentSettings.assistants.forEach { asst ->
                map["assistant" to asst.id.toString()] = json.encodeToString(asst.toPortableAttachmentPaths(localFilesDir))
            }
            currentSettings.providers.forEach { prov ->
                map["provider" to prov.id.toString()] = json.encodeToString(prov)
            }
            buildExtraSettingsMap(currentSettings).forEach { (k, v) ->
                map["extra_setting" to k] = v
            }
            map
        }

        // 1. 新增或修改项
        for ((key, rawJson) in snapshotMap) {
            val (entityType, entityId) = key
            val norm = CanonicalSyncHash.normalizeEntityPayload(entityType, rawJson, localFilesDir)
            val hash = CanonicalSyncHash.computeSemanticHash(entityType, norm, localFilesDir)
            val state = statesByKey[key]
            if (state == null || state.deleted || state.lastSyncedHash != hash) {
                syncMutationRecorder.recordUpsert(entityType, entityId, norm)
            }
        }

        // 2. 已在基线中存在但本地当前已删除的项（例如删除了某个自定义助手或提供商）
        val checkedTypes = if (includeRoomEntities) {
            setOf("assistant", "provider", "folder", "conversation", "favorite", "memory_file", "media_session", "media_node", "media_record")
        } else {
            setOf("assistant", "provider")
        }
        for ((key, state) in statesByKey) {
            if (!state.deleted && key.first in checkedTypes && key !in snapshotMap) {
                syncMutationRecorder.recordDelete(key.first, key.second)
            }
        }
    }

    private fun buildExtraSettingsMap(settings: Settings): Map<String, String> =
        SyncSettingsContract.buildExtraSettings(settings, json, localFilesDir)

    private suspend fun applySettingsBatch(changes: List<SyncV2ChangeItemDto>, initial: Boolean): Set<Pair<String, String>> {
        val eligible = mutableListOf<SyncV2ChangeItemDto>()
        for (change in changes) {
            if (change.entity_type !in setOf("assistant", "provider", "extra_setting")) continue
            if (!initial && syncV2Dao.getOutboxEntry(change.entity_type, change.entity_id) != null) continue
            eligible.add(change)
        }
        if (eligible.isNotEmpty()) {
            val batch = eligible.map { SyncSettingsContract.Change(it.entity_type, it.entity_id, it.operation, it.payload) }
            withContext(RemoteSyncApplyContext) {
                settingsStore.update { current -> SyncSettingsContract.applyChanges(current, batch, json, localFilesDir) }
            }
        }
        return eligible.mapTo(HashSet()) { it.entity_type to it.entity_id }
    }

    // ==================== 本地实体原子应用（均运行于 RemoteSyncApplyContext 下） ====================

    private suspend fun applyRemoteUpsertLocally(
        entityType: String,
        entityId: String,
        payloadJson: String,
    ) {
        if (payloadJson.isBlank()) return
        when (entityType) {
            "conversation" -> {
                val remoteConv = runCatching { json.decodeFromString<Conversation>(payloadJson) }.getOrNull() ?: return
                val localized = remoteConv.toLocalizedAttachmentPaths(localFilesDir)
                conversationBridge.applyRemoteSyncedConversation(localized)
            }
            "folder" -> {
                val dto = runCatching { json.decodeFromString<FolderSyncV2Payload>(payloadJson) }.getOrNull() ?: return
                val existing = folderDAO.getFolderById(dto.id)
                if (existing == null) {
                    folderDAO.insert(dto.toEntity())
                } else {
                    folderDAO.update(
                        existing.copy(
                            assistantId = dto.assistantId.ifEmpty { existing.assistantId },
                            name = dto.name,
                            sortIndex = dto.sortIndex,
                        )
                    )
                }
            }
            "assistant", "provider", "extra_setting" -> {
                settingsStore.update { current ->
                    SyncSettingsContract.applyChanges(
                        current,
                        listOf(SyncSettingsContract.Change(entityType, entityId, "upsert", payloadJson)),
                        json,
                        localFilesDir,
                    )
                }
            }
            "memory_file" -> {
                val payload = json.decodeFromString<MemorySyncPayload>(payloadJson).validate()
                require(payload.entityId == entityId) { "记忆同步实体 ID 不匹配" }
                memoryRepository.upsertFromSync(payload.toEntity(), force = true)
            }
            "favorite" -> {
                val dto = runCatching { json.decodeFromString<FavoriteSyncV2Payload>(payloadJson) }.getOrNull() ?: return
                favoriteDAO.upsert(dto.toEntity())
            }
            "media_session" -> {
                val norm = CanonicalSyncHash.normalizeEntityPayload("media_session", payloadJson, localFilesDir)
                val dto = runCatching { json.decodeFromString<MediaSessionSyncV2Payload>(norm) }.getOrNull() ?: return
                // 使用 @Upsert，绝不触发 ON DELETE CASCADE 清空子表 node / record！
                mediaCreationDAO.insertSession(dto.toEntity())
            }
            "media_node" -> {
                val dto = runCatching { json.decodeFromString<MediaNodeSyncV2Payload>(payloadJson) }.getOrNull() ?: return
                // 外键安全检查：父 session 存在时才写入，防止孤儿节点外键异常
                if (mediaCreationDAO.getSession(dto.sessionId) != null) {
                    mediaCreationDAO.upsertNode(dto.toEntity())
                } else {
                    logWarn("Skipping orphan media_node ${dto.id} because session ${dto.sessionId} is absent")
                }
            }
            "media_record" -> {
                val norm = CanonicalSyncHash.normalizeEntityPayload("media_record", payloadJson, localFilesDir)
                val dto = runCatching { json.decodeFromString<MediaRecordSyncV2Payload>(norm) }.getOrNull() ?: return
                if (mediaCreationDAO.getSession(dto.sessionId) != null && mediaCreationDAO.getNode(dto.nodeId) != null) {
                    mediaCreationDAO.upsertRecord(dto.toEntity())
                } else {
                    logWarn("Skipping orphan media_record ${dto.id} because parent session/node is absent")
                }
            }
        }
    }

    private suspend fun applyRemoteDeleteLocally(entityType: String, entityId: String) {
        when (entityType) {
            "conversation" -> {
                val convId = runCatching { Uuid.parse(entityId) }.getOrNull() ?: return
                conversationBridge.applyRemoteDeletedConversation(convId)
            }
            "folder" -> {
                val folderId = runCatching { Uuid.parse(entityId) }.getOrNull() ?: return
                conversationBridge.deleteFolder(folderId)
            }
            "assistant", "provider", "extra_setting" -> {
                settingsStore.update { current ->
                    SyncSettingsContract.applyChanges(
                        current,
                        listOf(SyncSettingsContract.Change(entityType, entityId, "delete", "")),
                        json,
                        localFilesDir,
                    )
                }
            }
            "memory_file" -> {
                memoryRepository.deleteFromSync(entityId, force = true)
            }
            "favorite" -> {
                favoriteDAO.deleteById(entityId)
            }
            "media_record" -> {
                mediaCreationDAO.deleteRecord(entityId)
            }
            "media_node" -> {
                mediaCreationDAO.deleteNode(entityId)
            }
            "media_session" -> {
                mediaCreationDAO.deleteSession(entityId)
            }
        }
    }

    // ==================== 附件原子同步与断点续传 ====================

    private suspend fun syncAttachments(
        baseUrl: String,
        token: String,
        syncFilesEnabled: Boolean,
        newlyReferencedRelPaths: Set<String>,
        scanAllLocalMissing: Boolean,
        onProgress: (String) -> Unit,
    ): Int {
        val now = System.currentTimeMillis()
        val downloadCandidates = LinkedHashSet<String>()
        newlyReferencedRelPaths.filterTo(downloadCandidates) { AttachmentPathNormalizer.isManagedRelativePath(it) }

        if (scanAllLocalMissing) {
            collectAllReferencedManagedPathsFromLocalEntities().filterTo(downloadCandidates) {
                AttachmentPathNormalizer.isManagedRelativePath(it)
            }
        }

        // 把本地缺失的远端引用附件先登记进下载队列（即便当前关闭了 syncFiles，后续打开开关也能自动补拉！）
        val missingLocalRelPaths = downloadCandidates.filter { rel ->
            val f = File(localFilesDir, rel)
            !f.isFile || f.length() == 0L
        }
        if (missingLocalRelPaths.isNotEmpty()) {
            syncV2Dao.upsertFileQueueAll(
                missingLocalRelPaths.map { rel ->
                    SyncFileQueueEntity(
                        direction = "download",
                        relativePath = rel,
                        updatedAt = now,
                    )
                }
            )
        }

        if (!syncFilesEnabled) {
            return 0
        }

        var syncedCount = 0

        // 1. 扫描本地受管目录文件并校验服务端缺失/损坏列表
        onProgress("正在校验多媒体与附件完整性...")
        val managedDirs = listOf(
            FileFolders.UPLOAD,
            FileFolders.MEDIA_CREATION,
            FileFolders.SKILLS,
            FileFolders.FONTS,
            "avatar",
            "images",
        )
        val localFilesToCheck = mutableListOf<FileCheckItemDto>()
        for (dirName in managedDirs) {
            val dir = File(localFilesDir, dirName)
            if (!dir.isDirectory) continue
            dir.walkTopDown()
                .filter { it.isFile && !it.name.startsWith(".sync_") && !it.name.endsWith(".tmp") }
                .forEach { file ->
                    val rel = file.relativeTo(localFilesDir).invariantSeparatorsPath
                    if (AttachmentPathNormalizer.isManagedRelativePath(rel)) {
                        val sha = runCatching { CanonicalSyncHash.sha256File(file) }.getOrDefault("")
                        localFilesToCheck.add(
                            FileCheckItemDto(
                                path = rel,
                                hash = sha,
                                size = file.length(),
                            )
                        )
                    }
                }
        }

        val missingOnServer = LinkedHashSet<String>()
        val shaByRelPath = localFilesToCheck.associate { it.path to it.hash }

        for (chunk in localFilesToCheck.chunked(FILE_CHECK_BATCH_LIMIT)) {
            val checkHttpReq = Request.Builder()
                .url(buildUrl(baseUrl, "api/sync/files/check"))
                .addHeader("Authorization", "Bearer $token")
                .post(json.encodeToString(FilesCheckRequestDto(chunk)).toRequestBody(JSON_MEDIA_TYPE))
                .build()
            runCatching {
                okHttpClient.newCall(checkHttpReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val dto = json.decodeFromString<FilesCheckResponseDto>(resp.body?.string().orEmpty())
                        missingOnServer.addAll(dto.missing_paths)
                    }
                }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                logWarn("File check batch failed, will retry next sync", e)
            }
        }

        // 合并此前上传失败留在队列里的文件
        syncV2Dao.getFileQueueByDirection("upload").forEach { q ->
            if (File(localFilesDir, q.relativePath).isFile) {
                missingOnServer.add(q.relativePath)
            } else {
                syncV2Dao.deleteFileQueue("upload", q.relativePath)
            }
        }

        // 2. 逐个上传服务端缺失附件
        val uploadList = missingOnServer.filter { AttachmentPathNormalizer.isManagedRelativePath(it) }
        uploadList.forEachIndexed { idx, relPath ->
            val file = File(localFilesDir, relPath)
            if (!file.isFile) {
                syncV2Dao.deleteFileQueue("upload", relPath)
                return@forEachIndexed
            }
            onProgress("正在上传附件 (${idx + 1}/${uploadList.size})...")
            val sha = shaByRelPath[relPath]?.takeIf { it.isNotBlank() }
                ?: runCatching { CanonicalSyncHash.sha256File(file) }.getOrDefault("")

            val uploadRes = runCatching {
                val formBuilder = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("path", relPath)
                if (sha.isNotBlank()) {
                    formBuilder.addFormDataPart("sha256", sha)
                }
                formBuilder.addFormDataPart("file", file.name, file.asRequestBody())
                val req = Request.Builder()
                    .url(buildUrl(baseUrl, "api/sync/files/upload"))
                    .addHeader("Authorization", "Bearer $token")
                    .post(formBuilder.build())
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        error("HTTP ${resp.code}")
                    }
                }
            }

            uploadRes.onSuccess {
                syncedCount++
                syncV2Dao.deleteFileQueue("upload", relPath)
            }.onFailure { err ->
                if (err is CancellationException) throw err
                logWarn("Failed to upload attachment $relPath", err)
                val existing = syncV2Dao.getFileQueueByDirection("upload").find { it.relativePath == relPath }
                if (existing == null) {
                    syncV2Dao.upsertFileQueue(
                        SyncFileQueueEntity(
                            direction = "upload",
                            relativePath = relPath,
                            sha256 = sha,
                            size = file.length(),
                            retryCount = 1,
                            lastError = err.message,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                } else {
                    syncV2Dao.markFileQueueRetry("upload", relPath, err.message, System.currentTimeMillis())
                }
            }
        }

        // 3. 逐个原子下载本地缺失附件
        val downloadQueue = syncV2Dao.getFileQueueByDirection("download")
        downloadQueue.forEachIndexed { idx, item ->
            val relPath = item.relativePath
            if (!AttachmentPathNormalizer.isManagedRelativePath(relPath)) {
                syncV2Dao.deleteFileQueue("download", relPath)
                return@forEachIndexed
            }
            val destFile = File(localFilesDir, relPath)
            if (destFile.isFile && destFile.length() > 0L) {
                syncV2Dao.deleteFileQueue("download", relPath)
                return@forEachIndexed
            }

            onProgress("正在下载云端附件 (${idx + 1}/${downloadQueue.size})...")
            val dlRes = runCatching {
                downloadFileAtomically(baseUrl, token, relPath, destFile)
            }
            dlRes.onSuccess { downloaded ->
                if (downloaded) syncedCount++
                // downloaded == false 表示远端 404 不存在该物理文件，移除无效队列避免死循环重试
                syncV2Dao.deleteFileQueue("download", relPath)
            }.onFailure { err ->
                if (err is CancellationException) throw err
                logWarn("Failed to download attachment $relPath", err)
                syncV2Dao.markFileQueueRetry("download", relPath, err.message, System.currentTimeMillis())
            }
        }

        return syncedCount
    }

    /**
     * 先下载至同目录隐藏 `.tmp` 文件并刷盘 `fd.sync()`，再原子重命名为正式目标文件，防止断网留下半截损坏图片。
     * @return true 表示成功落盘，false 表示远端返回 404（文件不存在）。
     */
    private fun downloadFileAtomically(
        baseUrl: String,
        token: String,
        relPath: String,
        destFile: File,
    ): Boolean {
        val parent = destFile.parentFile ?: error("目标路径缺少父目录: $relPath")
        if (!parent.exists()) parent.mkdirs()
        val tmpFile = File(parent, ".sync_dl_${Uuid.random()}.tmp")

        try {
            val req = Request.Builder()
                .url(buildUrl(baseUrl, "api/sync/files/download/$relPath"))
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            okHttpClient.newCall(req).execute().use { resp ->
                if (resp.code == 404) {
                    return false
                }
                if (!resp.isSuccessful) {
                    error("下载附件失败 HTTP ${resp.code}")
                }
                val body = resp.body ?: error("下载响应体为空")
                FileOutputStream(tmpFile).use { fos ->
                    body.byteStream().copyTo(fos, bufferSize = 32 * 1024)
                    fos.flush()
                    runCatching { fos.fd.sync() }
                }
            }

            if (tmpFile.length() == 0L) {
                tmpFile.delete()
                return false
            }

            try {
                Files.move(
                    tmpFile.toPath(),
                    destFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: Exception) {
                Files.move(
                    tmpFile.toPath(),
                    destFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            return true
        } finally {
            if (tmpFile.exists()) {
                tmpFile.delete()
            }
        }
    }

    private suspend fun collectAllReferencedManagedPathsFromLocalEntities(): Set<String> {
        val refs = LinkedHashSet<String>()
        val snapshot = collectLocalEntitySnapshotMap()
        for ((key, rawJson) in snapshot) {
            if (key.first in setOf("conversation", "assistant", "media_session", "media_record")) {
                refs.addAll(CanonicalSyncHash.extractReferencedFilesFromEntity(key.first, rawJson, localFilesDir))
            }
        }
        return refs
    }
}
