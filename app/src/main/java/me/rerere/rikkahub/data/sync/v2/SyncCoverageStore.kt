package me.rerere.rikkahub.data.sync.v2

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.db.entity.MemoryConsolidationEntity
import me.rerere.rikkahub.data.db.entity.SyncConflictEntity
import me.rerere.rikkahub.data.db.entity.SyncResourceEntity
import me.rerere.rikkahub.data.db.entity.TranslationHistoryEntity
import me.rerere.rikkahub.data.db.entity.UsageRecordEntity
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.SecretCipher
import me.rerere.workspace.ssh.SshConnectionPool
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.uuid.Uuid

interface SyncCoverage {
    val issues: List<SyncCoverageIssue> get() = emptyList()
    suspend fun refreshAvailability() {}
    suspend fun snapshot(): Map<Pair<String, String>, String>
    suspend fun snapshot(refreshWorkspaces: Boolean): Map<Pair<String, String>, String> = snapshot()
    suspend fun snapshotFilesAndPrefs(): Map<Pair<String, String>, String>? = null
    suspend fun snapshotEntity(type: String, id: String): String? = snapshot()[type to id]
    suspend fun sshWorkspaceIds(): Set<String>? = null
    suspend fun apply(type: String, id: String, payload: String?)
    suspend fun materializeResources()
    suspend fun confirmLocal(type: String, id: String, payload: String?) {}
    suspend fun managedTargets(): Set<String>
}

data class SyncCoverageIssue(val entityType: String, val entityId: String, val message: String)

/**
 * Settings 之外的持久化覆盖。资源使用不可变内容寻址文件，清单走 V2 修订与冲突机制；
 * 待下载清单始终保留在 Room 中，不把尚未落地误判为删除，也不在下载前清掉技能启用状态。
 */
class SyncCoverageStore(
    private val context: Context,
    private val db: AppDatabase,
    private val sshPool: SshConnectionPool? = null,
    private val encrypt: (String) -> String = SecretCipher::encrypt,
    private val decrypt: (String) -> String = SecretCipher::decrypt,
    private val fastCache: SyncLocalFastCache = SyncLocalFastCache(db),
) : SyncCoverage {
    private val dao = db.syncCoverageDao()
    private val json = JsonInstant
    private val filesDir = context.filesDir
    private val preferences = context.getSharedPreferences("rikkahub.preferences", Context.MODE_PRIVATE)
    private data class Fingerprint(val length: Long, val modified: Long, val blobModified: Long, val file: SyncBlobFile)
    private val fingerprints = HashMap<String, Fingerprint>()
    private val workspacePayloads = LinkedHashMap<Pair<String, String>, String>()
    private val workspaceIssues = LinkedHashMap<String, SyncCoverageIssue>()
    override val issues: List<SyncCoverageIssue> get() = workspaceIssues.values.toList()

    override suspend fun refreshAvailability() {
        workspacePayloads.clear()
        workspaceIssues.clear()
        db.workspaceDao().getAll().forEach { entity ->
            try {
                workspacePayloads["workspace" to entity.id] =
                    json.encodeToString(WorkspaceSyncPayload.fromEntity(entity, decrypt))
            } catch (error: WorkspaceCredentialUnavailable) {
                workspaceIssues[entity.id] = SyncCoverageIssue("workspace", entity.id, error.message.orEmpty())
            }
        }
    }

    override suspend fun sshWorkspaceIds(): Set<String> =
        db.workspaceDao().getAll().filter { it.isSsh() }.mapTo(HashSet()) { it.id }

    override suspend fun snapshot(): Map<Pair<String, String>, String> = snapshot(refreshWorkspaces = true)

    override suspend fun snapshot(refreshWorkspaces: Boolean): Map<Pair<String, String>, String> = buildMap {
        if (refreshWorkspaces) {
            refreshAvailability()
        }
        putAll(workspacePayloads)
        val allResources = dao.resources()
        // 会话可能因正在生成而延迟落地；先重试进度，未匹配的负载继续进入快照，不能误发删除。
        retryPendingMemoryProgress(allResources)
        dao.usage().forEach {
            put(
                "usage_record" to it.syncId,
                withoutLocalId(json.encodeToJsonElement(it).jsonObject)
            )
        }
        dao.translations().forEach { put("translation_history" to it.id, json.encodeToString(it)) }
        dao.generatedMedia().forEach { entity ->
            serializeGenMedia(entity)?.let { put("gen_media" to entity.syncId, it) }
        }
        dao.managedFiles().filter { ExtendedSyncTypes.isResourcePath(it.relativePath) }.forEach {
            put("managed_file" to pathId(it.relativePath), withoutLocalId(json.encodeToJsonElement(it).jsonObject))
        }
        val progressList = dao.memoryProgress()
        if (progressList.isNotEmpty()) {
            val nodesByConv = loadNodesGroupedByConversation(progressList.map { it.conversationId })
            progressList.forEach { progress ->
                val ids = branchIdsFromNodes(nodesByConv[progress.conversationId].orEmpty())
                put(
                    "memory_progress" to progress.conversationId,
                    json.encodeToString(
                        MemoryProgressSyncPayload(
                            progress.conversationId,
                            progress.consolidatedAt,
                            CanonicalSyncHash.sha256Hex(ids.joinToString("\n")),
                            ids
                        )
                    )
                )
            }
        }
        collectPrefsAndFileResources(this, allResources)
    }

    override suspend fun snapshotFilesAndPrefs(): Map<Pair<String, String>, String> = buildMap {
        val allResources = dao.resources()
        retryPendingMemoryProgress(allResources)
        collectPrefsAndFileResources(this, allResources)
    }

    override suspend fun snapshotEntity(type: String, id: String): String? {
        if (type !in ExtendedSyncTypes.all) return null
        return when (type) {
            "workspace" -> {
                if (id in workspaceIssues) return null
                workspacePayloads[type to id]?.let { return it }
                val entity = db.workspaceDao().getById(id) ?: return null
                try {
                    json.encodeToString(WorkspaceSyncPayload.fromEntity(entity, decrypt)).also {
                        workspacePayloads[type to id] = it
                    }
                } catch (error: WorkspaceCredentialUnavailable) {
                    workspaceIssues[entity.id] = SyncCoverageIssue("workspace", entity.id, error.message.orEmpty())
                    null
                }
            }
            "usage_record" -> dao.usageById(id)?.let { withoutLocalId(json.encodeToJsonElement(it).jsonObject) }
            "translation_history" -> dao.translationById(id)?.let { json.encodeToString(it) }
            "gen_media" -> dao.generatedMediaById(id)?.let { serializeGenMedia(it) }
            "managed_file" -> dao.managedFiles().firstOrNull {
                ExtendedSyncTypes.isResourcePath(it.relativePath) && pathId(it.relativePath) == id
            }?.let { withoutLocalId(json.encodeToJsonElement(it).jsonObject) }
            "memory_progress" -> {
                val pending = dao.resource("memory_progress", id)
                if (pending != null && !pending.deleted && pending.materializedHash.isEmpty()) {
                    if (applyMemoryProgress(json.decodeFromString(pending.payload))) {
                        dao.putResource(
                            pending.copy(
                                materializedHash = CanonicalSyncHash.computeSemanticHash("memory_progress", pending.payload)
                            )
                        )
                    } else {
                        return pending.payload
                    }
                }
                val consolidatedAt = db.memoryConsolidationDao().getConsolidatedAt(id) ?: return null
                val ids = branchIds(id)
                json.encodeToString(
                    MemoryProgressSyncPayload(
                        id,
                        consolidatedAt,
                        CanonicalSyncHash.sha256Hex(ids.joinToString("\n")),
                        ids
                    )
                )
            }
            "ui_preference" -> {
                val kind = ExtendedSyncTypes.sharedPreferenceKeys[id] ?: return null
                if (!preferences.contains(id)) return null
                val value = if (kind == "boolean") {
                    JsonPrimitive(preferences.getBoolean(id, false))
                } else {
                    JsonPrimitive(preferences.getString(id, "").orEmpty())
                }
                JsonObject(mapOf("value" to value)).toString()
            }
            "skill_bundle" -> {
                val pending = dao.resource(type, id)
                if (pending != null && !pending.deleted && pending.materializedHash.isEmpty()) {
                    return pending.payload
                }
                val skills = File(filesDir, "skills")
                val dir = skills.listFiles()?.firstOrNull {
                    it.isDirectory && !it.name.startsWith('.') &&
                        !Files.isSymbolicLink(it.toPath()) && pathId(it.name) == id
                } ?: return null
                if (!File(dir, "SKILL.md").isFile) return null
                val diskCache = fastCache.getAllFileCache()
                val newCacheWrites = ArrayList<SyncLocalFastCache.CachedFileEntry>()
                val files = regularFiles(dir).map {
                    blob(it, it.relativeTo(dir).invariantSeparatorsPath, diskCache, newCacheWrites)
                }
                fastCache.upsertFileCacheBatch(newCacheWrites)
                val bundle = SkillBundleSyncPayload(dir.name, files).validate()
                json.encodeToString(bundle)
            }
            "file_asset" -> {
                val pending = dao.resource(type, id)
                if (pending != null && !pending.deleted && pending.materializedHash.isEmpty()) {
                    return pending.payload
                }
                val diskCache = fastCache.getAllFileCache()
                val cachedMatch = diskCache.keys.firstOrNull { path ->
                    ExtendedSyncTypes.isResourcePath(path) && pathId(path) == id
                }?.let { File(filesDir, it) }?.takeIf { it.isFile && !Files.isSymbolicLink(it.toPath()) }
                val targetFile = cachedMatch ?: ExtendedSyncTypes.resourceRoots.asSequence()
                    .map { File(filesDir, it) }
                    .filter { it.isDirectory }
                    .flatMap { regularFiles(it).asSequence() }
                    .firstOrNull { file ->
                        val relative = file.relativeTo(filesDir).invariantSeparatorsPath
                        pathId(relative) == id
                    } ?: return null
                val relative = targetFile.relativeTo(filesDir).invariantSeparatorsPath
                val newCacheWrites = ArrayList<SyncLocalFastCache.CachedFileEntry>()
                val asset = blob(targetFile, relative, diskCache, newCacheWrites).validate()
                fastCache.upsertFileCacheBatch(newCacheWrites)
                val raw = json.encodeToString(asset)
                if (pending?.deleted == true &&
                    CanonicalSyncHash.computeSemanticHash("file_asset", pending.payload) ==
                    CanonicalSyncHash.computeSemanticHash("file_asset", raw)) {
                    null
                } else {
                    raw
                }
            }
            else -> null
        }
    }

    private suspend fun retryPendingMemoryProgress(allResources: List<SyncResourceEntity>) {
        allResources.filter { it.entityType == "memory_progress" && !it.deleted && it.materializedHash.isEmpty() }
            .forEach { pending ->
                if (applyMemoryProgress(json.decodeFromString(pending.payload))) {
                    dao.putResource(
                        pending.copy(
                            materializedHash = CanonicalSyncHash.computeSemanticHash("memory_progress", pending.payload)
                        )
                    )
                }
            }
    }

    private fun serializeGenMedia(entity: GenMediaEntity): String? {
        val portable = entity.copy(
            path = AttachmentPathNormalizer.normalizeAttachmentUrl(entity.path, filesDir),
            sourcePaths = entity.sourcePaths?.lines()?.joinToString("\n") { path ->
                AttachmentPathNormalizer.normalizeAttachmentUrl(path, filesDir)
            }
        )
        if (!ExtendedSyncTypes.isResourcePath(portable.path)) return null
        return withoutLocalId(json.encodeToJsonElement(portable).jsonObject)
    }

    private fun collectPrefsAndFileResources(
        target: MutableMap<Pair<String, String>, String>,
        allResources: List<SyncResourceEntity>,
    ) {
        ExtendedSyncTypes.sharedPreferenceKeys.forEach { (key, kind) ->
            if (preferences.contains(key)) {
                val value = if (kind == "boolean") {
                    JsonPrimitive(preferences.getBoolean(key, false))
                } else {
                    JsonPrimitive(preferences.getString(key, "").orEmpty())
                }
                target["ui_preference" to key] = JsonObject(mapOf("value" to value)).toString()
            }
        }
        val resources = allResources.associateBy { it.entityType to it.entityId }
        // 未安装完的资源仍属于用户数据。删除墓碑则压制相同旧文件，防止另一端清理后重新上传复活。
        resources.values.filter { !it.deleted && it.materializedHash.isEmpty() }.forEach {
            target[it.entityType to it.entityId] = it.payload
        }
        val diskCache = fastCache.getAllFileCache()
        val newCacheWrites = ArrayList<SyncLocalFastCache.CachedFileEntry>()
        val skills = File(filesDir, "skills")
        skills.listFiles()?.filter {
            it.isDirectory && !it.name.startsWith(
                '.'
            ) && !Files.isSymbolicLink(it.toPath())
        }?.sortedBy { it.name }?.forEach { dir ->
            if (!File(dir, "SKILL.md").isFile) return@forEach
            val pending = resources["skill_bundle" to pathId(dir.name)]
            if (pending != null && !pending.deleted && pending.materializedHash.isEmpty()) return@forEach
            val files = regularFiles(dir).map {
                blob(it, it.relativeTo(dir).invariantSeparatorsPath, diskCache, newCacheWrites)
            }
            val bundle = SkillBundleSyncPayload(dir.name, files).validate()
            target["skill_bundle" to pathId(dir.name)] = json.encodeToString(bundle)
        }
        for (name in ExtendedSyncTypes.resourceRoots) {
            val root = File(filesDir, name)
            if (!root.isDirectory) continue
            regularFiles(root).forEach { file ->
                val relative = file.relativeTo(filesDir).invariantSeparatorsPath
                val pending = resources["file_asset" to pathId(relative)]
                if (pending != null && !pending.deleted && pending.materializedHash.isEmpty()) return@forEach
                val asset = blob(file, relative, diskCache, newCacheWrites).validate()
                val key = "file_asset" to pathId(relative)
                val raw = json.encodeToString(asset)
                val prior = resources[key]
                if (prior?.deleted != true ||
                    CanonicalSyncHash.computeSemanticHash("file_asset", prior.payload) !=
                    CanonicalSyncHash.computeSemanticHash("file_asset", raw)) {
                    target[key] = raw
                }
            }
        }
        fastCache.upsertFileCacheBatch(newCacheWrites)
    }

    private fun withoutLocalId(value: JsonObject) = JsonObject(value - "id").toString()

    private suspend fun loadNodesGroupedByConversation(
        conversationIds: List<String>,
    ): Map<String, List<me.rerere.rikkahub.data.db.entity.MessageNodeEntity>> {
        val grouped = HashMap<String, MutableList<me.rerere.rikkahub.data.db.entity.MessageNodeEntity>>()
        conversationIds.chunked(500).forEach { chunk ->
            dao.messageNodesForConversations(chunk).forEach { node ->
                grouped.getOrPut(node.conversationId) { ArrayList() }.add(node)
            }
        }
        return grouped
    }

    private fun branchIdsFromNodes(
        nodes: List<me.rerere.rikkahub.data.db.entity.MessageNodeEntity>,
    ): List<String> = nodes.sortedBy { it.nodeIndex }.map { node ->
        val messages = json.decodeFromString<List<UIMessage>>(node.messages)
        "${node.id}:${messages.getOrNull(node.selectIndex)?.id}"
    }

    private suspend fun branchIds(conversationId: String): List<String> {
        return branchIdsFromNodes(db.messageNodeDao().getNodesOfConversation(conversationId))
    }

    private fun regularFiles(root: File): List<File> = root.walkTopDown()
        .onEnter { !Files.isSymbolicLink(it.toPath()) && (it == root || !it.name.startsWith('.')) }
        .filter {
            it.isFile && !Files.isSymbolicLink(it.toPath()) && !it.name.startsWith('.') &&
                !it.name.endsWith(".tmp")
        }
        .sortedBy { it.relativeTo(root).invariantSeparatorsPath }.toList()

    private fun blob(
        source: File,
        relativePath: String,
        diskCache: Map<String, SyncLocalFastCache.CachedFileEntry>? = null,
        pendingCacheWrites: MutableList<SyncLocalFastCache.CachedFileEntry>? = null,
    ): SyncBlobFile {
        val cacheKey = runCatching { source.relativeTo(filesDir).invariantSeparatorsPath }
            .getOrDefault(source.absolutePath)
        val sourceLength = source.length()
        val sourceModified = source.lastModified()
        fingerprints[cacheKey]?.let { cached ->
            if (cached.length == sourceLength && cached.modified == sourceModified &&
                File(filesDir, cached.file.blobPath).let {
                    it.isFile && it.length() == cached.length &&
                        it.lastModified() == cached.blobModified
                }) {
                return cached.file.copy(path = relativePath)
            }
        }
        val persisted = diskCache?.get(cacheKey) ?: fastCache.getFileCache(cacheKey)
        if (persisted != null && persisted.blobPath.isNotEmpty() &&
            persisted.size == sourceLength && persisted.modified == sourceModified) {
            val blobFile = File(filesDir, persisted.blobPath)
            if (blobFile.isFile && blobFile.length() == sourceLength &&
                blobFile.lastModified() == persisted.blobModified) {
                val result = SyncBlobFile(relativePath, persisted.blobPath, persisted.sha256, sourceLength)
                fingerprints[cacheKey] = Fingerprint(sourceLength, sourceModified, persisted.blobModified, result)
                return result
            }
        }
        val dir = File(filesDir, "sync_blobs").apply { mkdirs() }
        val temporary = File(dir, ".sync_${Uuid.random()}.tmp")
        try {
            source.inputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
            check(sourceLength == source.length() && sourceModified == source.lastModified()) { "文件正在修改，请稍后重试同步" }
            val sha = CanonicalSyncHash.sha256File(temporary)
            val destination = File(dir, sha)
            val blobRel = "sync_blobs/$sha"
            val destCached = diskCache?.get(blobRel) ?: fastCache.getFileCache(blobRel)
            val destValidFromCache = destCached != null && destination.isFile &&
                destination.length() == destCached.size &&
                destination.lastModified() == destCached.modified &&
                destCached.sha256 == sha
            if (!destination.isFile || destination.length() != temporary.length() ||
                (!destValidFromCache && CanonicalSyncHash.sha256File(destination) != sha)) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            val destModified = destination.lastModified()
            val result = SyncBlobFile(relativePath, blobRel, sha, sourceLength)
            fingerprints[cacheKey] = Fingerprint(sourceLength, sourceModified, destModified, result)
            val sourceCacheEntry = SyncLocalFastCache.CachedFileEntry(
                path = cacheKey,
                size = sourceLength,
                modified = sourceModified,
                sha256 = sha,
                blobPath = blobRel,
                blobModified = destModified,
                verifiedServerId = if (persisted?.verifiedSha256 == sha) persisted.verifiedServerId else "",
                verifiedSha256 = if (persisted?.verifiedSha256 == sha) persisted.verifiedSha256 else "",
                verifiedSize = if (persisted?.verifiedSha256 == sha) persisted.verifiedSize else 0L,
            )
            val blobCacheEntry = SyncLocalFastCache.CachedFileEntry(
                path = blobRel,
                size = sourceLength,
                modified = destModified,
                sha256 = sha,
                verifiedServerId = if (destCached?.verifiedSha256 == sha) destCached.verifiedServerId else "",
                verifiedSha256 = if (destCached?.verifiedSha256 == sha) destCached.verifiedSha256 else "",
                verifiedSize = if (destCached?.verifiedSha256 == sha) destCached.verifiedSize else 0L,
            )
            if (pendingCacheWrites != null) {
                pendingCacheWrites.add(sourceCacheEntry)
                pendingCacheWrites.add(blobCacheEntry)
            } else {
                fastCache.upsertFileCacheBatch(listOf(sourceCacheEntry, blobCacheEntry))
            }
            return result
        } finally {
            temporary.delete()
        }
    }

    override suspend fun apply(type: String, id: String, payload: String?) {
        require(type in ExtendedSyncTypes.all)
        if (payload == null) {
            delete(type, id)
            return
        }
        when (type) {
            "workspace" -> {
                val value = json.decodeFromString<WorkspaceSyncPayload>(payload).validate()
                require(value.id == id)
                val received = value.toEntity(db.workspaceDao().getById(id), encrypt)
                db.workspaceDao().upsert(received)
                sshPool?.close(id)
                if (value.type == "LOCAL") {
                    // 仅建立空的本机目录。不会传输文件或安装、替换 Linux 环境。
                    listOf(
                        "files",
                        "linux",
                        "tmp"
                    ).forEach { File(filesDir, "workspaces/${received.root}/$it").mkdirs() }
                }
            }
            "usage_record" -> {
                val value = json.decodeFromString<UsageRecordEntity>(payload)
                require(value.syncId == id)
                dao.upsertUsage(value.copy(id = dao.usageById(id)?.id ?: 0L))
            }
            "translation_history" -> {
                val value = json.decodeFromString<TranslationHistoryEntity>(payload)
                require(value.id == id)
                dao.upsertTranslation(value)
            }
            "gen_media" -> {
                val value = json.decodeFromString<GenMediaEntity>(payload)
                require(value.syncId == id && ExtendedSyncTypes.isResourcePath(value.path))
                dao.upsertGeneratedMedia(value.copy(id = dao.generatedMediaById(id)?.id ?: 0))
            }
            "managed_file" -> {
                val value = json.decodeFromString<ManagedFileEntity>(payload)
                require(
                    pathId(value.relativePath) == id &&
                        ExtendedSyncTypes.isResourcePath(value.relativePath)
                )
                dao.upsertManagedFile(value.copy(id = db.managedFileDao().getByPath(value.relativePath)?.id ?: 0L))
            }
            "memory_progress" -> {
                val value = json.decodeFromString<MemoryProgressSyncPayload>(payload)
                require(value.conversationId == id)
                val applied = applyMemoryProgress(value)
                dao.putResource(SyncResourceEntity(
                    type,
                    id,
                    payload,
                    materializedHash = if (applied) CanonicalSyncHash.computeSemanticHash(type, payload) else ""
                ))
            }
            "ui_preference" -> {
                val kind = ExtendedSyncTypes.sharedPreferenceKeys[id] ?: error("未知的共享偏好")
                val value = json.parseToJsonElement(payload).jsonObject.getValue("value").jsonPrimitive
                preferences.edit(commit = true) {
                    if (kind == "boolean") putBoolean(id, value.boolean) else putString(id, value.content)
                }
            }
            "skill_bundle", "file_asset" -> {
                if (type == "skill_bundle") {
                    require(pathId(json.decodeFromString<SkillBundleSyncPayload>(payload).validate().directory) == id)
                } else {
                    val value = json.decodeFromString<SyncBlobFile>(payload).validate()
                    require(
                        ExtendedSyncTypes.isResourcePath(value.path) &&
                            pathId(value.path) == id
                    )
                }
                val old = dao.resource(type, id)
                if (old?.payload != payload ||
                    old.deleted) {
                    dao.putResource(
                        SyncResourceEntity(
                            type,
                            id,
                            payload,
                            localHash = currentResource(
                                type,
                                payload
                            )?.let { CanonicalSyncHash.computeSemanticHash(type, it) }.orEmpty()
                        )
                    )
                }
            }
        }
    }

    private suspend fun delete(type: String, id: String) {
        when (type) {
            // 远端删除只移除配置，禁止调用会删除本机文件的 WorkspaceRepository.delete。
            "workspace" -> {
                db.workspaceDao().deleteById(id)
                sshPool?.close(id)
            }
            "usage_record" -> dao.deleteUsage(id)
            "translation_history" -> db.translationHistoryDao().deleteById(id)
            "gen_media" -> dao.deleteGeneratedMedia(id)
            "managed_file" -> dao.managedFiles().firstOrNull {
                pathId(
                    it.relativePath
                ) == id
            }?.let { db.managedFileDao().deleteById(it.id) }
            "memory_progress" -> {
                dao.deleteMemoryProgress(id)
                dao.resource(type, id)?.let { dao.putResource(it.copy(deleted = true)) }
            }
            "ui_preference" -> {
                require(id in ExtendedSyncTypes.sharedPreferenceKeys)
                preferences.edit(commit = true) { remove(id) }
            }
            "skill_bundle", "file_asset" -> (dao.resource(type, id) ?: snapshot()[type to id]?.let {
                SyncResourceEntity(type, id, it)
            })?.let {
                if (type == "skill_bundle") {
                    val bundle = json.decodeFromString<SkillBundleSyncPayload>(it.payload).validate()
                    File(filesDir, "skills/${bundle.directory}").deleteRecursively()
                }
                // 附件字节可能仍被消息或冲突副本引用，删除清单不直接删除物理文件。
                dao.putResource(it.copy(deleted = true))
            }
        }
    }

    override suspend fun managedTargets(): Set<String> = dao.resources().filter {
        !it.deleted &&
            it.entityType == "file_asset"
    }
        .mapTo(HashSet()) { json.decodeFromString<SyncBlobFile>(it.payload).path }

    override suspend fun confirmLocal(type: String, id: String, payload: String?) {
        if (type !in setOf("skill_bundle", "file_asset")) return
        if (payload != null) {
            val pending = dao.resource(type, id)
            if (pending != null && !pending.deleted && pending.materializedHash.isEmpty() &&
                CanonicalSyncHash.computeSemanticHash(type, pending.payload) ==
                CanonicalSyncHash.computeSemanticHash(type, payload)) {
                // 用户选用冲突副本后，推送确认可能早于字节落地；不能把旧磁盘内容再排队覆盖选定版本。
                return
            }
            dao.putResource(SyncResourceEntity(type, id, payload, CanonicalSyncHash.computeSemanticHash(type, payload)))
            val latest = currentResource(type, payload)
            if (latest != payload &&
                (latest == null ||
                    CanonicalSyncHash.computeSemanticHash(type, latest) !=
                    CanonicalSyncHash.computeSemanticHash(type, payload))) {
                val recorder = SyncMutationRecorder(db, db.syncV2Dao()) { filesDir }
                if (latest == null) recorder.recordDelete(type, id) else recorder.recordUpsert(type, id, latest)
            }
        } else {
            dao.resource(type, id)?.let { dao.putResource(it.copy(deleted = true)) }
        }
    }

    private fun currentResource(type: String, payload: String): String? = if (type == "file_asset") {
        val value = json.decodeFromString<SyncBlobFile>(payload).validate()
        val target = confined(value.path)
        if (target.isFile) json.encodeToString(blob(target, value.path)) else null
    } else {
        val value = json.decodeFromString<SkillBundleSyncPayload>(payload).validate()
        val target = confined("skills/${value.directory}")
        if (File(target, "SKILL.md").isFile) {
            json.encodeToString(
                SkillBundleSyncPayload(
                    value.directory,
                    regularFiles(target).map { blob(it, it.relativeTo(target).invariantSeparatorsPath) }
                )
            )
        } else {
            null
        }
    }

    private suspend fun protectEditsDuringDownload(resource: SyncResourceEntity) {
        if (resource.materializedHash.isNotEmpty()) return
        val local = currentResource(resource.entityType, resource.payload)
        val hash = local?.let { CanonicalSyncHash.computeSemanticHash(resource.entityType, it) }.orEmpty()
        if (hash == resource.localHash) return
        val state = db.syncV2Dao().getEntityState(resource.entityType, resource.entityId)
        db.syncV2Dao().upsertConflict(SyncConflictEntity(
            id = "resource-${Uuid.random()}", entityType = resource.entityType, entityId = resource.entityId,
            title = "资源下载期间的本地修改", operation = if (local == null) "delete" else "upsert",
            baseRevision = state?.lastSyncedRevision ?: 0L, serverRevision = state?.lastSyncedRevision ?: 0L,
            localPayload = local.orEmpty(), serverPayload = resource.payload, createdAt = System.currentTimeMillis(),
        ))
    }

    override suspend fun materializeResources() {
        for (resource in dao.resources().filter {
            !it.deleted && it.entityType in setOf(
                "file_asset",
                "skill_bundle"
            )
        }) {
            val hash = CanonicalSyncHash.computeSemanticHash(resource.entityType, resource.payload)
            if (db.syncV2Dao().getOutboxEntry(resource.entityType, resource.entityId) != null) continue
            if (resource.entityType == "file_asset") {
                val file = json.decodeFromString<SyncBlobFile>(resource.payload).validate()
                val target = confined(file.path)
                if (resource.materializedHash == hash && target.isFile) continue
                if (!verifiedBlob(file)) continue
                protectEditsDuringDownload(resource)
                if (!target.isFile || target.length() != file.size ||
                    CanonicalSyncHash.sha256File(target) != file.sha256) {
                    target.parentFile?.mkdirs()
                    val temporary = File(target.parentFile, ".sync_${Uuid.random()}.tmp")
                    try {
                        File(filesDir, file.blobPath).copyTo(temporary, overwrite = true)
                        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    } finally {
                        temporary.delete()
                    }
                }
            } else if (resource.entityType == "skill_bundle") {
                val bundle = json.decodeFromString<SkillBundleSyncPayload>(resource.payload).validate()
                val target = confined("skills/${bundle.directory}")
                if (resource.materializedHash == hash && bundle.files.all { File(target, it.path).isFile }) continue
                if (!bundle.files.all(::verifiedBlob)) continue
                protectEditsDuringDownload(resource)
                val staging = File(filesDir, "skills/.sync_${Uuid.random()}.tmp").apply { mkdirs() }
                val backup = File(filesDir, "skills/.sync_${Uuid.random()}.backup")
                try {
                    for (file in bundle.files) {
                        val destination = File(staging, file.path)
                        destination.parentFile?.mkdirs()
                        File(filesDir, file.blobPath).copyTo(destination, overwrite = true)
                    }
                    check(!target.exists() || target.renameTo(backup)) { "无法暂存现有技能" }
                    if (!staging.renameTo(target)) {
                        if (backup.exists()) backup.renameTo(target)
                        error("无法安装同步技能")
                    }
                    backup.deleteRecursively()
                } finally {
                    staging.deleteRecursively()
                }
            }
            dao.putResource(resource.copy(materializedHash = hash))
        }
    }

    private suspend fun applyMemoryProgress(value: MemoryProgressSyncPayload): Boolean {
        require(
            value.consolidatedAt >= 0 &&
                value.branchHash == CanonicalSyncHash.sha256Hex(value.branchIds.joinToString("\n"))
        )
        val currentBranch = branchIds(value.conversationId)
        if (!db.conversationDao().existsById(value.conversationId) || value.branchIds.isEmpty() ||
            currentBranch.take(value.branchIds.size) != value.branchIds) {
            return false
        }
        val previous = db.memoryConsolidationDao().getConsolidatedAt(value.conversationId) ?: 0L
        db.memoryConsolidationDao().upsert(
            MemoryConsolidationEntity(value.conversationId, maxOf(previous, value.consolidatedAt))
        )
        return true
    }

    private fun verifiedBlob(file: SyncBlobFile): Boolean {
        val blob = File(filesDir, file.blobPath)
        if (!blob.isFile || blob.length() != file.size) return false
        val modified = blob.lastModified()
        val cached = fastCache.getFileCache(file.blobPath)
        if (cached != null && cached.size == file.size && cached.modified == modified && cached.sha256 == file.sha256) {
            return true
        }
        if (CanonicalSyncHash.sha256File(blob) != file.sha256) return false
        fastCache.upsertFileCache(
            SyncLocalFastCache.CachedFileEntry(
                path = file.blobPath,
                size = file.size,
                modified = modified,
                sha256 = file.sha256,
                verifiedServerId = if (cached?.verifiedSha256 == file.sha256) cached.verifiedServerId else "",
                verifiedSha256 = if (cached?.verifiedSha256 == file.sha256) cached.verifiedSha256 else "",
                verifiedSize = if (cached?.verifiedSha256 == file.sha256) cached.verifiedSize else 0L,
            )
        )
        return true
    }

    private fun confined(path: String): File {
        require(AttachmentPathNormalizer.isManagedRelativePath(path))
        val root = filesDir.canonicalFile
        return File(root, path).canonicalFile.also { require(it.path.startsWith(root.path + File.separator)) }
    }

    companion object {
        fun pathId(path: String): String = CanonicalSyncHash.sha256Hex(path)
    }
}
