package me.rerere.ui.diagrams

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Fullscreen
import me.rerere.ui.components.Tooltip

@Composable
fun DiagramCardFrame(
    title: String?,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(12.dp),
    content: @Composable (Modifier, isFullScreen: Boolean) -> Unit,
) {
    var showFullScreen by remember { mutableStateOf(false) }
    val openLabel = "全屏查看图像"

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = openLabel) { showFullScreen = true },
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                title?.let {
                    Text(
                        text = it,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmallEmphasized,
                        maxLines = 2,
                    )
                } ?: Spacer(Modifier.weight(1f))
                ExpandDiagramButton(openLabel) { showFullScreen = true }
            }
            content(Modifier.fillMaxWidth(), false)
        }
    }

    if (showFullScreen) {
        DiagramPreviewDialog(title = title, onDismissRequest = { showFullScreen = false }) { previewModifier ->
            content(previewModifier, true)
        }
    }
}

@Composable
private fun ExpandDiagramButton(label: String, onClick: () -> Unit) {
    Tooltip(tooltip = { Text(label) }) {
        IconButton(onClick = onClick) {
            Icon(HugeIcons.Fullscreen, contentDescription = label)
        }
    }
}

@Composable
private fun DiagramPreviewDialog(
    title: String?,
    onDismissRequest: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    title?.let {
                        Text(
                            text = it,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmallEmphasized,
                            maxLines = 2,
                        )
                    } ?: Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismissRequest) {
                        Icon(HugeIcons.Cancel01, contentDescription = "关闭图像预览")
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    content(Modifier.fillMaxSize())
                }
            }
        }
    }
}
