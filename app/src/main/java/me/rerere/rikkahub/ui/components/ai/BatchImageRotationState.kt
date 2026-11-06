package me.rerere.rikkahub.ui.components.ai

internal data class BatchImageRotationEdit(
    val selected: Boolean = true,
    val quarterTurns: Int = 0,
)

/** 勾选只控制下一次旋转的范围，不移除附件，也不清除该图片已经设置的角度。 */
internal data class BatchImageRotationState(
    val edits: List<BatchImageRotationEdit>,
) {
    constructor(imageCount: Int) : this(List(imageCount) { BatchImageRotationEdit() })

    val selectedCount: Int get() = edits.count { it.selected }
    val modified: Boolean get() = edits.any { it.quarterTurns != 0 }

    fun toggleSelection(index: Int) = copy(
        edits = edits.mapIndexed { current, edit ->
            if (current == index) edit.copy(selected = !edit.selected) else edit
        }
    )

    fun selectAll(selected: Boolean) = copy(edits = edits.map { it.copy(selected = selected) })

    fun rotateSelected(quarterTurns: Int) = copy(
        edits = edits.map { edit ->
            if (edit.selected) edit.copy(quarterTurns = Math.floorMod(edit.quarterTurns + quarterTurns, 4)) else edit
        }
    )

    fun reset() = copy(edits = edits.map { it.copy(quarterTurns = 0) })
}
