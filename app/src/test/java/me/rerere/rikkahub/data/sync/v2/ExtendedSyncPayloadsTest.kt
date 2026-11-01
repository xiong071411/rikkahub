package me.rerere.rikkahub.data.sync.v2

import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.SshWorkspaceSettings
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.ssh.SshAuthType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ExtendedSyncPayloadsTest {
    @Test
    fun `local workspace shares configuration while preserving each devices environment`() {
        val id = Uuid.random().toString()
        val source = WorkspaceEntity(id, "项目", "phone-root", "READY", 10, 20, 30, "{\"shell\":true}", true)
        val target = source.copy(
            root = "watch-root",
            shellStatus = "DISABLED",
            shellCompatibilityMode = false,
            lastAccessAt = 80
        )
        val payload = WorkspaceSyncPayload.fromEntity(source) { error("本地工作区不应读取凭据") }
        val raw = JsonInstant.encodeToString(payload)
        assertFalse(raw.contains("phone-root"))
        assertFalse(raw.contains("shellStatus"))
        assertFalse(raw.contains("shellCompatibilityMode"))
        val received = JsonInstant.decodeFromString<WorkspaceSyncPayload>(raw).toEntity(target) { error("本地工作区不应加密凭据") }
        assertEquals("watch-root", received.root)
        assertEquals("DISABLED", received.shellStatus)
        assertFalse(received.shellCompatibilityMode)
        assertEquals(80L, received.lastAccessAt)
        assertEquals(source.toolApprovalOverrides(), received.toolApprovalOverrides())
        assertNull(received.sshConfig)
    }

    @Test
    fun `ssh secrets transfer independently of the source device encryption key`() {
        val settings = SshWorkspaceSettings(
            host = "server.example",
            username = "owner",
            remoteDir = "/srv/project",
            authType = SshAuthType.PASSWORD,
            encryptedPassword = "phone:password",
            encryptedPrivateKey = "phone:private-key",
            encryptedPassphrase = "phone:passphrase",
        )
        val source = WorkspaceEntity(
            Uuid.random().toString(),
            "服务器",
            "local-root",
            createdAt = 1,
            updatedAt = 2,
            type = "SSH",
            sshConfig = JsonInstant.encodeToString(settings)
        )
        val payload = WorkspaceSyncPayload.fromEntity(source) { it.removePrefix("phone:") }
        assertEquals("password", payload.ssh?.password)
        assertEquals("private-key", payload.ssh?.privateKey)
        val target = payload.toEntity(null) { "watch:$it" }
        assertEquals("watch:password", target.sshSettings()?.encryptedPassword)
        assertEquals("watch:private-key", target.sshSettings()?.encryptedPrivateKey)
        assertEquals("watch:passphrase", target.sshSettings()?.encryptedPassphrase)
        assertEquals(payload, WorkspaceSyncPayload.fromEntity(target) { it.removePrefix("watch:") })
    }

    @Test(expected = IllegalStateException::class)
    fun `unavailable local key does not turn credentials into a remote deletion`() {
        val settings =
            SshWorkspaceSettings("server", username = "owner", remoteDir = "/srv", encryptedPassword = "unreadable")
        val source = WorkspaceEntity(
            Uuid.random().toString(),
            "服务器",
            "root",
            createdAt = 1,
            updatedAt = 2,
            type = "SSH",
            sshConfig = JsonInstant.encodeToString(settings)
        )
        WorkspaceSyncPayload.fromEntity(source) { error("设备密钥不可用") }
    }

    @Test
    fun `resource references include immutable blobs and portable library files`() {
        val sha = "a".repeat(64)
        val file = SyncBlobFile("SKILL.md", "sync_blobs/$sha", sha, 50)
        val bundle = SkillBundleSyncPayload("my-skill", listOf(file)).validate()
        assertEquals(
            setOf("sync_blobs/$sha"),
            CanonicalSyncHash.extractReferencedFilesFromEntity("skill_bundle", JsonInstant.encodeToString(bundle))
        )
        assertEquals(
            setOf("sync_blobs/$sha"),
            CanonicalSyncHash.extractReferencedFilesFromEntity("file_asset", JsonInstant.encodeToString(file))
        )
        assertEquals(
            setOf("upload/source.pdf"),
            CanonicalSyncHash.extractReferencedFilesFromEntity(
                "managed_file",
                """{"relativePath":"upload/source.pdf"}"""
            )
        )
        assertFalse(AttachmentPathNormalizer.isManagedRelativePath("workspaces/project/files/secret.txt"))
    }

    @Test
    fun `skill paths reject traversal and keep nested resources`() {
        val sha = "b".repeat(64)
        assertTrue(SyncBlobFile("references/data.txt", "sync_blobs/$sha", sha, 0).validate().size == 0L)
        for (path in listOf("../data", "/etc/passwd", "a/../../data", "a\\data")) {
            assertTrue(runCatching { SyncBlobFile(path, "sync_blobs/$sha", sha, 1).validate() }.isFailure)
        }
    }
}
