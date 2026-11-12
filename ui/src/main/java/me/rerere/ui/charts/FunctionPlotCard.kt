package me.rerere.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import me.rerere.ui.diagrams.DiagramCardFrame

private const val FUNCTION_PLOT_SAMPLE_COUNT = 513
private val PI_LABEL_DENOMINATORS = listOf(1, 2, 3, 4, 6, 8, 12)

private data class CurveSamples(
    val values: List<Double?>,
)

private data class PlotTick(
    val value: Double,
    val label: String,
)

private data class FunctionPlotGeometry(
    val xScale: ChartAxisScale,
    val yScale: ChartAxisScale,
    val xTicks: List<PlotTick>,
    val yTicks: List<PlotTick>,
    val samples: List<CurveSamples>,
)

/** Renders a sampled function graph from a bounded, non-executable mathematical expression spec. */
@Composable
fun FunctionPlotCard(
    spec: FunctionPlotSpec,
    modifier: Modifier = Modifier,
) {
    val geometry = remember(spec) { spec.buildGeometry() }
    val colors = remember(spec.functions.size) { spec.functions.indices.map { ChartPalette[it % ChartPalette.size] } }
    DiagramCardFrame(
        title = spec.title,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
    ) { contentModifier, isFullScreen ->
        Column(modifier = contentModifier) {
            FunctionPlotGraph(
                geometry = geometry,
                colors = colors,
                modifier = if (isFullScreen) {
                    Modifier.weight(1f).fillMaxWidth()
                } else {
                    Modifier.fillMaxWidth().height(220.dp)
                },
            )
            if (spec.functions.size > 1 || spec.functions.any { it.label != null }) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    spec.functions.forEachIndexed { index, function ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(colors[index]),
                            )
                            Text(
                                text = function.label ?: "y = ${function.expression}",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FunctionPlotGraph(
    geometry: FunctionPlotGeometry,
    colors: List<Color>,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
    val axisColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.9f)

    Canvas(modifier = modifier) {
        val gap = 6.dp.toPx()
        val yLabels = geometry.yTicks.map { tick ->
            textMeasurer.measure(tick.label, labelStyle, maxLines = 1)
        }
        val xLabels = geometry.xTicks.map { tick ->
            textMeasurer.measure(tick.label, labelStyle, maxLines = 1)
        }
        val maxYWidth = yLabels.maxOfOrNull { it.size.width }?.toFloat() ?: 0f
        val labelHeight = textMeasurer.measure("0", labelStyle, maxLines = 1).size.height.toFloat()
        val plot = Rect(
            left = maxYWidth + gap * 2,
            top = gap,
            right = size.width - gap,
            bottom = size.height - labelHeight - gap * 2,
        )
        if (plot.width <= 0f || plot.height <= 0f) return@Canvas

        fun xToPx(value: Double): Float = plot.left + geometry.xScale.fraction(value) * plot.width
        fun yToPx(value: Double): Float = plot.bottom - geometry.yScale.fraction(value) * plot.height

        geometry.yTicks.forEachIndexed { index, tick ->
            val y = yToPx(tick.value)
            drawLine(gridColor, Offset(plot.left, y), Offset(plot.right, y), 1.dp.toPx())
            val label = yLabels[index]
            val labelSlot = plot.height / geometry.yTicks.size.coerceAtLeast(1)
            val labelStride = max(1, ceil((labelHeight + gap) / labelSlot).toInt())
            if (index % labelStride == 0 || index == geometry.yTicks.lastIndex) {
                drawText(
                    label,
                    topLeft = Offset(plot.left - gap - label.size.width, y - label.size.height / 2f),
                )
            }
        }
        geometry.xTicks.forEachIndexed { index, tick ->
            val x = xToPx(tick.value)
            drawLine(gridColor, Offset(x, plot.top), Offset(x, plot.bottom), 1.dp.toPx())
            val label = xLabels[index]
            val labelSlot = plot.width / geometry.xTicks.size.coerceAtLeast(1)
            val widestLabel = xLabels.maxOfOrNull { it.size.width }?.toFloat() ?: 0f
            val labelStride = max(1, ceil((widestLabel + gap) / labelSlot).toInt())
            if (index % labelStride == 0 || index == geometry.xTicks.lastIndex) {
                drawText(
                    label,
                    topLeft = Offset(
                        clampLabelX(x - label.size.width / 2f, label.size.width),
                        plot.bottom + gap,
                    ),
                )
            }
        }

        val xAxisY = if (geometry.yScale.min <= 0.0 && geometry.yScale.max >= 0.0) {
            yToPx(0.0)
        } else {
            plot.bottom
        }
        val yAxisX = if (geometry.xScale.min <= 0.0 && geometry.xScale.max >= 0.0) {
            xToPx(0.0)
        } else {
            plot.left
        }
        drawLine(axisColor, Offset(plot.left, xAxisY), Offset(plot.right, xAxisY), 1.25.dp.toPx())
        drawLine(axisColor, Offset(yAxisX, plot.top), Offset(yAxisX, plot.bottom), 1.25.dp.toPx())

        clipRect(plot.left, plot.top, plot.right, plot.bottom) {
            geometry.samples.forEachIndexed { index, curve ->
                val path = Path()
                var hasPreviousPoint = false
                var previousY = 0f
                curve.values.forEachIndexed valueLoop@{ sampleIndex, value ->
                    if (value == null) {
                        hasPreviousPoint = false
                        return@valueLoop
                    }
                    val x = specX(geometry, sampleIndex)
                    val y = yToPx(value)
                    if (!x.isFinite() || !y.isFinite()) {
                        hasPreviousPoint = false
                        return@valueLoop
                    }
                    if (!hasPreviousPoint || abs(y - previousY) > plot.height * 0.75f) {
                        path.moveTo(xToPx(x), y)
                    } else {
                        path.lineTo(xToPx(x), y)
                    }
                    previousY = y
                    hasPreviousPoint = true
                }
                drawPath(
                    path = path,
                    color = colors[index],
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}

private fun FunctionPlotSpec.buildGeometry(): FunctionPlotGeometry {
    val step = (xMax - xMin) / (FUNCTION_PLOT_SAMPLE_COUNT - 1)
    val samples = functions.map { function ->
        CurveSamples(
            values = List(FUNCTION_PLOT_SAMPLE_COUNT) { index ->
                val x = xMin + step * index
                function.evaluate(x).takeIf { it.isFinite() && abs(it) <= 1.0e12 }
            },
        )
    }
    val visibleValues = samples.flatMap { curve ->
        val finite = curve.values.filterNotNull().sorted()
        if (finite.size > 64) {
            val trim = (finite.size * 0.01).roundToInt()
            finite.subList(trim, finite.size - trim)
        } else {
            finite
        }
    }
    val xScale = buildAxisScale(
        values = listOf(xMin, xMax),
        userMin = xMin,
        userMax = xMax,
        log = false,
        includeZero = false,
    )
    val yScale = buildAxisScale(
        values = visibleValues,
        userMin = yMin,
        userMax = yMax,
        log = false,
        includeZero = false,
    )
    val autoXTicks = if (isPiRange(xMin, xMax)) {
        buildPiTicks(xMin, xMax)
    } else {
        xScale.ticks.map { PlotTick(it, formatChartNumber(it, decimals = xScale.tickDecimals)) }
    }
    val autoYTicks = yScale.ticks.map { PlotTick(it, formatChartNumber(it, decimals = yScale.tickDecimals)) }

    fun selectedTicks(custom: List<FunctionPlotTick>, auto: List<PlotTick>, min: Double, max: Double) =
        if (custom.isEmpty()) {
            auto
        } else {
            custom.filter { it.value in min..max }.map { tick ->
                PlotTick(tick.value, tick.label ?: formatChartNumber(tick.value))
            }
        }

    return FunctionPlotGeometry(
        xScale = xScale,
        yScale = yScale,
        xTicks = selectedTicks(xTicks, autoXTicks, xScale.min, xScale.max),
        yTicks = selectedTicks(yTicks, autoYTicks, yScale.min, yScale.max),
        samples = samples,
    )
}

private fun isPiRange(min: Double, max: Double): Boolean =
    formatPiMultiple(min) != null && formatPiMultiple(max) != null && max - min <= 24.0 * PI

private fun buildPiTicks(min: Double, max: Double): List<PlotTick> {
    val targetStep = (max - min) / 5.0
    val step = (PI_LABEL_DENOMINATORS.flatMap { denominator ->
        (1..12).map { numerator -> PI * numerator / denominator }
    }).minByOrNull { abs(ln(it / targetStep)) } ?: targetStep
    val first = ceil(min / step - 1e-9).toInt()
    val last = kotlin.math.floor(max / step + 1e-9).toInt()
    return (first..last).map { index ->
        val value = index * step
        PlotTick(value, formatPiMultiple(value) ?: formatChartNumber(value))
    }
}

private fun formatPiMultiple(value: Double): String? {
    val ratio = value / PI
    for (denominator in PI_LABEL_DENOMINATORS) {
        val numerator = (ratio * denominator).roundToInt()
        if (abs(ratio - numerator.toDouble() / denominator) <= 1e-6) {
            if (numerator == 0) return "0"
            val divisor = greatestCommonDivisor(abs(numerator), denominator)
            val reducedNumerator = abs(numerator) / divisor
            val reducedDenominator = denominator / divisor
            val sign = if (numerator < 0) "\u2212" else ""
            val symbol = "\u03C0"
            val coefficient = if (reducedNumerator == 1) "" else reducedNumerator.toString()
            return if (reducedDenominator == 1) {
                "$sign$coefficient$symbol"
            } else {
                "$sign$coefficient$symbol/$reducedDenominator"
            }
        }
    }
    return null
}

private tailrec fun greatestCommonDivisor(a: Int, b: Int): Int =
    if (b == 0) a else greatestCommonDivisor(b, a % b)

private fun DrawScope.clampLabelX(left: Float, labelWidth: Int): Float =
    left.coerceIn(0f, max(0f, size.width - labelWidth))

private fun specX(geometry: FunctionPlotGeometry, sampleIndex: Int): Double {
    val count = geometry.samples.firstOrNull()?.values?.size ?: return 0.0
    val fraction = sampleIndex.toDouble() / (count - 1).coerceAtLeast(1)
    return geometry.xScale.min + (geometry.xScale.max - geometry.xScale.min) * fraction
}
