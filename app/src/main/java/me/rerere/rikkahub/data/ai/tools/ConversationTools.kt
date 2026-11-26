package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.jsonPrimitiveOrNull
import me.rerere.common.http.stringOrNull
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlin.uuid.Uuid

private const val HISTORY_TITLE_LENGTH = 120
private const val HISTORY_QUERY_LENGTH = 120
private const val SEARCH_DEFAULT_LIMIT = 5
private const val SEARCH_MAX_LIMIT = 8
private const val HISTORY_EXCERPT_LENGTH = 240
private const val PREVIEW_DEFAULT_LIMIT = 4
private const val PREVIEW_MAX_LIMIT = 6
private const val PREVIEW_TEXT_LENGTH = 600
private val HISTORY_DATE_FORMAT = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
private val HISTORY_QUERY_SPACE = Regex("[\\s\\p{Z}]+")

/**
 * Tools that let the assistant query the user's past conversations on demand, instead of
 * statically injecting recent chats into the system prompt (which would break prompt caching).
 */
fun createConversationTools(
    conversationRepo: ConversationRepository,
    assistantId: Uuid,
    conversationId: Uuid,
): List<Tool> = listOf(
    Tool(
        name = "recent_chats",
        description = """
            Browse conversation titles and dates for this assistant. For early chats or an overview of a period,
            use order "oldest" and optional creation date filters instead of guessing search terms.
            Use conversation_read with a returned id to inspect a few representative messages.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("order", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add(JsonPrimitive("recent"))
                            add(JsonPrimitive("oldest"))
                        })
                        put(
                            "description",
                            "recent sorts by pinned first and last activity (default); oldest sorts by creation date"
                        )
                    })
                    historyDateFields()
                    integerField("offset", "Number of conversations to skip (default: 0)")
                    integerField("limit", "Maximum number of conversations (default: 10, max: 20)")
                }
            )
        },
        execute = listChats@{ input ->
            val args = input as? JsonObject ?: return@listChats historyError("Expected a JSON object.")
            val dateRange = readHistoryDateRange(args) ?: return@listChats historyError(HISTORY_DATE_ERROR)
            val recent = conversationRepo.getConversationHistory(
                assistantId = assistantId,
                currentId = conversationId,
                oldestFirst = args.stringOrNull("order") == "oldest",
                createdFrom = dateRange.from,
                createdBefore = dateRange.before,
                offset = args.offset(),
                limit = args.limit(default = 10, max = 20),
            )
            historyResult(buildJsonArray {
                recent.forEach { conversation ->
                    add(buildJsonObject {
                        put("id", conversation.id.toString())
                        put("title", conversation.title.historyTitle())
                        put("created_date", conversation.createAt.historyDate())
                        put("last_chat", conversation.updateAt.historyDate())
                    })
                }
            })
        }
    ),
    conversationSearchTool { query, from, before ->
        conversationRepo.searchMessages(
            keyword = query,
            assistantId = assistantId,
            excludeConversationId = conversationId,
            createdFrom = from,
            createdBefore = before,
        )
    },
    Tool(
        name = "conversation_read",
        description = """
            Preview a few messages from a past conversation with this assistant. Use an id from recent_chats
            or a conversation_id from conversation_search. Follow next_offset only for passages needed to answer.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("conversation_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Id of the conversation")
                    })
                    integerField("offset", "Zero-based position to start reading at (default: 0)")
                    integerField("limit", "Maximum number of messages (default: $PREVIEW_DEFAULT_LIMIT, max: $PREVIEW_MAX_LIMIT)")
                },
                required = listOf("conversation_id"),
            )
        },
        execute = preview@{ input ->
            val args = input as? JsonObject ?: return@preview historyError("Expected a JSON object.")
            val targetId = args.stringOrNull("conversation_id")?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                ?.takeUnless { it == conversationId }
                ?: return@preview historyError("Choose a past conversation id from recent_chats or conversation_search.")
            val offset = args.offset()
            val page = conversationRepo.readConversationHistory(
                assistantId = assistantId,
                conversationId = targetId,
                offset = offset,
                limit = args.limit(PREVIEW_DEFAULT_LIMIT, PREVIEW_MAX_LIMIT),
                textLength = PREVIEW_TEXT_LENGTH + 1,
            ) ?: return@preview historyError("Conversation not found for this assistant.")
            historyResult(buildJsonObject {
                put("title", page.title.historyTitle())
                put("messages", buildJsonArray {
                    page.messages.forEachIndexed { index, message ->
                        val text = message.text?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
                        if (message.isCheckpoint || (message.role != "user" && message.role != "assistant")) {
                            return@forEachIndexed
                        }
                        add(buildJsonObject {
                            put("position", offset + index)
                            put("role", message.role)
                            put("date", message.date)
                            put("text", text.historyExcerpt(PREVIEW_TEXT_LENGTH))
                        })
                    }
                })
                if (page.hasMore) put("next_offset", offset + page.messages.size)
            })
        },
    ),
)

internal fun conversationSearchTool(
    defaultLimit: Int = SEARCH_DEFAULT_LIMIT,
    maxLimit: Int = SEARCH_MAX_LIMIT,
    search: suspend (query: String, createdFrom: Instant?, createdBefore: Instant?) -> List<MessageSearchResult>,
) = Tool(
    name = "conversation_search",
    description = """
        Search past conversation messages for a specific person, topic, or phrase. Use distinctive keywords.
        For an overview of a period or the earliest conversations, browse by date with recent_chats when available.
        Only request as many results as you need. Matching words are marked with [brackets].
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Specific word or short phrase with at least two letters or digits")
                })
                historyDateFields()
                integerField("limit", "Maximum number of results (default: $defaultLimit, max: $maxLimit)")
            },
            required = listOf("query")
        )
    },
    execute = find@{ input ->
        val args = input as? JsonObject ?: return@find historyError("Expected a JSON object.")
        val query = normalizeHistoryQuery(args.stringOrNull("query").orEmpty())
        if (query.length > HISTORY_QUERY_LENGTH ||
            query.codePoints().filter { Character.isLetterOrDigit(it) }.limit(2).count() < 2
        ) {
            return@find historyError("Use a specific phrase with at least two letters or digits and no more than $HISTORY_QUERY_LENGTH characters.")
        }
        val dateRange = readHistoryDateRange(args) ?: return@find historyError(HISTORY_DATE_ERROR)
        val limit = args.limit(defaultLimit, maxLimit)
        val hitsPerConversation = mutableMapOf<String, Int>()
        val results = search(query, dateRange.from, dateRange.before).asSequence()
            .distinctBy { it.messageId }
            .filter { result ->
                val count = hitsPerConversation.getOrDefault(result.conversationId, 0)
                hitsPerConversation[result.conversationId] = count + 1
                count < 2
            }
            .take(limit)
        historyResult(buildJsonArray {
            results.forEach { result ->
                add(buildJsonObject {
                    put("conversation_id", result.conversationId)
                    put("title", result.title.historyTitle())
                    put("snippet", result.snippet.historyExcerpt(HISTORY_EXCERPT_LENGTH))
                    put("created_date", result.createAt.historyDate())
                    put("date", result.updateAt.historyDate())
                })
            }
        })
    }
)

private const val HISTORY_DATE_ERROR =
    "Use YYYY-MM-DD for dates, with created_before later than created_from."

private data class HistoryDateRange(val from: Instant?, val before: Instant?)

private fun readHistoryDateRange(args: JsonObject): HistoryDateRange? = try {
    val zone = ZoneId.systemDefault()
    val from = args.historyDate("created_from")?.atStartOfDay(zone)?.toInstant()
    val before = args.historyDate("created_before")?.atStartOfDay(zone)?.toInstant()
    HistoryDateRange(from, before).takeUnless { from != null && before != null && from >= before }
} catch (_: DateTimeParseException) {
    null
}

private fun JsonObject.historyDate(key: String): LocalDate? {
    if (this[key] == null || this[key] == JsonNull) return null
    val value = stringOrNull(key) ?: throw DateTimeParseException("Expected YYYY-MM-DD", key, 0)
    if (!HISTORY_DATE_FORMAT.matches(value)) throw DateTimeParseException("Expected YYYY-MM-DD", value, 0)
    return LocalDate.parse(value)
}

private fun JsonObjectBuilder.historyDateFields() {
    put("created_from", buildJsonObject {
        put("type", "string")
        put("description", "Earliest conversation creation date, inclusive (YYYY-MM-DD)")
    })
    put("created_before", buildJsonObject {
        put("type", "string")
        put("description", "Upper bound for conversation creation date, exclusive (YYYY-MM-DD)")
    })
}

private fun JsonObjectBuilder.integerField(name: String, description: String) {
    put(name, buildJsonObject {
        put("type", "integer")
        put("description", description)
    })
}

private fun JsonObject.offset(): Int =
    (this["offset"]?.jsonPrimitiveOrNull?.intOrNull ?: 0).coerceIn(0, Int.MAX_VALUE - 20)

private fun JsonObject.limit(default: Int, max: Int): Int =
    (this["limit"]?.jsonPrimitiveOrNull?.intOrNull ?: default).coerceIn(1, max)

internal fun normalizeHistoryQuery(query: String): String = query.replace(HISTORY_QUERY_SPACE, " ").trim()

private fun Instant.historyDate(): String = atZone(ZoneId.systemDefault()).toLocalDate().toString()

private fun String.historyTitle(): String = ifBlank { "Untitled" }.historyExcerpt(HISTORY_TITLE_LENGTH)

private fun String.historyExcerpt(limit: Int): String {
    if (length <= limit) return this
    val end = limit - 3
    return take(if (this[end - 1].isHighSurrogate()) end - 1 else end) + "..."
}

internal fun historyError(message: String): List<UIMessagePart> =
    historyResult(buildJsonObject { put("error", message) })

private fun historyResult(value: JsonElement): List<UIMessagePart> =
    listOf(UIMessagePart.Text(value.toString()))

private const val READ_DEFAULT_LIMIT = 20
private const val READ_MAX_LIMIT = 40
private const val READ_MESSAGE_LENGTH = 12_000

/**
 * 给后台记忆整理用的对话查询工具，只能看到 [assistantIds] 这些助手的对话：
 * 独立记忆库的助手不该读到别的助手的对话内容。
 */
fun createMemoryConversationTools(
    conversationRepo: ConversationRepository,
    assistantIds: Set<Uuid>,
    learningMode: Boolean = false,
): List<Tool> = listOf(
    conversationSearchTool(
        search = { query, from, before ->
            conversationRepo.searchMessagesOfAssistants(query, assistantIds, from, before)
        },
        defaultLimit = 15,
        maxLimit = 50,
    ),
    Tool(
        name = "conversation_read",
        description = """
            Read the messages of one conversation in full, by position.
            Use it when a message you were given was cut off, or a search snippet needs its surrounding context.
            Positions are the #numbers shown next to each message.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("conversation_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Id of the conversation")
                    })
                    put("offset", buildJsonObject {
                        put("type", "integer")
                        put("description", "Position of the first message to return (default: 0)")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Maximum number of messages to return (default: $READ_DEFAULT_LIMIT, max: $READ_MAX_LIMIT)"
                        )
                    })
                },
                required = listOf("conversation_id")
            )
        },
        execute = {
            val args = it.jsonObject
            val conversation = args["conversation_id"]?.jsonPrimitive?.contentOrNull
                ?.let { id -> runCatching { Uuid.parse(id) }.getOrNull() }
                ?.let { id -> conversationRepo.getConversationById(id) }
                ?.takeIf { conversation -> conversation.assistantId in assistantIds }
                ?: error("conversation not found")
            val offset = (args["offset"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
            val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: READ_DEFAULT_LIMIT).coerceIn(1, READ_MAX_LIMIT)
            val messages = conversation.dialogueMessages()
            val payload = buildJsonObject {
                put("title", conversation.title.ifBlank { "Untitled" })
                put("total", messages.size)
                put("messages", buildJsonArray {
                    messages.drop(offset).take(limit).forEachIndexed { index, message ->
                        add(buildJsonObject {
                            put("position", offset + index)
                            put("role", message.role.name)
                            put("date", message.createdAt.date.toString())
                            val text = message.toText()
                            put("text", if (learningMode) text else text.take(READ_MESSAGE_LENGTH))
                        })
                    }
                })
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    ),
)

/** 当前分支上用户和助手的消息，位置编号以这个列表为准 */
internal fun Conversation.dialogueMessages(): List<UIMessage> =
    currentMessages.filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
