package com.cutedog.app.pet.behavior

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PetBrainTest {
    private val all = PetBrain.supportedTargets
    private val day = PetContext(12, false, "balanced", false, false, false, 300f)

    private fun ready(seed: Long = 17, current: String = "stand", context: PetContext = day) = PetBrain(seed).also {
        it.update(0f, current, all, context)
    }

    @Test fun everyEmittedTargetIsAnAuthoredActionNotAPostureTransition() {
        val file = listOf(File("../art/character/motions.json"), File("art/character/motions.json")).firstOrNull { it.isFile }
            ?: error("Authored motion catalog is required for this contract test")
        val clips = JSONObject(file.readText()).getJSONArray("clips")
        val metadata = (0 until clips.length()).map { clips.getJSONObject(it) }.associateBy { it.getString("id") }
        assertTrue(metadata.keys.containsAll(all))
        all.forEach { id ->
            val clip = metadata.getValue(id)
            assertEquals("$id must be an endpoint/action, not a standalone transition", clip.getString("entryPose"), clip.getString("exitPose"))
        }
    }

    @Test fun requestedGoalWaitsThroughTheEntireAuthoredRouteWithoutChurning() {
        val brain = ready(current = "run")
        assertEquals("pet_head", brain.interact("head"))
        listOf("run_to_trot", "trot", "trot_to_walk", "walk", "walk_stop", "stand").forEach { clip ->
            assertNull(brain.update(3f, clip, all, day))
            assertEquals("pet_head", brain.pendingTarget)
        }
        assertNull(brain.update(0f, "pet_head", all, day))
        assertNull(brain.pendingTarget)
        assertNull(brain.update(1f, "pet_head", all, day))
    }

    @Test fun slowTransitionIsNeverOverwrittenByAnAutonomousWatchdog() {
        val brain = ready()
        assertEquals("cuddle", brain.interact("body"))
        assertNull(brain.update(200f, "sit", all, day))
        assertNull(brain.update(200f, "sit", all, day))
        assertEquals("cuddle", brain.pendingTarget)
    }

    @Test fun rejectedRequestBacksOffInsteadOfRetryingEveryFrame() {
        val brain = ready()
        val target = brain.interact("head")!!
        brain.requestRejected(target)
        assertNull(brain.pendingTarget)
        repeat(100) { assertNull(brain.update(.1f, "stand", all, day)) }
    }

    @Test fun quietNightRequestsSleepGoalThenStaysAsleepUntilAnExplicitInteraction() {
        val night = day.copy(hour = 23, quiet = true, interactionAgeSeconds = 600f)
        val brain = ready(context = night)
        assertEquals("sleep", brain.update(6f, "stand", all, night))
        listOf("sit", "sit_idle", "lie", "lie_idle", "fall_asleep").forEach { assertNull(brain.update(3f, it, all, night)) }
        assertNull(brain.update(0f, "sleep", all, night))
        repeat(300) { assertNull(brain.update(1f, "sleep", all, night)) }
        assertEquals("call", brain.interact("call"))
    }

    @Test fun morningRequestsStretchAndLetsThePlayerSupplyWakeTransitions() {
        val brain = ready(current = "sleep", context = day.copy(hour = 8))
        assertEquals("stretch", brain.update(61f, "sleep", all, day.copy(hour = 8)))
        assertEquals("stretch", brain.pendingTarget)
        listOf("wake", "lie_idle", "lie_to_stand", "stand").forEach {
            assertNull(brain.update(2f, it, all, day.copy(hour = 8)))
        }
    }

    @Test fun explicitRestRequestsRealSleepWhileCalmStillMeansComfort() {
        val brain = ready(current = "walk")
        assertEquals("sleep", brain.interact("sleep"))
        listOf("walk_stop", "stand", "sit", "sit_idle", "lie", "lie_idle", "fall_asleep").forEach {
            assertNull(brain.update(2f, it, all, day))
            assertEquals("sleep", brain.pendingTarget)
        }
        assertNull(brain.update(0f, "sleep", all, day))
        assertNull(brain.pendingTarget)
        assertEquals("soothe", ready().interact("calm"))
    }

    @Test fun elapsedTimeIsIndependentOfCallbackRate() {
        val slow = ready(); val fast = ready()
        repeat(60) { slow.update(1f, "stand", emptySet(), day) }
        repeat(600) { fast.update(.1f, "stand", emptySet(), day) }
        assertEquals(60.0, slow.elapsedSeconds, .0001)
        assertEquals(slow.elapsedSeconds, fast.elapsedSeconds, .0001)
    }

    @Test fun invalidTimingCannotPoisonDecisionState() {
        val brain = ready()
        assertNull(brain.update(Float.NaN, "stand", all, day))
        assertNull(brain.update(Float.POSITIVE_INFINITY, "stand", all, day))
        assertNull(brain.update(-1f, "stand", all, day))
        assertEquals(0.0, brain.elapsedSeconds, 0.0)
        assertEquals("happy", brain.interact("happy"))
    }

    @Test fun missingAssetsNeverYieldInventedOrUnrelatedInteractionClips() {
        val brain = PetBrain(5)
        assertNull(brain.interact("treat"))
        assertNull(brain.update(0f, "stand", setOf("stand"), day))
        assertNull(brain.update(1f, "stand", setOf("stand"), day))
        assertNull(brain.interact("treat"))
        assertNull(brain.interact("execute_anything"))
    }

    @Test fun availableSubsetIsRespectedThroughoutALongSession() {
        val subset = setOf("stand", "sit_idle", "lie_idle", "breathe")
        val requests = simulate(40, day, 3600, subset)
        assertTrue(requests.isNotEmpty())
        assertTrue(requests.all { it.second in subset })
    }

    @Test fun powerSavingQuietAndNightExcludeAutonomousRunningAndJumping() {
        val energetic = setOf("walk", "trot", "run", "hop", "jump", "excited", "play_ball")
        listOf(day.copy(powerSaving = true), day.copy(activity = "quiet"), day.copy(hour = 2)).forEach { context ->
            assertTrue(simulate(23, context, 3600).none { it.second in energetic })
        }
    }

    @Test fun livelyDayProducesMovementWhileChargingAvoidsHardExercise() {
        val lively = simulate(12, day.copy(activity = "lively", interactionAgeSeconds = 20f), 3600)
        assertTrue(lively.any { it.second in setOf("walk", "trot", "run", "hop", "jump") })
        val charging = simulate(12, day.copy(activity = "lively", charging = true), 3600)
        assertTrue(charging.none { it.second in setOf("run", "hop", "jump") })
    }

    @Test fun longSessionsHaveVarietyWithoutGuiltOrRepeatedRequestsForAttention() {
        val requests = simulate(902, day.copy(interactionAgeSeconds = 3600f), 7200)
        assertTrue("Expected varied context-selected actions", requests.map { it.second }.toSet().size >= 10)
        assertTrue(requests.none { it.second in setOf("sad", "startled") })
        val asks = requests.filter { it.second == "request_pet" }.map { it.first }
        asks.zipWithNext().forEach { (a, b) -> assertTrue("Pet requests must respect ten-minute cooldown", b - a >= 600) }
        val grooming = requests.filter { it.second == "scratch" }.map { it.first }
        grooming.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= 180) }
    }

    @Test fun seedAndIdenticalContextProduceReproducibleDecisions() {
        assertEquals(simulate(72, day, 1200), simulate(72, day, 1200))
    }

    @Test fun allNamedInteractionsMapToSpecificExistingActions() {
        val expected = mapOf("head" to "pet_head", "body" to "cuddle", "nose" to "curious", "call" to "call",
            "happy" to "happy", "calm" to "soothe", "curious" to "curious", "cuddle" to "cuddle",
            "wave" to "wave", "jump" to "jump", "treat" to "treat", "ball" to "play_ball", "follow" to "follow")
        expected.forEach { (kind, clip) -> assertEquals(clip, ready().interact(kind)) }
    }

    @Test fun rapidInteractionCoalescesWhileTheFirstActionIsRouting() {
        val brain = ready()
        assertEquals("happy", brain.interact("happy"))
        assertNull(brain.interact("head"))
        brain.update(1f, "stand", all, day)
        assertNull(brain.interact("wave"))
        assertEquals("happy", brain.pendingTarget)
        assertNull(brain.update(1f, "happy", all, day))
        assertEquals("wave", brain.update(4f, "stand", all, day))
    }

    @Test fun attentionIsBoundedTemporaryAndIgnoresInvalidCoordinates() {
        val brain = ready()
        brain.setAttentionTarget(-2f, 3f)
        assertEquals(AttentionTarget(0f, 1f), brain.attentionTarget)
        brain.setAttentionTarget(Float.NaN, 0f)
        assertEquals(AttentionTarget(0f, 1f), brain.attentionTarget)
        brain.update(13f, "stand", emptySet(), day)
        assertNull(brain.attentionTarget)
    }

    /** Pure selector harness acknowledges goals; authored frame/route continuity has separate player tests. */
    private fun simulate(seed: Long, context: PetContext, seconds: Int, available: Set<String> = all): List<Pair<Int, String>> {
        val brain = PetBrain(seed)
        var current = "stand"
        var arrived = false
        val results = mutableListOf<Pair<Int, String>>()
        brain.update(0f, current, available, context)
        repeat(seconds) { second ->
            val requested = brain.update(1f, current, available, context)
            if (requested != null) {
                results += (second + 1) to requested
                current = requested; arrived = true
            } else if (arrived) {
                arrived = false
                // A one-shot eventually returns via the player graph; the selector only sees its endpoint here.
                if (current !in setOf("sleep", "curl_idle")) current = "stand"
            }
        }
        return results
    }
}
