package me.rerere.rikkahub.ui.components.richtext;

import me.rerere.bettertext.latex.layout.SvgGeometryKt;

final class LatexTestBridge {
    private LatexTestBridge() {}

    static String sqrtPath(String size, float extraVinculum, float viewBoxHeight) {
        return SvgGeometryKt.sqrtPath(size, extraVinculum, viewBoxHeight);
    }
}
