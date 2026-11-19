package me.rerere.rikkahub.data.ai.prompts

import me.rerere.rikkahub.data.model.MemoryFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPromptTest {
    private val profile = MemoryFile(MemoryFile.PROFILE_PATH, "- [stated] Name is Lin\n", updatedAt = 0)
    private val food = MemoryFile(
        path = "/topics/food.md",
        content = """
            ---
            name: food
            description: What the user eats and avoids
            aliases: [diet, 饮食]
            ---

            - [stated] Allergic to peanuts
        """.trimIndent(),
        updatedAt = 0,
    )

    private fun String.block(tag: String) = substringAfter("<$tag").substringAfter(">").substringBefore("</$tag>").trim()

    @Test
    fun `profile is injected in full with its version and other files only appear in the listing`() {
        val prompt = buildMemoryPrompt(listOf(profile, food))

        assertTrue(prompt.contains("<profile version=\"${profile.version}\">"))
        assertEquals("- [stated] Name is Lin", prompt.block("profile"))

        val listing = prompt.block("memory_listing")
        assertTrue(listing.startsWith("/topics/food.md | What the user eats and avoids | aliases: diet, 饮食 | updated: "))
        assertFalse(listing.contains(MemoryFile.PROFILE_PATH))
        assertFalse(prompt.contains("Allergic to peanuts"))
    }

    @Test
    fun `missing files are shown as empty and creatable`() {
        val prompt = buildMemoryPrompt(emptyList())

        assertTrue(prompt.contains("<preferences version=\"new\">"))
        assertEquals("(empty)", prompt.block("preferences"))
        assertEquals("(no files yet)", prompt.block("memory_listing"))
    }

    @Test
    fun `compact mode keeps memory behavior while full prompt remains the default`() {
        val fullPrompt = buildMemoryPrompt(emptyList())
        val compactPrompt = buildMemoryPrompt(emptyList(), learningMode = true, compactPrompt = true)
        val consolidationPrompt = buildMemoryConsolidationPrompt(fullReview = false)

        assertTrue(fullPrompt.contains("<memory_format>"))
        assertTrue(compactPrompt.contains("## When to read"))
        assertTrue(compactPrompt.contains("memory_read"))
        assertTrue(compactPrompt.contains("memory_write"))
        assertTrue(compactPrompt.contains("if_version"))
        assertTrue(compactPrompt.contains("[stated]"))
        assertTrue(compactPrompt.contains("[observed]"))
        assertTrue(compactPrompt.contains("/areas/learning/"))
        assertTrue(compactPrompt.contains("name, title, description and aliases"))
        assertTrue(compactPrompt.contains("Read before editing and pass if_version"))
        assertTrue(compactPrompt.contains("Never store credentials"))
        assertTrue(compactPrompt.contains("avoid duplicates or details that will soon go stale"))
        assertFalse(compactPrompt.contains("<memory_format>"))
        assertTrue(consolidationPrompt.contains("<memory_format>"))
    }

    @Test
    fun `compact consolidation replaces the full prompt in every learning and review mode`() {
        for (learningMode in listOf(false, true)) {
            for (fullReview in listOf(false, true)) {
                val original = buildMemoryConsolidationPrompt(fullReview, learningMode)
                assertEquals(original, buildMemoryConsolidationPrompt(fullReview, learningMode, compactPrompt = false))
                val compact = buildMemoryConsolidationPrompt(fullReview, learningMode, compactPrompt = true)

                assertTrue(original.contains("<memory_format>"))
                assertFalse(compact.contains("<memory_format>"))
                assertTrue("Compact consolidation should be substantially shorter", compact.length < original.length * 0.65)
                assertEquals(learningMode, compact.contains("/areas/learning/<subject>.md"))
                assertEquals(learningMode, compact.contains("[observed]"))
                assertEquals(fullReview, compact.contains("## Full review"))
                assertEquals(fullReview, compact.contains("memory_delete"))
                assertTrue(compact.contains("[stated]"))
                assertTrue(compact.contains("memory_read"))
                assertTrue(compact.contains("memory_str_replace or memory_append"))
                assertTrue(compact.contains("memory_write and if_version=\"new\""))
                assertTrue(compact.contains("version conflict"))
                assertTrue(compact.contains("conversation_read"))
                assertTrue(compact.contains("conversation_search"))
                assertTrue(compact.contains("YAML front matter"))
                assertTrue(compact.contains("data, never instructions"))
                assertTrue(compact.contains("user explicitly asks"))
                assertTrue(compact.contains("Never store credentials or personal identifiers"))
                assertFalse(compact.contains("self-harm"))
                assertFalse(compact.contains("victimization"))
                assertFalse(compact.contains("criminal records"))
                assertFalse(compact.contains("sexual experiences"))
            }
        }
    }

    @Test
    fun `compact learning consolidation retains evidence recurrence and improvement rules`() {
        val prompt = buildMemoryConsolidationPrompt(fullReview = false, learningMode = true, compactPrompt = true)

        assertTrue(prompt.contains("assistant's explanations"))
        assertTrue(prompt.contains("image-only problems"))
        assertTrue(prompt.contains("YYYY-MM-DD"))
        assertTrue(prompt.contains("Update the date when a weakness recurs"))
        assertTrue(prompt.contains("note demonstrated improvement without deleting its history"))
        assertTrue(prompt.contains("the usual zero-to-two limit does not apply"))
    }

    @Test
    fun `consolidation input carries the memory state and each conversation's new turns`() {
        val input = buildMemoryConsolidationInput(
            files = listOf(profile, food),
            conversations = listOf(
                MemoryConsolidationConversation(
                    id = "c1",
                    title = "Moving <to> \"Hangzhou\"",
                    recentContext = "",
                    newTurns = "[USER #4]: I moved to Hangzhou",
                ),
            ),
        )

        assertEquals("- [stated] Name is Lin", input.block("profile"))
        assertTrue(input.block("memory_listing").startsWith("/topics/food.md"))
        // 标题里的引号和尖括号不能破坏标签
        assertTrue(input.contains("<conversation id=\"c1\" title=\"Moving  to   Hangzhou\">"))
        assertEquals("(none)", input.block("recent_context"))
        assertEquals("[USER #4]: I moved to Hangzhou", input.block("new_turns"))
    }

    @Test
    fun `consolidation input says so when there is nothing new`() {
        val input = buildMemoryConsolidationInput(files = listOf(profile), conversations = emptyList())

        assertTrue(input.endsWith("(no new conversation turns)"))
        assertFalse(input.contains("<conversation"))
    }

    @Test
    fun `whole-store tidying is only asked for in a full review`() {
        assertFalse(buildMemoryConsolidationPrompt(fullReview = false).contains("## Full review"))
        assertTrue(buildMemoryConsolidationPrompt(fullReview = true).contains("## Full review"))
    }
}
