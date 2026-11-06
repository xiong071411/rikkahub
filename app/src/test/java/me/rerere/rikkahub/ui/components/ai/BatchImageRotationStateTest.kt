package me.rerere.rikkahub.ui.components.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchImageRotationStateTest {
    @Test
    fun `mixed selections keep every image and apply turns only to selected images`() {
        val initial = BatchImageRotationState(3)
        assertEquals(3, initial.selectedCount)
        val upsideDown = initial.toggleSelection(1).rotateSelected(2)
        assertEquals(listOf(2, 0, 2), upsideDown.edits.map { it.quarterTurns })
        val mixed = upsideDown.toggleSelection(0).toggleSelection(1).rotateSelected(-1)
        assertEquals(listOf(2, 3, 1), mixed.edits.map { it.quarterTurns })
        assertEquals(listOf(false, true, true), mixed.edits.map { it.selected })
        assertEquals(2, mixed.selectedCount)
        assertTrue(mixed.modified)
    }

    @Test
    fun `opposite turns and a full rotation restore original without reencoding`() {
        var state = BatchImageRotationState(2).rotateSelected(-1).rotateSelected(1)
        assertFalse(state.modified)
        repeat(4) { state = state.rotateSelected(1) }
        assertEquals(listOf(0, 0), state.edits.map { it.quarterTurns })
        assertFalse(state.modified)
        state = state.rotateSelected(2).rotateSelected(2)
        assertFalse(state.modified)
    }

    @Test
    fun `reset restores even deselected images and select all keeps existing edits`() {
        val edited = BatchImageRotationState(2).rotateSelected(2).toggleSelection(0)
        assertEquals(listOf(2, 2), edited.selectAll(false).edits.map { it.quarterTurns })
        assertEquals(0, edited.selectAll(false).selectedCount)
        assertEquals(2, edited.selectAll(true).selectedCount)
        val reset = edited.reset()
        assertFalse(reset.modified)
        assertEquals(listOf(false, true), reset.edits.map { it.selected })
        assertEquals(listOf(0, 0), reset.edits.map { it.quarterTurns })
    }
}
