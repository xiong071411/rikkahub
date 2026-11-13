package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import me.rerere.bettertext.latex.DefaultMathScale
import me.rerere.bettertext.latex.LatexLayout
import me.rerere.bettertext.latex.LatexSettings
import me.rerere.bettertext.latex.rememberLatexLayout
import kotlin.math.ceil
import kotlin.math.roundToInt

/** 块级公式，居中显示，太宽时横向滚动；字号和颜色默认跟随当前文字样式 */
@Composable
fun MathBlock(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified
) {
    val style = LocalTextStyle.current
    val resolvedFontSize = fontSize.takeOrElse { style.fontSize }.takeOrElse { 16.sp }
    val resolvedColor = style.color.takeOrElse { LocalContentColor.current }
    val layout = rememberLatexLayout(
        latex = latex,
        settings = remember { LatexSettings(displayMode = true) },
    )
    SafeLatexBlock(
        layout = layout,
        modifier = modifier.padding(8.dp),
        fontSize = resolvedFontSize,
        color = resolvedColor,
    )
}

@Composable
private fun SafeLatexBlock(
    layout: LatexLayout,
    modifier: Modifier = Modifier,
    fontSize: TextUnit,
    color: Color,
    scale: Float = DefaultMathScale,
) {
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        SafeLatex(
            layout = layout,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .widthIn(min = maxWidth),
            fontSize = fontSize,
            color = color,
            scale = scale,
        )
    }
}

@Composable
private fun SafeLatex(
    layout: LatexLayout,
    modifier: Modifier = Modifier,
    fontSize: TextUnit,
    color: Color,
    scale: Float = DefaultMathScale,
) {
    val context = LocalContext.current
    val lineState = remember(layout) { LatexBoxRenderer.BlockLineState(layout) }
    val colorArgb = color.takeOrElse { Color.Black }.toArgb()

    Layout(
        modifier = modifier.drawBehind {
            val emPx = fontSize.toPx() * scale
            if (emPx <= 0f) return@drawBehind
            lineState.draw(
                SafeMathRenderer.forContext(context),
                drawContext.canvas.nativeCanvas,
                size.width,
                emPx,
                colorArgb,
            )
        },
    ) { _, constraints ->
        val emPx = fontSize.toPx() * scale
        if (emPx <= 0f) {
            return@Layout layout(constraints.minWidth, constraints.minHeight) {}
        }
        val maxEm = if (constraints.hasBoundedWidth) constraints.maxWidth / emPx else Float.POSITIVE_INFINITY
        val minEm = constraints.minWidth / emPx
        val metrics = lineState.measure(maxEm, minEm)
        val widthPx = ceil(metrics.widthEm * emPx).toInt().coerceIn(constraints.minWidth, constraints.maxWidth)
        val heightPx = ceil(metrics.totalHeightEm * emPx).toInt().coerceIn(constraints.minHeight, constraints.maxHeight)
        val firstBaseline = (metrics.firstBaselineEm * emPx).roundToInt()
        val lastBaseline = (metrics.lastBaselineEm * emPx).roundToInt()
        layout(
            width = widthPx,
            height = heightPx,
            alignmentLines = mapOf(
                FirstBaseline to firstBaseline,
                LastBaseline to lastBaseline,
            ),
        ) {}
    }
}
