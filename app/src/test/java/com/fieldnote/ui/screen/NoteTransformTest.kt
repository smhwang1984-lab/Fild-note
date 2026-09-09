package com.fieldnote.ui.screen

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteTransformTest {
    @Test
    fun a4PageFitsAndCentersInTabletViewportAtOneHundredPercent() {
        val transform = calculateNotePageTransform(
            viewport = Size(1200f, 800f),
            zoom = 1f,
            pan = Offset.Zero,
            marginPx = 20f
        )

        assertEquals(760f, transform.pageSize.height, 0.01f)
        assertEquals(760f * 210f / 297f, transform.pageSize.width, 0.01f)
        assertOffsetEquals(
            Offset((1200f - transform.pageSize.width) / 2f, 20f),
            transform.origin
        )
    }

    @Test
    fun pageAndScreenCoordinatesRoundTrip() {
        val transform = calculateNotePageTransform(Size(1200f, 800f), 1.5f, Offset(18f, -9f), 20f)
        val pagePoint = Offset(80f, 140f)
        assertOffsetEquals(pagePoint, transform.screenToPage(transform.pageToScreen(pagePoint)))
    }

    @Test
    fun deferredStabilizationPreservesBothEndpoints() {
        val raw = listOf(
            StrokePoint(Offset(10f, 10f)),
            StrokePoint(Offset(10.1f, 10.8f)),
            StrokePoint(Offset(10.3f, 11.7f)),
            StrokePoint(Offset(10.7f, 12.4f)),
            StrokePoint(Offset(11f, 13f))
        )
        val stabilized = finalizeStroke(raw, 1f)
        assertEquals(raw.first(), stabilized.first())
        assertEquals(raw.last(), stabilized.last())
    }

    @Test
    fun stabilizationOnlyChangesTheRequestedHandwritingBlock() {
        val firstBlock = InkStroke(
            color = Color.Black,
            width = 1f,
            points = listOf(
                StrokePoint(Offset(0f, 0f)),
                StrokePoint(Offset(1f, 0.4f)),
                StrokePoint(Offset(2f, 0f))
            ),
            isStabilized = false,
            stabilizationBlock = 4
        )
        val laterBlock = firstBlock.copy(stabilizationBlock = 5)

        val result = stabilizeStrokeBlock(listOf(firstBlock, laterBlock), targetBlock = 4, stability = 0.7f)

        assertTrue(result[0].isStabilized)
        assertFalse(result[1].isStabilized)
        assertSame(laterBlock, result[1])
    }

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
