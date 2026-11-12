package me.rerere.ui.diagrams

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VisualDiagramSpecTest {
    @Test
    fun `parses a bounded physics diagram with force arrows and labels`() {
        val spec = VisualDiagramSpec.fromJson(
            """{"version":1,"width":100,"height":70,"elements":[{"type":"arrow","from":[50,48],"to":[50,12],"color":"red"},{"type":"polygon","points":[[40,40],[60,40],[60,55],[40,55]],"fill":"secondary"},{"type":"text","at":[56,12],"text":"N","align":"start"}]}""",
        )

        assertNotNull(spec)
        assertEquals(3, spec!!.elements.size)
        assertEquals(VisualDiagramElement.Alignment.START, (spec.elements.last() as VisualDiagramElement.Label).alignment)
    }

    @Test
    fun `normalizes small model text and keeps valid elements when one is malformed`() {
        val spec = VisualDiagramSpec.fromJson(
            """{"version":1,"width":100,"height":70,"elements":[{"type":"text","at":[10,10],"text":"N","fontSize":4.5},{"type":"rect","x":90,"y":60,"width":20,"height":20},{"type":"arrow","from":[20,50],"to":[20,20]}]}""",
        )

        assertNotNull(spec)
        assertEquals(2, spec!!.elements.size)
        assertEquals(12f, (spec.elements.first() as VisualDiagramElement.Label).fontSize)
    }

    @Test
    fun `parses centered rectangles emitted in model diagrams`() {
        val spec = VisualDiagramSpec.fromJson(
            """{"version":1,"width":120,"height":75,"elements":[{"type":"rect","center":[48,38],"width":12,"height":8,"color":"primary","fill":"secondary"}]}""",
        )

        assertNotNull(spec)
        val rectangle = spec!!.elements.single() as VisualDiagramElement.Rectangle
        assertEquals(42f, rectangle.x)
        assertEquals(34f, rectangle.y)
        assertEquals(12f, rectangle.width)
        assertEquals(8f, rectangle.height)
    }

    @Test
    fun `rejects executable shapes invalid coordinates and too many elements`() {
        assertNull(VisualDiagramSpec.fromJson("""{"version":1,"width":100,"height":70,"elements":[{"type":"script","code":"alert(1)"}]}"""))
        assertNull(VisualDiagramSpec.fromJson("""{"version":1,"width":100,"height":70,"elements":[{"type":"line","from":[-1,4],"to":[2,3]}]}"""))
        val manyElements = (1..129).joinToString(",") { "{\"type\":\"line\",\"from\":[0,0],\"to\":[1,1]}" }
        assertNull(VisualDiagramSpec.fromJson("""{"version":1,"width":100,"height":70,"elements":[$manyElements]}"""))
    }
}
