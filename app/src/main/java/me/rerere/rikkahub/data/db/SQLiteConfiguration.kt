package me.rerere.rikkahub.data.db

import android.content.Context
import android.util.Log
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteCustomExtension
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import java.io.File

/** Use the same SQLite extensions for the live database and backup validation. */
internal object SQLiteConfiguration {
    const val DATABASE_NAME = "rikka_hub"
    private const val TAG = "SQLiteConfiguration"

    fun configure(context: Context, configuration: SQLiteDatabaseConfiguration): SQLiteDatabaseConfiguration {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val soFile = File(nativeDir, "libsimple.so")
        val extensionPath = if (soFile.exists()) {
            soFile.absolutePath
        } else {
            "$nativeDir/libsimple"
        }

        // 优先检查扩展是否存在，若不存在则记录日志跳过，防止缺失底层库时直接崩溃闪退
        if (soFile.exists() || File("$nativeDir/libsimple").exists()) {
            configuration.customExtensions.add(
                SQLiteCustomExtension(extensionPath, null)
            )
        } else {
            Log.w(TAG, "libsimple extension not found in $nativeDir, skipping registration")
        }
        return configuration
    }

    fun openHelperFactory(context: Context) = RequerySQLiteOpenHelperFactory(
        listOf(RequerySQLiteOpenHelperFactory.ConfigurationOptions { configure(context, it) })
    )
}
