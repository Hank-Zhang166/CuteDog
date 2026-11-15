package com.cutedog.app.pet

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random
import kotlin.math.abs

class PetBehaviorTest {
    @Test fun sleepTransitionsThroughSittingAndLying() {
        val b = PetBehavior(Random(3)); b.react("sleep")
        assertEquals(PetBehavior.State.SIT, b.state)
        val visited = mutableSetOf(b.state)
        repeat(300) { b.update(.05f); visited += b.state }
        assertTrue(visited.containsAll(listOf(PetBehavior.State.SIT, PetBehavior.State.LIE, PetBehavior.State.SLEEP)))
    }
    @Test fun wakeDoesNotInstantlyJumpToInteraction() {
        val b = PetBehavior(Random(4)); b.react("sleep")
        repeat(240) { b.update(.05f) }
        assertEquals(PetBehavior.State.SLEEP, b.state)
        b.react("pet"); assertEquals(PetBehavior.State.WAKE, b.state)
        repeat(70) { b.update(.05f) }
        assertEquals(PetBehavior.State.HAPPY, b.state)
    }
    @Test fun jumpHasAirbornePhaseAndLanding() {
        val b = PetBehavior(Random(4)); b.react("jump")
        var maxHeight = 0f; var sawLand = false
        repeat(100) { b.update(.02f); maxHeight = maxOf(maxHeight, b.jump); sawLand = sawLand || b.state == PetBehavior.State.LAND }
        assertTrue(maxHeight > .1f); assertTrue(sawLand); assertEquals(0f, b.jump, .001f)
    }
    @Test fun interactionDeceleratesAndMovementStaysInsideBounds() {
        val b = PetBehavior(Random(6)); b.active = true
        repeat(800) { b.update(.05f) }
        var previous = b.velocity
        repeat(5000) {
            if (it % 713 == 0) b.react("pet")
            b.update(.05f)
            assertTrue(b.position in 0f..1f)
            assertTrue(abs(b.velocity - previous) <= .0061f)
            previous = b.velocity
        }
    }
    @Test fun untrustedIntentsDoNotChangeState() {
        val b = PetBehavior(); b.react("exec:erase"); assertEquals(PetBehavior.State.IDLE, b.state)
    }
    @Test fun suspendedFrameCannotTeleportPet() {
        val b = PetBehavior(Random(7)); b.active = true
        repeat(700) { b.update(.05f) }
        val old = b.position; b.update(3600f)
        assertTrue(abs(b.position - old) <= .0061f)
    }
}
