package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

/** 自增主键只用于本机；历史备份里内容相同的流水获得相同同步 ID，避免换端后重复计数。 */
object Migration_33_34 : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for (table in listOf("usage_record", "genmediaentity")) {
            db.execSQL("ALTER TABLE $table ADD COLUMN sync_id TEXT NOT NULL DEFAULT ''")
            val occurrences = HashMap<String, Int>()
            db.query("SELECT * FROM $table ORDER BY id").use { cursor ->
                val identityColumns = (0 until cursor.columnCount).filter {
                    cursor.getColumnName(it) !in setOf("id", "sync_id")
                }
                while (cursor.moveToNext()) {
                    val content = identityColumns.joinToString("\u0000") { index ->
                        if (cursor.isNull(index)) "<null>" else cursor.getString(index)
                    }
                    val occurrence = occurrences.getOrDefault(content, 0)
                    occurrences[content] = occurrence + 1
                    val id = UUID.nameUUIDFromBytes(
                        "$table\u0000$content\u0000$occurrence".toByteArray(Charsets.UTF_8)
                    ).toString()
                    db.execSQL(
                        "UPDATE $table SET sync_id = ? WHERE id = ?",
                        arrayOf<Any>(id, cursor.getLong(cursor.getColumnIndexOrThrow("id")))
                    )
                }
            }
            db.execSQL("CREATE UNIQUE INDEX index_${table}_sync_id ON $table (sync_id)")
        }
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS sync_resources (entityType TEXT NOT NULL, entityId TEXT NOT NULL, payload TEXT NOT NULL, materializedHash TEXT NOT NULL, deleted INTEGER NOT NULL, localHash TEXT NOT NULL, PRIMARY KEY(entityType, entityId))"
        )
    }
}
