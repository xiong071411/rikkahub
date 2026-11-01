package me.rerere.rikkahub.data.sync.v2

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.SshWorkspaceSettings
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceType
import me.rerere.workspace.ssh.SshAuthType
import me.rerere.workspace.ssh.SshHostKey

/** 密文绑定设备，在线同步传输解密后的凭据，接收端再用自己的 Keystore 加密。 */
@Serializable
data class SshSyncPayload(
    val host: String,
    val port: Int,
    val username: String,
    val authType: SshAuthType,
    val remoteDir: String,
    val hostKey: SshHostKey? = null,
    val password: String? = null,
    val privateKey: String? = null,
    val passphrase: String? = null,
)

@Serializable
data class WorkspaceSyncPayload(
    val id: String,
    val name: String,
    val type: String,
    val createdAt: Long,
    val toolApprovals: Map<String, Boolean> = emptyMap(),
    val ssh: SshSyncPayload? = null,
) {
    fun validate() = apply {
        require(kotlin.uuid.Uuid.parse(id).toString() == id)
        require(name.isNotBlank())
        require(type in setOf(WorkspaceType.LOCAL.name, WorkspaceType.SSH.name))
        require((type == WorkspaceType.SSH.name) == (ssh != null))
        ssh?.let { require(it.host.isNotBlank() && it.port in 1..65535 && it.remoteDir.startsWith('/')) }
    }

    fun toEntity(existing: WorkspaceEntity?, encrypt: (String) -> String): WorkspaceEntity {
        validate()
        fun encode(value: String?): String? = value?.let(encrypt)
        val config = ssh?.let {
            SshWorkspaceSettings(
                host = it.host, port = it.port, username = it.username, authType = it.authType,
                remoteDir = it.remoteDir, hostKey = it.hostKey,
                encryptedPassword = encode(it.password),
                encryptedPrivateKey = encode(it.privateKey),
                encryptedPassphrase = encode(it.passphrase),
            )
        }
        // 更新只覆盖共享配置；已有设备的路径、兼容模式、安装状态和文件不随远端变化。
        return (existing ?: WorkspaceEntity(
            id = id,
            name = name,
            root = id,
            type = type,
            shellStatus = if (ssh != null) WorkspaceShellStatus.READY.name else WorkspaceShellStatus.DISABLED.name,
            createdAt = createdAt,
            updatedAt = createdAt,
        )).copy(
            name = name,
            type = type,
            createdAt = createdAt,
            toolApprovals = JsonInstant.encodeToString(toolApprovals),
            sshConfig = config?.let { JsonInstant.encodeToString(it) },
        )
    }

    companion object {
        fun fromEntity(entity: WorkspaceEntity, decrypt: (String) -> String): WorkspaceSyncPayload {
            val config = entity.sshSettings()
            // 解密失败必须终止本次同步，不能用空凭据覆盖云端仍然可用的密码或私钥。
            val ssh = config?.let {
                SshSyncPayload(
                    it.host, it.port, it.username, it.authType, it.remoteDir, it.hostKey,
                    it.encryptedPassword?.let(
                        decrypt
                    ), it.encryptedPrivateKey?.let(decrypt), it.encryptedPassphrase?.let(decrypt),
                )
            }
            return WorkspaceSyncPayload(
                entity.id,
                entity.name,
                entity.type,
                entity.createdAt,
                entity.toolApprovalOverrides(),
                ssh
            ).validate()
        }
    }
}

@Serializable
data class SyncBlobFile(val path: String, val blobPath: String, val sha256: String, val size: Long) {
    fun validate() = apply {
        require(path.isNotBlank() && !path.startsWith('/') && !path.contains('\\'))
        require(path.split('/').none { it.isBlank() || it == "." || it == ".." })
        require(sha256.matches(Regex("[0-9a-f]{64}")) && blobPath == "sync_blobs/$sha256" && size >= 0)
    }
}

@Serializable
data class SkillBundleSyncPayload(val directory: String, val files: List<SyncBlobFile>) {
    fun validate() = apply {
        require(directory.isNotBlank() && !directory.startsWith('.') && '/' !in directory && '\\' !in directory)
        require(files.any { it.path == "SKILL.md" } && files.map { it.path }.distinct().size == files.size)
        files.forEach { it.validate() }
    }
}

@Serializable
data class MemoryProgressSyncPayload(
    val conversationId: String,
    val consolidatedAt: Long,
    val branchHash: String,
    val branchIds: List<String> = emptyList()
)

/** 同步注册表与设备字段清单一起用于覆盖测试，新增存储不能仅检查 Settings。 */
object ExtendedSyncTypes {
    val resourceRoots = setOf("upload", "media_creation", "fonts", "avatar", "images")
    fun isResourcePath(path: String): Boolean = AttachmentPathNormalizer.isManagedRelativePath(path) &&
        path.substringBefore('/') in resourceRoots && path.split('/').none { it == "." }
    val all =
        setOf("workspace", "skill_bundle", "file_asset", "ui_preference", "translation_history", "usage_record", "gen_media", "managed_file", "memory_progress")
    val sharedPreferenceKeys = mapOf(
        "colorMode" to "string",
        "amoledDark" to "boolean",
        "create_new_conversation_on_start" to "boolean",
        "chat_input_collapsed" to "boolean",
        "model_list_collapsed_providers" to "string",
        "search_page_sort_order" to "string",
    )
}
