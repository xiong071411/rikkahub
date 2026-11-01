package me.rerere.rikkahub.data.sync.v2

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.UsageRecordEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
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
}
