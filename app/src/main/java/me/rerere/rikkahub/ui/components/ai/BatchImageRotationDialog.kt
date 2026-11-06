package me.rerere.rikkahub.ui.components.ai

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import coil3.compose.AsyncImage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01

@Composable
internal fun BatchImageRotationDialog(
    images: List<Uri>,
    saving: Boolean,
    completed: Int,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: (List<BatchImageRotationEdit>) -> Unit,
) {
    var state by remember(images) { mutableStateOf(BatchImageRotationState(images.size)) }
    val roundScreen = LocalConfiguration.current.isScreenRound
    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val containerColor = MaterialTheme.colorScheme.surfaceContainer
        val lightContainer = containerColor.luminance() > 0.5f
        SideEffect {
            window?.let {
                WindowCompat.getInsetsController(it, it.decorView).apply {
                    isAppearanceLightStatusBars = lightContainer
                    isAppearanceLightNavigationBars = lightContainer
                }
            }
        }
        Surface(color = containerColor, modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val horizontalInset = if (roundScreen) maxWidth * 0.15f else 0.dp
                val verticalInset = if (roundScreen) maxHeight * 0.15f else 0.dp
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                        .padding(horizontal = horizontalInset, vertical = verticalInset),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss, enabled = !saving) {
                            Icon(HugeIcons.Cancel01, contentDescription = "取消添加图片")
                        }
                        Text("批量旋转", style = MaterialTheme.typography.titleMediumEmphasized)
                    }
                    // 操作区也能随网格滚动，矮屏幕不会被固定工具栏挤掉预览空间。
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(128.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("已选 ${state.selectedCount}/${images.size} 张，旋转仅作用于勾选图片")
                                Button(
                                    onClick = { state = state.rotateSelected(2) },
                                    enabled = !saving && state.selectedCount > 0,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("旋转 180°") }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { state = state.rotateSelected(-1) },
                                        enabled = !saving && state.selectedCount > 0,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                        modifier = Modifier.weight(1f),
                                    ) { Text("左转 90°") }
                                    OutlinedButton(
                                        onClick = { state = state.rotateSelected(1) },
                                        enabled = !saving && state.selectedCount > 0,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                        modifier = Modifier.weight(1f),
                                    ) { Text("右转 90°") }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    TextButton(
                                        onClick = { state = state.selectAll(state.selectedCount != images.size) },
                                        enabled = !saving,
                                    ) { Text(if (state.selectedCount == images.size) "取消全选" else "全选") }
                                    TextButton(
                                        onClick = { state = state.reset() },
                                        enabled = !saving && state.modified
                                    ) {
                                        Text("重置")
                                    }
                                }
                            }
                        }
                        itemsIndexed(images, key = { index, _ -> index }) { index, uri ->
                            ImageRotationPreview(
                                uri = uri,
                                index = index,
                                edit = state.edits[index],
                                enabled = !saving,
                                onToggle = { state = state.toggleSelection(index) },
                            )
                        }
                    }
                    if (errorMessage != null) {
                        Text(
                            errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                    if (saving) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                            Text("正在处理 $completed/${images.size} 张图片")
                            LinearProgressIndicator(
                                progress = { completed.toFloat() / images.size },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            onClick = { onConfirm(state.reset().edits) },
                            enabled = !saving,
                            modifier = Modifier.weight(1f),
                        ) { Text("使用原图") }
                        Button(
                            onClick = { onConfirm(state.edits) },
                            enabled = !saving,
                            modifier = Modifier.weight(1f),
                        ) { Text("完成") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageRotationPreview(
    uri: Uri,
    index: Int,
    edit: BatchImageRotationEdit,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    var loadFailed by remember(uri) { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .toggleable(value = edit.selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() }),
        shape = MaterialTheme.shapes.extraLarge,
        color = if (edit.selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box {
            AsyncImage(
                model = uri,
                contentDescription = "第 ${index + 1} 张图片，已旋转 ${edit.quarterTurns * 90}°",
                contentScale = ContentScale.Fit,
                onError = { loadFailed = true },
                onSuccess = { loadFailed = false },
                // 在正方形缩略图内旋转，横竖图都能完整显示，预览阶段不编码原图。
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .graphicsLayer { rotationZ = edit.quarterTurns * 90f },
            )
            if (loadFailed) Text("预览失败", modifier = Modifier.align(Alignment.Center))
            Checkbox(
                checked = edit.selected,
                onCheckedChange = null,
                enabled = enabled,
                modifier = Modifier.align(Alignment.TopEnd)
            )
            Text(
                text = "${index + 1} · ${edit.quarterTurns * 90}°",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            )
        }
    }
}
