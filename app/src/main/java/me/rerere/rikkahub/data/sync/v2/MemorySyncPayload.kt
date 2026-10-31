package me.rerere.rikkahub.data.sync.v2

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.db.entity.MemoryFileEntity
import me.rerere.rikkahub.data.model.MemoryFile
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** 记忆正文属于数据库实体，与“同步图片与附件”开关无关。全局记忆保留 __global__ 所有者。 */
@Serializable
data class MemorySyncPayload(
    val assistantId: String,
    val path: String,
    val content: String,
    val updatedAt: Long = 0,
) {
    fun validate(): MemorySyncPayload = apply {
        require(assistantId == "__global__" || runCatching { Uuid.parse(assistantId).toString() == assistantId }.getOrDefault(false)) {
            "记忆所属助手不合法"
        }
        require(MemoryFile.normalizePath(path) == path) { "记忆路径不合法：$path" }
    }

    val entityId: String get() = entityId(assistantId, path)

    fun toEntity() = MemoryFileEntity(assistantId, path, content, updatedAt)

    companion object {
        fun fromEntity(file: MemoryFileEntity) = MemorySyncPayload(file.assistantId, file.path, file.content, file.updatedAt)

        // 分隔符不能出现在合法路径中，固定 SHA-256 编码避免路径长度与 URL 转义影响实体 ID。
        fun entityId(assistantId: String, path: String): String = MessageDigest.getInstance("SHA-256")
            .digest("$assistantId\u0000$path".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
