package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeMathRendererTest {
    @Test
    fun ensureMinSpanExpandsSubpixelFractionRuleSymmetrically() {
        val (top, bottom) = SafeMathRenderer.ensureMinSpan(100.0f, 100.42f, 2.0f)
        assertEquals(2.0f, bottom - top, 1e-4f)
        assertEquals(100.21f, (top + bottom) * 0.5f, 1e-4f)
    }

    @Test
    fun ensureMinSpanKeepsThickRuleUntouched() {
        val (top, bottom) = SafeMathRenderer.ensureMinSpan(50.0f, 52.5f, 2.0f)
        assertEquals(50.0f, top, 1e-4f)
        assertEquals(52.5f, bottom, 1e-4f)
    }

    @Test
    fun normalizeSvgPathDataPreservesCoordinateSeparatorsInSqrtPaths() {
        val main = SafeMathRenderer.normalizeSvgPathData(LatexTestBridge.sqrtPath("sqrtMain", 0f, 1080f))
        assertFalse(main.contains("83480"))
        assertTrue(main.contains("M834 80"))

        val tall = SafeMathRenderer.normalizeSvgPathData(LatexTestBridge.sqrtPath("sqrtTall", 0f, 3000f))
        assertFalse(tall.contains("40000040"))
        assertFalse(tall.contains("219661"))
        assertTrue(tall.contains("219 661"))
        assertTrue(tall.contains("H400000 40 H742"))
    }
}
