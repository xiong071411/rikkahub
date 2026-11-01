package me.rerere.rikkahub.data.sync.v2

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.entity.UsageRecordEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.db.migrations.Migration_33_34
import me.rerere.rikkahub.data.model.SshWorkspaceSettings
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class SyncCoverageStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val databases = mutableListOf<AppDatabase>()

    private fun device(name: String): Triple<File, AppDatabase, SyncCoverageStore> {
        val root = temporary.newFolder(name)
        val app = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(app) {
            override fun getFilesDir(): File = root
            override fun getSharedPreferences(key: String, mode: Int) = app.getSharedPreferences("$name-$key", mode)
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        databases.add(db)
        val store = SyncCoverageStore(context, db, encrypt = { "$name:$it" }, decrypt = { it.removePrefix("$name:") })
        return Triple(root, db, store)
    }

    @After fun close() {
        databases.forEach { it.close() }
    }

    @Test
    fun `workspace configuration and ssh credentials round trip between independent devices`() = runBlocking {
        val (phoneFiles, phoneDb, phone) = device("phone")
        val (watchFiles, watchDb, watch) = device("watch")
        val id = Uuid.random().toString()
        phoneDb.workspaceDao().upsert(WorkspaceEntity(id, "本地项目", id, "READY", 1, 2, shellCompatibilityMode = true))
        watchDb.workspaceDao().upsert(WorkspaceEntity(id, "旧名称", "watch-root", "DISABLED", 1, 2))
        val privateFile = File(phoneFiles, "workspaces/$id/files/private.txt").apply {
            parentFile!!.mkdirs()
            writeText("phone only")
        }
        val watchPrivate = File(watchFiles, "workspaces/watch-root/files/private.txt").apply {
            parentFile!!.mkdirs()
            writeText("watch only")
        }
        val sshId = Uuid.random().toString()
        phoneDb.workspaceDao().upsert(WorkspaceEntity(
            sshId,
            "服务器",
            sshId,
            createdAt = 1,
            updatedAt = 2,
            type = "SSH",
            sshConfig = JsonInstant.encodeToString(SshWorkspaceSettings(
                "server",
                username = "me",
                remoteDir = "/srv",
                encryptedPassword = "phone:password",
                encryptedPrivateKey = "phone:key",
                encryptedPassphrase = "phone:phrase"
            ))
        ))
        val snapshot = phone.snapshot()
        assertTrue(snapshot.keys.all { it.first == "workspace" })
        snapshot.forEach { (key, value) -> watch.apply(key.first, key.second, value) }
        val local = watchDb.workspaceDao().getById(id)!!
        assertEquals("本地项目", local.name)
        assertEquals("watch-root", local.root)
        assertEquals("DISABLED", local.shellStatus)
        assertFalse(local.shellCompatibilityMode)
        assertEquals("watch:password", watchDb.workspaceDao().getById(sshId)!!.sshSettings()!!.encryptedPassword)
        assertEquals("watch:key", watchDb.workspaceDao().getById(sshId)!!.sshSettings()!!.encryptedPrivateKey)
        watch.apply("workspace", id, null)
        assertEquals("phone only", privateFile.readText())
        assertEquals("watch only", watchPrivate.readText())
    }

    @Test
    fun `skills wait for every blob and install nested resources as one bundle`() = runBlocking {
        val (sourceFiles, _, source) = device("source")
        val (targetFiles, targetDb, target) = device("target")
        File(sourceFiles, "skills/demo/SKILL.md").apply {
            parentFile!!.mkdirs()
            writeText("---\nname: demo\ndescription: demo\n---\nbody")
        }
        File(sourceFiles, "skills/demo/references/data.txt").apply {
            parentFile!!.mkdirs()
            writeText("nested")
        }
        val entry = source.snapshot().entries.single { it.key.first == "skill_bundle" }
        target.apply(entry.key.first, entry.key.second, entry.value)
        target.materializeResources()
        assertFalse(File(targetFiles, "skills/demo").exists())
        assertEquals(entry.value, target.snapshot()[entry.key])
        File(sourceFiles, "sync_blobs").copyRecursively(File(targetFiles, "sync_blobs"), overwrite = true)
        target.materializeResources()
        assertEquals("nested", File(targetFiles, "skills/demo/references/data.txt").readText())
        assertTrue(
            targetDb.syncCoverageDao().resource(entry.key.first, entry.key.second)!!.materializedHash.isNotBlank()
        )
        assertEquals(entry.value, target.snapshot()[entry.key])
        target.apply(entry.key.first, entry.key.second, null)
        assertFalse(File(targetFiles, "skills/demo").exists())
        assertFalse(target.snapshot().containsKey(entry.key))
    }

    @Test
    fun `remote file updates preserve local changes made while blobs are downloading`() = runBlocking {
        val (sourceFiles, _, source) = device("source")
        val (targetFiles, targetDb, target) = device("target")
        File(sourceFiles, "upload/data.txt").apply {
            parentFile!!.mkdirs()
            writeText("remote")
        }
        val local = File(targetFiles, "upload/data.txt").apply {
            parentFile!!.mkdirs()
            writeText("old")
        }
        val entry = source.snapshot().entries.single { it.key.first == "file_asset" }
        target.apply(entry.key.first, entry.key.second, entry.value)
        assertEquals(entry.value, target.snapshot()[entry.key])
        local.writeText("edited during download")
        File(sourceFiles, "sync_blobs").copyRecursively(File(targetFiles, "sync_blobs"), overwrite = true)
        target.materializeResources()
        assertEquals("remote", local.readText())
        val conflict = targetDb.syncV2Dao().getUnresolvedConflicts().single()
        val saved = JsonInstant.decodeFromString<SyncBlobFile>(conflict.localPayload)
        assertEquals("edited during download", File(targetFiles, saved.blobPath).readText())
    }

    @Test
    fun `same numeric local usage id does not overwrite a different devices record or duplicate a retry`() = runBlocking {
        val (_, sourceDb, source) = device("source")
        val (_, targetDb, target) = device("target")
        fun usage(tokens: Int) = UsageRecordEntity(createdAt = 1, source = "CHAT", providerId = null, modelId = null,
            modelName = null, assistantId = null, conversationId = null, promptTokens = tokens, completionTokens = 1, cachedTokens = 0)
        sourceDb.usageRecordDao().insert(usage(10))
        targetDb.usageRecordDao().insert(usage(20))
        val entry = source.snapshot().entries.single { it.key.first == "usage_record" }
        repeat(3) { target.apply(entry.key.first, entry.key.second, entry.value) }
        assertEquals(2, targetDb.syncCoverageDao().usage().size)
        assertEquals(30L, targetDb.usageRecordDao().getTotals().promptTokens)
    }

    @Test
    fun `schema 33 migrates without data loss and generates stable ids across restored backups`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val schema = javaClass.classLoader!!.getResourceAsStream(
            "me.rerere.rikkahub.data.db.AppDatabase/33.json"
        )!!.bufferedReader().use {
            JsonInstant.parseToJsonElement(
                it.readText()
            ).jsonObject.getValue("database").jsonObject
        }
        suspend fun migrate(name: String, firstId: Int): List<String> {
            val helper = FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                    .callback(object : SupportSQLiteOpenHelper.Callback(33) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            schema.getValue("entities").jsonArray.forEach { element ->
                                val entity = element.jsonObject
                                val table = entity.getValue("tableName").jsonPrimitive.content
                                db.execSQL(
                                    entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)
                                )
                                entity["indices"]?.jsonArray?.forEach {
                                    db.execSQL(
                                        it.jsonObject.getValue(
                                            "createSql"
                                        ).jsonPrimitive.content.replace("\${TABLE_NAME}", table)
                                    )
                                }
                            }
                            schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                            repeat(2) { offset ->
                                db.execSQL(
                                    "INSERT INTO usage_record (id, created_at, source, prompt_tokens, completion_tokens, cached_tokens) VALUES (?, 10, 'CHAT', 7, 2, 0)",
                                    arrayOf(firstId + offset)
                                )
                                db.execSQL(
                                    "INSERT INTO genmediaentity (id, path, model_id, prompt, create_at, type) VALUES (?, 'images/example.png', 'model', 'prompt', 10, 'image_generation')",
                                    arrayOf(firstId + offset)
                                )
                            }
                        }
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    }).build()
            )
            helper.writableDatabase
            helper.close()
            val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .allowMainThreadQueries().addMigrations(Migration_33_34).build()
            databases.add(db)
            val usage = db.syncCoverageDao().usage()
            val media = db.syncCoverageDao().generatedMedia()
            assertEquals(2, usage.size)
            assertEquals(2, media.size)
            assertEquals(2, usage.map { it.syncId }.toSet().size)
            assertEquals(14L, db.usageRecordDao().getTotals().promptTokens)
            assertTrue(db.syncCoverageDao().resources().isEmpty())
            return usage.map { it.syncId } + media.map { it.syncId }
        }
        assertEquals(migrate("old-phone", 1), migrate("restored-watch", 91))
    }

    @Test
    fun `first received deletion suppresses old local asset without removing referenced bytes`() = runBlocking {
        val (files, _, store) = device("device")
        val file = File(files, "upload/old.txt").apply {
            parentFile!!.mkdirs()
            writeText("old")
        }
        store.apply("file_asset", SyncCoverageStore.pathId("upload/old.txt"), null)
        assertTrue(file.isFile)
        assertTrue(store.snapshot().keys.none { it.first == "file_asset" })
    }

    @Test
    fun `confirming a selected conflict version waits for its bytes without reuploading old disk content`() = runBlocking {
        val (sourceFiles, _, source) = device("source")
        val (targetFiles, targetDb, target) = device("target")
        File(sourceFiles, "upload/data.txt").apply {
            parentFile!!.mkdirs()
            writeText("chosen conflict")
        }
        File(targetFiles, "upload/data.txt").apply {
            parentFile!!.mkdirs()
            writeText("old authoritative")
        }
        val entry = source.snapshot().entries.single { it.key.first == "file_asset" }
        target.apply(entry.key.first, entry.key.second, entry.value)
        target.confirmLocal(entry.key.first, entry.key.second, entry.value)
        assertTrue(targetDb.syncV2Dao().getAllOutboxOrdered().isEmpty())
        File(sourceFiles, "sync_blobs").copyRecursively(File(targetFiles, "sync_blobs"), overwrite = true)
        target.materializeResources()
        assertEquals("chosen conflict", File(targetFiles, "upload/data.txt").readText())
    }

    @Test
    fun `memory progress waits for a deferred conversation instead of becoming a local deletion`() = runBlocking {
        val (_, db, store) = device("device")
        val id = Uuid.random().toString()
        val nodeId = Uuid.random().toString()
        val message = UIMessage(role = MessageRole.USER, parts = emptyList())
        val ids = listOf("$nodeId:${message.id}")
        val payload = JsonInstant.encodeToString(MemoryProgressSyncPayload(
            id,
            42,
            CanonicalSyncHash.sha256Hex(ids.joinToString("\n")),
            ids
        ))
        store.apply("memory_progress", id, payload)
        repeat(2) { assertEquals(payload, store.snapshot()["memory_progress" to id]) }
        db.conversationDao().insert(ConversationEntity(id, "assistant", "title", "[]", 1, 1, "[]", false))
        db.messageNodeDao().insert(MessageNodeEntity(nodeId, id, 0, JsonInstant.encodeToString(listOf(message)), 0))
        store.snapshot()
        assertEquals(42L, db.memoryConsolidationDao().getConsolidatedAt(id))
        store.apply("memory_progress", id, null)
        assertTrue(store.snapshot().keys.none { it.first == "memory_progress" })
    }
}
