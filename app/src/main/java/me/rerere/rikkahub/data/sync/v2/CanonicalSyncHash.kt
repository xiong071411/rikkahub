package me.rerere.rikkahub.data.sync.v2

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Sync V2 跨语言（Kotlin <-> Python `backend/app/canonical.py`）规范化与确定性语义哈希计算器。
 *
 * 算法保证：
 * 1. 所有 JSON Object 键递归按 Unicode 码位升序排列；
 * 2. 整数浮点（如 `1.0`）归一为整数 `1`；
 * 3. 字符串紧凑无空白序列化，`ensure_ascii=False`（非 ASCII UTF-8 直接输出，`/` 不转义，仅转义 JSON 必需控制字符）；
 * 4. 顶层易变时间字段（`conversation`/`folder`/`media_session`/`media_record` 的 `updateAt`，`favorite` 的 `updatedAt`）
 *    在哈希时剔除，确保内容未变时仅时间戳刷新不会误判冲突，而任意业务字段修改均能精准改变哈希。
 */
object CanonicalSyncHash {
    private val parser = Json {
        ignoreUnknownKeys = true
    }

    private val VALID_REASONING_LEVELS = setOf(
        "off", "auto", "low", "medium", "high", "xhigh", "max"
    )

    private val VOLATILE_ROOT_KEYS: Map<String, Set<String>> = mapOf(
        "conversation" to setOf("updateAt"),
        "folder" to setOf("updateAt"),
        "media_session" to setOf("updateAt"),
        "media_record" to setOf("updateAt"),
        "favorite" to setOf("updatedAt"),
    )

    /**
     * 将毫秒时间戳格式化为与 `Instant.ofEpochMilli(ms).toString()` 及 Python `epoch_ms_to_iso_utc` 逐字符一致的 UTC ISO-8601 字符串。
     */
    fun epochMsToIsoUtc(ms: Long): String = Instant.ofEpochMilli(ms).toString()

    /**
     * 兼容解析 ISO-8601 字符串或毫秒数字为毫秒时间戳。
     */
    fun isoOrEpochToMs(element: JsonElement?, fallbackMs: Long = 0L): Long {
        val prim = element as? JsonPrimitive ?: return fallbackMs
        if (!prim.isString) {
            prim.content.toLongOrNull()?.let { return it }
            prim.content.toDoubleOrNull()?.let { return Math.round(it) }
            return fallbackMs
        }
        val raw = prim.content.trim()
        if (raw.isEmpty()) return fallbackMs
        if (raw.all { it.isDigit() }) {
            return raw.toLongOrNull() ?: fallbackMs
        }
        return runCatching { Instant.parse(raw).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
            .getOrDefault(fallbackMs)
    }

    private fun normalizeMessagePartJson(part: JsonElement, filesDir: File? = null): JsonElement {
        val obj = part as? JsonObject ?: return part
        val typeStr = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val isAttachment = typeStr in setOf("image", "video", "audio", "document") ||
            typeStr.endsWith(".Image") ||
            typeStr.endsWith(".Video") ||
            typeStr.endsWith(".Audio") ||
            typeStr.endsWith(".Document")

        val mutable = obj.toMutableMap()
        if (isAttachment) {
            val urlPrim = mutable["url"] as? JsonPrimitive
            if (urlPrim != null && urlPrim.isString) {
                mutable["url"] = JsonPrimitive(AttachmentPathNormalizer.normalizeAttachmentUrl(urlPrim.content, filesDir))
            }
        }
        if (typeStr == "tool" || typeStr.endsWith(".Tool")) {
            val outArr = mutable["output"] as? JsonArray
            if (outArr != null) {
                mutable["output"] = JsonArray(outArr.map { normalizeMessagePartJson(it, filesDir) })
            }
        }
        return JsonObject(mutable)
    }

    private fun normalizeConversationJson(obj: JsonObject, filesDir: File? = null): JsonObject {
        val out = obj.toMutableMap()

        // 1. createAt / updateAt 统一为毫秒级 ISO-8601 UTC 字符串
        for (field in listOf("createAt", "updateAt")) {
            val valEl = out[field]
            val prim = valEl as? JsonPrimitive
            when {
                prim != null && !prim.isString -> {
                    val ms = isoOrEpochToMs(prim, System.currentTimeMillis())
                    out[field] = JsonPrimitive(epochMsToIsoUtc(ms))
                }
                prim != null && prim.isString && prim.content.isNotBlank() -> {
                    val parsedMs = isoOrEpochToMs(prim, -1L)
                    if (parsedMs >= 0L) {
                        out[field] = JsonPrimitive(epochMsToIsoUtc(parsedMs))
                    }
                }
                else -> {
                    out[field] = JsonPrimitive(epochMsToIsoUtc(System.currentTimeMillis()))
                }
            }
        }

        // 2. messageNodes 与历史 messages 字段兼容规整
        if ("messageNodes" !in out && out["messages"] is JsonArray) {
            out["messageNodes"] = out.remove("messages")!!
        }
        val nodesArr = out["messageNodes"] as? JsonArray ?: JsonArray(emptyList())
        val normalizedNodes = nodesArr.mapNotNull { nodeEl ->
            val nodeObj = nodeEl as? JsonObject ?: return@mapNotNull null
            val nodeMap = nodeObj.toMutableMap()
            val msgsArr = nodeMap["messages"] as? JsonArray
            if (msgsArr != null) {
                val newMsgs = msgsArr.mapNotNull { msgEl ->
                    val msgObj = msgEl as? JsonObject ?: return@mapNotNull null
                    val msgMap = msgObj.toMutableMap()
                    val rolePrim = msgMap["role"] as? JsonPrimitive
                    if (rolePrim != null && rolePrim.isString) {
                        val lower = rolePrim.content.lowercase()
                        if (lower in setOf("user", "assistant", "system", "tool") && rolePrim.content != lower) {
                            msgMap["role"] = JsonPrimitive(lower)
                        }
                    }
                    val partsArr = msgMap["parts"] as? JsonArray
                    if (partsArr != null) {
                        msgMap["parts"] = JsonArray(partsArr.map { normalizeMessagePartJson(it, filesDir) })
                    }
                    JsonObject(msgMap)
                }
                nodeMap["messages"] = JsonArray(newMsgs)
                val sel = (nodeMap["selectIndex"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                if (sel == null || (newMsgs.isNotEmpty() && sel !in newMsgs.indices)) {
                    nodeMap["selectIndex"] = JsonPrimitive((newMsgs.size - 1).coerceAtLeast(0))
                }
            }
            JsonObject(nodeMap)
        }
        out["messageNodes"] = JsonArray(normalizedNodes)

        // 3. ConversationConfig 字段规整
        val cfgObj = out["config"] as? JsonObject
        if (cfgObj != null) {
            val cfgMap = cfgObj.toMutableMap()
            if ("mcpServerIds" in cfgMap) {
                val mcpIds = cfgMap.remove("mcpServerIds")
                if ("mcpServers" !in cfgMap && mcpIds is JsonArray) {
                    cfgMap["mcpServers"] = mcpIds
                }
            }
            val rlEl = cfgMap["reasoningLevel"]
            when {
                rlEl is JsonPrimitive && rlEl.isString -> {
                    val rlLower = rlEl.content.lowercase()
                    cfgMap["reasoningLevel"] = if (rlLower == "default" || rlLower !in VALID_REASONING_LEVELS) {
                        JsonPrimitive("auto")
                    } else {
                        JsonPrimitive(rlLower)
                    }
                }
                rlEl == null || rlEl is JsonNull -> {
                    cfgMap["reasoningLevel"] = JsonPrimitive("auto")
                }
            }
            out["config"] = JsonObject(cfgMap)
        }

        return JsonObject(out)
    }

    private fun normalizeAssistantJson(obj: JsonObject, filesDir: File? = null): JsonObject {
        val out = obj.toMutableMap()
        val avatarObj = out["avatar"] as? JsonObject
        if (avatarObj != null && "url" in avatarObj) {
            val urlPrim = avatarObj["url"] as? JsonPrimitive
            if (urlPrim != null && urlPrim.isString) {
                val avMap = avatarObj.toMutableMap()
                avMap["url"] = JsonPrimitive(AttachmentPathNormalizer.normalizeAttachmentUrl(urlPrim.content, filesDir))
                out["avatar"] = JsonObject(avMap)
            }
        }
        val bgPrim = out["background"] as? JsonPrimitive
        if (bgPrim != null && bgPrim.isString && bgPrim.content.isNotEmpty()) {
            out["background"] = JsonPrimitive(AttachmentPathNormalizer.normalizeAttachmentUrl(bgPrim.content, filesDir))
        }
        val presets = out["presetMessages"] as? JsonArray
        if (presets != null) {
            out["presetMessages"] = JsonArray(
                presets.map { msgEl ->
                    val msgObj = msgEl as? JsonObject ?: return@map msgEl
                    val partsArr = msgObj["parts"] as? JsonArray ?: return@map msgEl
                    val msgMap = msgObj.toMutableMap()
                    msgMap["parts"] = JsonArray(partsArr.map { normalizeMessagePartJson(it, filesDir) })
                    JsonObject(msgMap)
                }
            )
        }
        return JsonObject(out)
    }

    private fun normalizeMediaAssetsJsonString(rawJson: String, filesDir: File? = null): Pair<String, Boolean> {
        if (rawJson.isBlank()) return rawJson to false
        val arr = runCatching { parser.parseToJsonElement(rawJson) as? JsonArray }.getOrNull() ?: return rawJson to false
        var changed = false
        val updated = arr.map { item ->
            val obj = item as? JsonObject ?: return@map item
            val map = obj.toMutableMap()
            for (k in listOf("path", "posterPath", "lastFramePath")) {
                val p = map[k] as? JsonPrimitive
                if (p != null && p.isString && p.content.isNotEmpty()) {
                    val norm = AttachmentPathNormalizer.normalizeAttachmentUrl(p.content, filesDir)
                    if (norm != p.content) {
                        map[k] = JsonPrimitive(norm)
                        changed = true
                    }
                }
            }
            JsonObject(map)
        }
        if (!changed) return rawJson to false
        return serializeCompactJsonPreservingOrder(JsonArray(updated)) to true
    }

    private fun normalizeMediaSessionJson(obj: JsonObject, filesDir: File? = null): JsonObject {
        val out = obj.toMutableMap()
        val draftPrim = out["draft"] as? JsonPrimitive
        if (draftPrim != null && draftPrim.isString && draftPrim.content.isNotBlank()) {
            val draftObj = runCatching { parser.parseToJsonElement(draftPrim.content) as? JsonObject }.getOrNull()
            val assetsArr = draftObj?.get("assets") as? JsonArray
            if (draftObj != null && assetsArr != null) {
                var changed = false
                val newAssets = assetsArr.map { assetEl ->
                    val assetObj = assetEl as? JsonObject ?: return@map assetEl
                    val pathPrim = assetObj["path"] as? JsonPrimitive
                    if (pathPrim != null && pathPrim.isString) {
                        val norm = AttachmentPathNormalizer.normalizeAttachmentUrl(pathPrim.content, filesDir)
                        if (norm != pathPrim.content) {
                            changed = true
                            val m = assetObj.toMutableMap()
                            m["path"] = JsonPrimitive(norm)
                            return@map JsonObject(m)
                        }
                    }
                    assetEl
                }
                if (changed) {
                    val dMap = draftObj.toMutableMap()
                    dMap["assets"] = JsonArray(newAssets)
                    out["draft"] = JsonPrimitive(serializeCompactJsonPreservingOrder(JsonObject(dMap)))
                }
            }
        }
        return JsonObject(out)
    }

    private fun normalizeMediaRecordJson(obj: JsonObject, filesDir: File? = null): JsonObject {
        val out = obj.toMutableMap()
        for (field in listOf("inputs", "outputs")) {
            val prim = out[field] as? JsonPrimitive
            if (prim != null && prim.isString && prim.content.isNotBlank()) {
                val (newStr, changed) = normalizeMediaAssetsJsonString(prim.content, filesDir)
                if (changed) {
                    out[field] = JsonPrimitive(newStr)
                }
            }
        }
        return JsonObject(out)
    }

    private fun normalizeParsedEntity(entityType: String, element: JsonElement, filesDir: File? = null): JsonElement {
        val obj = element as? JsonObject ?: return element
        return when (entityType) {
            "conversation" -> normalizeConversationJson(obj, filesDir)
            "assistant" -> normalizeAssistantJson(obj, filesDir)
            "media_session" -> normalizeMediaSessionJson(obj, filesDir)
            "media_record" -> normalizeMediaRecordJson(obj, filesDir)
            else -> obj
        }
    }

    /**
     * 对同步实体 JSON 做跨端规范化（附件路径转相对路径、时间戳格式规整、紧凑无空白）。
     */
    fun normalizeEntityPayload(entityType: String, payloadJson: String, filesDir: File? = null): String {
        if (payloadJson.isBlank()) return ""
        val parsed = runCatching { parser.parseToJsonElement(payloadJson) }.getOrNull() ?: return payloadJson
        val normalized = normalizeParsedEntity(entityType, parsed, filesDir)
        return serializeCompactJsonPreservingOrder(normalized)
    }

    /**
     * 计算与服务端 Python `compute_semantic_hash(entity_type, payload_json)` 逐字节一致的 SHA-256 十六进制字符串。
     */
    fun computeSemanticHash(entityType: String, payloadJson: String, filesDir: File? = null): String {
        if (payloadJson.isBlank()) {
            return sha256Hex("$entityType:__deleted__")
        }
        val compact = runCatching {
            val parsed = parser.parseToJsonElement(payloadJson)
            val normalized = normalizeParsedEntity(entityType, parsed, filesDir)
            buildCanonicalSortedJson(entityType, normalized, isRoot = true)
        }.getOrElse {
            payloadJson.trim()
        }
        return sha256Hex(compact)
    }

    /**
     * 提取任意实体 JSON 中引用的全部受管相对附件路径（包含会话全部分支、助手头像/背景、媒体创作全部输入/输出）。
     */
    fun extractReferencedFilesFromEntity(entityType: String, payloadJson: String, filesDir: File? = null): Set<String> {
        if (payloadJson.isBlank()) return emptySet()
        val obj = runCatching { parser.parseToJsonElement(payloadJson) as? JsonObject }.getOrNull() ?: return emptySet()
        val refs = LinkedHashSet<String>()

        fun collectFromParts(parts: JsonArray?) {
            if (parts == null) return
            for (partEl in parts) {
                val partObj = partEl as? JsonObject ?: continue
                val urlPrim = partObj["url"] as? JsonPrimitive
                if (urlPrim != null && urlPrim.isString) {
                    val norm = AttachmentPathNormalizer.normalizeAttachmentUrl(urlPrim.content, filesDir)
                    if (AttachmentPathNormalizer.isManagedRelativePath(norm)) {
                        refs.add(norm)
                    }
                }
                collectFromParts(partObj["output"] as? JsonArray)
            }
        }

        when (entityType) {
            "conversation" -> {
                val nodes = obj["messageNodes"] as? JsonArray
                nodes?.forEach { nodeEl ->
                    val msgs = (nodeEl as? JsonObject)?.get("messages") as? JsonArray
                    msgs?.forEach { msgEl ->
                        collectFromParts((msgEl as? JsonObject)?.get("parts") as? JsonArray)
                    }
                }
            }
            "assistant" -> {
                val avatarUrl = ((obj["avatar"] as? JsonObject)?.get("url") as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (avatarUrl != null) {
                    val norm = AttachmentPathNormalizer.normalizeAttachmentUrl(avatarUrl, filesDir)
                    if (AttachmentPathNormalizer.isManagedRelativePath(norm)) refs.add(norm)
                }
                val bg = (obj["background"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (bg != null) {
                    val norm = AttachmentPathNormalizer.normalizeAttachmentUrl(bg, filesDir)
                    if (AttachmentPathNormalizer.isManagedRelativePath(norm)) refs.add(norm)
                }
                val presets = obj["presetMessages"] as? JsonArray
                presets?.forEach { msgEl ->
                    collectFromParts((msgEl as? JsonObject)?.get("parts") as? JsonArray)
                }
            }
            "media_session" -> {
                val draftRaw = (obj["draft"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (!draftRaw.isNullOrBlank()) {
                    val draftObj = runCatching { parser.parseToJsonElement(draftRaw) as? JsonObject }.getOrNull()
                    val assets = draftObj?.get("assets") as? JsonArray
                    assets?.forEach { assetEl ->
                        val p = ((assetEl as? JsonObject)?.get("path") as? JsonPrimitive)?.takeIf { it.isString }?.content
                        if (p != null) {
                            val norm = AttachmentPathNormalizer.normalizeAttachmentUrl(p, filesDir)
                            if (AttachmentPathNormalizer.isManagedRelativePath(norm)) refs.add(norm)
                        }
                    }
                }
            }
            "media_record" -> {
                for (field in listOf("inputs", "outputs")) {
                    val raw = (obj[field] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (!raw.isNullOrBlank()) {
                        val arr = runCatching { parser.parseToJsonElement(raw) as? JsonArray }.getOrNull()
                        arr?.forEach { itemEl ->
                            val itemObj = itemEl as? JsonObject ?: return@forEach
                            for (k in listOf("path", "posterPath", "lastFramePath")) {
                                val p = (itemObj[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
                                if (p != null) {
                                    val norm = AttachmentPathNormalizer.normalizeAttachmentUrl(p, filesDir)
                                    if (AttachmentPathNormalizer.isManagedRelativePath(norm)) refs.add(norm)
                                }
                            }
                        }
                    }
                }
            }
        }
        return refs
    }

    private fun buildCanonicalSortedJson(entityType: String, element: JsonElement, isRoot: Boolean): String {
        val sb = StringBuilder()
        appendCanonicalElement(sb, entityType, element, isRoot = isRoot, sortKeys = true, normalizeIntegerFloat = true)
        return sb.toString()
    }

    private fun serializeCompactJsonPreservingOrder(element: JsonElement): String {
        val sb = StringBuilder()
        appendCanonicalElement(sb, "", element, isRoot = false, sortKeys = false, normalizeIntegerFloat = false)
        return sb.toString()
    }

    private val CODE_POINT_COMPARATOR = Comparator<String> { a, b ->
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val cpA = a.codePointAt(i)
            val cpB = b.codePointAt(j)
            if (cpA != cpB) return@Comparator cpA.compareTo(cpB)
            i += Character.charCount(cpA)
            j += Character.charCount(cpB)
        }
        a.length.compareTo(b.length)
    }

    private fun appendCanonicalElement(
        sb: StringBuilder,
        entityType: String,
        element: JsonElement,
        isRoot: Boolean,
        sortKeys: Boolean,
        normalizeIntegerFloat: Boolean,
    ) {
        when (element) {
            is JsonNull -> sb.append("null")
            is JsonObject -> {
                val ignored = if (isRoot) VOLATILE_ROOT_KEYS[entityType].orEmpty() else emptySet()
                val entries = if (sortKeys) {
                    element.entries
                        .filter { it.key !in ignored }
                        .sortedWith { e1, e2 -> CODE_POINT_COMPARATOR.compare(e1.key, e2.key) }
                } else {
                    element.entries.filter { it.key !in ignored }
                }
                sb.append('{')
                entries.forEachIndexed { idx, (k, v) ->
                    if (idx > 0) sb.append(',')
                    appendEscapedJsonString(sb, k)
                    sb.append(':')
                    appendCanonicalElement(sb, entityType, v, isRoot = false, sortKeys = sortKeys, normalizeIntegerFloat = normalizeIntegerFloat)
                }
                sb.append('}')
            }
            is JsonArray -> {
                sb.append('[')
                element.forEachIndexed { idx, item ->
                    if (idx > 0) sb.append(',')
                    appendCanonicalElement(sb, entityType, item, isRoot = false, sortKeys = sortKeys, normalizeIntegerFloat = normalizeIntegerFloat)
                }
                sb.append(']')
            }
            is JsonPrimitive -> {
                if (element.isString) {
                    appendEscapedJsonString(sb, element.content)
                } else {
                    val boolVal = element.booleanOrNull
                    if (boolVal != null) {
                        sb.append(if (boolVal) "true" else "false")
                    } else {
                        sb.append(formatNumberLiteral(element.content, normalizeIntegerFloat))
                    }
                }
            }
        }
    }

    private fun formatNumberLiteral(raw: String, normalizeIntegerFloat: Boolean): String {
        val trimmed = raw.trim()
        trimmed.toLongOrNull()?.let { return it.toString() }
        if (!normalizeIntegerFloat) return trimmed
        return runCatching {
            val bd = BigDecimal(trimmed)
            val stripped = bd.stripTrailingZeros()
            if (stripped.scale() <= 0) {
                stripped.toBigIntegerExact().toString()
            } else {
                // 与 Python float repr 对齐
                val d = trimmed.toDouble()
                if (d.isFinite()) {
                    if (d % 1.0 == 0.0 && d >= Long.MIN_VALUE.toDouble() && d < Long.MAX_VALUE.toDouble()) {
                        d.toLong().toString()
                    } else {
                        d.toString().lowercase()
                    }
                } else {
                    trimmed
                }
            }
        }.getOrDefault(trimmed)
    }

    private fun appendEscapedJsonString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code in 0x00..0x1F) {
                        sb.append("\\u")
                        sb.append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
    }

    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytesToHex(digest)
    }

    fun sha256File(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { fis ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = fis.read(buf)
                if (read <= 0) break
                md.update(buf, 0, read)
            }
        }
        return bytesToHex(md.digest())
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = "0123456789abcdef"
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = hexChars[v ushr 4]
            out[i * 2 + 1] = hexChars[v and 0x0F]
        }
        return String(out)
    }
}
