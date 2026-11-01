package me.rerere.rikkahub.data.sync.v2

import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import java.io.File

/**
 * Sync V2 附件路径规整与跨包名/跨平台路径本地化工具。
 *
 * 设计原则：
 * 1. 仅作用于结构化附件字段（UIMessagePart.Image/Video/Audio/Document、嵌套 Tool.output、
 *    Assistant.avatar/background/presetMessages、MediaCreation 素材路径），绝不对用户聊天正文做字符串替换。
 * 2. 兼容 github / coexist / coexistNormal / debug 各包名变体及 Android 多用户目录
 *    （如 file:///data/user/0/<pkg>/files/upload/... 或 /data/data/<pkg>/files/...）与 Web 端相对接口路径
 *    （/api/files/path/upload/...），统一收敛为受管相对路径（如 upload/xxx.jpg）。
 */
object AttachmentPathNormalizer {
    val KNOWN_REL_PREFIXES: List<String> = listOf(
        "upload/",
        "sync_blobs/",
        "media_creation/",
        "skills/",
        "fonts/",
        "avatar/",
        "images/",
        "tool_outputs/",
    )

    private val ANDROID_FILES_PREFIX_REGEX = Regex(
        """^(?:file://)?/data/(?:user/\d+|data)/[A-Za-z0-9._]+/files/(.+)$"""
    )

    private val WEB_API_FILES_PREFIX_REGEX = Regex(
        """^/api/files/path/(.+?)(?:\?.*)?$"""
    )

    /**
     * 判断路径是否为安全的受管相对路径（不含 `..` 穿越分段或反斜杠）。
     */
    fun isManagedRelativePath(path: String): Boolean {
        if (path.isBlank() || path.contains('\\')) return false
        if (KNOWN_REL_PREFIXES.none { path.startsWith(it) }) return false
        return path.split('/').none { it == ".." || it.isEmpty() }
    }

    /**
     * 将任意本地文件 URI 或 Web API 附件地址规整为跨端可移植的受管相对路径（如 `upload/abc.jpg`）。
     * 对 `data:`、`http(s):`、`blob:`、`content:` 或非受管路径保持原样不变。
     */
    fun normalizeAttachmentUrl(url: String, localFilesDir: File? = null): String {
        if (url.isEmpty()) return url
        val trimmed = url.trim()
        if (
            trimmed.startsWith("data:") ||
            trimmed.startsWith("http://") ||
            trimmed.startsWith("https://") ||
            trimmed.startsWith("blob:") ||
            trimmed.startsWith("content://")
        ) {
            return trimmed
        }

        // 1. 优先剥离当前进程明确传入的 filesDir 前缀（兼容单元测试临时目录及自定义挂载）
        if (localFilesDir != null) {
            val normalizedBase = localFilesDir.absolutePath.replace('\\', '/').trimEnd('/') + "/"
            val stripped = when {
                trimmed.startsWith("file://$normalizedBase") -> trimmed.removePrefix("file://$normalizedBase")
                trimmed.startsWith("file:///$normalizedBase") -> trimmed.removePrefix("file:///$normalizedBase")
                trimmed.startsWith(normalizedBase) -> trimmed.removePrefix(normalizedBase)
                else -> null
            }?.replace('\\', '/')?.trimStart('/')
            if (stripped != null && stripped.split('/').none { it == ".." || it.isEmpty() }) {
                return stripped
            }
        }

        // 2. 匹配 Web 端 /api/files/path/<rel>
        WEB_API_FILES_PREFIX_REGEX.matchEntire(trimmed)?.let { match ->
            val rel = match.groupValues[1].trimStart('/')
            if (isManagedRelativePath(rel)) return rel
        }

        // 3. 匹配 Android 任意包名变体的私有 files 目录
        ANDROID_FILES_PREFIX_REGEX.matchEntire(trimmed)?.let { match ->
            val rel = match.groupValues[1].trimStart('/')
            if (rel.split('/').none { it == ".." || it.isEmpty() }) {
                return rel
            }
        }

        // 4. 兜底匹配 file:// 中包含已知受管子目录前缀的路径
        if (trimmed.startsWith("file://")) {
            val rest = trimmed.removePrefix("file://").replace('\\', '/').trimStart('/')
            for (prefix in KNOWN_REL_PREFIXES) {
                val idx = rest.indexOf(prefix)
                if (idx >= 0) {
                    val candidate = rest.substring(idx)
                    if (isManagedRelativePath(candidate)) {
                        return candidate
                    }
                }
            }
        }

        // 5. 本身已是相对受管路径
        val cleanRel = trimmed.trimStart('/')
        if (isManagedRelativePath(cleanRel)) {
            return cleanRel
        }

        return url
    }

    /**
     * 将受管相对路径（或跨包名残留的旧绝对 file:// 地址）转换为当前应用实例 `filesDir` 下的 `file://` URI。
     * 注意：此方法用于 `Conversation` / `Assistant` 等使用 `file://` URI 的模型；
     * `MediaCreation` 自身在本地即存储相对于 `filesDir` 的相对路径，应使用 [normalizeAttachmentUrl]。
     */
    fun localizeAttachmentUrl(url: String, filesDir: File): String {
        val norm = normalizeAttachmentUrl(url, filesDir)
        if (!isManagedRelativePath(norm)) return url
        val base = filesDir.absolutePath.replace('\\', '/').trimEnd('/')
        return if (base.startsWith("/")) {
            "file://$base/$norm"
        } else {
            "file:///$base/$norm"
        }
    }

    private fun UIMessagePart.mapAttachmentUrls(transform: (String) -> String): UIMessagePart = when (this) {
        is UIMessagePart.Image -> copy(url = transform(url))
        is UIMessagePart.Document -> copy(url = transform(url))
        is UIMessagePart.Video -> copy(url = transform(url))
        is UIMessagePart.Audio -> copy(url = transform(url))
        is UIMessagePart.Tool -> copy(output = output.map { it.mapAttachmentUrls(transform) })
        else -> this
    }

    /**
     * 将 [Conversation] 全部分支消息中的本地受管附件地址转为跨平台相对路径，供推送/哈希计算使用。
     */
    fun Conversation.toPortableAttachmentPaths(filesDir: File? = null): Conversation = copy(
        messageNodes = messageNodes.map { node ->
            node.copy(
                messages = node.messages.map { msg ->
                    msg.copy(
                        parts = msg.parts.map { part ->
                            part.mapAttachmentUrls { raw -> normalizeAttachmentUrl(raw, filesDir) }
                        }
                    )
                }
            )
        }
    )

    /**
     * 将从云端拉取的 [Conversation] 相对附件路径绑定到当前安装包私有 `filesDir`。
     */
    fun Conversation.toLocalizedAttachmentPaths(filesDir: File): Conversation = copy(
        messageNodes = messageNodes.map { node ->
            node.copy(
                messages = node.messages.map { msg ->
                    msg.copy(
                        parts = msg.parts.map { part ->
                            part.mapAttachmentUrls { raw -> localizeAttachmentUrl(raw, filesDir) }
                        }
                    )
                }
            )
        }
    )

    /**
     * 将 [Assistant] 的头像、背景图及预设消息附件转为跨平台相对路径。
     */
    fun Assistant.toPortableAttachmentPaths(filesDir: File? = null): Assistant = copy(
        avatar = when (val av = avatar) {
            is Avatar.Image -> Avatar.Image(normalizeAttachmentUrl(av.url, filesDir))
            else -> av
        },
        background = background?.let { normalizeAttachmentUrl(it, filesDir) },
        presetMessages = presetMessages.map { msg ->
            msg.copy(
                parts = msg.parts.map { part ->
                    part.mapAttachmentUrls { raw -> normalizeAttachmentUrl(raw, filesDir) }
                }
            )
        }
    )

    /**
     * 将从云端拉取的 [Assistant] 相对附件路径绑定到当前安装包私有 `filesDir`。
     */
    fun Assistant.toLocalizedAttachmentPaths(filesDir: File): Assistant = copy(
        avatar = when (val av = avatar) {
            is Avatar.Image -> Avatar.Image(localizeAttachmentUrl(av.url, filesDir))
            else -> av
        },
        background = background?.let { localizeAttachmentUrl(it, filesDir) },
        presetMessages = presetMessages.map { msg ->
            msg.copy(
                parts = msg.parts.map { part ->
                    part.mapAttachmentUrls { raw -> localizeAttachmentUrl(raw, filesDir) }
                }
            )
        }
    )
}
