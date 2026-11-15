package com.cutedog.app.pet.animation

import org.junit.Assert.*
import org.junit.Test

/** Synthetic metadata only. No fixture art is shipped as a product animation. */
class ClipDirectorTest {
    private fun clip(id: String, entry: String, exit: String, loop: Boolean, vararg next: String) = FrameClip(id,
        (0..3).map { "pet3d/$id/00$it.png" }, 24, entry, exit, loop,
        if (loop) ClipInterrupt.BOUNDARY else ClipInterrupt.NEVER, next.toList())
    private fun fixture(): List<FrameClip> = listOf(
        clip("stand", "S", "S", true, "walk_start", "sit", "jump"),
        clip("walk_start", "S", "W", false, "walk"), clip("walk", "W", "W", true, "run_start", "walk_stop"),
        clip("run_start", "W", "R", false, "run"), clip("run", "R", "R", true, "run_stop"),
        clip("run_stop", "R", "W", false, "walk"), clip("walk_stop", "W", "S", false, "stand"),
        clip("sit", "S", "I", false, "sit_idle"), clip("sit_idle", "I", "I", true, "lie", "stand_up"),
        clip("stand_up", "I", "S", false, "stand"), clip("lie", "I", "L", false, "lie_idle"),
        clip("lie_idle", "L", "L", true, "fall_asleep", "lie_to_stand"), clip("lie_to_stand", "L", "S", false, "stand"),
        clip("fall_asleep", "L", "Z", false, "sleep"), clip("sleep", "Z", "Z", true, "wake"),
        clip("wake", "Z", "L", false, "lie_idle"), clip("jump", "S", "S", false, "stand"),
    )
    private fun manifest(clips: List<FrameClip> = fixture()) = ClipManifest(384, 384, "stand", clips, emptySet())
    private fun advanceTo(director: ClipDirector, id: String): List<String> {
        val visited = mutableListOf(director.clipId)
        repeat(200) {
            if (director.clipId == id) return visited
            director.commit(director.peekNext())
            if (visited.last() != director.clipId) visited += director.clipId
        }
        error("Fixture route failed to reach $id")
    }

    @Test fun runningToSleepUsesEveryAuthoredDecelerationAndPostureTransition() {
        val director = ClipDirector(manifest())
        assertTrue(director.request("run")); advanceTo(director, "run")
        assertTrue(director.request("sleep"))
        assertEquals(listOf("run", "run_stop", "walk", "walk_stop", "stand", "sit", "sit_idle", "lie", "lie_idle", "fall_asleep", "sleep"), advanceTo(director, "sleep"))
    }

    @Test fun uninterruptibleJumpFinishesEveryFrameBeforeChangingIntent() {
        val director = ClipDirector(manifest())
        director.request("jump"); advanceTo(director, "jump")
        director.commit(director.peekNext())
        director.request("sleep")
        assertEquals("jump", director.peekNext().clipId)
        assertEquals(2, director.peekNext().index)
        director.commit(director.peekNext())
        assertEquals(3, director.peekNext().index)
        director.commit(director.peekNext())
        assertEquals("stand", director.peekNext().clipId)
    }

    @Test fun missingDecodedFrameDoesNotAdvanceDirectorAndIllegalSkippingFails() {
        val director = ClipDirector(manifest())
        val current = director.current
        repeat(100) { director.prefetchPaths(); director.peekNext() }
        assertEquals(current, director.current)
        assertThrows(IllegalArgumentException::class.java) { director.commit(ClipFrame("stand", 3, "pet3d/stand/003.png")) }
        assertEquals(current, director.current)
    }

    @Test fun newIntentReplansFromActualPoseAndUnknownActionDoesNotMutateQueue() {
        val director = ClipDirector(manifest())
        director.request("sleep"); advanceTo(director, "lie")
        director.request("run")
        val queue = director.queuedClips
        assertEquals("lie_idle", queue.first())
        assertFalse(director.request("nonexistent"))
        assertEquals(queue, director.queuedClips)
        assertEquals(listOf("lie", "lie_idle", "lie_to_stand", "stand", "walk_start", "walk", "run_start", "run"), advanceTo(director, "run"))
    }

    @Test fun wakingUsesRealWakeAndReturnClipAndOneShotReturnsHome() {
        val director = ClipDirector(manifest())
        director.request("sleep"); advanceTo(director, "sleep")
        director.request("stand")
        assertEquals(listOf("sleep", "wake", "lie_idle", "lie_to_stand", "stand"), advanceTo(director, "stand"))
        director.request("jump"); advanceTo(director, "jump")
        assertEquals(listOf("jump", "stand"), advanceTo(director, "stand"))
    }

    @Test fun manifestRejectsMissingCoreActionsAliasesBadFpsAndDiscontinuousGraph() {
        assertThrows(IllegalArgumentException::class.java) { ClipManifest(384, 384, "stand", fixture()) }
        assertThrows(IllegalArgumentException::class.java) { manifest(fixture().map { if (it.id == "walk") it.copy(fps = 13) else it }) }
        assertThrows(IllegalArgumentException::class.java) { manifest(fixture().map { if (it.id == "stand") it.copy(frames = listOf("pet3d/stand/000.png", "pet3d/stand/000.png")) else it }) }
        assertThrows(IllegalArgumentException::class.java) { manifest(fixture().map { if (it.id == "run") it.copy(transitions = listOf("sleep")) else it }) }
        assertThrows(IllegalArgumentException::class.java) { manifest(fixture().filter { it.id != "wake" }) }
    }

    @Test fun manifestRejectsSingleImageFakeClipUnsafeInterruptAndPathTraversal() {
        assertThrows(IllegalArgumentException::class.java) { manifest(fixture().map { if (it.id == "jump") it.copy(frames = it.frames.take(1)) else it }) }
        assertThrows(IllegalArgumentException::class.java) { manifest(fixture().map { if (it.id == "jump") it.copy(interrupt = ClipInterrupt.IMMEDIATE) else it }) }
        assertThrows(IllegalArgumentException::class.java) { manifest(fixture().map { if (it.id == "stand") it.copy(frames = listOf("../secret", "pet3d/stand/001.png")) else it }) }
    }
}
