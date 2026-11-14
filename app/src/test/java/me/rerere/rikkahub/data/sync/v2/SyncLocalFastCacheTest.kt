package me.rerere.rikkahub.data.sync.v2

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.SyncServerConfig
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.db.entity.MemoryConsolidationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.entity.UsageRecordEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.sync.engine.FilesCheckRequestDto
import me.rerere.rikkahub.data.sync.engine.FilesCheckResponseDto
import me.rerere.rikkahub.data.sync.engine.IncrementalSyncEngine
import me.rerere.rikkahub.data.sync.engine.SyncTombstoneManager
import me.rerere.rikkahub.data.sync.engine.SyncV2ChangeItemDto
import me.rerere.rikkahub.data.sync.engine.SyncV2InfoResponseDto
import me.rerere.rikkahub.data.sync.engine.SyncV2MutationResultDto
import me.rerere.rikkahub.data.sync.engine.SyncV2PullRequestDto
import me.rerere.rikkahub.data.sync.engine.SyncV2PullResponseDto
import me.rerere.rikkahub.data.sync.engine.SyncV2PushRequestDto
import me.rerere.rikkahub.data.sync.engine.SyncV2PushResponseDto
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class SyncLocalFastCacheTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val databases = mutableListOf<AppDatabase>()
    private val scopes = mutableListOf<CoroutineScope>()

    private fun createEnv(name: String): Triple<Context, AppDatabase, File> {
        val root = tempFolder.newFolder(name)
        val app = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(app) {
            override fun getFilesDir(): File = root
            override fun getSharedPreferences(key: String, mode: Int) = app.getSharedPreferences("$name-$key", mode)
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        SyncTombstoneManager.createTables(db.openHelper.writableDatabase)
        databases.add(db)
        return Triple(context, db, root)
    }

    @After
    fun tearDown() {
        runBlocking {
            scopes.forEach { it.coroutineContext.job.cancelAndJoin() }
        }
        databases.forEach { it.close() }
    }

    @Test
    fun `sqlite triggers mark dirty entities and CAS clear preserves concurrent edits`() = runBlocking {
        val (_, db, _) = createEnv("dirty-cas")
        val cache = SyncLocalFastCache(db)

        // 触发器应在首次访问时自动建好
        assertTrue(cache.getDirtyEntries().isEmpty())

        val convId = Uuid.random().toString()
        db.conversationDao().insert(
            ConversationEntity(
                id = convId,
                assistantId = Uuid.random().toString(),
                title = "初始标题",
                nodes = "[]",
                createAt = 1000L,
                updateAt = 1000L,
                chatSuggestions = "[]",
                isPinned = false,
            )
        )
        val firstSnapshot = cache.getDirtyEntries()
        assertEquals(1, firstSnapshot.size)
        assertEquals("conversation" to convId, firstSnapshot[0].entityType to firstSnapshot[0].entityId)
        assertEquals(1L, firstSnapshot[0].dirtySeq)

        // 模拟在校验期间发生一次并发修改（message_node 插入触发 dirty_seq 递增）
        db.messageNodeDao().insert(
            MessageNodeEntity(
                id = Uuid.random().toString(),
                conversationId = convId,
                nodeIndex = 0,
                messages = JsonInstant.encodeToString(
                    listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi"))))
                ),
                selectIndex = 0,
            )
        )

        // 旧序号的 CAS 清理不得抹掉并发产生的新脏标记
        cache.clearDirtyEntries(firstSnapshot)
        val afterCas = cache.getDirtyEntries()
        assertEquals(1, afterCas.size)
        assertEquals(2L, afterCas[0].dirtySeq)

        // 最新序号的 CAS 清理成功移除
        cache.clearDirtyEntries(afterCas)
        assertTrue(cache.getDirtyEntries().isEmpty())

        val genMedia = GenMediaEntity(
            path = "images/out.png",
            modelId = "img-1",
            prompt = "test",
            createAt = 200L,
        )
        db.genMediaDao().insert(genMedia)
        val genMediaDirty = cache.getDirtyEntries().single()
        assertEquals("gen_media" to genMedia.syncId, genMediaDirty.entityType to genMediaDirty.entityId)
    }

    @Test
    fun `persisted file cache survives SyncCoverageStore restart and matches snapshotEntity`() = runBlocking {
        val (context, db, filesDir) = createEnv("file-cache-restart")
        val assetFile = File(filesDir, "upload/sample.txt").apply {
            parentFile!!.mkdirs()
            writeText("hello persistent cache")
        }
        db.usageRecordDao().insert(
            UsageRecordEntity(
                createdAt = 10L,
                source = "CHAT",
                providerId = "p1",
                modelId = "m1",
                modelName = "gpt-test",
                assistantId = null,
                conversationId = null,
                promptTokens = 5,
                completionTokens = 7,
                cachedTokens = 0,
            )
        )

        val store1 = SyncCoverageStore(context, db)
        val fullSnapshot = store1.snapshot()
        val cached = SyncLocalFastCache(db).getFileCache("upload/sample.txt")
        assertNotNull(cached)
        assertEquals(assetFile.length(), cached!!.size)
        assertTrue(cached.blobPath.startsWith("sync_blobs/"))

        // 模拟手表冷启动后构造全新 SyncCoverageStore 实例，直接命中持久化的 sync_file_cache
        val store2 = SyncCoverageStore(context, db)
        val partialSnapshot = store2.snapshotFilesAndPrefs()
        val fileAssetKey = fullSnapshot.keys.single { it.first == "file_asset" }
        assertEquals(fullSnapshot[fileAssetKey], partialSnapshot[fileAssetKey])

        val usageKey = fullSnapshot.keys.single { it.first == "usage_record" }
        assertEquals(fullSnapshot[usageKey], store2.snapshotEntity("usage_record", usageKey.second))
    }

    @Test
    fun `incremental sync with Room dirty path detects direct DAO inserts and avoids redundant file check`() = runBlocking {
        val (context, db, filesDir) = createEnv("room-engine-e2e")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val appScope = me.rerere.rikkahub.AppScope()
        scopes.add(scope)
        scopes.add(appScope)

        val dsFile = File(tempFolder.root, "room_e2e_settings.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { dsFile }
        val settingsStore = SettingsStore(dataStore, scope)
        val recorder = SyncMutationRecorder(db, db.syncV2Dao()) { filesDir }
        val tombstones = SyncTombstoneManager(db)
        val convRepo = ConversationRepository(
            conversationDAO = db.conversationDao(),
            messageNodeDAO = db.messageNodeDao(),
            favoriteDAO = db.favoriteDao(),
            database = db,
            filesManager = FilesManager(
                context,
                me.rerere.rikkahub.data.repository.FilesRepository(db.managedFileDao(), recorder),
                appScope,
            ),
            messageFtsManager = me.rerere.rikkahub.data.db.fts.MessageFtsManager(db),
            syncTombstoneManager = tombstones,
            syncMutationRecorder = recorder,
        )
        val folderRepo = FolderRepository(db.folderDao(), db.conversationDao(), tombstones, recorder)
        val memoryRepo = MemoryRepository(db.memoryDao(), recorder)
        val mockServer = LightweightSyncServer()
        val engine = IncrementalSyncEngine(
            context = context,
            database = db,
            syncV2Dao = db.syncV2Dao(),
            syncMutationRecorder = recorder,
            settingsStore = settingsStore,
            memoryRepository = memoryRepo,
            conversationRepository = convRepo,
            folderRepository = folderRepo,
            conversationDAO = db.conversationDao(),
            folderDAO = db.folderDao(),
            messageNodeDAO = db.messageNodeDao(),
            favoriteDAO = db.favoriteDao(),
            mediaCreationDAO = db.mediaCreationDao(),
            syncTombstoneManager = tombstones,
            okHttpClient = OkHttpClient.Builder().addInterceptor(mockServer).build(),
            appScope = scope,
        )

        val config = SyncServerConfig(
            serverUrl = "http://sync.test.local:8765",
            authToken = "rk_test_token_123456",
            deviceName = "Watch-Test",
            syncFiles = true,
        )

        // 1. 准备本地文件并首次同步（基线对账 + 种子建立）
        File(filesDir, "upload/photo.png").apply {
            parentFile!!.mkdirs()
            writeBytes("png-bytes-1".toByteArray())
        }
        val convId = Uuid.random()
        db.conversationDao().insert(
            ConversationEntity(
                id = convId.toString(),
                assistantId = Uuid.random().toString(),
                title = "手表首条会话",
                nodes = "[]",
                createAt = 1000L,
                updateAt = 1000L,
                chatSuggestions = "[]",
                isPinned = false,
            )
        )
        db.messageNodeDao().insert(
            MessageNodeEntity(
                id = Uuid.random().toString(),
                conversationId = convId.toString(),
                nodeIndex = 0,
                messages = JsonInstant.encodeToString(
                    listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("你好"))))
                ),
                selectIndex = 0,
            )
        )

        val s1 = engine.sync(config).getOrThrow()
        assertEquals(1, s1.pushedConversations)
        assertTrue(mockServer.fileCheckCalls >= 1)
        val checksAfterFirstSync = mockServer.fileCheckCalls

        // 2. 无变更二次同步：进入快速脏路径，且已校验过的文件不得再次请求 /files/check
        engine.sync(config).getOrThrow()
        assertEquals(checksAfterFirstSync, mockServer.fileCheckCalls)

        // 3. 不经过 Repository 直接通过 DAO 插入 usage_record、genmediaentity、memory_consolidation，必须被触发器捕获并同步
        val usage = UsageRecordEntity(
            createdAt = 100L,
            source = "CHAT",
            providerId = null,
            modelId = null,
            modelName = "m",
            assistantId = null,
            conversationId = convId.toString(),
            promptTokens = 12,
            completionTokens = 8,
            cachedTokens = 0,
        )
        db.usageRecordDao().insert(usage)
        val gen = GenMediaEntity(
            path = "images/out.png",
            modelId = "img-1",
            prompt = "cat",
            createAt = 200L,
        )
        db.genMediaDao().insert(gen)
        db.memoryConsolidationDao().upsert(MemoryConsolidationEntity(convId.toString(), 999L))

        engine.sync(config).getOrThrow()
        assertNotNull(mockServer.entities["usage_record" to usage.syncId])
        assertNotNull(mockServer.entities["gen_media" to gen.syncId])
        assertNotNull(mockServer.entities["memory_progress" to convId.toString()])

        // 4. 直接通过 DAO 删除一条本地记录，快速路径的轻量 liveKeys 校验必须检测到本地删除
        db.conversationDao().deleteById(convId.toString())
        engine.sync(config).getOrThrow()
        assertEquals("delete", mockServer.entities["conversation" to convId.toString()]?.first)
    }

    private class LightweightSyncServer : Interceptor {
        val serverId = "srv-fast-test"
        var rev = 0L
        val entities = LinkedHashMap<Pair<String, String>, Pair<String, String>>()
        val history = mutableListOf<SyncV2ChangeItemDto>()
        val files = LinkedHashMap<String, ByteArray>()
        var fileCheckCalls = 0

        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val path = req.url.encodedPath
            val body = req.body?.let { b -> Buffer().apply { b.writeTo(this) }.readUtf8() }.orEmpty()

            return when {
                path == "/api/sync/v2/info" -> jsonResp(
                    req,
                    SyncV2InfoResponseDto(
                        protocol_version = 2,
                        supported_entity_types = listOf(
                            "conversation", "folder", "assistant", "provider", "favorite",
                            "media_session", "media_node", "media_record", "extra_setting", "memory_file",
                        ) + ExtendedSyncTypes.all,
                        server_id = serverId,
                        current_revision = rev,
                        server_time = System.currentTimeMillis(),
                    )
                )
                path == "/api/sync/v2/pull" -> {
                    val pullReq = JsonInstant.decodeFromString<SyncV2PullRequestDto>(body)
                    val upper = pullReq.upper_bound_revision ?: rev
                    val items = history.filter { it.revision > pullReq.cursor && it.revision <= upper }
                    jsonResp(
                        req,
                        SyncV2PullResponseDto(
                            server_id = serverId,
                            current_revision = rev,
                            upper_bound_revision = upper,
                            next_cursor = upper,
                            has_more = false,
                            changes = items,
                        )
                    )
                }
                path == "/api/sync/v2/push" -> {
                    val pushReq = JsonInstant.decodeFromString<SyncV2PushRequestDto>(body)
                    val results = pushReq.mutations.map { m ->
                        rev++
                        if (m.operation == "delete") {
                            entities[m.entity_type to m.entity_id] = "delete" to ""
                        } else {
                            entities[m.entity_type to m.entity_id] = "upsert" to m.payload
                        }
                        history.add(
                            SyncV2ChangeItemDto(
                                revision = rev,
                                entity_type = m.entity_type,
                                entity_id = m.entity_id,
                                operation = m.operation,
                                payload = m.payload,
                                content_hash = m.content_hash,
                                updated_at = System.currentTimeMillis(),
                            )
                        )
                        SyncV2MutationResultDto(
                            mutation_id = m.mutation_id,
                            entity_type = m.entity_type,
                            entity_id = m.entity_id,
                            status = "applied",
                            revision = rev,
                        )
                    }
                    jsonResp(
                        req,
                        SyncV2PushResponseDto(
                            server_id = serverId,
                            current_revision = rev,
                            server_time = System.currentTimeMillis(),
                            results = results,
                        )
                    )
                }
                path == "/api/sync/files/check" -> {
                    fileCheckCalls++
                    val checkReq = JsonInstant.decodeFromString<FilesCheckRequestDto>(body)
                    val missing = checkReq.files.filter { item ->
                        val existing = files[item.path]
                        existing == null || existing.size.toLong() != item.size
                    }.map { it.path }
                    jsonResp(req, FilesCheckResponseDto(missing_paths = missing))
                }
                path == "/api/sync/files/upload" -> {
                    val text = body
                    val pathMatch = Regex("""name="path"\r\n\r\n([^\r\n]+)""").find(text)?.groupValues?.get(1)
                    if (pathMatch != null) {
                        files[pathMatch] = ByteArray(0)
                    }
                    jsonRespRaw(req, """{"status":"ok"}""")
                }
                else -> jsonRespRaw(req, """{"status":"ok"}""")
            }
        }

        private inline fun <reified T> jsonResp(req: okhttp3.Request, value: T): Response =
            jsonRespRaw(req, JsonInstant.encodeToString(value))

        private fun jsonRespRaw(req: okhttp3.Request, jsonStr: String): Response =
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(jsonStr.toResponseBody("application/json; charset=utf-8".toMediaType()))
                .build()
    }
}
