package me.rerere.ui.diagrams

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagramZoomStateTest {
    @Test
    fun `pinch keeps the gesture centroid anchored and clamps scale`() {
        val viewport = Size(200f, 160f)
        val zoomed = DiagramZoomState().transform(
            centroid = Offset(100f, 80f),
            pan = Offset.Zero,
            zoomChange = 9f,
            viewport = viewport,
        )

        assertEquals(6f, zoomed.scale)
        assertEquals(Offset(-500f, -400f), zoomed.offset)
    }

    @Test
    fun `double tap zooms toward the touched point and a second double tap resets`() {
        val viewport = Size(200f, 160f)
        val zoomed = DiagramZoomState().doubleTap(Offset(160f, 80f), viewport)

        assertEquals(2.5f, zoomed.scale)
        assertEquals(Offset(-90f, 0f), zoomed.offset)
        assertEquals(DiagramZoomState(), zoomed.doubleTap(Offset(160f, 80f), viewport))
    }

    @Test
    fun `panning remains bounded by the zoomed viewport`() {
        val zoomed = DiagramZoomState(scale = 2f, offset = Offset(20f, 10f)).transform(
            centroid = Offset(100f, 80f),
            pan = Offset(500f, -500f),
            zoomChange = 1f,
            viewport = Size(200f, 160f),
        )

        assertEquals(Offset(100f, -80f), zoomed.offset)
    }
}
