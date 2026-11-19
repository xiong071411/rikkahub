package me.rerere.rikkahub.data.db.migrations

import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

internal object SyncSchemaMigration {
    // Upstream and personal releases both used database version 34 with different schemas.
    fun ensureCurrentSchema(db: SupportSQLiteDatabase) {
        ensureWorkspaceStorageMounts(db)
        ensureSyncTables(db)
        ensureSyncResources(db)
        ensureSyncIds(db, "usage_record")
        ensureSyncIds(db, "genmediaentity")
        ensureMessageNodeIndex(db)
    }

    fun ensureSyncTables(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_entity_state` (" +
                "`entity_type` TEXT NOT NULL, " +
                "`entity_id` TEXT NOT NULL, " +
                "`last_synced_revision` INTEGER NOT NULL, " +
                "`last_synced_hash` TEXT NOT NULL, " +
                "`deleted` INTEGER NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`entity_type`, `entity_id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_outbox` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`mutation_id` TEXT NOT NULL, " +
                "`entity_type` TEXT NOT NULL, " +
                "`entity_id` TEXT NOT NULL, " +
                "`operation` TEXT NOT NULL, " +
                "`base_revision` INTEGER NOT NULL, " +
                "`payload` TEXT NOT NULL, " +
                "`content_hash` TEXT NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "`retry_count` INTEGER NOT NULL, " +
                "`last_error` TEXT)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_outbox_entity_type_entity_id` " +
                "ON `sync_outbox` (`entity_type`, `entity_id`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_inbox_pending` (" +
                "`entity_type` TEXT NOT NULL, " +
                "`entity_id` TEXT NOT NULL, " +
                "`revision` INTEGER NOT NULL, " +
                "`operation` TEXT NOT NULL, " +
                "`payload` TEXT NOT NULL, " +
                "`content_hash` TEXT NOT NULL, " +
                "`received_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`entity_type`, `entity_id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_conflicts` (" +
                "`id` TEXT NOT NULL, " +
                "`entity_type` TEXT NOT NULL, " +
                "`entity_id` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`operation` TEXT NOT NULL, " +
                "`base_revision` INTEGER NOT NULL, " +
                "`server_revision` INTEGER NOT NULL, " +
                "`local_payload` TEXT NOT NULL, " +
                "`server_payload` TEXT NOT NULL, " +
                "`resolved` INTEGER NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_sync_conflicts_resolved_created_at` " +
                "ON `sync_conflicts` (`resolved`, `created_at`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_file_queue` (" +
                "`direction` TEXT NOT NULL, " +
                "`relative_path` TEXT NOT NULL, " +
                "`sha256` TEXT NOT NULL, " +
                "`size` INTEGER NOT NULL, " +
                "`retry_count` INTEGER NOT NULL, " +
                "`last_error` TEXT, " +
                "`updated_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`direction`, `relative_path`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_meta` (" +
                "`key` TEXT NOT NULL, " +
                "`value` TEXT NOT NULL, " +
                "PRIMARY KEY(`key`))"
        )
    }

    fun migrateLegacyTombstones(db: SupportSQLiteDatabase) {
        val hasLegacyTombstone = db.query(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'sync_tombstone'"
        ).use { cursor -> cursor.moveToFirst() }
        if (hasLegacyTombstone) {
            db.execSQL(
                """
                INSERT OR IGNORE INTO `sync_outbox` (
                    `mutation_id`, `entity_type`, `entity_id`, `operation`,
                    `base_revision`, `payload`, `content_hash`, `created_at`, `retry_count`, `last_error`
                )
                SELECT
                    'legacy-tomb-' || `entity_type` || '-' || `id`,
                    CASE WHEN `entity_type` = 'media_creation' THEN 'media_session' ELSE `entity_type` END,
                    `id`,
                    'delete',
                    0,
                    '',
                    '',
                    `deleted_at`,
                    0,
                    NULL
                FROM `sync_tombstone`
                """.trimIndent()
            )
        }
    }

    private fun ensureSyncResources(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_resources` (" +
                "`entityType` TEXT NOT NULL, " +
                "`entityId` TEXT NOT NULL, " +
                "`payload` TEXT NOT NULL, " +
                "`materializedHash` TEXT NOT NULL, " +
                "`deleted` INTEGER NOT NULL, " +
                "`localHash` TEXT NOT NULL, " +
                "PRIMARY KEY(`entityType`, `entityId`))"
        )
    }

    private fun ensureWorkspaceStorageMounts(db: SupportSQLiteDatabase) {
        if (!hasColumn(db, "workspaces", "storage_mounts")) {
            db.execSQL("ALTER TABLE `workspaces` ADD COLUMN `storage_mounts` TEXT NOT NULL DEFAULT '[]'")
        }
    }

    private fun ensureSyncIds(db: SupportSQLiteDatabase, table: String) {
        if (!hasColumn(db, table, "sync_id")) {
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `sync_id` TEXT NOT NULL DEFAULT ''")
        }
        val occurrences = HashMap<String, Int>()
        db.query("SELECT * FROM `$table` ORDER BY `id`").use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("id")
            val syncIdIndex = cursor.getColumnIndexOrThrow("sync_id")
            val identityColumns = (0 until cursor.columnCount).filter {
                it != idIndex && it != syncIdIndex
            }
            while (cursor.moveToNext()) {
                val content = identityColumns.joinToString("\u0000") { index ->
                    if (cursor.isNull(index)) "<null>" else cursor.getString(index)
                }
                val occurrence = occurrences.getOrDefault(content, 0)
                occurrences[content] = occurrence + 1
                if (cursor.getString(syncIdIndex).isNullOrEmpty()) {
                    val id = UUID.nameUUIDFromBytes(
                        "$table\u0000$content\u0000$occurrence".toByteArray(Charsets.UTF_8)
                    ).toString()
                    db.execSQL(
                        "UPDATE `$table` SET `sync_id` = ? WHERE `id` = ?",
                        arrayOf<Any>(id, cursor.getLong(idIndex))
                    )
                }
            }
        }
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_${table}_sync_id` ON `$table` (`sync_id`)")
    }

    fun ensureMessageNodeIndex(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_message_node_conversation_id_node_index` " +
                "ON `message_node` (`conversation_id`, `node_index`)"
        )
        db.execSQL("DROP INDEX IF EXISTS `index_message_node_conversation_id`")
    }

    private fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }.contains(column)
        }
}
