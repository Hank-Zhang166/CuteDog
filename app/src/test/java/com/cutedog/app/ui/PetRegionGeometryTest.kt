package com.cutedog.app.ui

import org.junit.Assert.*
import org.junit.Test

class PetRegionGeometryTest {
    @Test fun movingPastEdgePreservesAreaAndBounds() {
        val original = PetRegion(.2f, .3f, .6f, .7f)
        val moved = moveRegion(original, 2f, -2f, -1)
        assertEquals(.6f, moved.left, .0001f)
        assertEquals(1f, moved.right, .0001f)
        assertEquals(0f, moved.top, .0001f)
        assertEquals(original.bottom - original.top, moved.bottom - moved.top, .0001f)
    }
    @Test fun draggingCornerCannotInvertOrCollapseRegion() {
        val resized = moveRegion(PetRegion(.2f, .3f, .6f, .7f), 4f, 4f, 0)
        assertEquals(.08f, resized.right - resized.left, .0001f)
        assertEquals(.08f, resized.bottom - resized.top, .0001f)
    }
    @Test fun editorDraftRoundTripKeepsFractionalCoordinates() {
        val original = PetRegion(.125f, .25f, .875f, .95f)
        assertEquals(original, PetRegion.decode(original.encode()))
    }
}
