package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_32_33"

val Migration_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 32 to 33")
        DatabaseMigrationTracker.onMigrationStart(32, 33)
        try {
            db.beginTransaction()
            try {
                SyncSchemaMigration.ensureSyncTables(db)
                SyncSchemaMigration.migrateLegacyTombstones(db)
                SyncSchemaMigration.ensureMessageNodeIndex(db)
                db.setTransactionSuccessful()
                Log.i(TAG, "migrate: migrate from 32 to 33 success")
            } finally {
                db.endTransaction()
            }
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
