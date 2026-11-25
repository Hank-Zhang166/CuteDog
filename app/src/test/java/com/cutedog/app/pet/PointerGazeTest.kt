package com.cutedog.app.pet

import org.junit.Assert.*
import org.junit.Test

class PointerGazeTest {
    @Test fun followsFingertipInBothAxesWhilePawsStayPlanted() {
        val gaze = PointerGaze()
        val head = vertex(gaze, 128, 160)
        val foot = vertex(gaze, 192, 416)
        gaze.position(0f, 0f, 512f)
        val base = gaze.vertices.copyOf()
        gaze.target(1f, 0f)
        repeat(12) { gaze.step(1f / 24) }
        gaze.position(0f, 0f, 512f)
        assertTrue(gaze.vertices[head] > base[head] + 6f)
        assertTrue(gaze.vertices[head + 1] < base[head + 1] - 5f)
        assertEquals(base[foot], gaze.vertices[foot], 0f)
        assertEquals(base[foot + 1], gaze.vertices[foot + 1], 0f)
        gaze.target(0f, 1f)
        repeat(12) { gaze.step(1f / 24) }
        gaze.position(0f, 0f, 512f)
        assertTrue(gaze.vertices[head] < base[head] - 6f)
        assertTrue(gaze.vertices[head + 1] > base[head + 1] + 5f)
        gaze.release()
        repeat(24) { gaze.step(1f / 24) }
        assertFalse(gaze.active)
    }

    @Test fun mirroredHeadTracksTheScreenAndEyePatchKeepsItsWidth() {
        val gaze = PointerGaze()
        val eyeStart = vertex(gaze, 512 - 144, 160)
        val eyeEnd = vertex(gaze, 512 - 128, 160)
        val rear = vertex(gaze, 512 - 352, 320)
        gaze.position(0f, 0f, 512f, mirrored = true)
        val base = gaze.vertices.copyOf()
        gaze.target(1f, .5f)
        repeat(18) { gaze.step(1f / 24) }
        gaze.position(0f, 0f, 512f, mirrored = true)
        assertTrue(gaze.vertices[eyeStart] > base[eyeStart] + 5f)
        assertEquals(16f, gaze.vertices[eyeEnd] - gaze.vertices[eyeStart], 1.1f)
        assertEquals(base[rear], gaze.vertices[rear], 0f)
        assertEquals(base[rear + 1], gaze.vertices[rear + 1], 0f)
    }

    private fun vertex(gaze: PointerGaze, pixelX: Int, pixelY: Int) =
        ((pixelY * gaze.divisions / 512) * (gaze.divisions + 1) + (pixelX * gaze.divisions / 512)) * 2
}
