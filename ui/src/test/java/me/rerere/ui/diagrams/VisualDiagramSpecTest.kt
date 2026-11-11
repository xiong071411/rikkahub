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
    fun `rejects executable shapes invalid coordinates and too many elements`() {
        assertNull(VisualDiagramSpec.fromJson("""{"version":1,"width":100,"height":70,"elements":[{"type":"script","code":"alert(1)"}]}"""))
        assertNull(VisualDiagramSpec.fromJson("""{"version":1,"width":100,"height":70,"elements":[{"type":"line","from":[-1,4],"to":[2,3]}]}"""))
        val manyElements = (1..129).joinToString(",") { "{\"type\":\"line\",\"from\":[0,0],\"to\":[1,1]}" }
        assertNull(VisualDiagramSpec.fromJson("""{"version":1,"width":100,"height":70,"elements":[$manyElements]}"""))
    }
}
