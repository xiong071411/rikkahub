package me.rerere.ui.chemistry

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.ui.diagrams.DiagramCardFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.min

/** Builds molecule coordinates away from the UI thread and falls back to source when parsing fails. */
@Composable
fun ChemicalStructureBlock(
    spec: ChemicalStructureSpec,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit,
) {
    val result = produceState<ChemicalDiagramResult>(ChemicalDiagramResult.Loading, spec) {
        value = withContext(Dispatchers.Default) {
            try {
                ChemicalDiagramResult.Ready(
                    reactants = spec.reactants.map(::buildChemicalMoleculeDiagram),
                    products = spec.products.map(::buildChemicalMoleculeDiagram),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ChemicalDiagramResult.Invalid
            }
        }
    }.value

    when (result) {
        ChemicalDiagramResult.Loading -> Box(
            modifier = modifier
                .fillMaxWidth()
                .height(148.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }

        ChemicalDiagramResult.Invalid -> fallback()
        is ChemicalDiagramResult.Ready -> ChemicalStructureCard(
            spec = spec,
            reactants = result.reactants,
            products = result.products,
            modifier = modifier,
        )
    }
}

private sealed interface ChemicalDiagramResult {
    data object Loading : ChemicalDiagramResult
    data object Invalid : ChemicalDiagramResult
    data class Ready(
        val reactants: List<ChemicalMoleculeDiagram>,
        val products: List<ChemicalMoleculeDiagram>,
    ) : ChemicalDiagramResult
}

@Composable
private fun ChemicalStructureCard(
    spec: ChemicalStructureSpec,
    reactants: List<ChemicalMoleculeDiagram>,
    products: List<ChemicalMoleculeDiagram>,
    modifier: Modifier,
) {
    DiagramCardFrame(title = spec.name, modifier = modifier) { contentModifier, isFullScreen ->
        BoxWithConstraints(contentModifier) {
            val availableWidth = maxWidth.value
            Column(
                modifier = if (isFullScreen) Modifier.fillMaxSize() else Modifier,
                verticalArrangement = if (isFullScreen) Arrangement.Center else Arrangement.spacedBy(8.dp),
            ) {
                if (spec.type == ChemicalStructureType.MOLECULE || spec.type == ChemicalStructureType.POLYMER) {
                    MoleculeItem(
                        diagram = reactants.single(),
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        width = minOf(availableWidth, if (isFullScreen) 760f else 320f),
                        repeatLabel = spec.repeatLabel,
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        reactants.forEachIndexed { index, diagram ->
                            if (index > 0) ReactionPlus()
                            MoleculeItem(diagram, width = if (isFullScreen) 320f else 200f)
                        }
                        ReactionArrow()
                        products.forEachIndexed { index, diagram ->
                            if (index > 0) ReactionPlus()
                            MoleculeItem(diagram, width = if (isFullScreen) 320f else 200f)
                        }
                    }
                    spec.conditions?.let { conditions ->
                        Text(
                            text = conditions,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MoleculeItem(
    diagram: ChemicalMoleculeDiagram,
    modifier: Modifier = Modifier,
    width: Float = 320f,
    repeatLabel: String? = null,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        MoleculeDiagramCanvas(diagram, repeatLabel, Modifier.width(width.dp).height((width * 0.67f).dp))
        diagram.label?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun ReactionPlus() {
    Text(
        text = "+",
        modifier = Modifier.padding(horizontal = 4.dp),
        style = MaterialTheme.typography.titleMediumEmphasized,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ReactionArrow() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = Modifier.size(width = 56.dp, height = 28.dp)) {
        val start = Offset(4.dp.toPx(), size.height / 2f)
        val end = Offset(size.width - 5.dp.toPx(), size.height / 2f)
        val stroke = 1.7.dp.toPx()
        drawLine(color, start, end, strokeWidth = stroke, cap = StrokeCap.Round)
        val head = Path().apply {
            moveTo(end.x, end.y)
            lineTo(end.x - 8.dp.toPx(), end.y - 5.dp.toPx())
            lineTo(end.x - 8.dp.toPx(), end.y + 5.dp.toPx())
            close()
        }
        drawPath(head, color)
    }
}

@Composable
private fun MoleculeDiagramCanvas(diagram: ChemicalMoleculeDiagram, repeatLabel: String?, modifier: Modifier) {
    val bounds = remember(diagram) {
        val minX = diagram.atoms.minOf { it.x }
        val maxX = diagram.atoms.maxOf { it.x }
        val minY = diagram.atoms.minOf { it.y }
        val maxY = diagram.atoms.maxOf { it.y }
        DiagramBounds(minX, maxX, minY, maxY)
    }
    val textMeasurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurface
    val bondColor = MaterialTheme.colorScheme.onSurface
    val backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    val atomStyle = MaterialTheme.typography.bodyMedium.copy(
        color = labelColor,
        fontWeight = FontWeight.Medium,
    )

    Canvas(modifier = modifier) {
        if (diagram.primitives.isNotEmpty()) {
            val scale = min(size.width / diagram.width, size.height / diagram.height)
            val offsetX = (size.width - diagram.width * scale) / 2f
            val offsetY = (size.height - diagram.height * scale) / 2f
            fun point(x: Float, y: Float) = Offset(offsetX + x * scale, offsetY + y * scale)
            diagram.primitives.forEach { primitive ->
                when (primitive) {
                    is ChemicalDrawPrimitive.Line -> {
                        val color = if ((primitive.color and 0x00FFFFFF) == 0) labelColor else Color(primitive.color)
                        drawLine(
                            color = color,
                            start = point(primitive.x1, primitive.y1),
                            end = point(primitive.x2, primitive.y2),
                            strokeWidth = primitive.width * scale,
                            cap = StrokeCap.Round,
                            pathEffect = if (primitive.dashed) {
                                PathEffect.dashPathEffect(floatArrayOf(4f * scale, 3f * scale))
                            } else {
                                null
                            },
                        )
                    }

                    is ChemicalDrawPrimitive.Polygon -> {
                        val path = Path().apply {
                            primitive.points.forEachIndexed { index, value ->
                                val mapped = point(value.x, value.y)
                                if (index == 0) moveTo(mapped.x, mapped.y) else lineTo(mapped.x, mapped.y)
                            }
                            close()
                        }
                        val color = if ((primitive.color and 0x00FFFFFF) == 0) labelColor else Color(primitive.color)
                        drawPath(path, color)
                    }

                    is ChemicalDrawPrimitive.Circle -> {
                        val color = if ((primitive.color and 0x00FFFFFF) == 0) labelColor else Color(primitive.color)
                        drawCircle(color, primitive.diameter * scale / 2f, point(primitive.x, primitive.y))
                    }

                    is ChemicalDrawPrimitive.Text -> {
                        val color = if ((primitive.color and 0x00FFFFFF) == 0) labelColor else Color(primitive.color)
                        val layout = textMeasurer.measure(
                            primitive.text,
                            style = atomStyle.copy(color = color, fontSize = (primitive.size * scale).sp),
                            maxLines = 1,
                        )
                        val textPoint = point(primitive.x, primitive.y)
                        drawText(
                            layout,
                            topLeft = Offset(
                                textPoint.x - layout.size.width / 2f,
                                textPoint.y - layout.size.height / 2f,
                            ),
                        )
                    }
                }
            }
            diagram.repeatBounds?.let { bounds ->
                val leftAtom = if (bounds.leftAtom.x <= bounds.rightAtom.x) bounds.leftAtom else bounds.rightAtom
                val rightAtom = if (bounds.leftAtom.x <= bounds.rightAtom.x) bounds.rightAtom else bounds.leftAtom
                val left = leftAtom.x - 22f
                val right = rightAtom.x + 22f
                val top = bounds.top - 4f
                val bottom = bounds.bottom + 4f
                val leftBracket = Path().apply {
                    val start = point(left + 7f, top)
                    val topLeft = point(left, top)
                    val bottomLeft = point(left, bottom)
                    val bottomEnd = point(left + 7f, bottom)
                    moveTo(start.x, start.y)
                    lineTo(topLeft.x, topLeft.y)
                    lineTo(bottomLeft.x, bottomLeft.y)
                    lineTo(bottomEnd.x, bottomEnd.y)
                }
                val rightBracket = Path().apply {
                    val start = point(right - 7f, top)
                    val topRight = point(right, top)
                    val bottomRight = point(right, bottom)
                    val bottomEnd = point(right - 7f, bottom)
                    moveTo(start.x, start.y)
                    lineTo(topRight.x, topRight.y)
                    lineTo(bottomRight.x, bottomRight.y)
                    lineTo(bottomEnd.x, bottomEnd.y)
                }
                val bracketStroke = Stroke(width = 1.8f * scale, cap = StrokeCap.Round)
                drawLine(labelColor, point(leftAtom.x, leftAtom.y), point(left, leftAtom.y), 1.6f * scale)
                drawLine(labelColor, point(rightAtom.x, rightAtom.y), point(right, rightAtom.y), 1.6f * scale)
                drawPath(leftBracket, labelColor, style = bracketStroke)
                drawPath(rightBracket, labelColor, style = bracketStroke)
                val label = repeatLabel ?: "n"
                val layout = textMeasurer.measure(label, style = atomStyle.copy(fontSize = 18.sp), maxLines = 1)
                val labelPoint = point(right + 4f, bottom - 2f)
                drawText(layout, topLeft = Offset(labelPoint.x, labelPoint.y - layout.size.height))
            }
            return@Canvas
        }
        val margin = 18.dp.toPx()
        val rangeX = bounds.width.coerceAtLeast(0.15f)
        val rangeY = bounds.height.coerceAtLeast(0.15f)
        val scale = min(
            (size.width - margin * 2f) / rangeX,
            (size.height - margin * 2f) / rangeY,
        ).coerceAtLeast(0.1f)
        val drawingWidth = rangeX * scale
        val drawingHeight = rangeY * scale
        val left = (size.width - drawingWidth) / 2f
        val top = (size.height - drawingHeight) / 2f
        val points = diagram.atoms.map { atom ->
            Offset(
                x = left + (atom.x - bounds.minX) * scale,
                y = top + (bounds.maxY - atom.y) * scale,
            )
        }

        diagram.bonds.forEach { bond ->
            val start = points[bond.start]
            val end = points[bond.end]
            val dx = end.x - start.x
            val dy = end.y - start.y
            val length = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
            val normal = Offset(-dy / length, dx / length)
            if (bond.order == 1 && bond.stereo != ChemicalBondStereo.NONE) {
                val halfWidth = 5.dp.toPx()
                when (bond.stereo) {
                    ChemicalBondStereo.UP -> {
                        val wedge = Path().apply {
                            moveTo(start.x, start.y)
                            lineTo(end.x + normal.x * halfWidth, end.y + normal.y * halfWidth)
                            lineTo(end.x - normal.x * halfWidth, end.y - normal.y * halfWidth)
                            close()
                        }
                        drawPath(wedge, bondColor)
                    }

                    ChemicalBondStereo.DOWN, ChemicalBondStereo.UNKNOWN -> {
                        val tickCount = 5
                        for (tick in 1..tickCount) {
                            val fraction = tick / (tickCount + 1f)
                            val center = start + Offset(dx * fraction, dy * fraction)
                            val tickWidth = halfWidth * fraction
                            drawLine(
                                color = bondColor,
                                start = center - normal * tickWidth,
                                end = center + normal * tickWidth,
                                strokeWidth = 1.4.dp.toPx(),
                                cap = StrokeCap.Round,
                            )
                        }
                    }

                    ChemicalBondStereo.NONE -> Unit
                }
                return@forEach
            }
            val lineCount = bond.order.coerceIn(1, 3)
            val gap = if (lineCount == 1) 0f else 4.dp.toPx()
            repeat(lineCount) { lineIndex ->
                val offset = (lineIndex - (lineCount - 1) / 2f) * gap
                val shift = normal * offset
                drawLine(
                    color = bondColor,
                    start = start + shift,
                    end = end + shift,
                    strokeWidth = 1.7.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }

        diagram.atoms.forEachIndexed { index, atom ->
            val label = atom.label ?: return@forEachIndexed
            val measured = textMeasurer.measure(label, style = atomStyle, maxLines = 1)
            val point = points[index]
            val padding = 2.dp.toPx()
            val labelSize = Size(measured.size.width + padding * 2f, measured.size.height + padding * 2f)
            val labelTopLeft = Offset(point.x - labelSize.width / 2f, point.y - labelSize.height / 2f)
            drawRoundRect(
                color = backgroundColor,
                topLeft = labelTopLeft,
                size = labelSize,
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
            drawText(
                textLayoutResult = measured,
                topLeft = labelTopLeft + Offset(padding, padding),
            )
        }
    }
}

private data class DiagramBounds(
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
) {
    val width: Float get() = maxX - minX
    val height: Float get() = maxY - minY
}
