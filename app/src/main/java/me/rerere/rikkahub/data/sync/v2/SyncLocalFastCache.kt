package me.rerere.rikkahub.data.sync.v2

import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.AppDatabase

/**
 * Sync V2 本地快速缓存与脏实体追踪器（不改动 Room Schema，兼容现有 Migration_33_34）。
 *
 * 1. `sync_local_dirty`：由 SQLite `AFTER INSERT / UPDATE / DELETE` 触发器在业务写事务内原子维护，
 *    使常规增量同步只需加载并哈希实际发生变更的实体（$O(\Delta)$），避免每轮同步反序列化全库会话。
 * 2. `sync_file_cache`：持久化本地受管文件的 `(size, modified, sha256, blob_path, blob_modified)` 与
 *    已向当前 `server_id` 确认存在的校验记录，消除手表进程重启后重复拷贝与全盘 SHA-256 计算。
 */
class SyncLocalFastCache(
    private val database: AppDatabase?,
) {
    data class DirtyEntry(
        val entityType: String,
        val entityId: String,
        val dirtySeq: Long,
    )

    data class CachedFileEntry(
        val path: String,
        val size: Long,
        val modified: Long,
        val sha256: String,
        val blobPath: String = "",
        val blobModified: Long = 0L,
        val verifiedServerId: String = "",
        val verifiedSha256: String = "",
        val verifiedSize: Long = 0L,
    )

    @Volatile
    private var initialized = false

    val isSupported: Boolean get() = database != null

    private fun writableDb(): SupportSQLiteDatabase? {
        val db = database?.openHelper?.writableDatabase ?: return null
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    createTablesAndTriggers(db)
                    initialized = true
                }
            }
        }
        return db
    }

    private fun readableDb(): SupportSQLiteDatabase? {
        // 先确保表与触发器已建立（兼容未使用 AppDatabaseFactory 的内存测试库）
        if (!initialized) writableDb()
        return database?.openHelper?.readableDatabase
    }

    fun getDirtyEntries(): List<DirtyEntry> {
        val db = readableDb() ?: return emptyList()
        val result = ArrayList<DirtyEntry>()
        db.query("SELECT entity_type, entity_id, dirty_seq FROM sync_local_dirty").use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    DirtyEntry(
                        entityType = cursor.getString(0),
                        entityId = cursor.getString(1),
                        dirtySeq = cursor.getLong(2),
                    )
                )
            }
        }
        return result
    }

    fun getDirtySeq(entityType: String, entityId: String): Long {
        val db = readableDb() ?: return 0L
        db.query(
            "SELECT dirty_seq FROM sync_local_dirty WHERE entity_type = ? AND entity_id = ? LIMIT 1",
            arrayOf(entityType, entityId),
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    fun clearDirtyIfSeq(entityType: String, entityId: String, expectedSeq: Long) {
        if (expectedSeq <= 0L) return
        val db = writableDb() ?: return
        db.execSQL(
            "DELETE FROM sync_local_dirty WHERE entity_type = ? AND entity_id = ? AND dirty_seq = ?",
            arrayOf<Any>(entityType, entityId, expectedSeq),
        )
    }

    fun clearDirtyEntries(entries: Collection<DirtyEntry>) {
        if (entries.isEmpty()) return
        val db = writableDb() ?: return
        db.beginTransaction()
        try {
            val stmt = db.compileStatement(
                "DELETE FROM sync_local_dirty WHERE entity_type = ? AND entity_id = ? AND dirty_seq = ?"
            )
            for (entry in entries) {
                stmt.clearBindings()
                stmt.bindString(1, entry.entityType)
                stmt.bindString(2, entry.entityId)
                stmt.bindLong(3, entry.dirtySeq)
                stmt.executeUpdateDelete()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearAllDirty() {
        val db = writableDb() ?: return
        db.execSQL("DELETE FROM sync_local_dirty")
    }

    /**
     * 轻量级查询当前本地存活的实体主键集合（不读取会话消息正文 JSON）。
     * 用于在快速增量路径下做到 100% 精确的本地删除检测。
     */
    fun loadLiveEntityKeys(
        includeExtended: Boolean,
        memoryEntityIdOf: (assistantId: String, path: String) -> String,
        pathIdOf: (String) -> String,
        isResourcePath: (String) -> Boolean,
    ): Set<Pair<String, String>>? {
        val db = readableDb() ?: return null
        val keys = HashSet<Pair<String, String>>(1024)

        // 跳过尚未产生实际内容的空白草稿会话
        db.query(
            """
            SELECT id FROM conversationentity
            WHERE TRIM(title) != ''
               OR EXISTS(SELECT 1 FROM message_node m WHERE m.conversation_id = conversationentity.id)
            """.trimIndent()
        ).use { c ->
            while (c.moveToNext()) keys.add("conversation" to c.getString(0))
        }

        db.query("SELECT id FROM conversation_folder").use { c ->
            while (c.moveToNext()) keys.add("folder" to c.getString(0))
        }

        db.query("SELECT id FROM favorites").use { c ->
            while (c.moveToNext()) keys.add("favorite" to c.getString(0))
        }

        db.query("SELECT id FROM media_creation_session").use { c ->
            while (c.moveToNext()) keys.add("media_session" to c.getString(0))
        }
        db.query("SELECT id FROM media_creation_node").use { c ->
            while (c.moveToNext()) keys.add("media_node" to c.getString(0))
        }
        db.query("SELECT id FROM media_creation_record").use { c ->
            while (c.moveToNext()) keys.add("media_record" to c.getString(0))
        }

        db.query("SELECT assistant_id, path FROM memory_file").use { c ->
            while (c.moveToNext()) {
                keys.add("memory_file" to memoryEntityIdOf(c.getString(0), c.getString(1)))
            }
        }

        if (includeExtended) {
            db.query("SELECT id FROM workspaces").use { c ->
                while (c.moveToNext()) keys.add("workspace" to c.getString(0))
            }
            db.query("SELECT sync_id FROM usage_record WHERE sync_id != ''").use { c ->
                while (c.moveToNext()) keys.add("usage_record" to c.getString(0))
            }
            db.query("SELECT sync_id, path FROM genmediaentity WHERE sync_id != ''").use { c ->
                while (c.moveToNext()) {
                    if (isResourcePath(c.getString(1))) {
                        keys.add("gen_media" to c.getString(0))
                    }
                }
            }
            db.query("SELECT conversation_id FROM memory_consolidation").use { c ->
                while (c.moveToNext()) keys.add("memory_progress" to c.getString(0))
            }
            db.query(
                "SELECT entityType, entityId FROM sync_resources WHERE deleted = 0 AND materializedHash = ''"
            ).use { c ->
                while (c.moveToNext()) keys.add(c.getString(0) to c.getString(1))
            }
            // managed_file 与 translation_history 不参与本地删除扫库，此处无需收集
            db.query("SELECT relative_path FROM managed_files").use { c ->
                while (c.moveToNext()) {
                    val rel = c.getString(0)
                    if (isResourcePath(rel)) keys.add("managed_file" to pathIdOf(rel))
                }
            }
        }

        return keys
    }

    fun getFileCache(path: String): CachedFileEntry? {
        val db = readableDb() ?: return null
        db.query(
            """
            SELECT path, size, modified, sha256, blob_path, blob_modified,
                   verified_server_id, verified_sha256, verified_size
            FROM sync_file_cache WHERE path = ? LIMIT 1
            """.trimIndent(),
            arrayOf(path),
        ).use { c ->
            if (!c.moveToFirst()) return null
            return CachedFileEntry(
                path = c.getString(0),
                size = c.getLong(1),
                modified = c.getLong(2),
                sha256 = c.getString(3),
                blobPath = c.getString(4),
                blobModified = c.getLong(5),
                verifiedServerId = c.getString(6),
                verifiedSha256 = c.getString(7),
                verifiedSize = c.getLong(8),
            )
        }
    }

    fun getAllFileCache(): Map<String, CachedFileEntry> {
        val db = readableDb() ?: return emptyMap()
        val map = HashMap<String, CachedFileEntry>()
        db.query(
            """
            SELECT path, size, modified, sha256, blob_path, blob_modified,
                   verified_server_id, verified_sha256, verified_size
            FROM sync_file_cache
            """.trimIndent()
        ).use { c ->
            while (c.moveToNext()) {
                val entry = CachedFileEntry(
                    path = c.getString(0),
                    size = c.getLong(1),
                    modified = c.getLong(2),
                    sha256 = c.getString(3),
                    blobPath = c.getString(4),
                    blobModified = c.getLong(5),
                    verifiedServerId = c.getString(6),
                    verifiedSha256 = c.getString(7),
                    verifiedSize = c.getLong(8),
                )
                map[entry.path] = entry
            }
        }
        return map
    }

    fun upsertFileCache(entry: CachedFileEntry) {
        upsertFileCacheBatch(listOf(entry))
    }

    fun upsertFileCacheBatch(entries: Collection<CachedFileEntry>) {
        if (entries.isEmpty()) return
        val db = writableDb() ?: return
        db.beginTransaction()
        try {
            val stmt = db.compileStatement(
                """
                INSERT OR REPLACE INTO sync_file_cache (
                    path, size, modified, sha256, blob_path, blob_modified,
                    verified_server_id, verified_sha256, verified_size
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            )
            for (e in entries) {
                stmt.clearBindings()
                stmt.bindString(1, e.path)
                stmt.bindLong(2, e.size)
                stmt.bindLong(3, e.modified)
                stmt.bindString(4, e.sha256)
                stmt.bindString(5, e.blobPath)
                stmt.bindLong(6, e.blobModified)
                stmt.bindString(7, e.verifiedServerId)
                stmt.bindString(8, e.verifiedSha256)
                stmt.bindLong(9, e.verifiedSize)
                stmt.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearServerVerifications() {
        val db = writableDb() ?: return
        db.execSQL(
            "UPDATE sync_file_cache SET verified_server_id = '', verified_sha256 = '', verified_size = 0"
        )
    }

    companion object {
        private const val CURRENT_TRIGGER_VERSION = 1

        fun createTablesAndTriggers(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS sync_local_dirty (
                    entity_type TEXT NOT NULL,
                    entity_id TEXT NOT NULL,
                    dirty_seq INTEGER NOT NULL,
                    PRIMARY KEY(entity_type, entity_id)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS sync_file_cache (
                    path TEXT PRIMARY KEY,
                    size INTEGER NOT NULL,
                    modified INTEGER NOT NULL,
                    sha256 TEXT NOT NULL,
                    blob_path TEXT NOT NULL DEFAULT '',
                    blob_modified INTEGER NOT NULL DEFAULT 0,
                    verified_server_id TEXT NOT NULL DEFAULT '',
                    verified_sha256 TEXT NOT NULL DEFAULT '',
                    verified_size INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS sync_fast_meta (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """.trimIndent()
            )

            val installedVer = db.query(
                "SELECT value FROM sync_fast_meta WHERE key = 'trigger_version' LIMIT 1"
            ).use { if (it.moveToFirst()) it.getString(0).toIntOrNull() ?: 0 else 0 }
            if (installedVer >= CURRENT_TRIGGER_VERSION) return

            val existingTables = HashSet<String>()
            db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
                while (c.moveToNext()) existingTables.add(c.getString(0))
            }

            fun upsertDirtySql(entityType: String, idExpr: String): String = """
                INSERT OR REPLACE INTO sync_local_dirty (entity_type, entity_id, dirty_seq)
                VALUES (
                    '$entityType',
                    $idExpr,
                    COALESCE((SELECT dirty_seq FROM sync_local_dirty WHERE entity_type = '$entityType' AND entity_id = $idExpr), 0) + 1
                );
            """.trimIndent()

            fun installPkTriggers(table: String, entityType: String, idCol: String = "id", guardNonEmpty: Boolean = false) {
                if (table !in existingTables) return
                val insertGuard = if (guardNonEmpty) "WHEN NEW.$idCol != ''" else ""
                val deleteGuard = if (guardNonEmpty) "WHEN OLD.$idCol != ''" else ""
                for (op in listOf("INSERT", "UPDATE", "DELETE")) {
                    db.execSQL("DROP TRIGGER IF EXISTS trg_sync_dirty_${table}_${op.lowercase()}")
                }
                db.execSQL(
                    """
                    CREATE TRIGGER trg_sync_dirty_${table}_insert AFTER INSERT ON $table
                    $insertGuard
                    BEGIN
                        ${upsertDirtySql(entityType, "NEW.$idCol")}
                    END
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TRIGGER trg_sync_dirty_${table}_update AFTER UPDATE ON $table
                    $insertGuard
                    BEGIN
                        ${upsertDirtySql(entityType, "NEW.$idCol")}
                    END
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TRIGGER trg_sync_dirty_${table}_delete AFTER DELETE ON $table
                    $deleteGuard
                    BEGIN
                        ${upsertDirtySql(entityType, "OLD.$idCol")}
                    END
                    """.trimIndent()
                )
            }

            db.beginTransaction()
            try {
                installPkTriggers("conversationentity", "conversation", "id")
                installPkTriggers("conversation_folder", "folder", "id")
                installPkTriggers("favorites", "favorite", "id")
                installPkTriggers("media_creation_session", "media_session", "id")
                installPkTriggers("media_creation_node", "media_node", "id")
                installPkTriggers("media_creation_record", "media_record", "id")
                installPkTriggers("workspaces", "workspace", "id")
                installPkTriggers("usage_record", "usage_record", "sync_id", guardNonEmpty = true)
                installPkTriggers("genmediaentity", "gen_media", "sync_id", guardNonEmpty = true)
                installPkTriggers("translation_history", "translation_history", "id")
                installPkTriggers("memory_consolidation", "memory_progress", "conversation_id")

                // message_node 变更同步标脏所属 conversation；若存在记忆整理进度或待落地进度也同步标脏 memory_progress
                if ("message_node" in existingTables) {
                    for (op in listOf("insert", "update", "delete")) {
                        db.execSQL("DROP TRIGGER IF EXISTS trg_sync_dirty_message_node_$op")
                        db.execSQL("DROP TRIGGER IF EXISTS trg_sync_dirty_message_node_mem_$op")
                    }
                    db.execSQL(
                        """
                        CREATE TRIGGER trg_sync_dirty_message_node_insert AFTER INSERT ON message_node
                        BEGIN
                            ${upsertDirtySql("conversation", "NEW.conversation_id")}
                        END
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TRIGGER trg_sync_dirty_message_node_update AFTER UPDATE ON message_node
                        BEGIN
                            ${upsertDirtySql("conversation", "NEW.conversation_id")}
                        END
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TRIGGER trg_sync_dirty_message_node_delete AFTER DELETE ON message_node
                        BEGIN
                            ${upsertDirtySql("conversation", "OLD.conversation_id")}
                        END
                        """.trimIndent()
                    )
                    if ("memory_consolidation" in existingTables && "sync_resources" in existingTables) {
                        fun memGuard(ref: String): String = """
                            WHEN EXISTS(SELECT 1 FROM memory_consolidation WHERE conversation_id = $ref)
                              OR EXISTS(SELECT 1 FROM sync_resources WHERE entityType = 'memory_progress' AND entityId = $ref AND deleted = 0 AND materializedHash = '')
                        """.trimIndent()
                        db.execSQL(
                            """
                            CREATE TRIGGER trg_sync_dirty_message_node_mem_insert AFTER INSERT ON message_node
                            ${memGuard("NEW.conversation_id")}
                            BEGIN
                                ${upsertDirtySql("memory_progress", "NEW.conversation_id")}
                            END
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TRIGGER trg_sync_dirty_message_node_mem_update AFTER UPDATE ON message_node
                            ${memGuard("NEW.conversation_id")}
                            BEGIN
                                ${upsertDirtySql("memory_progress", "NEW.conversation_id")}
                            END
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TRIGGER trg_sync_dirty_message_node_mem_delete AFTER DELETE ON message_node
                            ${memGuard("OLD.conversation_id")}
                            BEGIN
                                ${upsertDirtySql("memory_progress", "OLD.conversation_id")}
                            END
                            """.trimIndent()
                        )
                    }
                }

                // memory_file 与 managed_files 的 entityId 由 SHA-256 派生，标脏全局哨兵键，仅在实际发生变更时扫描
                for ((table, type) in listOf("memory_file" to "memory_file", "managed_files" to "managed_file")) {
                    if (table !in existingTables) continue
                    for (op in listOf("insert", "update", "delete")) {
                        db.execSQL("DROP TRIGGER IF EXISTS trg_sync_dirty_${table}_$op")
                        db.execSQL(
                            """
                            CREATE TRIGGER trg_sync_dirty_${table}_$op AFTER ${op.uppercase()} ON $table
                            BEGIN
                                ${upsertDirtySql(type, "'__scan_all__'")}
                            END
                            """.trimIndent()
                        )
                    }
                }

                db.execSQL(
                    "INSERT OR REPLACE INTO sync_fast_meta (key, value) VALUES ('trigger_version', ?)",
                    arrayOf<Any>(CURRENT_TRIGGER_VERSION.toString()),
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }
}
