package me.rerere.rikkahub.ui.components.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 逐张准备附件，全部成功后才交给输入框。
 * 在 IO 切回界面时也可能取消，因此资源归属覆盖整个批次，取消或失败都会清理已生成的文件。
 */
internal suspend fun <T> processBatchImageRotation(
    edits: List<BatchImageRotationEdit>,
    prepare: (index: Int, quarterTurns: Int) -> T,
    discard: (List<T>) -> Unit,
    commit: (List<T>) -> Unit,
    onProgress: suspend (Int) -> Unit = {},
) {
    val prepared = mutableListOf<T>()
    var committed = false
    try {
        withContext(Dispatchers.IO) {
            edits.forEachIndexed { index, edit ->
                currentCoroutineContext().ensureActive()
                prepared.add(prepare(index, edit.quarterTurns))
                onProgress(prepared.size)
            }
        }
        currentCoroutineContext().ensureActive()
        commit(prepared.toList())
        committed = true
    } finally {
        if (!committed) {
            withContext(NonCancellable + Dispatchers.IO) { discard(prepared.toList()) }
        }
    }
}
