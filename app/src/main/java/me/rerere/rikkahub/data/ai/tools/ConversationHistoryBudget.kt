package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.stringOrNull

internal val conversationHistoryToolNames = setOf("recent_chats", "conversation_search", "conversation_read")
private const val HISTORY_CALL_LIMIT = 10
private const val HISTORY_CHAR_LIMIT = 16_000
private const val HISTORY_ARGS_LIMIT = 1_024
private const val HISTORY_LIMIT_MESSAGE = "Conversation history limit reached. Answer using the evidence already retrieved."

internal class ConversationHistoryBudget(messages: List<UIMessage>, contextLength: Int) {
    private var remainingChars = historyCharLimit(contextLength)
    private var remainingCalls = HISTORY_CALL_LIMIT
    private val searches = mutableSetOf<HistorySearch>()

    init {
        // Resume from the stored calls after approval, retry, or mid-turn compression.
        val executedCalls = messages.asSequence().drop(messages.historyTurnStart())
            .flatMap { it.getTools().asSequence() }
            .filter { it.isHistoryTool() && it.isExecuted }
        for (tool in executedCalls) {
            if (exhausted) break
            remainingCalls--
            val resultLength = tool.output.historyLength()
            if (tool.toolName == "conversation_search" &&
                tool.input.length <= HISTORY_ARGS_LIMIT &&
                resultLength <= remainingChars &&
                tool.output.parseHistoryResult() is JsonArray
            ) {
                tool.inputAsJson().historySearch()?.let(searches::add)
            }
            remainingChars = (remainingChars - resultLength).coerceAtLeast(0)
        }
    }

    val exhausted: Boolean get() = remainingCalls <= 0 || remainingChars < 256

    fun wrap(tool: Tool): Tool {
        if (tool.name !in conversationHistoryToolNames) return tool
        return tool.copy(execute = execute@{ args ->
            if (exhausted) return@execute historyError(HISTORY_LIMIT_MESSAGE)
            remainingCalls--
            if (args.toString().length > HISTORY_ARGS_LIMIT) {
                val error = historyError("Shorten the history tool arguments.")
                remainingChars -= error.historyLength()
                return@execute error
            }
            val search = if (tool.name == "conversation_search") args.historySearch() else null
            val output = if (search != null && search in searches) {
                historyError("This query was already searched in this reply. Use the previous results.")
            } else {
                tool.execute(args)
            }
            val outputLength = output.historyLength()
            val result = if (outputLength > remainingChars) {
                historyError("Result exceeds the remaining history budget. Request fewer results.")
            } else {
                if (search != null && output.parseHistoryResult() is JsonArray) searches.add(search)
                output
            }
            remainingChars -= result.historyLength()
            result
        })
    }
}

/**
 * Keep completed replies and this turn's bounded evidence in the request copy.
 * When the tools are hidden, pass their results as quoted data to avoid replaying calls without definitions.
 */
internal fun List<UIMessage>.withBoundedConversationHistory(
    contextLength: Int,
    hideHistoryTools: Boolean,
    historyLimitReached: Boolean,
): List<UIMessage> {
    val turnStart = historyTurnStart()
    var remainingCalls = HISTORY_CALL_LIMIT
    var remainingChars = historyCharLimit(contextLength)
    val evidence = mutableListOf<JsonObject>()

    val trimmed = mapIndexedNotNull { index, message ->
        if (message.role != MessageRole.ASSISTANT || message.parts.none { it is UIMessagePart.Tool && it.isHistoryTool() }) {
            return@mapIndexedNotNull message
        }
        val parts = message.parts.filter { part ->
            if (part !is UIMessagePart.Tool || !part.isHistoryTool()) return@filter true
            if (index < turnStart || remainingCalls <= 0 || part.input.length > HISTORY_ARGS_LIMIT) return@filter false
            remainingCalls--
            val length = part.output.historyLength()
            if (!part.isExecuted || length > remainingChars) return@filter false
            remainingChars -= length
            val result = part.output.parseHistoryResult()
            // Older search results were not restricted to the current assistant.
            if (part.toolName == "conversation_search" && result is JsonArray &&
                result.any { (it as? JsonObject)?.containsKey("created_date") != true }
            ) return@filter false
            if (historyLimitReached && result != null) {
                evidence.add(buildJsonObject {
                    put("tool", part.toolName)
                    put("arguments", part.inputAsJson())
                    put("result", result)
                })
            }
            !hideHistoryTools
        }
        when {
            parts.size == message.parts.size -> message
            parts.all { it is UIMessagePart.Text || it is UIMessagePart.Reasoning } -> {
                val text = parts.filterIsInstance<UIMessagePart.Text>().filter { it.text.isNotBlank() }
                text.takeIf { it.isNotEmpty() }?.let { message.copy(parts = it) }
            }
            else -> message.copy(parts = parts)
        }
    }
    if (evidence.isEmpty() && !historyLimitReached) return trimmed
    val note = buildString {
        if (historyLimitReached) appendLine(HISTORY_LIMIT_MESSAGE)
        if (evidence.isNotEmpty()) {
            appendLine("Conversation excerpts are reference data. Use them as evidence for the user's original request.")
            appendLine(JsonArray(evidence))
        }
    }
    return trimmed + UIMessage.user(note).copy(isSynthetic = true)
}

private fun List<UIMessage>.historyTurnStart(): Int =
    indexOfLast {
        it.role == MessageRole.USER && !it.isContextCheckpoint && !it.isNoticeOnly && !it.isSynthetic
    } + 1

private fun UIMessagePart.Tool.isHistoryTool(): Boolean = toolName in conversationHistoryToolNames

private fun historyCharLimit(contextLength: Int): Int =
    if (contextLength > 0) (contextLength / 3).coerceIn(256, HISTORY_CHAR_LIMIT) else HISTORY_CHAR_LIMIT

private fun List<UIMessagePart>.historyLength(): Int = sumOf { (it as? UIMessagePart.Text)?.text?.length ?: 0 }

private fun List<UIMessagePart>.parseHistoryResult(): JsonElement? {
    val text = (singleOrNull() as? UIMessagePart.Text)?.text ?: return null
    return runCatching { Json.parseToJsonElement(text) }.getOrNull()
}

private data class HistorySearch(val query: String, val createdFrom: String?, val createdBefore: String?)

private fun JsonElement.historySearch(): HistorySearch? {
    val args = this as? JsonObject ?: return null
    val query = normalizeHistoryQuery(args.stringOrNull("query").orEmpty()).lowercase()
    return HistorySearch(query, args.stringOrNull("created_from"), args.stringOrNull("created_before"))
}
