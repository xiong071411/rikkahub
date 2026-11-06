package me.rerere.rikkahub.ui.components.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class BatchImageRotationProcessorTest {
    @Test
    fun `prepares sequentially and commits every image once in picker order`() = runBlocking {
        val events = mutableListOf<String>()
        var committed = emptyList<String>()
        var discarded = false
        processBatchImageRotation(
            edits = listOf(
                BatchImageRotationEdit(selected = false, quarterTurns = 2),
                BatchImageRotationEdit(quarterTurns = 0),
                BatchImageRotationEdit(quarterTurns = 3),
            ),
            prepare = { index, turns -> "$index:$turns".also { events.add("prepare $it") } },
            discard = { discarded = true },
            commit = {
                committed = it
                events.add("commit")
            },
            onProgress = { events.add("progress $it") },
        )
        assertEquals(listOf("0:2", "1:0", "2:3"), committed)
        assertEquals(
            listOf("prepare 0:2", "progress 1", "prepare 1:0", "progress 2", "prepare 2:3", "progress 3", "commit"),
            events,
        )
        assertFalse(discarded)
    }

    @Test
    fun `failed image discards prepared attachments without partially changing input`() = runBlocking {
        val failure = IllegalStateException("Unreadable image")
        var discarded = emptyList<Int>()
        var committed = false
        try {
            processBatchImageRotation(
                edits = BatchImageRotationState(3).edits,
                prepare = { index, _ -> if (index == 1) throw failure else index },
                discard = { discarded = it },
                commit = { committed = true },
            )
            fail("Expected image failure")
        } catch (error: IllegalStateException) {
            assertSame(failure, error)
        }
        assertEquals(listOf(0), discarded)
        assertFalse(committed)
    }

    @Test
    fun `cancellation after last image still discards whole uncommitted batch`() = runBlocking {
        var discarded = emptyList<Int>()
        var committed = false
        try {
            processBatchImageRotation(
                edits = BatchImageRotationState(2).edits,
                prepare = { index, _ -> index },
                discard = { discarded = it },
                commit = { committed = true },
                onProgress = { if (it == 2) throw CancellationException("Chat closed") },
            )
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(listOf(0, 1), discarded)
        }
        assertFalse(committed)
    }
}
