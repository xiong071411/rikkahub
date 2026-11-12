package me.rerere.ui.diagrams

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.hypot
import kotlin.math.min

@Composable
fun VisualDiagramCard(
    spec: VisualDiagramSpec,
    modifier: Modifier = Modifier,
) {
    DiagramCardFrame(title = spec.title, modifier = modifier) { contentModifier, isFullScreen ->
        if (isFullScreen) {
            VisualDiagramCanvas(spec, contentModifier)
        } else {
            BoxWithConstraints(contentModifier) {
                val diagramHeight = (maxWidth * (spec.height / spec.width)).coerceIn(160.dp, 360.dp)
                VisualDiagramCanvas(spec, Modifier.fillMaxWidth().height(diagramHeight))
            }
        }
    }
}

@Composable
private fun VisualDiagramCanvas(spec: VisualDiagramSpec, modifier: Modifier) {
    val textMeasurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme

    Canvas(modifier) {
        val scale = min(size.width / spec.width, size.height / spec.height)
        if (!scale.isFinite() || scale <= 0f) return@Canvas
        val offsetX = (size.width - spec.width * scale) / 2f
        val offsetY = (size.height - spec.height * scale) / 2f
        fun map(point: VisualDiagramPoint) = Offset(offsetX + point.x * scale, offsetY + point.y * scale)
        fun color(value: VisualDiagramColor): Color = when (value) {
            VisualDiagramColor.TEXT -> colors.onSurface
            VisualDiagramColor.MUTED -> colors.onSurfaceVariant
            VisualDiagramColor.PRIMARY -> colors.primary
            VisualDiagramColor.SECONDARY -> colors.secondary
            VisualDiagramColor.TERTIARY -> colors.tertiary
            VisualDiagramColor.ERROR, VisualDiagramColor.RED -> colors.error
            VisualDiagramColor.BLUE -> Color(0xFF1565C0)
            VisualDiagramColor.GREEN -> Color(0xFF2E7D32)
            VisualDiagramColor.ORANGE -> Color(0xFFEF6C00)
        }
        fun stroke(width: Float, dashed: Boolean): Stroke = Stroke(
            width = width.dp.toPx(),
            cap = StrokeCap.Round,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx())) else null,
        )
        fun drawSegment(
            from: VisualDiagramPoint,
            to: VisualDiagramPoint,
            value: VisualDiagramColor,
            width: Float,
            dashed: Boolean,
            arrow: Boolean,
        ) {
            val start = map(from)
            val end = map(to)
            val paint = color(value)
            val line = Path().apply {
                moveTo(start.x, start.y)
                lineTo(end.x, end.y)
            }
            drawPath(line, paint, style = stroke(width, dashed))
            if (arrow) {
                val dx = end.x - start.x
                val dy = end.y - start.y
                val length = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
                val headLength = min(10.dp.toPx(), length * 0.3f)
                val halfWidth = headLength * 0.42f
                val base = Offset(end.x - dx / length * headLength, end.y - dy / length * headLength)
                val normal = Offset(-dy / length, dx / length)
                val head = Path().apply {
                    moveTo(end.x, end.y)
                    lineTo(base.x + normal.x * halfWidth, base.y + normal.y * halfWidth)
                    lineTo(base.x - normal.x * halfWidth, base.y - normal.y * halfWidth)
                    close()
                }
                drawPath(head, paint)
            }
        }

        spec.elements.forEach { element ->
            when (element) {
                is VisualDiagramElement.Segment -> drawSegment(
                    element.from,
                    element.to,
                    element.color,
                    element.strokeWidth,
                    element.dashed,
                    element.arrow,
                )

                is VisualDiagramElement.Shape -> {
                    val path = Path().apply {
                        element.points.map(::map).forEachIndexed { index, point ->
                            if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                        }
                        if (element.closed) close()
                    }
                    element.fillColor?.let { drawPath(path, color(it)) }
                    drawPath(path, color(element.color), style = stroke(element.strokeWidth, element.dashed))
                }

                is VisualDiagramElement.Rectangle -> {
                    val topLeft = map(VisualDiagramPoint(element.x, element.y))
                    val rectSize = Size(element.width * scale, element.height * scale)
                    element.fillColor?.let { drawRect(color(it), topLeft, rectSize) }
                    val outline = Path().apply { addRect(androidx.compose.ui.geometry.Rect(topLeft, rectSize)) }
                    drawPath(outline, color(element.color), style = stroke(element.strokeWidth, element.dashed))
                }

                is VisualDiagramElement.Circle -> {
                    val center = map(element.center)
                    val radius = element.radius * scale
                    element.fillColor?.let { drawCircle(color(it), radius, center) }
                    drawCircle(
                        color = color(element.color),
                        radius = radius,
                        center = center,
                        style = stroke(element.strokeWidth, element.dashed),
                    )
                }

                is VisualDiagramElement.Label -> {
                    val point = map(element.point)
                    val style = TextStyle(color = color(element.color), fontSize = element.fontSize.sp)
                    val layout = textMeasurer.measure(element.text, style = style, maxLines = 2)
                    val x = when (element.alignment) {
                        VisualDiagramElement.Alignment.START -> point.x
                        VisualDiagramElement.Alignment.CENTER -> point.x - layout.size.width / 2f
                        VisualDiagramElement.Alignment.END -> point.x - layout.size.width
                    }
                    drawText(layout, topLeft = Offset(x, point.y - layout.size.height / 2f))
                }
            }
        }
    }
}
