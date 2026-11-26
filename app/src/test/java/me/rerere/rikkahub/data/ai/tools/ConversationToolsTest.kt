package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ConversationToolsTest {
    @Test
    fun `one character and punctuation queries do not hit the index`() = runTest {
        val search = conversationSearchTool { _, _, _ -> error("Index must not be queried") }

        for (query in listOf("的", "?", "我？", "…！", " a ")) {
            val result = Json.parseToJsonElement(search.execute(searchArgs(query)).text()).jsonObject
            assertTrue(result.containsKey("error"))
        }
    }

    @Test
    fun `invalid dates do not silently broaden a query`() = runTest {
        val search = conversationSearchTool { _, _, _ -> error("Index must not be queried") }
        val invalid = search.execute(buildJsonObject {
            put("query", "specific topic")
            put("created_from", "2025-02-29")
        })
        val emptyRange = search.execute(buildJsonObject {
            put("query", "specific topic")
            put("created_from", "2025-02-01")
            put("created_before", "2025-02-01")
        })

        assertTrue(invalid.text().contains("YYYY-MM-DD"))
        assertTrue(emptyRange.text().contains("YYYY-MM-DD"))
    }

    @Test
    fun `search limits and diversifies results and passes the creation date range`() = runTest {
        val fromDate = "2025-01-01"
        val beforeDate = "2025-02-01"
        val zone = ZoneId.systemDefault()
        val hits = List(11) { index ->
            MessageSearchResult(
                nodeId = "node-$index",
                messageId = "message-$index",
                conversationId = if (index < 4) "first" else "conversation-$index",
                title = "Title ".repeat(100),
                createAt = Instant.parse("2025-01-30T12:00:00Z"),
                updateAt = Instant.parse("2025-10-03T12:00:00Z"),
                snippet = "Excerpt ".repeat(500),
            )
        }
        val search = conversationSearchTool { query, from, before ->
            assertEquals("中文 关键词", query)
            assertEquals(LocalDate.parse(fromDate).atStartOfDay(zone).toInstant(), from)
            assertEquals(LocalDate.parse(beforeDate).atStartOfDay(zone).toInstant(), before)
            listOf(hits[0]) + hits
        }
        val response = search.execute(buildJsonObject {
            put("query", "  中文　关键词  ")
            put("created_from", fromDate)
            put("created_before", beforeDate)
            put("limit", 50)
        })
        val rows = Json.parseToJsonElement(response.text()).jsonArray.map { it.jsonObject }

        assertEquals(8, rows.size)
        assertEquals(2, rows.count { it["conversation_id"]?.jsonPrimitive?.content == "first" })
        assertTrue(rows.all { it.getValue("snippet").jsonPrimitive.content.length <= 240 })
        assertTrue(rows.all { it.getValue("title").jsonPrimitive.content.length <= 120 })
    }

    @Test
    fun `duplicate searches and excess calls are blocked across an approval or compression`() = runTest {
        val earlierCalls = List(8) { index -> searchCall(index, "query $index", "[]") }
        val reply = UIMessage.assistant("").copy(parts = earlierCalls)
        val checkpoint = UIMessage.user("Summary").copy(isContextCheckpoint = true)
        val messages = listOf(UIMessage.user("Review those conversations"), reply, checkpoint)
        var executed = 0
        val budget = ConversationHistoryBudget(messages, contextLength = 100_000)
        val search = budget.wrap(Tool(name = "conversation_search", description = "") {
            executed++
            listOf(UIMessagePart.Text("[]"))
        })

        assertTrue(search.execute(searchArgs("QUERY   0")).text().contains("already searched"))
        assertEquals("[]", search.execute(searchArgs("another query")).text())
        assertTrue(budget.exhausted)
        assertTrue(search.execute(searchArgs("new query")).text().contains("limit reached"))
        assertEquals(1, executed)
        assertFalse(ConversationHistoryBudget(messages + UIMessage.user("Next"), 100_000).exhausted)
    }

    @Test
    fun `a large search consumes the shared character budget for history tools`() = runTest {
        val budget = ConversationHistoryBudget(emptyList(), contextLength = 6_000)
        val search = budget.wrap(Tool(name = "conversation_search", description = "") {
            listOf(UIMessagePart.Text("x".repeat(1_900)))
        })
        val browse = budget.wrap(Tool(name = "recent_chats", description = "") {
            error("History budget must be shared across tools")
        })

        assertEquals(1_900, search.execute(searchArgs("specific query")).text().length)
        assertTrue(budget.exhausted)
        assertTrue(browse.execute(buildJsonObject {}).text().contains("limit reached"))
    }

    @Test
    fun `oversized old search results are removed from the request copy`() {
        val reply = UIMessage.assistant("Previous answer")
        val oldCalls = List(273) { index -> searchCall(index, "query $index", "x".repeat(7_000)) }
        val oldReply = reply.copy(parts = oldCalls + reply.parts)
        val newestUser = UIMessage.user("Follow-up")
        val oldMessages = listOf(UIMessage.user("Analyze old chats"), oldReply, newestUser)

        val replay = oldMessages.withBoundedConversationHistory(100_000, false, false)

        assertEquals("Previous answer", replay[1].toText())
        assertTrue(replay[1].getTools().isEmpty())
        assertEquals(273, oldReply.getTools().size)
        assertSame(newestUser, replay.last())
    }

    @Test
    fun `exhausted history tools leave recent evidence and preserve unrelated tools`() {
        val result = """[{"created_date":"2025-01-30","snippet":"Relevant excerpt"}]"""
        val search = searchCall(0, "specific query", result)
        val oldSearch = searchCall(1, "unscoped result", """[{"snippet":"Legacy result"}]""")
        val other = search.copy(toolCallId = "other", toolName = "memory_read")
        val reply = UIMessage.assistant("").copy(parts = listOf(search, oldSearch, other))

        val replay = listOf(UIMessage.user("Find an excerpt"), reply)
            .withBoundedConversationHistory(100_000, true, true)

        assertEquals(listOf(other), replay[1].getTools())
        assertTrue(replay.last().isSynthetic)
        assertTrue(replay.last().toText().contains("Relevant excerpt"))
        assertFalse(replay.last().toText().contains("Legacy result"))
    }

    private fun searchCall(index: Int, query: String, output: String) = UIMessagePart.Tool(
        toolCallId = "search-$index",
        toolName = "conversation_search",
        input = searchArgs(query).toString(),
        output = listOf(UIMessagePart.Text(output)),
    )

    private fun searchArgs(query: String) = buildJsonObject { put("query", query) }

    private fun List<UIMessagePart>.text() = (single() as UIMessagePart.Text).text
}
