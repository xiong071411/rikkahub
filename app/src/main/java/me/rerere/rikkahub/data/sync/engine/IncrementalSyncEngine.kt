package me.rerere.rikkahub.data.sync.engine

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderSetting
import me.rerere.asr.ASRProviderSetting
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.SyncServerConfig
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.dao.FolderDAO
import me.rerere.rikkahub.data.db.dao.MediaCreationDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.entity.FavoriteEntity
import me.rerere.rikkahub.data.db.entity.FolderEntity
import me.rerere.rikkahub.data.db.entity.MediaCreationSessionEntity
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.model.mapLocalFileUrls
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
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
import kotlin.uuid.Uuid

private const val TAG = "IncrementalSyncEngine"
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

@Serializable
data class ConversationSyncDto(
    val id: String,
    val assistantId: String = "",
    val title: String = "",
    val payload: String,
    val updateAt: Long,
    val createAt: Long = 0,
    val folderId: String = "",
    val isPinned: Boolean = false,
)

@Serializable
data class FolderSyncDto(
    val id: String,
    val assistantId: String = "",
    val name: String,
    val sortIndex: Int = 0,
    val createAt: Long = 0,
    val updateAt: Long,
)

@Serializable
data class AssistantSyncDto(
    val id: String,
    val name: String = "",
    val payload: String,
    val updateAt: Long = 0,
)

@Serializable
data class ProviderSyncDto(
    val id: String,
    val name: String = "",
    val payload: String,
    val updateAt: Long = 0,
)

@Serializable
data class FavoriteSyncDto(
    val id: String,
    val refKey: String = "",
    val type: String = "",
    val payload: String,
    val updateAt: Long = 0,
)

@Serializable
data class FavoriteSyncPayload(
    val id: String,
    val refKey: String,
    val type: String,
    val refJson: String,
    val snapshotJson: String,
    val metaJson: String? = null,
    val createdAt: Long,
)

@Serializable
data class MediaCreationSyncDto(
    val id: String,
    val title: String = "",
    val payload: String,
    val updateAt: Long = 0,
)

@Serializable
data class MediaCreationSyncPayload(
    val id: String,
    val title: String,
    val draft: String,
    val createAt: Long,
    val updateAt: Long,
)

@Serializable
data class ExtraSettingsSyncDto(
    val key: String,
    val payload: String,
    val updateAt: Long = 0,
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
data class TombstoneSyncDto(
    val id: String,
    val entity_type: String,
    val deleted_at: Long,
)

@Serializable
data class PullRequestDto(
    val device_id: String,
    val device_name: String,
    val since_timestamp: Long,
)

@Serializable
data class PullResponseDto(
    val server_timestamp: Long,
    val conversations: List<ConversationSyncDto> = emptyList(),
    val folders: List<FolderSyncDto> = emptyList(),
    val assistants: List<AssistantSyncDto> = emptyList(),
    val providers: List<ProviderSyncDto> = emptyList(),
    val favorites: List<FavoriteSyncDto> = emptyList(),
    val media_creations: List<MediaCreationSyncDto> = emptyList(),
    val extra_settings: List<ExtraSettingsSyncDto> = emptyList(),
    val tombstones: List<TombstoneSyncDto> = emptyList(),
)

@Serializable
data class PushRequestDto(
    val device_id: String,
    val device_name: String,
    val conversations: List<ConversationSyncDto> = emptyList(),
    val folders: List<FolderSyncDto> = emptyList(),
    val assistants: List<AssistantSyncDto> = emptyList(),
    val providers: List<ProviderSyncDto> = emptyList(),
    val favorites: List<FavoriteSyncDto> = emptyList(),
    val media_creations: List<MediaCreationSyncDto> = emptyList(),
    val extra_settings: List<ExtraSettingsSyncDto> = emptyList(),
    val tombstones: List<TombstoneSyncDto> = emptyList(),
)

@Serializable
data class PushResponseDto(
    val server_timestamp: Long,
    val accepted_conversations: Int = 0,
    val accepted_folders: Int = 0,
    val accepted_assistants: Int = 0,
    val accepted_providers: Int = 0,
    val accepted_favorites: Int = 0,
    val accepted_media_creations: Int = 0,
    val accepted_extra_settings: Int = 0,
    val accepted_tombstones: Int = 0,
)

@Serializable
data class FileCheckItemDto(
    val path: String,
    val hash: String = "",
    val size: Long = 0,
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
    val serverTimestamp: Long,
)

/**
 * 专为 RikkaHub 打造的完全替代并超越冷备份的【全量业务实体增量同步引擎】
 * 覆盖：会话、文件夹、助手配置、大模型提供商Key、收藏夹、生图会话、世界书知识库、AI提示词模板、附件文件
 */
class IncrementalSyncEngine(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val conversationRepository: ConversationRepository,
    private val folderRepository: FolderRepository,
    private val conversationDAO: ConversationDAO,
    private val folderDAO: FolderDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val favoriteDAO: FavoriteDAO,
    private val mediaCreationDAO: MediaCreationDAO,
    private val syncTombstoneManager: SyncTombstoneManager,
    private val okHttpClient: OkHttpClient,
    private val json: Json = JsonInstant,
) {
    private val deviceId: String by lazy {
        val model = Build.MODEL ?: "Android-Device"
        val serial = Build.FINGERPRINT.hashCode().toString()
        "${model}_$serial"
    }

    private val deviceName: String by lazy {
        "${Build.MANUFACTURER} ${Build.MODEL}"
    }

    private fun buildUrl(baseUrl: String, path: String): String {
        return baseUrl.trimEnd('/') + "/" + path.trimStart('/')
    }

    /**
     * 测试与服务端的连通性
     */
    suspend fun testConnection(config: SyncServerConfig): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(config.serverUrl.isNotBlank()) { "服务器地址不能为空" }
            val request = Request.Builder()
                .url(buildUrl(config.serverUrl, "api/status"))
                .addHeader("Authorization", "Bearer ${config.authToken.trim()}")
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()
            response.use {
                if (!it.isSuccessful) {
                    error("服务器返回错误状态码: ${it.code} (${it.message})")
                }
            }
        }
    }

    /**
     * 执行完整的增量同步闭环
     */
    suspend fun sync(
        config: SyncServerConfig,
        onProgress: (String) -> Unit = {}
    ): Result<SyncSummary> = withContext(Dispatchers.IO) {
        runCatching {
            require(config.serverUrl.isNotBlank()) { "服务器地址未配置" }
            val baseUrl = config.serverUrl.trimEnd('/')
            val token = config.authToken.trim()
            val lastSync = config.lastSyncTime

            onProgress("正在连接同步服务器...")
            testConnection(config).getOrThrow()

            // 1. PULL 阶段
            onProgress("正在拉取最新数据 (会话、助手、服务商)...")
            val pullReq = PullRequestDto(
                device_id = if (config.deviceName.isNotBlank()) config.deviceName else deviceId,
                device_name = if (config.deviceName.isNotBlank()) config.deviceName else deviceName,
                since_timestamp = lastSync
            )
            val pullHttpReq = Request.Builder()
                .url(buildUrl(baseUrl, "api/sync/pull"))
                .addHeader("Authorization", "Bearer $token")
                .post(json.encodeToString(pullReq).toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val pullResponse: PullResponseDto = okHttpClient.newCall(pullHttpReq).execute().use { resp ->
                if (!resp.isSuccessful) error("Pull 失败: HTTP ${resp.code}")
                json.decodeFromString(resp.body?.string().orEmpty())
            }

            // 2. 本地合并阶段 (Merge)
            onProgress("正在合并助手与提供商配置...")
            var pulledCount = 0
            var syncedAssts = 0
            var syncedProvs = 0

            // 2.1 应用远端墓碑 (删除传播)
            pullResponse.tombstones.forEach { tomb ->
                when (tomb.entity_type) {
                    "conversation" -> {
                        val localConv = runCatching { Uuid.parse(tomb.id) }.getOrNull()?.let {
                            conversationRepository.getConversationById(it)
                        }
                        if (localConv != null && localConv.updateAt.toEpochMilli() <= tomb.deleted_at) {
                            conversationRepository.deleteConversation(localConv)
                        }
                    }
                    "folder" -> {
                        val folderId = runCatching { Uuid.parse(tomb.id) }.getOrNull()
                        if (folderId != null) folderRepository.deleteFolder(folderId)
                    }
                    "assistant" -> {
                        val asstId = runCatching { Uuid.parse(tomb.id) }.getOrNull()
                        if (asstId != null) {
                            settingsStore.update { curr ->
                                curr.copy(assistants = curr.assistants.filterNot { it.id == asstId })
                            }
                        }
                    }
                    "provider" -> {
                        val provId = runCatching { Uuid.parse(tomb.id) }.getOrNull()
                        if (provId != null) {
                            settingsStore.update { curr ->
                                curr.copy(providers = curr.providers.filterNot { it.id == provId })
                            }
                        }
                    }
                    "favorite" -> {
                        favoriteDAO.deleteById(tomb.id)
                    }
                    "media_creation" -> {
                        mediaCreationDAO.deleteSession(tomb.id)
                    }
                }
            }

            // 2.2 合并助手 (Assistants) 与提供商 (Providers) 及扩展设置
            settingsStore.update { currentSettings ->
                var updated = currentSettings

                // 2.2.1 助手合并 (解决新设备上自定义助手与消息消失的问题)
                if (pullResponse.assistants.isNotEmpty()) {
                    val remoteAssistants = pullResponse.assistants.mapNotNull {
                        runCatching { json.decodeFromString<Assistant>(it.payload) }.getOrNull()
                    }
                    val existingIds = updated.assistants.map { it.id }.toSet()
                    val newAssistants = remoteAssistants.filter { it.id !in existingIds }
                    val mergedAssistants = updated.assistants.map { local ->
                        remoteAssistants.find { it.id == local.id } ?: local
                    } + newAssistants
                    updated = updated.copy(assistants = mergedAssistants)
                    syncedAssts = remoteAssistants.size
                }

                // 2.2.2 提供商合并 (API Key 与 Base URL 漫游)
                if (pullResponse.providers.isNotEmpty()) {
                    val remoteProviders = pullResponse.providers.mapNotNull {
                        runCatching { json.decodeFromString<ProviderSetting>(it.payload) }.getOrNull()
                    }
                    val existingIds = updated.providers.map { it.id }.toSet()
                    val newProviders = remoteProviders.filter { it.id !in existingIds }
                    val mergedProviders = updated.providers.map { local ->
                        remoteProviders.find { it.id == local.id } ?: local
                    } + newProviders
                    updated = updated.copy(providers = mergedProviders)
                    syncedProvs = remoteProviders.size
                }

                // 2.2.3 扩展设置合并 (标签、世界书、注入模板、快捷短语、主题、AI全局偏好)
                pullResponse.extra_settings.forEach { extra ->
                    when (extra.key) {
                        "assistant_tags" -> {
                            val tags = runCatching { json.decodeFromString<List<Tag>>(extra.payload) }.getOrNull()
                            if (tags != null) {
                                val existingTagIds = updated.assistantTags.map { it.id }.toSet()
                                updated = updated.copy(assistantTags = updated.assistantTags + tags.filter { it.id !in existingTagIds })
                            }
                        }
                        "lorebooks" -> {
                            val books = runCatching { json.decodeFromString<List<Lorebook>>(extra.payload) }.getOrNull()
                            if (books != null) {
                                val existingIds = updated.lorebooks.map { it.id }.toSet()
                                updated = updated.copy(lorebooks = updated.lorebooks + books.filter { it.id !in existingIds })
                            }
                        }
                        "mode_injections" -> {
                            val injections = runCatching { json.decodeFromString<List<PromptInjection.ModeInjection>>(extra.payload) }.getOrNull()
                            if (injections != null) {
                                val existingIds = updated.modeInjections.map { it.id }.toSet()
                                updated = updated.copy(modeInjections = updated.modeInjections + injections.filter { it.id !in existingIds })
                            }
                        }
                        "quick_messages" -> {
                            val qms = runCatching { json.decodeFromString<List<QuickMessage>>(extra.payload) }.getOrNull()
                            if (qms != null) {
                                val existingIds = updated.quickMessages.map { it.id }.toSet()
                                updated = updated.copy(quickMessages = updated.quickMessages + qms.filter { it.id !in existingIds })
                            }
                        }
                        "custom_themes" -> {
                            val themes = runCatching { json.decodeFromString<List<CustomTheme>>(extra.payload) }.getOrNull()
                            if (themes != null) {
                                val existingIds = updated.customThemes.map { it.id }.toSet()
                                updated = updated.copy(customThemes = updated.customThemes + themes.filter { it.id !in existingIds })
                            }
                        }
                        "media_generation_providers" -> {
                            val mgps = runCatching { json.decodeFromString<List<MediaGenerationProviderSetting>>(extra.payload) }.getOrNull()
                            if (mgps != null) {
                                val existingIds = updated.mediaGenerationProviders.map { it.id }.toSet()
                                updated = updated.copy(mediaGenerationProviders = updated.mediaGenerationProviders + mgps.filter { it.id !in existingIds })
                            }
                        }
                        "tts_providers" -> {
                            val ttss = runCatching { json.decodeFromString<List<TTSProviderSetting>>(extra.payload) }.getOrNull()
                            if (ttss != null) {
                                val existingIds = updated.ttsProviders.map { it.id }.toSet()
                                updated = updated.copy(ttsProviders = updated.ttsProviders + ttss.filter { it.id !in existingIds })
                            }
                        }
                        "asr_providers" -> {
                            val asrs = runCatching { json.decodeFromString<List<ASRProviderSetting>>(extra.payload) }.getOrNull()
                            if (asrs != null) {
                                val existingIds = updated.asrProviders.map { it.id }.toSet()
                                updated = updated.copy(asrProviders = updated.asrProviders + asrs.filter { it.id !in existingIds })
                            }
                        }
                        "search_services" -> {
                            val sss = runCatching { json.decodeFromString<List<SearchServiceOptions>>(extra.payload) }.getOrNull()
                            if (sss != null) {
                                val existingIds = updated.searchServices.map { it.id }.toSet()
                                updated = updated.copy(searchServices = updated.searchServices + sss.filter { it.id !in existingIds })
                            }
                        }
                        "mcp_servers" -> {
                            val mcps = runCatching { json.decodeFromString<List<McpServerConfig>>(extra.payload) }.getOrNull()
                            if (mcps != null) {
                                val existingIds = updated.mcpServers.map { it.id }.toSet()
                                updated = updated.copy(mcpServers = updated.mcpServers + mcps.filter { it.id !in existingIds })
                            }
                        }
                        "ai_preferences" -> {
                            val prefs = runCatching { json.decodeFromString<AiPreferencesSyncDto>(extra.payload) }.getOrNull()
                            if (prefs != null) {
                                updated = updated.copy(
                                    favoriteModels = prefs.favoriteModels.mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() },
                                    chatModelId = runCatching { Uuid.parse(prefs.chatModelId) }.getOrNull() ?: updated.chatModelId,
                                    fastModelId = runCatching { Uuid.parse(prefs.fastModelId) }.getOrNull() ?: updated.fastModelId,
                                    enableAutoTitle = prefs.enableAutoTitle,
                                    titlePrompt = prefs.titlePrompt.ifEmpty { updated.titlePrompt },
                                    translatePrompt = prefs.translatePrompt.ifEmpty { updated.translatePrompt },
                                    translateTargetLanguage = prefs.translateTargetLanguage.ifEmpty { updated.translateTargetLanguage },
                                    enableSuggestion = prefs.enableSuggestion,
                                    suggestionPrompt = prefs.suggestionPrompt.ifEmpty { updated.suggestionPrompt },
                                    ocrPrompt = prefs.ocrPrompt.ifEmpty { updated.ocrPrompt },
                                    defaultTTSPlaybackSpeed = prefs.defaultTTSPlaybackSpeed,
                                )
                            }
                        }
                    }
                }

                updated
            }

            // 2.3 应用远端文件夹
            pullResponse.folders.forEach { folderDto ->
                val existing = folderDAO.getFolderById(folderDto.id)
                if (existing == null) {
                    folderDAO.insert(
                        FolderEntity(
                            id = folderDto.id,
                            assistantId = folderDto.assistantId,
                            name = folderDto.name,
                            sortIndex = folderDto.sortIndex,
                            createAt = folderDto.createAt,
                        )
                    )
                } else {
                    folderDAO.update(
                        existing.copy(name = folderDto.name, sortIndex = folderDto.sortIndex)
                    )
                }
            }

            // 2.4 应用远端收藏夹 (Favorites)
            pullResponse.favorites.forEach { favDto ->
                val favPayload = runCatching { json.decodeFromString<FavoriteSyncPayload>(favDto.payload) }.getOrNull()
                if (favPayload != null) {
                    favoriteDAO.upsert(
                        FavoriteEntity(
                            id = favPayload.id,
                            refKey = favPayload.refKey,
                            type = favPayload.type,
                            refJson = favPayload.refJson,
                            snapshotJson = favPayload.snapshotJson,
                            metaJson = favPayload.metaJson,
                            createdAt = favPayload.createdAt,
                        )
                    )
                }
            }

            // 2.5 应用远端媒体生图会话 (Media Creations)
            pullResponse.media_creations.forEach { mediaDto ->
                val sessionPayload = runCatching { json.decodeFromString<MediaCreationSyncPayload>(mediaDto.payload) }.getOrNull()
                if (sessionPayload != null) {
                    mediaCreationDAO.insertSession(
                        MediaCreationSessionEntity(
                            id = sessionPayload.id,
                            title = sessionPayload.title,
                            draft = sessionPayload.draft,
                            createAt = sessionPayload.createAt,
                            updateAt = sessionPayload.updateAt,
                        )
                    )
                }
            }

            // 2.6 应用远端会话 (Conversations)
            onProgress("正在合并会话与消息记录...")
            val baseFilesDir = context.filesDir.absolutePath
            pullResponse.conversations.forEach { convDto ->
                val remoteConv = runCatching {
                    json.decodeFromString<Conversation>(convDto.payload)
                }.getOrNull() ?: return@forEach

                val localizedConv = remoteConv.copy(
                    messageNodes = remoteConv.messageNodes.map { node ->
                        node.copy(
                            messages = node.messages.map { msg ->
                                msg.copy(
                                    parts = msg.parts.map { part ->
                                        part.mapLocalFileUrls { relative ->
                                            if (relative.startsWith("file://")) {
                                                relative
                                            } else {
                                                "file://$baseFilesDir/${relative.trimStart('/')}"
                                            }
                                        }
                                    }
                                )
                            }
                        )
                    }
                )

                val localEntity = conversationDAO.getConversationById(convDto.id)
                if (localEntity == null) {
                    conversationRepository.insertConversation(localizedConv)
                    pulledCount++
                } else if (convDto.updateAt >= localEntity.updateAt) {
                    conversationRepository.updateConversation(localizedConv)
                    pulledCount++
                }
            }

            // 3. PUSH 阶段 (向远端推送本地全量业务实体)
            onProgress("正在向云端推送本地数据 (会话、助手、配置)...")
            val currentSettings = settingsStore.settingsFlow.value

            // 3.1 推送会话
            val localUpdatedConvs = conversationDAO.getConversationsUpdatedSince(lastSync)
            val pushConvItems = localUpdatedConvs.mapNotNull { entity ->
                val conv = runCatching { Uuid.parse(entity.id) }.getOrNull()?.let {
                    conversationRepository.getConversationById(it)
                } ?: return@mapNotNull null

                val portableConv = conv.copy(
                    messageNodes = conv.messageNodes.map { node ->
                        node.copy(
                            messages = node.messages.map { msg ->
                                msg.copy(
                                    parts = msg.parts.map { part ->
                                        part.mapLocalFileUrls { absUrl ->
                                            absUrl.removePrefix("file://$baseFilesDir/")
                                                .removePrefix("file://")
                                        }
                                    }
                                )
                            }
                        )
                    }
                )

                ConversationSyncDto(
                    id = entity.id,
                    assistantId = entity.assistantId,
                    title = entity.title,
                    payload = json.encodeToString(portableConv),
                    updateAt = entity.updateAt,
                    createAt = entity.createAt,
                    folderId = entity.folderId,
                    isPinned = entity.isPinned,
                )
            }

            // 3.2 推送助手 (Assistants)
            val pushAsstItems = currentSettings.assistants.map { asst ->
                AssistantSyncDto(
                    id = asst.id.toString(),
                    name = asst.name,
                    payload = json.encodeToString(asst),
                    updateAt = System.currentTimeMillis()
                )
            }

            // 3.3 推送提供商 (Providers)
            val pushProvItems = currentSettings.providers.map { prov ->
                ProviderSyncDto(
                    id = prov.id.toString(),
                    name = prov.name,
                    payload = json.encodeToString(prov),
                    updateAt = System.currentTimeMillis()
                )
            }

            // 3.4 推送收藏夹 (Favorites)
            val pushFavItems = favoriteDAO.getAllFavorites().map { fav ->
                FavoriteSyncDto(
                    id = fav.id,
                    refKey = fav.refKey,
                    type = fav.type,
                    payload = json.encodeToString(
                        FavoriteSyncPayload(
                            id = fav.id,
                            refKey = fav.refKey,
                            type = fav.type,
                            refJson = fav.refJson,
                            snapshotJson = fav.snapshotJson,
                            metaJson = fav.metaJson,
                            createdAt = fav.createdAt,
                        )
                    ),
                    updateAt = fav.createdAt
                )
            }

            // 3.5 推送媒体生图 (Media Creations)
            val pushMediaItems = mediaCreationDAO.getAllSessions().map { session ->
                MediaCreationSyncDto(
                    id = session.id,
                    title = session.title,
                    payload = json.encodeToString(
                        MediaCreationSyncPayload(
                            id = session.id,
                            title = session.title,
                            draft = session.draft,
                            createAt = session.createAt,
                            updateAt = session.updateAt,
                        )
                    ),
                    updateAt = session.updateAt
                )
            }

            // 3.6 推送扩展设置与AI偏好
            val nowTime = System.currentTimeMillis()
            val pushExtraItems = listOf(
                ExtraSettingsSyncDto("assistant_tags", json.encodeToString(currentSettings.assistantTags), nowTime),
                ExtraSettingsSyncDto("lorebooks", json.encodeToString(currentSettings.lorebooks), nowTime),
                ExtraSettingsSyncDto("mode_injections", json.encodeToString(currentSettings.modeInjections), nowTime),
                ExtraSettingsSyncDto("quick_messages", json.encodeToString(currentSettings.quickMessages), nowTime),
                ExtraSettingsSyncDto("custom_themes", json.encodeToString(currentSettings.customThemes), nowTime),
                ExtraSettingsSyncDto("media_generation_providers", json.encodeToString(currentSettings.mediaGenerationProviders), nowTime),
                ExtraSettingsSyncDto("tts_providers", json.encodeToString(currentSettings.ttsProviders), nowTime),
                ExtraSettingsSyncDto("asr_providers", json.encodeToString(currentSettings.asrProviders), nowTime),
                ExtraSettingsSyncDto("search_services", json.encodeToString(currentSettings.searchServices), nowTime),
                ExtraSettingsSyncDto("mcp_servers", json.encodeToString(currentSettings.mcpServers), nowTime),
                ExtraSettingsSyncDto(
                    "ai_preferences",
                    json.encodeToString(
                        AiPreferencesSyncDto(
                            favoriteModels = currentSettings.favoriteModels.map { it.toString() },
                            chatModelId = currentSettings.chatModelId.toString(),
                            fastModelId = currentSettings.fastModelId.toString(),
                            enableAutoTitle = currentSettings.enableAutoTitle,
                            titlePrompt = currentSettings.titlePrompt,
                            translatePrompt = currentSettings.translatePrompt,
                            translateTargetLanguage = currentSettings.translateTargetLanguage,
                            enableSuggestion = currentSettings.enableSuggestion,
                            suggestionPrompt = currentSettings.suggestionPrompt,
                            ocrPrompt = currentSettings.ocrPrompt,
                            defaultTTSPlaybackSpeed = currentSettings.defaultTTSPlaybackSpeed,
                        )
                    ),
                    nowTime
                ),
            )

            // 3.7 文件夹与墓碑
            val localFolders = folderDAO.getAllFolders().map {
                FolderSyncDto(
                    id = it.id,
                    assistantId = it.assistantId,
                    name = it.name,
                    sortIndex = it.sortIndex,
                    createAt = it.createAt,
                    updateAt = nowTime,
                )
            }

            val localTombstones = syncTombstoneManager.getTombstonesSince(lastSync).map {
                TombstoneSyncDto(id = it.id, entity_type = it.entity_type, deleted_at = it.deleted_at)
            }

            val pushReq = PushRequestDto(
                device_id = if (config.deviceName.isNotBlank()) config.deviceName else deviceId,
                device_name = if (config.deviceName.isNotBlank()) config.deviceName else deviceName,
                conversations = pushConvItems,
                folders = localFolders,
                assistants = pushAsstItems,
                providers = pushProvItems,
                favorites = pushFavItems,
                media_creations = pushMediaItems,
                extra_settings = pushExtraItems,
                tombstones = localTombstones,
            )

            val pushHttpReq = Request.Builder()
                .url(buildUrl(baseUrl, "api/sync/push"))
                .addHeader("Authorization", "Bearer $token")
                .post(json.encodeToString(pushReq).toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val pushResponse: PushResponseDto = okHttpClient.newCall(pushHttpReq).execute().use { resp ->
                if (!resp.isSuccessful) error("Push 失败: HTTP ${resp.code}")
                json.decodeFromString(resp.body?.string().orEmpty())
            }

            // 4. 附件增量比对与传输阶段 (支持 upload/, media_creation/, skills/, fonts/ 全覆盖)
            var syncedFilesCount = 0
            if (config.syncFiles) {
                onProgress("正在比对多媒体与字体附件...")
                val localFiles = mutableListOf<FileCheckItemDto>()
                listOf(FileFolders.UPLOAD, FileFolders.MEDIA_CREATION, FileFolders.SKILLS, FileFolders.FONTS).forEach { folderName ->
                    val dir = File(context.filesDir, folderName)
                    if (dir.exists()) {
                        dir.walkTopDown().filter { it.isFile }.forEach { file ->
                            val relative = file.relativeTo(context.filesDir).invariantSeparatorsPath
                            localFiles.add(FileCheckItemDto(path = relative, size = file.length()))
                        }
                    }
                }

                // 4.1 比对缺失清单
                if (localFiles.isNotEmpty()) {
                    val checkReq = FilesCheckRequestDto(files = localFiles)
                    val checkHttpReq = Request.Builder()
                        .url(buildUrl(baseUrl, "api/sync/files/check"))
                        .addHeader("Authorization", "Bearer $token")
                        .post(json.encodeToString(checkReq).toRequestBody(JSON_MEDIA_TYPE))
                        .build()

                    val checkResp: FilesCheckResponseDto = okHttpClient.newCall(checkHttpReq).execute().use { resp ->
                        if (resp.isSuccessful) {
                            json.decodeFromString(resp.body?.string().orEmpty())
                        } else FilesCheckResponseDto()
                    }

                    // 4.2 仅上传服务端缺失的本地文件
                    checkResp.missing_paths.forEachIndexed { idx, relPath ->
                        onProgress("上传新附件 (${idx + 1}/${checkResp.missing_paths.size})...")
                        val file = File(context.filesDir, relPath)
                        if (file.isFile) {
                            val formBody = MultipartBody.Builder()
                                .setType(MultipartBody.FORM)
                                .addFormDataPart("path", relPath)
                                .addFormDataPart("file", file.name, file.asRequestBody())
                                .build()

                            val uploadReq = Request.Builder()
                                .url(buildUrl(baseUrl, "api/sync/files/upload"))
                                .addHeader("Authorization", "Bearer $token")
                                .post(formBody)
                                .build()

                            okHttpClient.newCall(uploadReq).execute().close()
                            syncedFilesCount++
                        }
                    }
                }

                // 4.3 补充拉取远端已有但本地缺失的附件
                pullResponse.conversations.forEach { convDto ->
                    val remoteConv = runCatching { json.decodeFromString<Conversation>(convDto.payload) }.getOrNull()
                    remoteConv?.files?.forEach { uri ->
                        val pathStr = uri.toString().removePrefix("file://")
                        val file = File(pathStr)
                        if (!file.exists()) {
                            val relPath = file.relativeToOrNull(context.filesDir)?.invariantSeparatorsPath
                                ?: if (pathStr.contains("upload/")) "upload/" + pathStr.substringAfterLast("upload/")
                                else if (pathStr.contains("media_creation/")) "media_creation/" + pathStr.substringAfterLast("media_creation/")
                                else null

                            if (relPath != null) {
                                file.parentFile?.mkdirs()
                                val downloadReq = Request.Builder()
                                    .url(buildUrl(baseUrl, "api/sync/files/download/$relPath"))
                                    .addHeader("Authorization", "Bearer $token")
                                    .get()
                                    .build()
                                runCatching {
                                    okHttpClient.newCall(downloadReq).execute().use { resp ->
                                        if (resp.isSuccessful && resp.body != null) {
                                            file.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
                                            syncedFilesCount++
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 5. 保存最新同步时间戳并清理老旧墓碑
            val newSyncTime = pushResponse.server_timestamp.coerceAtLeast(pullResponse.server_timestamp)
            settingsStore.update { current ->
                current.copy(
                    syncServerConfig = current.syncServerConfig.copy(lastSyncTime = newSyncTime)
                )
            }
            syncTombstoneManager.purgeOlderThan(System.currentTimeMillis() - 30L * 24 * 3600 * 1000)

            onProgress("增量同步完成！")
            SyncSummary(
                pulledConversations = pulledCount,
                pushedConversations = pushResponse.accepted_conversations,
                syncedAssistants = syncedAssts,
                syncedProviders = syncedProvs,
                syncedFiles = syncedFilesCount,
                serverTimestamp = newSyncTime
            )
        }
    }
}
