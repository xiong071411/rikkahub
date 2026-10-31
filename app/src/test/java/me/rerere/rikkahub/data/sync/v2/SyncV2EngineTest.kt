package me.rerere.rikkahub.data.sync.v2

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.paging.PagingSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.SyncServerConfig
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.dao.FolderDAO
import me.rerere.rikkahub.data.db.dao.MediaCreationDAO
import me.rerere.rikkahub.data.db.dao.SyncV2Dao
import me.rerere.rikkahub.data.db.entity.FavoriteEntity
import me.rerere.rikkahub.data.db.entity.FolderEntity
import me.rerere.rikkahub.data.db.entity.MediaCreationNodeEntity
import me.rerere.rikkahub.data.db.entity.MediaCreationRecordEntity
import me.rerere.rikkahub.data.db.entity.MediaCreationRecordWithVersion
import me.rerere.rikkahub.data.db.entity.MediaCreationSessionEntity
import me.rerere.rikkahub.data.db.entity.MediaCreationSessionWithStats
import me.rerere.rikkahub.data.db.entity.SyncConflictEntity
import me.rerere.rikkahub.data.db.entity.SyncEntityStateEntity
import me.rerere.rikkahub.data.db.entity.SyncFileQueueEntity
import me.rerere.rikkahub.data.db.entity.SyncInboxPendingEntity
import me.rerere.rikkahub.data.db.entity.SyncMetaEntity
import me.rerere.rikkahub.data.db.entity.SyncOutboxEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.sync.engine.FilesCheckRequestDto
import me.rerere.rikkahub.data.sync.engine.FilesCheckResponseDto
import me.rerere.rikkahub.data.sync.engine.IncrementalSyncEngine
import me.rerere.rikkahub.data.sync.engine.SyncConversationBridge
import me.rerere.rikkahub.data.sync.engine.SyncV2ChangeItemDto
import me.rerere.rikkahub.data.sync.engine.SyncV2InfoResponseDto
import me.rerere.rikkahub.data.sync.engine.SyncV2MutationResultDto
import me.rerere.rikkahub.data.sync.engine.SyncV2PullRequestDto
import me.rerere.rikkahub.data.sync.engine.SyncV2PullResponseDto
import me.rerere.rikkahub.data.sync.engine.SyncV2PushRequestDto
import me.rerere.rikkahub.data.sync.engine.SyncV2PushResponseDto
import me.rerere.rikkahub.data.sync.v2.AttachmentPathNormalizer.toPortableAttachmentPaths
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.File
import java.time.Instant
import kotlin.uuid.Uuid

/** 测试用固定助手 ID（Conversation.assistantId 为必填字段）。 */
private val TEST_ASSISTANT_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000000")

/**
 * Sync V2 增量同步引擎端到端单元测试：
 * 覆盖 `upper_bound_revision` 分页一致性、离线双副本冲突保留与分叉恢复、`mutation_id` 幂等重推、
 * 鉴权/426 错误语义、流式生成会话 `sync_inbox_pending` 暂存、连续 3 次生图记录无损同步以及附件 `.tmp` 原子补拉。
 */
class SyncV2EngineTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private lateinit var filesDir: File
    private lateinit var storeScope: CoroutineScope
    private lateinit var settingsStore: SettingsStore
    private lateinit var syncV2Dao: FakeSyncV2Dao
    private lateinit var recorder: SyncMutationRecorder
    private lateinit var conversationBridge: FakeSyncConversationBridge
    private lateinit var folderDao: FakeFolderDao
    private lateinit var favoriteDao: FakeFavoriteDao
    private lateinit var mediaDao: FakeMediaCreationDao
    private lateinit var server: FakeSyncV2HttpServer
    private lateinit var memoryRepository: me.rerere.rikkahub.data.repository.MemoryRepository
    private lateinit var engine: IncrementalSyncEngine

    private val validToken = "rk_sync_unit_test_secret_token"
    private val defaultConfig = SyncServerConfig(
        serverUrl = "http://sync.test.local:8765",
        authToken = validToken,
        deviceName = "RMX3800-Test",
        syncFiles = true,
    )

    @Before
    fun setUp() {
        filesDir = File(tempFolder.root, "app_files").apply { mkdirs() }
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dsFile = File(tempFolder.root, "test_settings_${Uuid.random()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) { dsFile }
        settingsStore = SettingsStore(dataStore, storeScope)

        syncV2Dao = FakeSyncV2Dao()
        recorder = SyncMutationRecorder(
            database = null,
            syncV2Dao = syncV2Dao,
            filesDirProvider = { filesDir },
        )
        memoryRepository = me.rerere.rikkahub.data.repository.MemoryRepository(me.rerere.rikkahub.data.repository.FakeMemoryDAO(), recorder)
        conversationBridge = FakeSyncConversationBridge(recorder, filesDir)
        folderDao = FakeFolderDao()
        favoriteDao = FakeFavoriteDao()
        mediaDao = FakeMediaCreationDao()
        server = FakeSyncV2HttpServer(expectedToken = validToken)

        val okHttp = OkHttpClient.Builder()
            .addInterceptor(server)
            .build()

        engine = IncrementalSyncEngine(
            localFilesDir = filesDir,
            database = null,
            syncV2Dao = syncV2Dao,
            syncMutationRecorder = recorder,
            settingsStore = settingsStore,
            memoryRepository = memoryRepository,
            conversationBridge = conversationBridge,
            folderDAO = folderDao,
            favoriteDAO = favoriteDao,
            mediaCreationDAO = mediaDao,
            okHttpClient = okHttp,
            pullPageLimit = 2, // 故意设为每页 2 条以覆盖多页游标与快照上界测试
        )
    }

    @After
    fun tearDown() {
        runBlocking {
            storeScope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun `multi page pull locks upper bound revision without skipping or duplicating items`() = runBlocking {
        // 先完成空基线对账，使客户端进入常规增量模式
        assertTrue(engine.sync(defaultConfig).isSuccess)
        val baselineCursor = syncV2Dao.getMetaValue("cursor")!!.toLong()

        // 在服务端写入 5 条新会话（由于 pullPageLimit = 2，将跨越 3 个分页请求）
        val createdIds = (1..5).map { idx ->
            val conv = sampleConversation(title = "分页会话 #$idx", userText = "内容 $idx")
            server.seedRemoteEntity("conversation", conv.id.toString(), "upsert", JsonInstant.encodeToString(conv))
            conv.id
        }

        // 模拟第 1 页拉取返回后，另一台设备在服务端又插入第 6 条会话（验证 upper_bound_revision 快照锁）
        val lateConv = sampleConversation(title = "分页中途插入的第6条会话", userText = "下轮再拉")
        server.onAfterFirstPullPage = {
            server.seedRemoteEntity("conversation", lateConv.id.toString(), "upsert", JsonInstant.encodeToString(lateConv))
        }

        val summary = engine.sync(defaultConfig).getOrThrow()
        assertEquals(5, summary.pulledConversations)
        assertTrue(summary.currentRevision > baselineCursor)
        createdIds.forEach { id ->
            assertNotNull("应当完整拉取会话 $id", conversationBridge.conversations[id])
        }
        // 中途超界插入的第 6 条会话未被半途混入本轮快照，而是在下一轮增量同步中精准补齐
        assertFalse(lateConv.id in conversationBridge.conversations)

        val nextSummary = engine.sync(defaultConfig).getOrThrow()
        assertEquals(1, nextSummary.pulledConversations)
        assertNotNull(conversationBridge.conversations[lateConv.id])
    }

    @Test
    fun `offline concurrent conversation edit preserves both copies in sync_conflicts and supports fork recovery`() = runBlocking {
        val sharedConv = sampleConversation(title = "项目周报讨论", userText = "初始问题")
        server.seedRemoteEntity(
            "conversation",
            sharedConv.id.toString(),
            "upsert",
            JsonInstant.encodeToString(sharedConv),
        )

        // 1. 手机端首次同步对齐基线
        engine.sync(defaultConfig).getOrThrow()
        val initialState = syncV2Dao.getEntityState("conversation", sharedConv.id.toString())!!
        val baseRev = initialState.lastSyncedRevision
        assertTrue(baseRev > 0L)

        // 2. 手机离线连续编辑同一会话两次：验证 Outbox 始终保留最初分叉时的 baseRevision
        val phoneEdit1 = sharedConv.copy(
            title = "项目周报讨论",
            messageNodes = sharedConv.messageNodes + MessageNode(
                messages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("手机离线草稿 1"))))
            ),
            updateAt = Instant.ofEpochMilli(1700000100000L),
        )
        conversationBridge.saveUserLocalEdit(phoneEdit1)
        val outboxAfter1 = syncV2Dao.getOutboxEntry("conversation", sharedConv.id.toString())!!
        assertEquals(baseRev, outboxAfter1.baseRevision)

        val phoneEdit2 = phoneEdit1.copy(
            messageNodes = phoneEdit1.messageNodes + MessageNode(
                messages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("手机离线最终补充 2"))))
            ),
            updateAt = Instant.ofEpochMilli(1700000200000L),
        )
        conversationBridge.saveUserLocalEdit(phoneEdit2)
        val outboxAfter2 = syncV2Dao.getOutboxEntry("conversation", sharedConv.id.toString())!!
        assertEquals("连续本地修改不得覆盖最初分叉 baseRevision", baseRev, outboxAfter2.baseRevision)

        // 3. 同一时间 Web 端在服务端修改了同一条会话，使服务端修订前进
        val webEdit = sharedConv.copy(
            title = "项目周报讨论（Web 已定稿）",
            messageNodes = sharedConv.messageNodes + MessageNode(
                messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Web 端生成的总结回复"))))
            ),
            updateAt = Instant.ofEpochMilli(1700000300000L),
        )
        server.seedRemoteEntity(
            "conversation",
            sharedConv.id.toString(),
            "upsert",
            JsonInstant.encodeToString(webEdit),
        )

        // 4. 手机恢复联网执行同步：不得静默丢弃手机端离线消息，必须产生双副本冲突记录
        val conflictSync = engine.sync(defaultConfig).getOrThrow()
        assertEquals(1, conflictSync.conflictsDetected)

        val unresolved = syncV2Dao.getUnresolvedConflicts()
        assertEquals(1, unresolved.size)
        val conflict = unresolved.single()
        assertTrue(conflict.localPayload.contains("手机离线最终补充 2"))
        assertTrue(conflict.serverPayload.contains("Web 端生成的总结回复"))
        assertEquals(1, server.serverConflicts.size)

        // 本地主会话当前对齐到云端权威版本
        assertEquals("项目周报讨论（Web 已定稿）", conversationBridge.conversations[sharedConv.id]?.title)

        // 5. 用户在冲突中心点击“另存为新会话”：手机离线副本完整恢复为独立新会话并同步上云
        val forked = engine.resolveConflictForkConversation(conflict.id, defaultConfig).getOrThrow()
        assertTrue(forked.title.contains("(冲突恢复副本)"))
        assertEquals(3, forked.messageNodes.size)
        assertTrue(syncV2Dao.getUnresolvedConflicts().isEmpty())
        assertTrue("服务端冲突记录应同步收到 ack", conflict.id in server.ackedConflictIds)

        val afterForkSync = engine.sync(defaultConfig).getOrThrow()
        assertEquals(1, afterForkSync.pushedConversations)
        assertNotNull("另存的新会话必须已推送至服务端", server.entities["conversation" to forked.id.toString()])
    }

    @Test
    fun `invalid token 401 legacy 426 and mutation_id retry idempotency behave deterministically`() = runBlocking {
        // 1. 错误 Token 触发明确鉴权错误
        val badAuthRes = engine.sync(defaultConfig.copy(authToken = "wrong-token"))
        assertTrue(badAuthRes.isFailure)
        assertTrue(badAuthRes.exceptionOrNull()!!.message!!.contains("401"))

        // 2. 服务端返回 426 升级提示时向用户透传清晰中文引导
        server.forceUpgradeRequired426 = true
        val upgradeRes = engine.sync(defaultConfig)
        assertTrue(upgradeRes.isFailure)
        assertTrue(upgradeRes.exceptionOrNull()!!.message!!.contains("升级"))
        server.forceUpgradeRequired426 = false

        // 3. 基线同步后模拟“服务端已落库 mutation_id 但响应在回传途中网络中断”，验证下次重推幂等 noop
        engine.sync(defaultConfig).getOrThrow()
        val conv = sampleConversation(title = "幂等重推会话", userText = "断网重试不重复")
        conversationBridge.saveUserLocalEdit(conv)
        val queuedMutationId = syncV2Dao.getOutboxEntry("conversation", conv.id.toString())!!.mutationId

        server.dropPushResponseAfterCommitOnce = true
        val droppedSync = engine.sync(defaultConfig)
        assertTrue("响应丢失应返回失败并保留本地 outbox 条目", droppedSync.isFailure)
        assertEquals(
            "重推前 mutationId 必须保持不变以命中服务端幂等表",
            queuedMutationId,
            syncV2Dao.getOutboxEntry("conversation", conv.id.toString())?.mutationId,
        )
        val revAfterFirstCommit = server.currentRevision

        // 再次同步：服务端识别相同 mutation_id 返回 noop，版本号不重复自增，outbox 正常清空
        val retrySync = engine.sync(defaultConfig).getOrThrow()
        assertEquals(0, retrySync.conflictsDetected)
        assertEquals(revAfterFirstCommit, server.currentRevision)
        assertTrue(syncV2Dao.getAllOutboxOrdered().isEmpty())
    }

    @Test
    fun `active streaming conversation stages remote update in sync_inbox_pending until idle`() = runBlocking {
        val conv = sampleConversation(title = "正在流式生成的对话", userText = "旧提问")
        server.seedRemoteEntity("conversation", conv.id.toString(), "upsert", JsonInstant.encodeToString(conv))
        engine.sync(defaultConfig).getOrThrow()

        // 标记该会话正在本地流式生成中
        conversationBridge.busyConversationIds.add(conv.id)

        // 云端此时产生了该会话的新版本
        val remoteUpdated = conv.copy(
            title = "云端更新后的标题",
            messageNodes = conv.messageNodes + MessageNode(
                messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("远端新回复"))))
            ),
        )
        server.seedRemoteEntity(
            "conversation",
            conv.id.toString(),
            "upsert",
            JsonInstant.encodeToString(remoteUpdated),
        )

        // 生成期间同步：不得打断/覆盖本地活跃会话，而是安全暂存在 sync_inbox_pending
        engine.sync(defaultConfig).getOrThrow()
        assertEquals("正在流式生成的对话", conversationBridge.conversations[conv.id]?.title)
        assertEquals(1, syncV2Dao.getAllInboxPending().size)

        // 流式生成结束后再次同步：自动冲刷暂存队列并清空 sync_inbox_pending
        conversationBridge.busyConversationIds.remove(conv.id)
        engine.sync(defaultConfig).getOrThrow()
        assertTrue(syncV2Dao.getAllInboxPending().isEmpty())
        assertEquals("云端更新后的标题", conversationBridge.conversations[conv.id]?.title)
        assertEquals(2, conversationBridge.conversations[conv.id]?.messageNodes?.size)
    }

    @Test
    fun `three consecutive syncs preserve all media creation sessions nodes and records without cascade loss`() = runBlocking {
        // 在本地构造包含 1 个会话、2 个节点、3 条版本记录的完整生图树
        val session = MediaCreationSessionEntity(
            id = "ms-1",
            title = "赛博手表壁纸创作",
            draft = """{"prompt":"watch face","assets":[]}""",
            createAt = 1700000000000L,
            updateAt = 1700000001000L,
        )
        val node1 = MediaCreationNodeEntity(id = "mn-1", sessionId = "ms-1", selectedRecordId = "mr-2", createAt = 1700000001000L)
        val node2 = MediaCreationNodeEntity(id = "mn-2", sessionId = "ms-1", selectedRecordId = "mr-3", createAt = 1700000002000L)
        val rec1 = sampleMediaRecord(id = "mr-1", sessionId = "ms-1", nodeId = "mn-1", prompt = "第一版草图")
        val rec2 = sampleMediaRecord(id = "mr-2", sessionId = "ms-1", nodeId = "mn-1", prompt = "第一版精修")
        val rec3 = sampleMediaRecord(id = "mr-3", sessionId = "ms-1", nodeId = "mn-2", prompt = "第二版构图")

        mediaDao.insertSession(session)
        mediaDao.upsertNode(node1)
        mediaDao.upsertNode(node2)
        mediaDao.upsertRecord(rec1)
        mediaDao.upsertRecord(rec2)
        mediaDao.upsertRecord(rec3)

        // 第 1 次同步：全量拓扑推送上云
        engine.sync(defaultConfig).getOrThrow()
        assertEquals(1, mediaDao.getAllSessions().size)
        assertEquals(2, mediaDao.getAllNodes().size)
        assertEquals(3, mediaDao.getAllRecords().size)

        // 模拟服务端更新了父表 media_session 的标题/草稿（过去 REPLACE 会触发 ON DELETE CASCADE 删光全部子节点与记录！）
        val updatedSessionPayload = JsonInstant.encodeToString(
            MediaSessionSyncV2Payload.fromEntity(session.copy(title = "赛博手表壁纸创作（已归档）", updateAt = 1700000099000L))
        )
        server.seedRemoteEntity("media_session", "ms-1", "upsert", updatedSessionPayload)

        // 第 2 次与第 3 次连续同步：验证父表 @Upsert 原地更新，子表 2 个节点与 3 条记录 100% 完好无损！
        engine.sync(defaultConfig).getOrThrow()
        engine.sync(defaultConfig).getOrThrow()

        assertEquals("赛博手表壁纸创作（已归档）", mediaDao.getSession("ms-1")?.title)
        assertEquals(2, mediaDao.getAllNodes().size)
        assertEquals(3, mediaDao.getAllRecords().size)
        assertNotNull(mediaDao.getRecord("mr-1"))
        assertNotNull(mediaDao.getRecord("mr-2"))
        assertNotNull(mediaDao.getRecord("mr-3"))
    }

    @Test
    fun `attachment download queues while syncFiles is disabled and atomically downloads when enabled`() = runBlocking {
        val imageBytes = "FAKE_JPEG_BINARY_PAYLOAD_2026_RIKKA".toByteArray(Charsets.UTF_8)
        server.files["upload/chart_v2.jpg"] = imageBytes

        val remoteConv = Conversation(
            id = Uuid.random(),
            assistantId = TEST_ASSISTANT_ID,
            title = "带图表的历史记录",
            messageNodes = listOf(
                MessageNode(
                    messages = listOf(
                        UIMessage(
                            role = MessageRole.USER,
                            parts = listOf(
                                UIMessagePart.Text("请看这张架构图"),
                                UIMessagePart.Image("upload/chart_v2.jpg"),
                            ),
                        )
                    )
                )
            ),
        )
        server.seedRemoteEntity(
            "conversation",
            remoteConv.id.toString(),
            "upsert",
            JsonInstant.encodeToString(remoteConv),
        )

        // 1. 先以 syncFiles = false 同步：对话立即入库，缺失图片登记进下载队列但暂不下载
        val textOnlySummary = engine.sync(defaultConfig.copy(syncFiles = false)).getOrThrow()
        assertEquals(1, textOnlySummary.pulledConversations)
        assertEquals(0, textOnlySummary.syncedFiles)
        val targetFile = File(filesDir, "upload/chart_v2.jpg")
        assertFalse(targetFile.exists())
        assertEquals(1, syncV2Dao.getFileQueueByDirection("download").size)

        // 2. 开启 syncFiles = true 再次同步：从队列自动原子补拉附件，且不残留任何 .tmp 临时文件
        val withFilesSummary = engine.sync(defaultConfig.copy(syncFiles = true)).getOrThrow()
        assertEquals(1, withFilesSummary.syncedFiles)
        assertTrue(targetFile.isFile)
        assertArrayEquals(imageBytes, targetFile.readBytes())
        assertTrue(syncV2Dao.getFileQueueByDirection("download").isEmpty())
        val leftoverTmp = targetFile.parentFile!!.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }
        assertTrue("原子下载完成后目录内不得残留 .tmp 文件", leftoverTmp.isEmpty())
    }

    @Test
    fun `global and assistant memory sync without attachments and survive deletes`() = runBlocking {
        engine.sync(defaultConfig).getOrThrow()
        val owner = Uuid.random().toString()
        memoryRepository.writeFile("__global__", "/preferences.md", "共享偏好：左侧翻页")
        memoryRepository.writeFile(owner, "/profile.md", "独立助手记忆")
        engine.sync(defaultConfig.copy(syncFiles = false)).getOrThrow()
        val globalId = MemorySyncPayload.entityId("__global__", "/preferences.md")
        val ownId = MemorySyncPayload.entityId(owner, "/profile.md")
        assertTrue(server.entities.getValue("memory_file" to globalId).payload.contains("共享偏好"))
        assertTrue(server.entities.getValue("memory_file" to ownId).payload.contains("独立助手记忆"))
        val remote = MemorySyncPayload(owner, "/topics/test.md", "云端新记忆", 123)
        server.seedRemoteEntity("memory_file", remote.entityId, "upsert", JsonInstant.encodeToString(remote))
        engine.sync(defaultConfig.copy(syncFiles = false)).getOrThrow()
        assertEquals("云端新记忆", memoryRepository.getFile(owner, remote.path)?.content)
        memoryRepository.deleteFile(owner, "/profile.md")
        engine.sync(defaultConfig).getOrThrow()
        assertEquals("delete", server.entities.getValue("memory_file" to ownId).operation)
        assertNotNull(memoryRepository.getFile("__global__", "/preferences.md"))
    }

    @Test
    fun `offline memory conflict retains both complete copies`() = runBlocking {
        val remote = MemorySyncPayload("__global__", "/profile.md", "初始记忆", 1)
        server.seedRemoteEntity("memory_file", remote.entityId, "upsert", JsonInstant.encodeToString(remote))
        engine.sync(defaultConfig).getOrThrow()
        memoryRepository.writeFile(remote.assistantId, remote.path, "手表离线记忆")
        server.seedRemoteEntity("memory_file", remote.entityId, "upsert", JsonInstant.encodeToString(remote.copy(content = "手机新增记忆", updatedAt = 2)))
        engine.sync(defaultConfig).getOrThrow()
        val conflict = syncV2Dao.getUnresolvedConflicts().single { it.entityType == "memory_file" }
        assertTrue(conflict.localPayload.contains("手表离线记忆"))
        assertTrue(conflict.serverPayload.contains("手机新增记忆"))
        assertEquals("手机新增记忆", memoryRepository.getFile(remote.assistantId, remote.path)?.content)
    }

    @Test
    fun `initial pull applies assistant references and preferences atomically across pages`() = runBlocking {
        val mode = me.rerere.rikkahub.data.model.PromptInjection.ModeInjection(id = Uuid.random(), name = "远端模式", content = "test")
        val assistant = me.rerere.rikkahub.data.model.Assistant(id = Uuid.random(), modeInjectionIds = setOf(mode.id))
        server.seedRemoteEntity("assistant", assistant.id.toString(), "upsert", JsonInstant.encodeToString(assistant))
        server.seedRemoteEntity("extra_setting", "mode_injections", "upsert", JsonInstant.encodeToString(listOf(mode)))
        server.seedRemoteEntity("extra_setting", SyncSettingsContract.PREFERENCES_KEY, "upsert", """{"displaySetting":{"messageJumperOnLeft":true}}""")
        engine.sync(defaultConfig).getOrThrow()
        val settings = settingsStore.awaitLoaded()
        assertTrue(settings.displaySetting.messageJumperOnLeft)
        assertEquals(setOf(mode.id), settings.assistants.single { it.id == assistant.id }.modeInjectionIds)
    }

    @Test
    fun `upgrade replays memory ignored by an older client cursor`() = runBlocking {
        val remote = MemorySyncPayload("__global__", "/profile.md", "旧客户端没处理的记忆", 1)
        server.seedRemoteEntity("memory_file", remote.entityId, "upsert", JsonInstant.encodeToString(remote))
        syncV2Dao.upsertMeta(SyncMetaEntity("server_id", server.serverId))
        syncV2Dao.upsertMeta(SyncMetaEntity("cursor", server.currentRevision.toString()))
        syncV2Dao.upsertMeta(SyncMetaEntity("v2_reconciled", "1"))
        syncV2Dao.upsertEntityState(SyncEntityStateEntity(
            entityType = "memory_file", entityId = remote.entityId,
            lastSyncedRevision = server.currentRevision,
            lastSyncedHash = CanonicalSyncHash.computeSemanticHash("memory_file", JsonInstant.encodeToString(remote)),
            deleted = false, updatedAt = 1,
        ))
        engine.sync(defaultConfig).getOrThrow()
        assertEquals("upsert", server.entities.getValue("memory_file" to remote.entityId).operation)
        assertEquals(remote.content, memoryRepository.getFile(remote.assistantId, remote.path)?.content)
        assertEquals(SyncSettingsContract.COVERAGE_VERSION, syncV2Dao.getMetaValue("sync_coverage_version"))
    }

    @Test
    fun `memory edited while push is in flight keeps its newer mutation`() = runBlocking {
        engine.sync(defaultConfig).getOrThrow()
        memoryRepository.writeFile("__global__", "/preferences.md", "已发出的版本")
        server.onBeforePush = {
            runBlocking { memoryRepository.writeFile("__global__", "/preferences.md", "同步期间的新版本") }
        }
        engine.sync(defaultConfig).getOrThrow()
        val identity = MemorySyncPayload.entityId("__global__", "/preferences.md")
        assertTrue(syncV2Dao.getOutboxEntry("memory_file", identity)!!.payload.contains("同步期间的新版本"))
        assertEquals("同步期间的新版本", memoryRepository.getFile("__global__", "/preferences.md")?.content)
        engine.sync(defaultConfig).getOrThrow()
        assertTrue(server.entities.getValue("memory_file" to identity).payload.contains("同步期间的新版本"))
        assertTrue(syncV2Dao.getAllOutboxOrdered().isEmpty())
    }

    @Test
    fun `invalid preferences are not acknowledged as synced`() = runBlocking {
        server.seedRemoteEntity("extra_setting", SyncSettingsContract.PREFERENCES_KEY, "upsert", """{"displaySetting":{"messageJumperOnLeft":"invalid"}}""")
        assertTrue(engine.sync(defaultConfig).isFailure)
        assertEquals(null, syncV2Dao.getEntityState("extra_setting", SyncSettingsContract.PREFERENCES_KEY))
        server.seedRemoteEntity("extra_setting", SyncSettingsContract.PREFERENCES_KEY, "upsert", """{"displaySetting":{"messageJumperOnLeft":true}}""")
        engine.sync(defaultConfig).getOrThrow()
        assertTrue(settingsStore.awaitLoaded().displaySetting.messageJumperOnLeft)
    }

    private fun sampleConversation(title: String, userText: String): Conversation = Conversation(
        id = Uuid.random(),
        assistantId = TEST_ASSISTANT_ID,
        title = title,
        messageNodes = listOf(
            MessageNode(
                messages = listOf(
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Text(userText)),
                    )
                )
            )
        ),
        createAt = Instant.ofEpochMilli(1700000000000L),
        updateAt = Instant.ofEpochMilli(1700000005000L),
    )

    private fun sampleMediaRecord(
        id: String,
        sessionId: String,
        nodeId: String,
        prompt: String,
    ): MediaCreationRecordEntity = MediaCreationRecordEntity(
        id = id,
        sessionId = sessionId,
        nodeId = nodeId,
        providerId = "openai-media",
        providerName = "OpenAI",
        modelId = "gpt-image-1",
        kind = "IMAGE",
        prompt = prompt,
        params = "{}",
        inputs = "[]",
        status = "SUCCEEDED",
        taskId = null,
        error = null,
        outputs = """[{"path":"media_creation/$sessionId/$id/0.png","mime":"image/png"}]""",
        createAt = 1700000001000L,
        updateAt = 1700000002000L,
    )
}

// ==================== 内存测试桩实现 ====================

private class FakeSyncConversationBridge(
    private val recorder: SyncMutationRecorder,
    private val filesDir: File,
) : SyncConversationBridge {
    val conversations = LinkedHashMap<Uuid, Conversation>()
    val busyConversationIds = LinkedHashSet<Uuid>()

    suspend fun saveUserLocalEdit(conversation: Conversation) {
        conversations[conversation.id] = conversation
        val portableJson = JsonInstant.encodeToString(conversation.toPortableAttachmentPaths(filesDir))
        recorder.recordUpsert("conversation", conversation.id.toString(), portableJson)
    }

    override fun isConversationBusyForSync(convId: Uuid): Boolean = convId in busyConversationIds

    override suspend fun getAllConversationIds(): List<String> = conversations.keys.map { it.toString() }

    override suspend fun loadConversation(convId: Uuid): Conversation? = conversations[convId]

    override suspend fun applyRemoteSyncedConversation(conversation: Conversation) {
        conversations[conversation.id] = conversation
    }

    override suspend fun applyRemoteDeletedConversation(convId: Uuid) {
        conversations.remove(convId)
    }

    override suspend fun insertForkedConversation(conversation: Conversation) {
        saveUserLocalEdit(conversation)
    }

    override suspend fun deleteFolder(folderId: Uuid) = Unit

    override suspend fun purgeLegacyTombstones(olderThanMs: Long) = Unit
}

private class FakeSyncV2Dao : SyncV2Dao {
    private val metaMap = MutableStateFlow<Map<String, String>>(emptyMap())
    private val entityStates = LinkedHashMap<Pair<String, String>, SyncEntityStateEntity>()
    private val outboxFlow = MutableStateFlow<List<SyncOutboxEntity>>(emptyList())
    private val inboxPending = LinkedHashMap<Pair<String, String>, SyncInboxPendingEntity>()
    private val conflictsFlow = MutableStateFlow<List<SyncConflictEntity>>(emptyList())
    private val fileQueueFlow = MutableStateFlow<List<SyncFileQueueEntity>>(emptyList())
    private var nextOutboxId = 1L

    override suspend fun getMetaValue(key: String): String? = metaMap.value[key]
    override fun observeMetaValue(key: String): Flow<String?> = metaMap.map { it[key] }
    override suspend fun upsertMeta(meta: SyncMetaEntity) {
        metaMap.value = metaMap.value + (meta.key to meta.value)
    }
    override suspend fun deleteMeta(key: String) {
        metaMap.value = metaMap.value - key
    }

    override suspend fun getEntityState(entityType: String, entityId: String): SyncEntityStateEntity? =
        entityStates[entityType to entityId]

    override suspend fun getEntityStatesByType(entityType: String): List<SyncEntityStateEntity> =
        entityStates.values.filter { it.entityType == entityType }

    override suspend fun getAllEntityStates(): List<SyncEntityStateEntity> = entityStates.values.toList()

    override suspend fun upsertEntityState(state: SyncEntityStateEntity) {
        entityStates[state.entityType to state.entityId] = state
    }

    override suspend fun upsertEntityStates(states: List<SyncEntityStateEntity>) {
        states.forEach { upsertEntityState(it) }
    }

    override suspend fun clearAllEntityStates() {
        entityStates.clear()
    }

    override suspend fun getAllOutboxOrdered(): List<SyncOutboxEntity> =
        outboxFlow.value.sortedBy { it.id }

    override fun observeOutboxCount(): Flow<Int> = outboxFlow.map { it.size }

    override suspend fun getOutboxEntry(entityType: String, entityId: String): SyncOutboxEntity? =
        outboxFlow.value.firstOrNull { it.entityType == entityType && it.entityId == entityId }

    override suspend fun insertOrReplaceOutbox(entry: SyncOutboxEntity): Long {
        val assignedId = if (entry.id != 0L) entry.id else nextOutboxId++
        val saved = entry.copy(id = assignedId)
        outboxFlow.value = outboxFlow.value
            .filterNot { (it.entityType == entry.entityType && it.entityId == entry.entityId) || it.id == assignedId } + saved
        return assignedId
    }

    override suspend fun deleteOutboxById(id: Long) {
        outboxFlow.value = outboxFlow.value.filterNot { it.id == id }
    }

    override suspend fun deleteOutboxByMutationId(mutationId: String) {
        outboxFlow.value = outboxFlow.value.filterNot { it.mutationId == mutationId }
    }

    override suspend fun deleteOutboxByEntity(entityType: String, entityId: String) {
        outboxFlow.value = outboxFlow.value.filterNot { it.entityType == entityType && it.entityId == entityId }
    }

    override suspend fun markOutboxRetry(id: Long, error: String?) {
        outboxFlow.value = outboxFlow.value.map {
            if (it.id == id) it.copy(retryCount = it.retryCount + 1, lastError = error) else it
        }
    }

    override suspend fun getAllInboxPending(): List<SyncInboxPendingEntity> =
        inboxPending.values.sortedBy { it.revision }

    override suspend fun upsertInboxPending(item: SyncInboxPendingEntity) {
        inboxPending[item.entityType to item.entityId] = item
    }

    override suspend fun deleteInboxPending(entityType: String, entityId: String) {
        inboxPending.remove(entityType to entityId)
    }

    override fun observeUnresolvedConflicts(): Flow<List<SyncConflictEntity>> =
        conflictsFlow.map { list -> list.filter { !it.resolved }.sortedByDescending { it.createdAt } }

    override fun observeUnresolvedConflictCount(): Flow<Int> =
        conflictsFlow.map { list -> list.count { !it.resolved } }

    override suspend fun getUnresolvedConflicts(): List<SyncConflictEntity> =
        conflictsFlow.value.filter { !it.resolved }.sortedByDescending { it.createdAt }

    override suspend fun getConflictById(id: String): SyncConflictEntity? =
        conflictsFlow.value.firstOrNull { it.id == id }

    override suspend fun upsertConflict(conflict: SyncConflictEntity) {
        conflictsFlow.value = conflictsFlow.value.filterNot { it.id == conflict.id } + conflict
    }

    override suspend fun markConflictResolved(id: String) {
        conflictsFlow.value = conflictsFlow.value.map {
            if (it.id == id) it.copy(resolved = true) else it
        }
    }

    override suspend fun getAllFileQueue(): List<SyncFileQueueEntity> =
        fileQueueFlow.value.sortedBy { it.updatedAt }

    override suspend fun getFileQueueByDirection(direction: String): List<SyncFileQueueEntity> =
        fileQueueFlow.value.filter { it.direction == direction }.sortedBy { it.updatedAt }

    override fun observePendingFileCount(): Flow<Int> = fileQueueFlow.map { it.size }

    override suspend fun upsertFileQueue(item: SyncFileQueueEntity) {
        fileQueueFlow.value = fileQueueFlow.value
            .filterNot { it.direction == item.direction && it.relativePath == item.relativePath } + item
    }

    override suspend fun upsertFileQueueAll(items: List<SyncFileQueueEntity>) {
        items.forEach { upsertFileQueue(it) }
    }

    override suspend fun deleteFileQueue(direction: String, relativePath: String) {
        fileQueueFlow.value = fileQueueFlow.value
            .filterNot { it.direction == direction && it.relativePath == relativePath }
    }

    override suspend fun markFileQueueRetry(direction: String, relativePath: String, error: String?, updatedAt: Long) {
        fileQueueFlow.value = fileQueueFlow.value.map {
            if (it.direction == direction && it.relativePath == relativePath) {
                it.copy(retryCount = it.retryCount + 1, lastError = error, updatedAt = updatedAt)
            } else {
                it
            }
        }
    }
}

private class FakeFolderDao : FolderDAO {
    private val map = LinkedHashMap<String, FolderEntity>()
    override fun getFoldersOfAssistant(assistantId: String): Flow<List<FolderEntity>> = MutableStateFlow(emptyList())
    override suspend fun getFolderById(id: String): FolderEntity? = map[id]
    override suspend fun getAllFolders(): List<FolderEntity> = map.values.toList()
    override suspend fun insert(folder: FolderEntity) {
        map[folder.id] = folder
    }
    override suspend fun update(folder: FolderEntity) {
        map[folder.id] = folder
    }
    override suspend fun rename(id: String, name: String) {
        map[id]?.let { map[id] = it.copy(name = name) }
    }
    override suspend fun delete(folder: FolderEntity) {
        map.remove(folder.id)
    }
    override suspend fun deleteById(id: String) {
        map.remove(id)
    }
}

private class FakeFavoriteDao : FavoriteDAO {
    private val map = LinkedHashMap<String, FavoriteEntity>()
    override suspend fun upsert(favorite: FavoriteEntity) {
        map[favorite.id] = favorite
    }
    override fun listAll(): Flow<List<FavoriteEntity>> = MutableStateFlow(emptyList())
    override suspend fun getAllFavorites(): List<FavoriteEntity> = map.values.toList()
    override fun listByType(type: String): Flow<List<FavoriteEntity>> = MutableStateFlow(emptyList())
    override suspend fun getRefKeysByType(type: String): List<String> = emptyList()
    override suspend fun getFavoriteNodeIdsOfConversation(conversationId: String): List<String> = emptyList()
    override suspend fun getByRefKey(refKey: String): FavoriteEntity? = map.values.firstOrNull { it.refKey == refKey }
    override suspend fun existsByRefKey(refKey: String): Boolean = map.values.any { it.refKey == refKey }
    override suspend fun deleteByRefKey(refKey: String): Int = 0
    override suspend fun deleteById(id: String): Int = if (map.remove(id) != null) 1 else 0
}

/**
 * 严格模拟 SQLite 外键级联行为的内存 `MediaCreationDAO`：
 * - `insertSession` 对应 `@Upsert`（原地更新父行，绝不触发级联删除）；
 * - `deleteSession` / `deleteNode` 对应 SQL `DELETE`（严格触发 `ON DELETE CASCADE` 删除子表记录）。
 */
private class FakeMediaCreationDao : MediaCreationDAO {
    private val sessions = LinkedHashMap<String, MediaCreationSessionEntity>()
    private val nodes = LinkedHashMap<String, MediaCreationNodeEntity>()
    private val records = LinkedHashMap<String, MediaCreationRecordEntity>()

    override fun observeSessions(activeStatuses: List<String>, succeededStatus: String): Flow<List<MediaCreationSessionWithStats>> =
        MutableStateFlow(emptyList())
    override fun observeSession(id: String): Flow<MediaCreationSessionEntity?> = MutableStateFlow(sessions[id])
    override suspend fun getSession(id: String): MediaCreationSessionEntity? = sessions[id]
    override suspend fun getLatestSession(): MediaCreationSessionEntity? = sessions.values.maxByOrNull { it.updateAt }
    override suspend fun getEmptySession(): MediaCreationSessionEntity? = null

    override suspend fun insertSession(session: MediaCreationSessionEntity) {
        // @Upsert 语义：原地插入或更新，保留关联子表
        sessions[session.id] = session
    }

    override suspend fun getAllSessions(): List<MediaCreationSessionEntity> = sessions.values.toList()
    override suspend fun updateSession(session: MediaCreationSessionEntity) {
        sessions[session.id] = session
    }
    override suspend fun renameSession(id: String, title: String) {
        sessions[id]?.let { sessions[id] = it.copy(title = title) }
    }
    override suspend fun updateDraft(id: String, draft: String) {
        sessions[id]?.let { sessions[id] = it.copy(draft = draft) }
    }

    override suspend fun deleteSession(id: String) {
        // ON DELETE CASCADE 语义
        sessions.remove(id)
        val removedNodeIds = nodes.values.filter { it.sessionId == id }.map { it.id }.toSet()
        removedNodeIds.forEach { nodes.remove(it) }
        records.entries.removeIf { it.value.sessionId == id || it.value.nodeId in removedNodeIds }
    }

    override fun nodePagingSource(sessionId: String): PagingSource<Int, MediaCreationRecordWithVersion> =
        throw UnsupportedOperationException()
    override suspend fun getNodePosition(id: String): Int = 0
    override suspend fun getSelectedRecordsOfSession(sessionId: String): List<MediaCreationRecordEntity> = emptyList()
    override suspend fun getNode(id: String): MediaCreationNodeEntity? = nodes[id]
    override suspend fun getAllNodes(): List<MediaCreationNodeEntity> = nodes.values.toList()
    override suspend fun insertNode(node: MediaCreationNodeEntity) {
        check(node.sessionId in sessions) { "FK violation: missing session ${node.sessionId}" }
        nodes[node.id] = node
    }
    override suspend fun upsertNode(node: MediaCreationNodeEntity) {
        check(node.sessionId in sessions) { "FK violation: missing session ${node.sessionId}" }
        nodes[node.id] = node
    }
    override suspend fun selectRecord(id: String, recordId: String) {
        nodes[id]?.let { nodes[id] = it.copy(selectedRecordId = recordId) }
    }
    override suspend fun deleteNode(id: String) {
        nodes.remove(id)
        records.entries.removeIf { it.value.nodeId == id }
    }

    override suspend fun getVersionIds(nodeId: String): List<String> = emptyList()
    override suspend fun getRecord(id: String): MediaCreationRecordEntity? = records[id]
    override suspend fun getAllRecords(): List<MediaCreationRecordEntity> = records.values.toList()
    override suspend fun getRecordsOfSession(sessionId: String): List<MediaCreationRecordEntity> =
        records.values.filter { it.sessionId == sessionId }
    override suspend fun getRecordsByStatus(statuses: List<String>): List<MediaCreationRecordEntity> = emptyList()
    override fun observeRecentRecords(status: String, limit: Int): Flow<List<MediaCreationRecordEntity>> =
        MutableStateFlow(emptyList())
    override suspend fun getLatestRecordOfKind(kind: String): MediaCreationRecordEntity? = null
    override suspend fun insertRecord(record: MediaCreationRecordEntity) {
        check(record.sessionId in sessions && record.nodeId in nodes) { "FK violation" }
        records[record.id] = record
    }
    override suspend fun upsertRecord(record: MediaCreationRecordEntity) {
        check(record.sessionId in sessions && record.nodeId in nodes) { "FK violation" }
        records[record.id] = record
    }
    override suspend fun updateRecord(record: MediaCreationRecordEntity) {
        records[record.id] = record
    }
    override suspend fun deleteRecord(id: String) {
        records.remove(id)
    }
}

/**
 * 内存版 Sync V2 协议测试服务端拦截器（复刻 `backend/app/routes/sync.py` 核心修订状态机）。
 */
private class FakeSyncV2HttpServer(
    private val expectedToken: String,
    val serverId: String = "srv-unit-test-v2",
) : Interceptor {
    data class StoredEntity(
        val entityType: String,
        val entityId: String,
        val revision: Long,
        val operation: String,
        val payload: String,
        val contentHash: String,
        val updatedAt: Long,
    )

    var currentRevision: Long = 0L
    val entities = LinkedHashMap<Pair<String, String>, StoredEntity>()
    val appliedMutations = LinkedHashMap<String, Long>()
    val serverConflicts = LinkedHashMap<String, Pair<String, String>>()
    val ackedConflictIds = LinkedHashSet<String>()
    val files = LinkedHashMap<String, ByteArray>()

    var forceUpgradeRequired426: Boolean = false
    var dropPushResponseAfterCommitOnce: Boolean = false
    var onAfterFirstPullPage: (() -> Unit)? = null
    var onBeforePush: (() -> Unit)? = null

    @Synchronized
    fun seedRemoteEntity(entityType: String, entityId: String, operation: String, rawPayload: String): StoredEntity {
        currentRevision++
        val norm = if (operation == "delete") "" else CanonicalSyncHash.normalizeEntityPayload(entityType, rawPayload)
        val hash = CanonicalSyncHash.computeSemanticHash(entityType, norm)
        val stored = StoredEntity(
            entityType = entityType,
            entityId = entityId,
            revision = currentRevision,
            operation = operation,
            payload = norm,
            contentHash = hash,
            updatedAt = System.currentTimeMillis(),
        )
        entities[entityType to entityId] = stored
        return stored
    }

    @Synchronized
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val path = req.url.encodedPath

        if (forceUpgradeRequired426) {
            return jsonResponse(req, 426, """{"detail":"同步协议已升级至 Sync V2，请升级客户端"}""")
        }

        val auth = req.header("Authorization").orEmpty()
        if (auth != "Bearer $expectedToken") {
            return jsonResponse(req, 401, """{"detail":"无效的同步令牌"}""")
        }

        val bodyText = req.body?.let { body ->
            val buf = Buffer()
            body.writeTo(buf)
            buf.readUtf8()
        }.orEmpty()

        return when {
            path == "/api/sync/v2/info" -> {
                val dto = SyncV2InfoResponseDto(
                    supported_entity_types = listOf("memory_file"),
                    protocol_version = 2,
                    server_id = serverId,
                    current_revision = currentRevision,
                    server_time = System.currentTimeMillis(),
                    unresolved_conflicts = serverConflicts.size - ackedConflictIds.size,
                )
                jsonResponse(req, 200, JsonInstant.encodeToString(dto))
            }

            path == "/api/sync/v2/pull" -> {
                val pullReq = JsonInstant.decodeFromString<SyncV2PullRequestDto>(bodyText)
                val upper = pullReq.upper_bound_revision ?: currentRevision
                val matching = entities.values
                    .filter { it.revision > pullReq.cursor && it.revision <= upper }
                    .sortedBy { it.revision }
                val pageItems = matching.take(pullReq.limit)
                val hasMore = matching.size > pullReq.limit
                val nextCursor = if (hasMore) pageItems.last().revision else maxOf(pullReq.cursor, upper)

                val hook = onAfterFirstPullPage
                if (hook != null) {
                    onAfterFirstPullPage = null
                    hook.invoke()
                }

                val resp = SyncV2PullResponseDto(
                    server_id = serverId,
                    current_revision = currentRevision,
                    upper_bound_revision = upper,
                    next_cursor = nextCursor,
                    has_more = hasMore,
                    reset_required = false,
                    server_time = System.currentTimeMillis(),
                    changes = pageItems.map {
                        SyncV2ChangeItemDto(
                            revision = it.revision,
                            entity_type = it.entityType,
                            entity_id = it.entityId,
                            operation = it.operation,
                            payload = it.payload,
                            content_hash = it.contentHash,
                            updated_at = it.updatedAt,
                        )
                    },
                )
                jsonResponse(req, 200, JsonInstant.encodeToString(resp))
            }

            path == "/api/sync/v2/push" -> {
                val hook = onBeforePush
                onBeforePush = null
                hook?.invoke()
                val pushReq = JsonInstant.decodeFromString<SyncV2PushRequestDto>(bodyText)
                val results = mutableListOf<SyncV2MutationResultDto>()
                for (m in pushReq.mutations) {
                    val prevRev = appliedMutations[m.mutation_id]
                    if (prevRev != null) {
                        results.add(
                            SyncV2MutationResultDto(
                                mutation_id = m.mutation_id,
                                entity_type = m.entity_type,
                                entity_id = m.entity_id,
                                status = "noop",
                                revision = prevRev,
                            )
                        )
                        continue
                    }

                    val key = m.entity_type to m.entity_id
                    val existing = entities[key]
                    val normPayload = if (m.operation == "delete") "" else CanonicalSyncHash.normalizeEntityPayload(m.entity_type, m.payload)
                    val hash = CanonicalSyncHash.computeSemanticHash(m.entity_type, normPayload)

                    if (existing != null && existing.contentHash == hash && existing.operation == m.operation) {
                        appliedMutations[m.mutation_id] = existing.revision
                        results.add(
                            SyncV2MutationResultDto(
                                mutation_id = m.mutation_id,
                                entity_type = m.entity_type,
                                entity_id = m.entity_id,
                                status = "noop",
                                revision = existing.revision,
                            )
                        )
                        continue
                    }

                    val expectedBase = existing?.revision ?: 0L
                    if (existing != null && m.base_revision != expectedBase) {
                        val cid = "conf-${Uuid.random()}"
                        serverConflicts[cid] = normPayload to existing.payload
                        results.add(
                            SyncV2MutationResultDto(
                                mutation_id = m.mutation_id,
                                entity_type = m.entity_type,
                                entity_id = m.entity_id,
                                status = "conflict",
                                revision = existing.revision,
                                conflict_id = cid,
                                server_operation = existing.operation,
                                server_payload = existing.payload,
                                server_hash = existing.contentHash,
                            )
                        )
                        continue
                    }

                    val stored = seedRemoteEntity(m.entity_type, m.entity_id, m.operation, normPayload)
                    appliedMutations[m.mutation_id] = stored.revision
                    results.add(
                        SyncV2MutationResultDto(
                            mutation_id = m.mutation_id,
                            entity_type = m.entity_type,
                            entity_id = m.entity_id,
                            status = "applied",
                            revision = stored.revision,
                        )
                    )
                }

                if (dropPushResponseAfterCommitOnce) {
                    dropPushResponseAfterCommitOnce = false
                    throw java.io.IOException("Simulated network drop after server commit")
                }

                val resp = SyncV2PushResponseDto(
                    server_id = serverId,
                    current_revision = currentRevision,
                    server_time = System.currentTimeMillis(),
                    results = results,
                )
                jsonResponse(req, 200, JsonInstant.encodeToString(resp))
            }

            path.startsWith("/api/sync/v2/conflicts/") && path.endsWith("/ack") -> {
                val cid = path.removePrefix("/api/sync/v2/conflicts/").removeSuffix("/ack")
                ackedConflictIds.add(cid)
                jsonResponse(req, 200, """{"ok":true}""")
            }

            path == "/api/sync/files/check" -> {
                val checkReq = JsonInstant.decodeFromString<FilesCheckRequestDto>(bodyText)
                val missing = checkReq.files.filter { item ->
                    val bytes = files[item.path]
                    bytes == null || bytes.size.toLong() != item.size
                }.map { it.path }
                jsonResponse(req, 200, JsonInstant.encodeToString(FilesCheckResponseDto(missing_paths = missing)))
            }

            path == "/api/sync/files/upload" -> {
                jsonResponse(req, 200, """{"ok":true}""")
            }

            path.startsWith("/api/sync/files/download/") -> {
                val rel = path.removePrefix("/api/sync/files/download/")
                val bytes = files[rel] ?: return jsonResponse(req, 404, """{"detail":"not found"}""")
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(bytes.toResponseBody("application/octet-stream".toMediaType()))
                    .build()
            }

            else -> jsonResponse(req, 404, """{"detail":"unknown test route $path"}""")
        }
    }

    private fun jsonResponse(req: okhttp3.Request, code: Int, body: String): Response =
        Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "ERR")
            .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()
}
