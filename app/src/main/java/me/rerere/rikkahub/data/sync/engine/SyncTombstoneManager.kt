package me.rerere.rikkahub.data.sync.engine

import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.db.AppDatabase

@Serializable
data class SyncTombstoneItem(
    val id: String,
    val entity_type: String, // "conversation" or "folder"
    val deleted_at: Long,
)

/**
 * 增量同步墓碑追踪管理器
 * 负责记录本地被删除的会话和文件夹，以便在下次同步时通知远端删除，防止多端误恢复
 */
class SyncTombstoneManager(
    private val database: AppDatabase,
) {
    companion object {
        fun createTables(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS sync_tombstone (
                    id TEXT PRIMARY KEY,
                    entity_type TEXT NOT NULL,
                    deleted_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_tombstone_time ON sync_tombstone(deleted_at)")
        }
    }

    fun recordTombstone(id: String, entityType: String) {
        val now = System.currentTimeMillis()
        val db = database.openHelper.writableDatabase
        db.execSQL(
            """
            INSERT OR REPLACE INTO sync_tombstone (id, entity_type, deleted_at)
            VALUES (?, ?, ?)
            """.trimIndent(),
            arrayOf(id, entityType, now)
        )
    }

    fun getTombstonesSince(sinceTimestamp: Long): List<SyncTombstoneItem> {
        val db = database.openHelper.readableDatabase
        val list = mutableListOf<SyncTombstoneItem>()
        db.query(
            "SELECT id, entity_type, deleted_at FROM sync_tombstone WHERE deleted_at > ? ORDER BY deleted_at ASC",
            arrayOf(sinceTimestamp)
        ).use { cursor ->
            val idIdx = cursor.getColumnIndex("id")
            val typeIdx = cursor.getColumnIndex("entity_type")
            val timeIdx = cursor.getColumnIndex("deleted_at")
            while (cursor.moveToNext()) {
                list.add(
                    SyncTombstoneItem(
                        id = cursor.getString(idIdx),
                        entity_type = cursor.getString(typeIdx),
                        deleted_at = cursor.getLong(timeIdx)
                    )
                )
            }
        }
        return list
    }

    fun removeTombstone(id: String) {
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM sync_tombstone WHERE id = ?",
            arrayOf(id)
        )
    }

    fun purgeOlderThan(beforeTimestamp: Long) {
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM sync_tombstone WHERE deleted_at < ?",
            arrayOf(beforeTimestamp)
        )
    }
}
