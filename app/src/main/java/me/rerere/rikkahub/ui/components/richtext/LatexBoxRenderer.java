package me.rerere.rikkahub.ui.components.richtext;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import java.util.List;
import me.rerere.bettertext.latex.LatexLayout;
import me.rerere.bettertext.latex.Line;
import me.rerere.bettertext.latex.LineCache;
import me.rerere.bettertext.latex.LineResult;
import me.rerere.bettertext.latex.layout.Box;
import me.rerere.bettertext.latex.layout.RuleBox;
import me.rerere.bettertext.latex.layout.SpanBox;
import me.rerere.bettertext.latex.layout.SvgBox;
import me.rerere.bettertext.latex.layout.SvgElement;
import me.rerere.bettertext.latex.layout.SymbolBox;
import me.rerere.bettertext.latex.layout.VListBox;
import me.rerere.bettertext.latex.layout.VListItem;
import me.rerere.bettertext.latex.render.MathTypefaces;

/**
 * 直接访问 bettertext-latex 的排版结果与 Box 树并委托给 SafeMathRenderer 绘制。
 * bettertext 的部分类型在 Kotlin 元数据中为 internal，但在 JVM 字节码中均为 public；
 * 通过 Java 桥接直接调用可避免反射且兼容 R8 裁剪。
 */
final class LatexBoxRenderer {
    private LatexBoxRenderer() {}

    static final class BlockLineState {
        final LatexLayout layout;
        final LineCache lineCache;

        BlockLineState(LatexLayout layout) {
            this.layout = layout;
            this.lineCache = new LineCache(layout);
        }

        MeasuredMetrics measure(float maxEm, float minEm) {
            LineResult result = lineCache.measure(maxEm, minEm);
            List<Line> lines = result.getLines();
            float firstBaseline = lines.isEmpty() ? 0f : lines.get(0).getBaseline();
            float lastBaseline = lines.isEmpty() ? 0f : lines.get(lines.size() - 1).getBaseline();
            return new MeasuredMetrics(
                result.getWidth(),
                result.getTotalHeight(),
                firstBaseline,
                lastBaseline
            );
        }

        void draw(
            SafeMathRenderer renderer,
            Canvas canvas,
            float widthPx,
            float emPx,
            int colorArgb
        ) {
            LineResult result = lineCache.getLast();
            if (result == null) {
                float em = widthPx / emPx;
                result = lineCache.measure(em, em);
            }
            List<SpanBox> chunks = layout.getChunks$latex();
            for (Line line : result.getLines()) {
                float xEm = line.getX();
                float baselinePx = line.getBaseline() * emPx;
                for (int i = line.getStart(); i < line.getEnd(); i++) {
                    SpanBox chunk = chunks.get(i);
                    drawBox(renderer, canvas, chunk, xEm * emPx, baselinePx, emPx, colorArgb);
                    xEm += chunk.getAdvance();
                }
            }
            Box tag = layout.getTag$latex();
            if (tag != null) {
                drawBox(
                    renderer,
                    canvas,
                    tag,
                    result.getTagX() * emPx,
                    result.getTagBaseline() * emPx,
                    emPx,
                    colorArgb
                );
            }
        }
    }

    static final class MeasuredMetrics {
        final float widthEm;
        final float totalHeightEm;
        final float firstBaselineEm;
        final float lastBaselineEm;

        MeasuredMetrics(float widthEm, float totalHeightEm, float firstBaselineEm, float lastBaselineEm) {
            this.widthEm = widthEm;
            this.totalHeightEm = totalHeightEm;
            this.firstBaselineEm = firstBaselineEm;
            this.lastBaselineEm = lastBaselineEm;
        }
    }

    static Typeface typeface(Context context, String font) {
        return MathTypefaces.Companion.get(context).get(font);
    }

    static void drawChunk(
        SafeMathRenderer renderer,
        Canvas canvas,
        LatexLayout layout,
        int chunkIndex,
        float x,
        float y,
        float scale,
        int defaultColor
    ) {
        List<SpanBox> chunks = layout.getChunks$latex();
        if (chunkIndex < 0 || chunkIndex >= chunks.size()) return;
        drawBox(renderer, canvas, chunks.get(chunkIndex), x, y, scale, defaultColor);
    }

    static void drawBox(
        SafeMathRenderer renderer,
        Canvas canvas,
        Box box,
        float x,
        float y,
        float scale,
        int defaultColor
    ) {
        Integer boxColor = box.getColor();
        int resolvedColor = boxColor != null ? boxColor : defaultColor;
        float top = y - box.getHeight() * scale;
        float bottom = y + box.getDepth() * scale;
        float widthPx = box.getWidth() * scale;

        Integer bgColor = box.getBackgroundColor();
        if (bgColor != null) {
            renderer.drawFillRect(canvas, x, top, x + widthPx, bottom, bgColor);
        }

        boolean clip = box.getClip();
        if (clip) {
            canvas.save();
            canvas.clipRect(x, top, x + widthPx, bottom);
        }

        if (box instanceof SymbolBox symbol) {
            if (Color.alpha(resolvedColor) != 0 && !symbol.getText().isEmpty()) {
                renderer.drawSymbol(
                    canvas,
                    symbol.getText(),
                    symbol.getFont(),
                    symbol.getScale() * scale,
                    x,
                    y,
                    resolvedColor
                );
            }
        } else if (box instanceof SpanBox span) {
            float curX = x;
            for (Box child : span.getChildren()) {
                curX += child.getMarginLeft() * scale;
                drawBox(renderer, canvas, child, curX, y + child.getShiftY() * scale, scale, resolvedColor);
                curX += (child.getAdvance() - child.getMarginLeft()) * scale;
            }
        } else if (box instanceof VListBox vlist) {
            for (VListItem item : vlist.getItems()) {
                Box child = item.getBox();
                float itemX = x + (vlist.itemX(item) + child.getMarginLeft()) * scale;
                float itemY = y + (item.getY() + child.getShiftY()) * scale;
                drawBox(renderer, canvas, child, itemX, itemY, scale, resolvedColor);
            }
        } else if (box instanceof RuleBox rule) {
            if (Color.alpha(resolvedColor) != 0) {
                renderer.drawRule(canvas, x, top, x + widthPx, bottom, rule.getDashed(), resolvedColor);
            }
        } else if (box instanceof SvgBox svg) {
            if (Color.alpha(resolvedColor) != 0) {
                drawSvg(renderer, canvas, svg, x, y, scale, resolvedColor);
            }
        }

        if (clip) {
            canvas.restore();
        }

        if (box.getBorderWidth() > 0f) {
            Integer borderColor = box.getBorderColor();
            int resolvedBorder = borderColor != null ? borderColor : resolvedColor;
            if (Color.alpha(resolvedBorder) != 0) {
                renderer.drawBorder(
                    canvas,
                    x,
                    top,
                    x + widthPx,
                    bottom,
                    box.getBorderWidth() * scale,
                    resolvedBorder
                );
            }
        }
    }

    private static void drawSvg(
        SafeMathRenderer renderer,
        Canvas canvas,
        SvgBox svg,
        float x,
        float y,
        float scale,
        int color
    ) {
        float widthPx = svg.getWidth() * scale;
        float heightPx = svg.getSvgHeight() * scale;
        if (widthPx <= 0f || heightPx <= 0f) return;

        float svgTop = y - heightPx;
        float[] viewBox = svg.getViewBox();

        if (viewBox == null) {
            canvas.save();
            for (SvgElement element : svg.getElements()) {
                if (element instanceof SvgElement.Line line) {
                    renderer.drawSvgLine(
                        canvas,
                        x + line.getX1() * widthPx,
                        svgTop + line.getY1() * heightPx,
                        x + line.getX2() * widthPx,
                        svgTop + line.getY2() * heightPx,
                        line.getStrokeWidth() * scale,
                        color
                    );
                }
            }
            canvas.restore();
            return;
        }

        renderer.beginSvgViewport(
            canvas,
            x,
            svgTop,
            widthPx,
            heightPx,
            viewBox,
            svg.getPreserveAspectRatio(),
            color
        );
        for (SvgElement element : svg.getElements()) {
            if (element instanceof SvgElement.Path path) {
                renderer.drawSafeSvgPath(canvas, path.getD());
            } else if (element instanceof SvgElement.Line line) {
                renderer.drawSvgLine(
                    canvas,
                    x + line.getX1() * widthPx,
                    svgTop + line.getY1() * heightPx,
                    x + line.getX2() * widthPx,
                    svgTop + line.getY2() * heightPx,
                    line.getStrokeWidth() * scale,
                    color
                );
            }
        }
        renderer.endSvgViewport(canvas);
    }
}
