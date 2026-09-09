package com.fieldnote.ui.screen

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteTransformTest {
    @Test
    fun panOnlyGestureKeepsExistingPanAndAddsCentroidMovement() {
        val result = transformAroundCentroid(
            zoom = 1f,
            pan = Offset(12f, -8f),
            previousCentroid = Offset(100f, 200f),
            currentCentroid = Offset(110f, 185f),
            zoomDelta = 1f
        )

        assertOffsetEquals(Offset(22f, -23f), result.pan)
        assertEquals(1f, result.zoom, 0.0001f)
    }

    @Test
    fun pinchKeepsTheNotePointUnderTheFingerCentroid() {
        val previousCentroid = Offset(100f, 160f)
        val originalPan = Offset(20f, 40f)
        val notePoint = (previousCentroid - originalPan) / 1f

        val result = transformAroundCentroid(
            zoom = 1f,
            pan = originalPan,
            previousCentroid = previousCentroid,
            currentCentroid = previousCentroid,
            zoomDelta = 2f
        )

        assertEquals(2f, result.zoom, 0.0001f)
        assertOffsetEquals(previousCentroid, result.pan + notePoint * result.zoom)
    }

    @Test
    fun zoomClampUsesTheActuallyAppliedScaleForPan() {
        val centroid = Offset(80f, 120f)
        val originalZoom = 3.9f
        val notePoint = centroid / originalZoom

        val result = transformAroundCentroid(
            zoom = originalZoom,
            pan = Offset.Zero,
            previousCentroid = centroid,
            currentCentroid = centroid,
            zoomDelta = 2f
        )

        assertEquals(4f, result.zoom, 0.0001f)
        assertOffsetEquals(centroid, result.pan + notePoint * result.zoom)
    }

    private fun assertOffsetEquals(expected: Offset, actual: Offset) {
        assertEquals(expected.x, actual.x, 0.001f)
        assertEquals(expected.y, actual.y, 0.001f)
    }
}
