package me.rerere.rikkahub.data.repository

import androidx.paging.PagingSource
import me.rerere.rikkahub.data.db.dao.TranslationHistoryDAO
import me.rerere.rikkahub.data.db.entity.TranslationHistoryEntity
import kotlin.uuid.Uuid

private const val MAX_HISTORY_COUNT = 100

class TranslationHistoryRepository(
    private val dao: TranslationHistoryDAO,
    private val recorder: me.rerere.rikkahub.data.sync.v2.SyncMutationRecorder? = null,
) {
    fun pagingAll(): PagingSource<Int, TranslationHistoryEntity> = dao.pagingAll()

    suspend fun add(
        sourceText: String,
        translatedText: String,
        targetLanguage: String,
        modelId: Uuid,
    ) {
        val entity = TranslationHistoryEntity(
            id = Uuid.random().toString(),
            sourceText = sourceText,
            translatedText = translatedText,
            targetLanguage = targetLanguage,
            modelId = modelId.toString(),
            createAt = System.currentTimeMillis(),
        )
        dao.insert(entity)
        recorder?.recordUpsert(
            "translation_history",
            entity.id,
            me.rerere.rikkahub.utils.JsonInstant.encodeToString(entity)
        )
        dao.trimTo(MAX_HISTORY_COUNT)
    }

    suspend fun deleteById(id: String) {
        recorder?.recordDelete("translation_history", id)
        dao.deleteById(id)
    }

    suspend fun deleteAll() {
        recorder?.recordDeleteAll("translation_history", dao.getAllHistory().map { it.id })
        dao.deleteAll()
    }
}
