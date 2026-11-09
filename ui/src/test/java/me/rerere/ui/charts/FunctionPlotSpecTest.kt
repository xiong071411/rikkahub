package me.rerere.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI

class FunctionPlotSpecTest {
    @Test
    fun `parses common functions constants powers and implicit multiplication`() {
        val spec = FunctionPlotSpec.fromJson(
            """
            {
              "functions": [
                {"expression": "sin(pi/2) + sqrt(9) + log(100) + ln(e) + 2x"}
              ]
            }
            """.trimIndent(),
        )

        assertNotNull(spec)
        assertEquals(9.0, spec!!.functions.single().evaluate(1.0), 1e-10)
    }

    @Test
    fun `honors conventional unary and right associative power precedence`() {
        val spec = FunctionPlotSpec.fromJson(
            """
            {"functions":[{"expression":"-x^2 + 2^3^2"}]}
            """.trimIndent(),
        )

        assertEquals(508.0, spec!!.functions.single().evaluate(2.0), 1e-10)
    }

    @Test
    fun `parses pi ranges and keeps exact fractional pi axis labels`() {
        val spec = FunctionPlotSpec.fromJson(
            """
            {
              "xRange": ["-π", "π"],
              "xTicks": ["-π", "-π/2", "0", "π/2", "π"],
              "yTicks": [{"value":"π/3","label":"π/3"}],
              "functions": [{"expression":"sin(x)"}]
            }
            """.trimIndent(),
        )

        assertNotNull(spec)
        assertEquals(-PI, spec!!.xMin, 1e-10)
        assertEquals(PI, spec.xMax, 1e-10)
        assertEquals(listOf("−π", "−π/2", "0", "π/2", "π"), spec.xTicks.map { it.label })
        assertEquals("π/3", spec.yTicks.single().label)
    }

    @Test
    fun `rejects executable syntax unknown functions and variable axis values`() {
        assertNull(FunctionPlotSpec.fromJson("""{"functions":[{"expression":"globalThis.alert(1)"}]}"""))
        assertNull(FunctionPlotSpec.fromJson("""{"xRange":["x","pi"],"functions":[{"expression":"x"}]}"""))
        assertNull(FunctionPlotSpec.fromJson("""{"functions":[{"expression":"unknown(x)"}]}"""))
    }

    @Test
    fun `bounds function count and expression complexity`() {
        val manyFunctions = (1..9).joinToString(",") { "{\"expression\":\"x+$it\"}" }
        assertNull(FunctionPlotSpec.fromJson("""{"functions":[$manyFunctions]}"""))

        val nested = "(".repeat(40) + "x" + ")".repeat(40)
        assertNull(FunctionPlotSpec.fromJson("""{"functions":[{"expression":"$nested"}]}"""))
    }
}
