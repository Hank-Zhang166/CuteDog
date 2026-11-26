package com.cutedog.app.pet.behavior

import com.cutedog.app.pet.animation.ClipDirector
import com.cutedog.app.pet.animation.ClipInterrupt
import com.cutedog.app.pet.animation.ClipManifest
import com.cutedog.app.pet.animation.FrameClip
import com.cutedog.app.pet.animation.ReleasedArtwork
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PetBrainTest {
    private val all = PetBrain.supportedTargets
    private val day = PetContext(12, false, "balanced", false, false, false, 300f)

    private fun ready(seed: Long = 17, current: String = "stand", context: PetContext = day) = PetBrain(seed).also {
        it.update(0f, current, all, context)
    }

    @Test fun liveFingertipAndRecordedFollowingUseSeparateSources() {
        assertEquals("stand", ready().interact("finger_follow"))
        assertEquals("follow", ready().interact("follow"))
    }

    @Test fun regionalTouchesUseOnlyTheRelevantRetainedReactions() {
        val head = ready()
        assertEquals("pet_head", head.interact("touch_head"))
        head.update(.4f, "pet_head", all, day)
        assertEquals("nuzzle", head.interact("touch_head"))
        val paw = ready()
        assertEquals("wave", paw.interact("touch_paw"))
        paw.update(.4f, "wave", all, day)
        assertEquals("lick_paw", paw.interact("touch_paw"))
        val tail = ready()
        assertEquals("tail_wag", tail.interact("touch_tail"))
        tail.update(1f, "tail_wag", all, day)
        assertEquals("tail_wag", tail.interact("touch_tail"))
    }

    @Test fun staleRequestsForRemovedActionsLeaveAnOngoingGestureAlone() {
        val brain = ready()
        assertEquals("wave", brain.interact("wave"))
        for (id in listOf("tongue", "sad")) {
            assertFalse(id in PetBrain.supportedTargets)
            assertNull(brain.interact(id))
            assertEquals("wave", brain.pendingTarget)
        }
    }

    @Test fun everyEmittedTargetIsAnAuthoredActionNotAPostureTransition() {
        val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
        val manifest = ClipManifest.parse(File(assets, ClipManifest.ASSET_PATH).readText(), ReleasedArtwork.REQUIRED_ACTIONS)
        // The optional rear-facing pose has no released drawing and must never enter the actual queue.
        assertEquals(setOf("back_stand"), all - manifest.requestableClipIds)
        val available = all intersect manifest.requestableClipIds
        available.forEach { id ->
            val clip = manifest.clips.getValue(id)
            assertEquals("$id must be an endpoint/action", clip.entryPose, clip.exitPose)
            assertNotNull("Missing route from stand to $id", manifest.route("stand", id))
        }
        val brain = PetBrain(17)
        brain.update(0f, "stand", manifest.requestableClipIds, day)
        assertNull(brain.interact("back_stand"))
    }

    @Test fun requestedGoalWaitsThroughTheEntireAuthoredRouteWithoutChurning() {
        val brain = ready(current = "trot")
        assertEquals("pet_head", brain.interact("head"))
        listOf("trot_to_walk", "walk", "walk_stop", "stand").forEach { clip ->
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

    @Test fun everyDesktopMenuGestureRequestsAReleasedAction() {
        val actions = com.cutedog.app.pet.PetInteractionMenu.actions
        assertEquals(actions.size, actions.map { it.intent }.toSet().size)
        assertEquals(actions.size, actions.map { it.label }.toSet().size)
        val available = ReleasedArtwork.REQUIRED_ACTIONS
        for (action in actions.filter { it.intent != com.cutedog.app.pet.PetInteractionMenu.CHAT }) {
            val brain = PetBrain(17)
            brain.update(0f, "stand", available, day)
            assertTrue("${action.label} must dispatch a playable action", brain.interact(action.intent) in available)
        }
    }

    @Test fun publishedDesktopGesturesAndAllowedModelIntentsHaveActualAvailableTargets() {
        val available = ReleasedArtwork.REQUIRED_ACTIONS
        val desktopAndGestures = mapOf("call" to "call", "pet" to "pet_head", "ball" to "play_ball",
            "treat" to "treat", "sleep" to "sleep", "jump" to "jump", "cuddle" to "cuddle", "follow" to "follow")
        val model = mapOf("happy" to "happy", "calm" to "soothe", "curious" to "curious",
            "cuddle" to "cuddle", "wave" to "wave", "excited" to "excited",
            "sleepy" to "sleepy", "shy" to "shy", "startled" to "startled")
        assertEquals(com.cutedog.app.chat.ChatPolicy.allowedIntents, model.keys)
        listOf(desktopAndGestures to PetBrain.InteractionSource.USER, model to PetBrain.InteractionSource.MODEL).forEach { (intents, source) ->
            intents.forEach { (intent, expected) ->
                val brain = PetBrain(17).also { it.update(0f, "stand", available, day) }
                val target = brain.interact(intent, source)
                assertEquals("$source / $intent must select the actual reviewed action", expected, target)
                assertTrue("$intent must not invent an unavailable action", target in available)
            }
        }
    }

    @Test fun restAndAffectionPreferSpecificActionsBeforeGentleAvailableResponses() {
        fun target(intent: String, available: Set<String>): String? = PetBrain(17).let {
            it.update(0f, "stand", available + "stand", day)
            it.interact(intent)
        }
        assertEquals("sleep", target("sleep", setOf("sleep", "curl_idle", "sit_idle", "relax")))
        assertEquals("curl_idle", target("sleep", setOf("curl_idle", "sit_idle", "relax")))
        assertEquals("sit_idle", target("sleep", setOf("sit_idle", "relax")))
        assertEquals("relax", target("sleep", setOf("relax")))
        assertEquals("cuddle", target("cuddle", setOf("cuddle", "nuzzle", "soothe", "relax")))
        assertEquals("nuzzle", target("cuddle", setOf("nuzzle", "soothe", "relax")))
        assertEquals("belly_up", target("cuddle", setOf("belly_up", "soothe", "relax")))
        assertEquals("soothe", target("cuddle", setOf("soothe", "relax")))
        assertEquals("relax", target("cuddle", setOf("relax")))
        assertNull(target("sleep", emptySet()))
        assertNull(target("cuddle", emptySet()))
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
        val energetic = setOf("walk", "trot", "hop", "jump", "excited", "play_ball")
        listOf(day.copy(powerSaving = true), day.copy(activity = "quiet"), day.copy(hour = 2)).forEach { context ->
            assertTrue(simulate(23, context, 3600).none { it.second in energetic })
        }
    }

    @Test fun livelyDayProducesMovementWhileChargingAvoidsHardExercise() {
        val lively = simulate(12, day.copy(activity = "lively", interactionAgeSeconds = 20f), 3600)
        assertTrue(lively.any { it.second in setOf("walk", "trot", "hop", "jump") })
        val charging = simulate(12, day.copy(activity = "lively", charging = true), 3600)
        assertTrue(charging.none { it.second in setOf("hop", "jump") })
    }

    @Test fun longSessionsHaveVarietyWithoutGuiltOrRepeatedRequestsForAttention() {
        val requests = simulate(902, day.copy(interactionAgeSeconds = 3600f), 7200)
        assertTrue("Expected varied context-selected actions", requests.map { it.second }.toSet().size >= 10)
        assertTrue(requests.none { it.second == "startled" })
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
            "wave" to "wave", "jump" to "jump", "treat" to "treat", "ball" to "play_ball", "follow" to "follow",
            "excited" to "excited", "sleepy" to "sleepy",
            "shy" to "shy", "startled" to "startled", "request_pet" to "request_pet", "seek" to "seek")
        expected.forEach { (kind, clip) -> assertEquals(clip, ready().interact(kind)) }
    }

    @Test fun newestUserGoalReplacesThePendingGoalWithoutWaitingForItsDwell() {
        val brain = ready()
        assertEquals("wave", brain.interact("wave"))
        assertNull(brain.update(.65f, "stand", all, day))
        assertEquals("happy", brain.interact("happy"))
        assertEquals("happy", brain.pendingTarget)
        assertNull(brain.update(1f, "stand", all, day))
        assertNull(brain.update(0f, "happy", all, day))
        assertNull(brain.pendingTarget)
        // No stale wave is replayed after the newest goal arrives.
        assertNull(brain.update(1f, "stand", all, day))
    }

    @Test fun distinctRapidInputsKeepTheLatestIntentAndIdenticalRepeatsCoalesce() {
        val brain = ready()
        assertEquals("wave", brain.interact("wave"))
        assertEquals("pet_head", brain.interact("head"))
        assertNull(brain.interact("head"))
        assertEquals("pet_head", brain.pendingTarget)
        assertEquals("wave", brain.interact("wave")) // Returning to an unplayed goal is not on cooldown.
        assertNull(brain.interact("wave"))
        assertEquals("wave", brain.pendingTarget)
    }

    @Test fun explicitInputDuringAnActionIgnoresAutonomousDwellAndStableClipGating() {
        val brain = ready()
        assertEquals("wave", brain.interact("wave"))
        assertNull(brain.update(0f, "wave", all, day))
        assertEquals("pet_head", brain.interact("head"))
        assertEquals("pet_head", brain.pendingTarget)
    }

    @Test fun replacingPendingInteractionPreservesEveryRequiredPlayerFrame() {
        val ids = listOf("stand", "wave", "happy")
        val manifest = ClipManifest(512, 512, "stand", ids.map { id ->
            FrameClip(id, (0 until if (id == "stand") 96 else 60).map { "pet3d/$id/$it.webp" }, 24,
                "stand_left", "stand_left", id == "stand", ClipInterrupt.BOUNDARY, ids,
                sampling = "interval_starts")
        }, emptySet())
        val director = ClipDirector(manifest)
        val brain = PetBrain(17)
        brain.update(0f, director.clipId, ids.toSet(), day)
        assertTrue(director.request(brain.interact("wave")!!))
        repeat(16) { director.commit(director.peekNext()) }
        brain.update(16f / 24, director.clipId, ids.toSet(), day)
        assertTrue(director.request(brain.interact("happy")!!))
        assertEquals(16, director.frameIndex) // Replanning must not move the real pose.
        for (index in 17 until 96) {
            assertEquals("stand", director.peekNext().clipId)
            assertEquals(index, director.peekNext().index)
            director.commit(director.peekNext())
        }
        assertEquals("happy", director.peekNext().clipId)
        director.commit(director.peekNext())
        assertEquals(0, director.frameIndex)
        assertEquals("happy", director.clipId)
    }

    @Test fun reminderUpgradesAnExistingWaveAndDefersOnlyTheLatestUserUntilItFinishes() {
        val brain = ready()
        assertEquals("wave", brain.interact("wave"))
        assertNull(brain.interact("wave", PetBrain.InteractionSource.REMINDER))
        assertNull(brain.interact("happy"))
        assertNull(brain.interact("curious", PetBrain.InteractionSource.MODEL))
        assertEquals("wave", brain.pendingTarget)
        assertNull(brain.update(0f, "wave", all, day))
        assertNull(brain.interact("head")) // Replaces the deferred happy request.
        assertNull(brain.update(1f, "wave", all, day))
        // The actual reminder clip left; do not wait for its remaining autonomous dwell.
        assertEquals("pet_head", brain.update(0f, "stand", all, day))
        assertEquals("pet_head", brain.pendingTarget)
    }

    @Test fun reminderPreemptsOrdinaryInputEvenInsideTheTouchCoalescingWindow() {
        val brain = ready()
        assertEquals("pet_head", brain.interact("head"))
        assertEquals("wave", brain.interact("wave", PetBrain.InteractionSource.REMINDER))
        assertNull(brain.interact("happy", PetBrain.InteractionSource.MODEL))
        assertEquals("wave", brain.pendingTarget)
    }

    @Test fun reminderAndLatestUserSurviveRequestsBeforeAssetsAreKnown() {
        val brain = PetBrain(17)
        assertNull(brain.interact("wave", PetBrain.InteractionSource.REMINDER))
        assertNull(brain.interact("happy"))
        assertNull(brain.interact("head"))
        assertEquals("wave", brain.update(0f, "stand", all, day))
        assertNull(brain.update(0f, "wave", all, day))
        assertEquals("pet_head", brain.update(0f, "stand", all, day))
    }

    @Test fun rejectedReminderReleasesDeferredUserWithoutAutonomousBackoff() {
        val brain = ready()
        assertEquals("wave", brain.interact("wave", PetBrain.InteractionSource.REMINDER))
        assertNull(brain.interact("head"))
        brain.requestRejected("wave")
        assertEquals("pet_head", brain.update(0f, "stand", all, day))
    }

    @Test fun modelGoalCannotOverwritePendingUserAndUnavailableInputKeepsAValidRoute() {
        val brain = PetBrain(17)
        brain.update(0f, "stand", setOf("stand", "wave", "happy"), day)
        assertEquals("wave", brain.interact("wave"))
        assertNull(brain.interact("happy", PetBrain.InteractionSource.MODEL))
        assertNull(brain.interact("treat"))
        assertEquals("wave", brain.pendingTarget)
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
