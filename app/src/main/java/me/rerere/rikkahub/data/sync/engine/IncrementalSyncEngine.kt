package me.rerere.rikkahub.data.sync.engine

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.SyncServerConfig
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FolderDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.entity.FolderEntity
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.mapLocalFileUrls
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest
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
    val tombstones: List<TombstoneSyncDto> = emptyList(),
)

@Serializable
data class PushRequestDto(
    val device_id: String,
    val device_name: String,
    val conversations: List<ConversationSyncDto> = emptyList(),
    val folders: List<FolderSyncDto> = emptyList(),
    val tombstones: List<TombstoneSyncDto> = emptyList(),
)

@Serializable
data class PushResponseDto(
    val server_timestamp: Long,
    val accepted_conversations: Int = 0,
    val accepted_folders: Int = 0,
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
    val syncedFiles: Int,
    val serverTimestamp: Long,
)

/**
 * 专为 RikkaHub 智能手表端与多端设计的自建增量同步引擎
 * 采用拉取 -> 合并 -> 推送 -> 附件按需比对闭环，实现毫秒级增量协同
 */
class IncrementalSyncEngine(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val conversationRepository: ConversationRepository,
    private val folderRepository: FolderRepository,
    private val conversationDAO: ConversationDAO,
    private val folderDAO: FolderDAO,
    private val messageNodeDAO: MessageNodeDAO,
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
            onProgress("正在从服务器拉取最新更新...")
            val pullReq = PullRequestDto(
                device_id = if (config.deviceName.isNotBlank()) config.deviceName else deviceId,
                device_name = if (config.deviceName.isNotBlank()) config.deviceName else deviceName,
                since_timestamp = lastSync
            )
            val pullHttpReq = Request.Builder()
                .url(buildUrl(baseUrl, "api/sync/pull"))
                .addHeader("Authorization", "Bearer $token")
                .post(json.encodeToString(PullRequestDto.serializer(), pullReq).toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val pullResponse: PullResponseDto = okHttpClient.newCall(pullHttpReq).execute().use { resp ->
                if (!resp.isSuccessful) error("Pull 失败: HTTP ${resp.code}")
                json.decodeFromString(PullResponseDto.serializer(), resp.body?.string().orEmpty())
            }

            // 2. 本地合并阶段 (Merge)
            onProgress("正在合并本地与远端数据...")
            var pulledCount = 0

            // 2.1 应用远端墓碑 (删除传播)
            pullResponse.tombstones.forEach { tomb ->
                if (tomb.entity_type == "conversation") {
                    val localConv = runCatching { Uuid.parse(tomb.id) }.getOrNull()?.let {
                        conversationRepository.getConversationById(it)
                    }
                    if (localConv != null && localConv.updateAt.toEpochMilli() <= tomb.deleted_at) {
                        conversationRepository.deleteConversation(localConv)
                    }
                } else if (tomb.entity_type == "folder") {
                    val folderId = runCatching { Uuid.parse(tomb.id) }.getOrNull()
                    if (folderId != null) {
                        folderRepository.deleteFolder(folderId)
                    }
                }
            }

            // 2.2 应用远端文件夹
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

            // 2.3 应用远端会话
            val baseFilesDir = context.filesDir.absolutePath
            pullResponse.conversations.forEach { convDto ->
                val remoteConv = runCatching {
                    json.decodeFromString<Conversation>(convDto.payload)
                }.getOrNull() ?: return@forEach

                // 将跨设备的相对文件路径映射到本机绝对可用 URI
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

            // 3. PUSH 阶段 (推送本地在 lastSync 后的改动)
            onProgress("正在将本地改动推送到云端...")
            val localUpdatedConvs = conversationDAO.getConversationsUpdatedSince(lastSync)
            val pushConvItems = localUpdatedConvs.mapNotNull { entity ->
                val conv = runCatching { Uuid.parse(entity.id) }.getOrNull()?.let {
                    conversationRepository.getConversationById(it)
                } ?: return@mapNotNull null

                // 将本地文件地址抽象为相对路径以便跨端共享
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
                    payload = json.encodeToString(Conversation.serializer(), portableConv),
                    updateAt = entity.updateAt,
                    createAt = entity.createAt,
                    folderId = entity.folderId,
                    isPinned = entity.isPinned,
                )
            }

            val localFolders = folderDAO.getAllFolders().map {
                FolderSyncDto(
                    id = it.id,
                    assistantId = it.assistantId,
                    name = it.name,
                    sortIndex = it.sortIndex,
                    createAt = it.createAt,
                    updateAt = System.currentTimeMillis(),
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
                tombstones = localTombstones,
            )

            val pushHttpReq = Request.Builder()
                .url(buildUrl(baseUrl, "api/sync/push"))
                .addHeader("Authorization", "Bearer $token")
                .post(json.encodeToString(PushRequestDto.serializer(), pushReq).toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val pushResponse: PushResponseDto = okHttpClient.newCall(pushHttpReq).execute().use { resp ->
                if (!resp.isSuccessful) error("Push 失败: HTTP ${resp.code}")
                json.decodeFromString(PushResponseDto.serializer(), resp.body?.string().orEmpty())
            }

            // 4. 附件增量比对与传输阶段
            var syncedFilesCount = 0
            if (config.syncFiles) {
                onProgress("正在检查多媒体附件...")
                val localFiles = mutableListOf<FileCheckItemDto>()
                val uploadDir = File(context.filesDir, FileFolders.UPLOAD)
                if (uploadDir.exists()) {
                    uploadDir.walkTopDown().filter { it.isFile }.forEach { file ->
                        val relative = "upload/${file.name}"
                        localFiles.add(FileCheckItemDto(path = relative, size = file.length()))
                    }
                }
                val mediaDir = File(context.filesDir, FileFolders.MEDIA_CREATION)
                if (mediaDir.exists()) {
                    mediaDir.walkTopDown().filter { it.isFile }.forEach { file ->
                        val relative = "media_creation/${file.name}"
                        localFiles.add(FileCheckItemDto(path = relative, size = file.length()))
                    }
                }

                // 4.1 比对缺失清单
                if (localFiles.isNotEmpty()) {
                    val checkReq = FilesCheckRequestDto(files = localFiles)
                    val checkHttpReq = Request.Builder()
                        .url(buildUrl(baseUrl, "api/sync/files/check"))
                        .addHeader("Authorization", "Bearer $token")
                        .post(json.encodeToString(FilesCheckRequestDto.serializer(), checkReq).toRequestBody(JSON_MEDIA_TYPE))
                        .build()

                    val checkResp: FilesCheckResponseDto = okHttpClient.newCall(checkHttpReq).execute().use { resp ->
                        if (resp.isSuccessful) {
                            json.decodeFromString(FilesCheckResponseDto.serializer(), resp.body?.string().orEmpty())
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
                            val relPath = if (pathStr.contains("upload/")) "upload/" + pathStr.substringAfterLast("upload/")
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
                syncedFiles = syncedFilesCount,
                serverTimestamp = newSyncTime
            )
        }
    }
}
