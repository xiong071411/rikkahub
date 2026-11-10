package me.rerere.ui.chemistry

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.min

private const val BOND_LENGTH_DP = 38f
private const val MAX_DIAGRAM_SIDE_DP = 480f

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
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            spec.name?.let { name ->
                Text(text = name, style = MaterialTheme.typography.titleSmallEmphasized, maxLines = 2)
            }
            if (spec.type == ChemicalStructureType.MOLECULE) {
                MoleculeItem(reactants.single(), Modifier.align(Alignment.CenterHorizontally))
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
                        MoleculeItem(diagram)
                    }
                    ReactionArrow()
                    products.forEachIndexed { index, diagram ->
                        if (index > 0) ReactionPlus()
                        MoleculeItem(diagram)
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

@Composable
private fun MoleculeItem(
    diagram: ChemicalMoleculeDiagram,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        MoleculeDiagramCanvas(diagram)
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
private fun MoleculeDiagramCanvas(diagram: ChemicalMoleculeDiagram) {
    val bounds = remember(diagram) {
        val minX = diagram.atoms.minOf { it.x }
        val maxX = diagram.atoms.maxOf { it.x }
        val minY = diagram.atoms.minOf { it.y }
        val maxY = diagram.atoms.maxOf { it.y }
        DiagramBounds(minX, maxX, minY, maxY)
    }
    val width = ((bounds.width * BOND_LENGTH_DP + 36f).coerceIn(140f, MAX_DIAGRAM_SIDE_DP)).dp
    val height = ((bounds.height * BOND_LENGTH_DP + 36f).coerceIn(116f, 340f)).dp
    val textMeasurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurface
    val bondColor = MaterialTheme.colorScheme.onSurface
    val backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    val atomStyle = MaterialTheme.typography.bodyMedium.copy(
        color = labelColor,
        fontWeight = FontWeight.Medium,
    )

    Canvas(modifier = Modifier.width(width).height(height)) {
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
