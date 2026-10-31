package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_32_33"

/**
 * Room 数据库 32 -> 33 显式增量迁移脚本：
 * 1. 创建 Sync V2 核心持久化表：
 *    - sync_entity_state：已同步实体修订基线与语义哈希
 *    - sync_outbox：离线待推送变更队列（含唯一实体索引）
 *    - sync_inbox_pending：流式生成中会话的延迟收件箱
 *    - sync_conflicts：多端并发冲突双副本保护表
 *    - sync_file_queue：附件上传/下载待重试队列
 *    - sync_meta：同步游标与服务端标识元数据表
 * 2. 若旧版手工创建的 sync_tombstone 表中存在待同步删除记录，将其无损迁移至 sync_outbox，
 *    确保升级前离线删除的会话、文件夹等操作不会在升级后丢失。
 */
val Migration_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 32 to 33 (Sync V2 tables)")
        DatabaseMigrationTracker.onMigrationStart(32, 33)
        db.beginTransaction()
        try {
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

            // 兼容旧版 sync_tombstone：若表存在，把旧版尚未清理的墓碑迁入 sync_outbox
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

            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 32 to 33 success")
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
