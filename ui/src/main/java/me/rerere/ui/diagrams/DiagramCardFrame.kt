package me.rerere.ui.diagrams

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Fullscreen
import me.rerere.hugeicons.stroke.ZoomIn
import me.rerere.hugeicons.stroke.ZoomOut
import me.rerere.ui.components.Tooltip
import kotlin.math.roundToInt

internal val LocalDiagramIsZoomed = staticCompositionLocalOf { false }

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
    var zoomState by remember { mutableStateOf(DiagramZoomState()) }
    var viewportSize by remember { mutableStateOf(Size.Zero) }
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 4.dp),
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
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clipToBounds()
                        .onSizeChanged { viewportSize = Size(it.width.toFloat(), it.height.toFloat()) }
                        .pointerInput(Unit) {
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                zoomState = zoomState.transform(
                                    centroid = centroid,
                                    pan = pan,
                                    zoomChange = zoom,
                                    viewport = Size(size.width.toFloat(), size.height.toFloat()),
                                )
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = { point ->
                                zoomState = zoomState.doubleTap(
                                    point = point,
                                    viewport = Size(size.width.toFloat(), size.height.toFloat()),
                                )
                            })
                        },
                ) {
                    CompositionLocalProvider(LocalDiagramIsZoomed provides (zoomState.scale > 1.01f)) {
                        content(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = zoomState.scale
                                    scaleY = zoomState.scale
                                    translationX = zoomState.offset.x
                                    translationY = zoomState.offset.y
                                },
                        )
                    }
                }
                DiagramZoomToolbar(
                    scale = zoomState.scale,
                    onZoomOut = { zoomState = zoomState.zoomBy(1f / 1.5f, viewportSize) },
                    onReset = { zoomState = DiagramZoomState() },
                    onZoomIn = { zoomState = zoomState.zoomBy(1.5f, viewportSize) },
                )
            }
        }
    }
}

@Composable
private fun DiagramZoomToolbar(
    scale: Float,
    onZoomOut: () -> Unit,
    onReset: () -> Unit,
    onZoomIn: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (scale <= 1.01f) {
            Text(
                text = "支持双指捏合 / 双击局部放大",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                FilledTonalIconButton(
                    onClick = onZoomOut,
                    enabled = scale > 1.01f,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(HugeIcons.ZoomOut, contentDescription = "缩小", modifier = Modifier.size(18.dp))
                }
                Text(
                    text = "${(scale * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clickable(enabled = scale > 1.01f, onClick = onReset)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
                FilledTonalIconButton(
                    onClick = onZoomIn,
                    enabled = scale < 7.99f,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(HugeIcons.ZoomIn, contentDescription = "放大", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
