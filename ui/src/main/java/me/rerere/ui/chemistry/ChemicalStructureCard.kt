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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.ui.diagrams.DiagramCardFrame
import me.rerere.ui.diagrams.LocalDiagramIsZoomed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private const val MAX_INLINE_MOLECULE_HEIGHT_DP = 250f
private const val MAX_INLINE_LARGE_MOLECULE_HEIGHT_DP = 280f
private const val MAX_INLINE_DISPLAY_SCALE = 1.0f
private const val MAX_TINY_MOLECULE_DISPLAY_SCALE = 1.18f
private const val TINY_MOLECULE_EXTENT_DP = 100f
private const val MAX_INLINE_REACTION_HEIGHT_DP = 136f
private const val MAX_INLINE_REACTION_SCALE = 0.9f
private const val MAX_FULLSCREEN_REACTION_HEIGHT_DP = 320f
private const val MAX_FULLSCREEN_REACTION_SCALE = 1.6f
private const val MIN_STROKE_PX = 1.15f

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
        val isZoomed = isFullScreen && LocalDiagramIsZoomed.current
        BoxWithConstraints(contentModifier) {
            val availableWidth = maxWidth.value
            val availableHeight = maxHeight.value
            // 内嵌模式下外层 Column 必须占满宽度，否则窄高型大分子（如催产素）会贴左对齐而非居中
            Column(
                modifier = if (isFullScreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
                verticalArrangement = if (isFullScreen) Arrangement.Center else Arrangement.spacedBy(8.dp),
            ) {
                if (spec.type == ChemicalStructureType.MOLECULE || spec.type == ChemicalStructureType.POLYMER) {
                    val diagram = reactants.single()
                    if (isFullScreen) {
                        MoleculeItem(
                            diagram = diagram,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            fillAvailableSpace = true,
                            repeatLabel = spec.repeatLabel,
                        )
                    } else {
                        val maxExtent = max(diagram.width, diagram.height)
                        val maxInlineScale = if (maxExtent < TINY_MOLECULE_EXTENT_DP) {
                            MAX_TINY_MOLECULE_DISPLAY_SCALE
                        } else {
                            MAX_INLINE_DISPLAY_SCALE
                        }
                        val maxInlineHeight = if (diagram.atoms.size >= 36) {
                            MAX_INLINE_LARGE_MOLECULE_HEIGHT_DP
                        } else {
                            MAX_INLINE_MOLECULE_HEIGHT_DP
                        }
                        val fitScale = minOf(
                            availableWidth / diagram.width,
                            maxInlineHeight / diagram.height,
                            maxInlineScale,
                        ).coerceAtLeast(0.1f)
                        MoleculeItem(
                            diagram = diagram,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            width = diagram.width * fitScale,
                            height = diagram.height * fitScale,
                            repeatLabel = spec.repeatLabel,
                        )
                    }
                } else {
                    val maxItemHeight = if (isFullScreen) {
                        min(availableHeight * 0.72f, MAX_FULLSCREEN_REACTION_HEIGHT_DP).coerceAtLeast(180f)
                    } else {
                        MAX_INLINE_REACTION_HEIGHT_DP
                    }
                    val maxItemScale = if (isFullScreen) MAX_FULLSCREEN_REACTION_SCALE else MAX_INLINE_REACTION_SCALE
                    val reactionModifier = if (isFullScreen) {
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    } else {
                        Modifier.fillMaxWidth()
                    }
                    // 利用 widthIn(min = availableWidth)：反应式较窄时自动居中，超宽时从左起平滑横向滚动；放大后关闭横向滚动以免抢占平移手势
                    Box(
                        modifier = reactionModifier,
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState(), enabled = !isZoomed)
                                .widthIn(min = availableWidth.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            ReactionSide(reactants, availableWidth, maxItemHeight, maxItemScale)
                            ReactionArrow()
                            ReactionSide(products, availableWidth, maxItemHeight, maxItemScale)
                        }
                    }
                    spec.conditions?.let { conditions ->
                        Text(
                            text = conditions,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
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
private fun ReactionSide(
    diagrams: List<ChemicalMoleculeDiagram>,
    availableWidth: Float,
    maxItemHeight: Float,
    maxItemScale: Float,
) {
    diagrams.forEachIndexed { index, diagram ->
        if (index > 0) ReactionPlus()
        val itemScale = reactionItemScale(diagram, availableWidth, maxItemHeight, maxItemScale)
        MoleculeItem(
            diagram = diagram,
            width = diagram.width * itemScale,
            height = diagram.height * itemScale,
        )
    }
}

private fun reactionItemScale(
    diagram: ChemicalMoleculeDiagram,
    availableWidth: Float,
    maxHeight: Float,
    maxScale: Float,
): Float {
    val widthCap = (availableWidth * 0.72f).coerceAtLeast(72f)
    return minOf(
        widthCap / diagram.width,
        maxHeight / diagram.height,
        maxScale,
    ).coerceAtLeast(0.1f)
}

@Composable
private fun MoleculeItem(
    diagram: ChemicalMoleculeDiagram,
    modifier: Modifier = Modifier,
    width: Float = diagram.width,
    height: Float = diagram.height,
    fillAvailableSpace: Boolean = false,
    repeatLabel: String? = null,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val canvasModifier = if (fillAvailableSpace) {
            Modifier
                .fillMaxWidth()
                .weight(1f)
        } else {
            Modifier
                .width(width.dp)
                .height(height.dp)
        }
        MoleculeDiagramCanvas(diagram, repeatLabel, canvasModifier)
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
        modifier = Modifier.padding(horizontal = 3.dp),
        style = MaterialTheme.typography.titleMediumEmphasized,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ReactionArrow() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = Modifier.size(width = 40.dp, height = 24.dp)) {
        val start = Offset(4.dp.toPx(), size.height / 2f)
        val end = Offset(size.width - 4.dp.toPx(), size.height / 2f)
        val stroke = 1.6.dp.toPx()
        drawLine(color, start, end, strokeWidth = stroke, cap = StrokeCap.Round)
        val head = Path().apply {
            moveTo(end.x, end.y)
            lineTo(end.x - 6.5.dp.toPx(), end.y - 4.dp.toPx())
            lineTo(end.x - 6.5.dp.toPx(), end.y + 4.dp.toPx())
            close()
        }
        drawPath(head, color)
    }
}

@Composable
private fun MoleculeDiagramCanvas(diagram: ChemicalMoleculeDiagram, repeatLabel: String?, modifier: Modifier) {
    val fallbackBounds = remember(diagram) {
        if (diagram.primitives.isNotEmpty() || diagram.atoms.isEmpty()) {
            null
        } else {
            val minX = diagram.atoms.minOf { it.x }
            val maxX = diagram.atoms.maxOf { it.x }
            val minY = diagram.atoms.minOf { it.y }
            val maxY = diagram.atoms.maxOf { it.y }
            DiagramBounds(minX, maxX, minY, maxY)
        }
    }
    val textMeasurer = rememberTextMeasurer(cacheSize = 32)
    val labelColor = MaterialTheme.colorScheme.onSurface
    val bondColor = MaterialTheme.colorScheme.onSurface
    val backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    // 不继承 bodyMedium 自带的固定 lineHeight 与 letterSpacing，确保原子符号居中对齐且不被行高撑歪
    val atomBaseStyle = remember(labelColor) {
        TextStyle(
            color = labelColor,
            fontWeight = FontWeight.Normal,
            lineHeight = TextUnit.Unspecified,
            letterSpacing = 0.sp,
        )
    }

    Canvas(modifier = modifier) {
        if (diagram.primitives.isNotEmpty()) {
            val scale = min(size.width / diagram.width, size.height / diagram.height)
            if (!scale.isFinite() || scale <= 0f) return@Canvas
            val offsetX = (size.width - diagram.width * scale) / 2f
            val offsetY = (size.height - diagram.height * scale) / 2f
            fun point(x: Float, y: Float) = Offset(offsetX + x * scale, offsetY + y * scale)
            diagram.primitives.forEach { primitive ->
                when (primitive) {
                    is ChemicalDrawPrimitive.Line -> {
                        val color = resolveChemicalColor(primitive.color, labelColor)
                        drawLine(
                            color = color,
                            start = point(primitive.x1, primitive.y1),
                            end = point(primitive.x2, primitive.y2),
                            strokeWidth = (primitive.width * scale).coerceAtLeast(MIN_STROKE_PX),
                            cap = StrokeCap.Round,
                            pathEffect = if (primitive.dashed) {
                                PathEffect.dashPathEffect(
                                    floatArrayOf(maxOf(4f * scale, 3f), maxOf(3f * scale, 2.5f)),
                                )
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
                        val color = resolveChemicalColor(primitive.color, labelColor)
                        drawPath(path, color)
                    }

                    is ChemicalDrawPrimitive.Circle -> {
                        val color = resolveChemicalColor(primitive.color, labelColor)
                        drawCircle(color, primitive.diameter * scale / 2f, point(primitive.x, primitive.y))
                    }

                    is ChemicalDrawPrimitive.Text -> {
                        val color = resolveChemicalColor(primitive.color, labelColor)
                        // primitive.size * scale 已是画布像素尺寸，必须经 toSp() 转换，避免 Compose 二次乘以 density * fontScale
                        val scaledFontSize = (primitive.size * scale).coerceAtLeast(6f).toSp()
                        val layout = textMeasurer.measure(
                            primitive.text,
                            style = atomBaseStyle.copy(color = color, fontSize = scaledFontSize),
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
                val bracketStroke = Stroke(
                    width = (1.8f * scale).coerceAtLeast(MIN_STROKE_PX),
                    cap = StrokeCap.Round,
                )
                val continuationWidth = (1.6f * scale).coerceAtLeast(MIN_STROKE_PX)
                drawLine(labelColor, point(leftAtom.x, leftAtom.y), point(left, leftAtom.y), continuationWidth)
                drawLine(labelColor, point(rightAtom.x, rightAtom.y), point(right, rightAtom.y), continuationWidth)
                drawPath(leftBracket, labelColor, style = bracketStroke)
                drawPath(rightBracket, labelColor, style = bracketStroke)
                val label = repeatLabel ?: "n"
                val labelFontSize = (13f * scale).coerceAtLeast(8f).toSp()
                val layout = textMeasurer.measure(
                    label,
                    style = atomBaseStyle.copy(fontSize = labelFontSize),
                    maxLines = 1,
                )
                val labelPoint = point(right + 4f, bottom - 2f)
                drawText(layout, topLeft = Offset(labelPoint.x, labelPoint.y - layout.size.height))
            }
            return@Canvas
        }
        val bounds = fallbackBounds ?: return@Canvas
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
            val measured = textMeasurer.measure(
                label,
                style = atomBaseStyle.copy(fontSize = 14.sp),
                maxLines = 1,
            )
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

private fun resolveChemicalColor(primitiveColor: Int, fallbackColor: Color): Color =
    if ((primitiveColor and 0x00FFFFFF) == 0) fallbackColor else Color(primitiveColor)

private data class DiagramBounds(
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
) {
    val width: Float get() = maxX - minX
    val height: Float get() = maxY - minY
}
