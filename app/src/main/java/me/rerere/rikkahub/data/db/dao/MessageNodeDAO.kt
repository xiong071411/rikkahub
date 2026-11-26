package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity

@Dao
interface MessageNodeDAO {
    // 使用与 messages 相同的 JSON 编码，保守保留所有分支中出现的 URL。
    @Query("SELECT EXISTS(SELECT 1 FROM message_node WHERE instr(messages, :encodedFileUrl) > 0)")
    suspend fun hasFileReference(encodedFileUrl: String): Boolean

    @Query("SELECT * FROM message_node WHERE conversation_id = :conversationId ORDER BY node_index ASC")
    suspend fun getNodesOfConversation(conversationId: String): List<MessageNodeEntity>

    @Query(
        "SELECT * FROM message_node WHERE conversation_id = :conversationId " +
            "ORDER BY node_index ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun getNodesOfConversationPaged(
        conversationId: String,
        limit: Int,
        offset: Int
    ): List<MessageNodeEntity>

    // 旧消息里可能存着很大的工具结果，只把选中分支的文本片段交给 Cursor。
    @Query(
        """
        SELECT json_extract(messages, '$[' || select_index || '].role') AS role,
               substr(json_extract(messages, '$[' || select_index || '].createdAt'), 1, 10) AS date,
               coalesce(json_extract(messages, '$[' || select_index || '].isContextCheckpoint'), 0) AS isCheckpoint,
               (SELECT substr(json_extract(value, '$.text'), 1, :textLength)
                   FROM json_each(message_node.messages, '$[' || message_node.select_index || '].parts')
                   WHERE json_extract(value, '$.type') = 'text'
                     AND coalesce(json_extract(value, '$.text'), '') != ''
                   LIMIT 1
               ) AS text
        FROM message_node
        WHERE conversation_id = :conversationId
        ORDER BY node_index
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getConversationExcerpts(
        conversationId: String,
        offset: Int,
        limit: Int,
        textLength: Int,
    ): List<ConversationExcerpt>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(nodes: List<MessageNodeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(node: MessageNodeEntity)

    @Update
    suspend fun update(node: MessageNodeEntity)

    @Query("DELETE FROM message_node WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("DELETE FROM message_node WHERE id = :nodeId")
    suspend fun deleteById(nodeId: String)

    // 消息节点数（重新生成的分支不另算）
    @Query("SELECT COUNT(*) FROM message_node")
    suspend fun countAll(): Int
}

data class ConversationExcerpt(
    val role: String?,
    val date: String?,
    val isCheckpoint: Boolean,
    val text: String?,
)
