package me.rerere.ui.diagrams

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.max

private const val MAX_DIAGRAM_ZOOM = 8f
private const val DOUBLE_TAP_ZOOM = 2.5f

@Immutable
internal data class DiagramZoomState(
    val scale: Float = 1f,
    val offset: Offset = Offset.Zero,
) {
    fun transform(
        centroid: Offset,
        pan: Offset,
        zoomChange: Float,
        viewport: Size,
    ): DiagramZoomState {
        val nextScale = (scale * zoomChange).coerceIn(1f, MAX_DIAGRAM_ZOOM)
        if (nextScale <= 1.001f) return DiagramZoomState()
        val factor = nextScale / scale
        // detectTransformGestures 返回以容器左上角为原点的 centroid，而 graphicsLayer 默认绕视口中心缩放
        val centeredCentroid = centroid - Offset(viewport.width / 2f, viewport.height / 2f)
        val nextOffset = centeredCentroid + pan - (centeredCentroid - offset) * factor
        return copy(scale = nextScale, offset = nextOffset.clamp(nextScale, viewport))
    }

    fun zoomBy(zoomFactor: Float, viewport: Size): DiagramZoomState {
        val nextScale = (scale * zoomFactor).coerceIn(1f, MAX_DIAGRAM_ZOOM)
        if (nextScale <= 1.001f) return DiagramZoomState()
        val nextOffset = (offset * (nextScale / scale)).clamp(nextScale, viewport)
        return copy(scale = nextScale, offset = nextOffset)
    }

    fun doubleTap(point: Offset, viewport: Size): DiagramZoomState {
        if (scale > 1f) return DiagramZoomState()
        val nextScale = DOUBLE_TAP_ZOOM
        val centeredPoint = point - Offset(viewport.width / 2f, viewport.height / 2f)
        val nextOffset = centeredPoint - (centeredPoint - offset) * (nextScale / scale)
        return copy(scale = nextScale, offset = nextOffset.clamp(nextScale, viewport))
    }

    private fun Offset.clamp(scale: Float, viewport: Size): Offset {
        val maxX = max(0f, viewport.width * (scale - 1f) / 2f)
        val maxY = max(0f, viewport.height * (scale - 1f) / 2f)
        return Offset(x.coerceIn(-maxX, maxX), y.coerceIn(-maxY, maxY))
    }
}
