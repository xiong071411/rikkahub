package me.rerere.rikkahub.ui.components.richtext

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.LruCache
import androidx.core.graphics.PathParser
import androidx.core.graphics.createBitmap
import me.rerere.bettertext.latex.LatexInline
import me.rerere.bettertext.latex.LatexSettings
import me.rerere.bettertext.text.DrawnInline
import me.rerere.bettertext.text.InlineMetrics
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 兼容 Android 8.1 (OpenGLRenderer) 与低密度/小屏缩放的 LaTeX 渲染器。
 *
 * 修复 bettertext-latex v0.1.5 的两处渲染缺陷：
 * 1. RuleBox（分数线等）在低 DPI 下高度 < 1px 时易被 OpenGLRenderer 亚像素裁切丢弃，确保最小绘制厚度 >= 1.15px。
 * 2. SvgBox（如 \sqrt 沿用 KaTeX 400000 虚拟宽路径）直接 drawPath 会超出 Android 8.1 HWUI PathCache 纹理上限，
 *    且 bettertext 预处理去掉全部空格会导致 sqrtMain/sqrtTall 路径坐标粘连；此处保留空格解析并按可视框裁剪/分块绘制。
 */
internal class SafeMathRenderer(
    private val appContext: Context,
) {
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val rulePaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = false
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val matrix = Matrix()
    private val tmpPath = Path()
    private val clipRectPath = Path()
    private val tilePath = Path()
    private val rect = RectF()
    private val pathBounds = RectF()

    @JvmName("drawFillRect")
    internal fun drawFillRect(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        color: Int,
    ) {
        fillPaint.color = color
        canvas.drawRect(left, top, right, bottom, fillPaint)
    }

    @JvmName("drawSymbol")
    internal fun drawSymbol(
        canvas: Canvas,
        text: String,
        font: String,
        textSize: Float,
        x: Float,
        y: Float,
        color: Int,
    ) {
        textPaint.color = color
        textPaint.typeface = LatexBoxRenderer.typeface(appContext, font)
        textPaint.textSize = textSize
        canvas.drawText(text, x, y, textPaint)
    }

    @JvmName("drawRule")
    internal fun drawRule(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        dashed: Boolean,
        color: Int,
    ) {
        if (right <= left || bottom <= top) return
        val (drawLeft, drawRight) = ensureMinSpan(left, right, MIN_RULE_PX)
        val (drawTop, drawBottom) = ensureMinSpan(top, bottom, MIN_RULE_PX)
        rulePaint.color = color
        if (dashed) {
            drawDashed(canvas, drawLeft, drawTop, drawRight, drawBottom, rulePaint)
        } else {
            canvas.drawRect(drawLeft, drawTop, drawRight, drawBottom, rulePaint)
        }
    }

    @JvmName("drawBorder")
    internal fun drawBorder(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        strokeWidthPx: Float,
        color: Int,
    ) {
        val strokeWidth = max(strokeWidthPx, MIN_RULE_PX)
        strokePaint.color = color
        strokePaint.strokeWidth = strokeWidth
        val half = strokeWidth / 2f
        canvas.drawRect(left + half, top + half, right - half, bottom - half, strokePaint)
    }

    @JvmName("beginSvgViewport")
    internal fun beginSvgViewport(
        canvas: Canvas,
        x: Float,
        svgTop: Float,
        widthPx: Float,
        heightPx: Float,
        viewBox: FloatArray,
        preserveAspectRatio: String,
        color: Int,
    ) {
        val parTokens = preserveAspectRatio.trim().split(' ')
        val align = parTokens[0]
        val isSlice = parTokens.getOrNull(1) == "slice"
        val sx = widthPx / viewBox[2]
        val sy = heightPx / viewBox[3]

        matrix.reset()
        if (align == "none") {
            matrix.setScale(sx, sy)
            matrix.postTranslate(x - viewBox[0] * sx, svgTop - viewBox[1] * sy)
        } else {
            val s = if (isSlice) max(sx, sy) else min(sx, sy)
            val dx = when {
                align.startsWith("xMin") -> 0f
                align.startsWith("xMax") -> widthPx - viewBox[2] * s
                else -> (widthPx - viewBox[2] * s) / 2f
            }
            val dy = when {
                align.endsWith("YMin") -> 0f
                align.endsWith("YMax") -> heightPx - viewBox[3] * s
                else -> (heightPx - viewBox[3] * s) / 2f
            }
            matrix.setScale(s, s)
            matrix.postTranslate(x + dx - viewBox[0] * s, svgTop + dy - viewBox[1] * s)
        }

        canvas.save()
        rect.set(x, svgTop, x + widthPx, svgTop + heightPx)
        canvas.clipRect(rect)
        fillPaint.color = color
    }

    @JvmName("endSvgViewport")
    internal fun endSvgViewport(canvas: Canvas) {
        canvas.restore()
    }

    @JvmName("drawSvgLine")
    internal fun drawSvgLine(
        canvas: Canvas,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        strokeWidthPx: Float,
        color: Int,
    ) {
        strokePaint.color = color
        strokePaint.strokeWidth = max(strokeWidthPx, MIN_RULE_PX)
        canvas.drawLine(x1, y1, x2, y2, strokePaint)
    }

    @JvmName("drawSafeSvgPath")
    internal fun drawSafeSvgPath(canvas: Canvas, pathData: String) {
        val parsed = parsePath(pathData)
        if (parsed.isEmpty) return

        tmpPath.set(parsed)
        tmpPath.transform(matrix)
        tmpPath.computeBounds(pathBounds, true)
        if (pathBounds.isEmpty) return

        val clipLeft = rect.left - CLIP_MARGIN_PX
        val clipTop = rect.top - CLIP_MARGIN_PX
        val clipRight = rect.right + CLIP_MARGIN_PX
        val clipBottom = rect.bottom + CLIP_MARGIN_PX

        val needsClip =
            pathBounds.left < clipLeft ||
                pathBounds.right > clipRight ||
                pathBounds.top < clipTop ||
                pathBounds.bottom > clipBottom ||
                pathBounds.width() > MAX_HWUI_PATH_DIM_PX ||
                pathBounds.height() > MAX_HWUI_PATH_DIM_PX

        var clipSucceeded = true
        if (needsClip) {
            clipRectPath.rewind()
            clipRectPath.addRect(clipLeft, clipTop, clipRight, clipBottom, Path.Direction.CW)
            clipSucceeded = tmpPath.op(clipRectPath, Path.Op.INTERSECT)
            if (clipSucceeded) {
                tmpPath.computeBounds(pathBounds, true)
            }
        }

        if (pathBounds.isEmpty) return

        if (clipSucceeded && pathBounds.width() <= MAX_HWUI_PATH_DIM_PX && pathBounds.height() <= MAX_HWUI_PATH_DIM_PX) {
            canvas.drawPath(tmpPath, fillPaint)
            return
        }

        val startX = max(pathBounds.left, clipLeft)
        val endX = min(pathBounds.right, clipRight)
        val startY = max(pathBounds.top, clipTop)
        val endY = min(pathBounds.bottom, clipBottom)
        if (endX <= startX || endY <= startY) return

        var tileY = startY
        while (tileY < endY) {
            val nextY = min(tileY + TILE_SIZE_PX, endY)
            var tileX = startX
            while (tileX < endX) {
                val nextX = min(tileX + TILE_SIZE_PX, endX)
                clipRectPath.rewind()
                clipRectPath.addRect(tileX, tileY, nextX, nextY, Path.Direction.CW)
                if (tilePath.op(tmpPath, clipRectPath, Path.Op.INTERSECT)) {
                    if (!tilePath.isEmpty) {
                        canvas.drawPath(tilePath, fillPaint)
                    }
                } else {
                    drawTileSoftwareFallback(canvas, tmpPath, tileX, tileY, nextX, nextY)
                }
                tileX = nextX
            }
            tileY = nextY
        }
    }

    private fun drawTileSoftwareFallback(
        canvas: Canvas,
        path: Path,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        val w = ceil(right - left).toInt().coerceIn(1, TILE_SIZE_PX.toInt())
        val h = ceil(bottom - top).toInt().coerceIn(1, TILE_SIZE_PX.toInt())
        val bitmap = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val swCanvas = Canvas(bitmap)
            swCanvas.translate(-left, -top)
            swCanvas.drawPath(path, fillPaint)
            canvas.drawBitmap(bitmap, left, top, null)
        } finally {
            bitmap.recycle()
        }
    }

    private fun drawDashed(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        paint: Paint,
    ) {
        val width = right - left
        val height = bottom - top
        if (height > width) {
            val step = max(width * 3f, 2f)
            var curY = top
            while (curY < bottom) {
                canvas.drawRect(left, curY, right, min(curY + step, bottom), paint)
                curY += step * 2f
            }
        } else {
            val step = max(height * 3f, 2f)
            var curX = left
            while (curX < right) {
                canvas.drawRect(curX, top, min(curX + step, right), bottom, paint)
                curX += step * 2f
            }
        }
    }

    companion object {
        // 使用非抗锯齿画笔并保证 >= 2.0px（floor(b) - ceil(a) >= 1），避免 Android 8.1 OpenGLRenderer 亚像素取整或 AA 内缩导致细线丢失
        private const val MIN_RULE_PX = 2.0f
        private const val CLIP_MARGIN_PX = 1f
        private const val MAX_HWUI_PATH_DIM_PX = 1536f
        private const val TILE_SIZE_PX = 1024f

        private val whitespaceRegex = Regex("\\s+")

        private val pathCache = object : LruCache<String, Path>(256) {
            override fun create(key: String): Path {
                val normalized = normalizeSvgPathData(key)
                return runCatching { PathParser.createPathFromPathData(normalized) }
                    .getOrElse { Path() }
            }
        }

        private val threadLocalRenderer = ThreadLocal<SafeMathRenderer>()

        fun forContext(context: Context): SafeMathRenderer {
            val appContext = context.applicationContext ?: context
            return threadLocalRenderer.get() ?: SafeMathRenderer(appContext).also {
                threadLocalRenderer.set(it)
            }
        }

        internal fun normalizeSvgPathData(d: String): String {
            return d.replace(whitespaceRegex, " ").trim()
        }

        private fun parsePath(d: String): Path {
            return synchronized(pathCache) {
                pathCache.get(d)
            }
        }

        internal fun ensureMinSpan(start: Float, end: Float, minSpan: Float): Pair<Float, Float> {
            val span = end - start
            if (span >= minSpan) return start to end
            val mid = (start + end) * 0.5f
            val half = minSpan * 0.5f
            return (mid - half) to (mid + half)
        }
    }
}

/**
 * 复用 [LatexInline] 的解析与布局缓存，并通过 [SafeMathRenderer] 绘制行内公式。
 */
internal class SafeLatexInline private constructor(
    private val context: Context,
    private val delegate: LatexInline,
) : DrawnInline() {
    override val segmentCount: Int
        get() = delegate.segmentCount

    override fun measure(segment: Int, paint: Paint, metrics: InlineMetrics) {
        delegate.measure(segment, paint, metrics)
    }

    override fun draw(segment: Int, canvas: Canvas, x: Float, baseline: Float, paint: Paint) {
        LatexBoxRenderer.drawChunk(
            SafeMathRenderer.forContext(context),
            canvas,
            delegate.layout,
            segment,
            x,
            baseline,
            paint.textSize * delegate.scale,
            paint.color,
        )
    }

    companion object {
        fun cached(
            latex: String,
            context: Context,
            settings: LatexSettings = LatexSettings(),
        ): SafeLatexInline {
            val appContext = context.applicationContext ?: context
            return SafeLatexInline(
                context = appContext,
                delegate = LatexInline.cached(latex, appContext, settings),
            )
        }
    }
}
