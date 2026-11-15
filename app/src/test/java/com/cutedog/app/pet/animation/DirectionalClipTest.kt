package com.cutedog.app.pet.animation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Pure metadata/control fixtures. These are not measured gait data or shipped character art. */
class DirectionalClipTest {
    private fun clip(id: String, entry: String, exit: String, loop: Boolean, vararg next: String) = FrameClip(
        id, (0..3).map { "pet3d/$id/00$it.png" }, 24, entry, exit, loop,
        if (loop) ClipInterrupt.BOUNDARY else ClipInterrupt.NEVER, next.toList(), heading = ClipHeading.LEFT,
        projectedTravel = List(4) { 0.0 }, travelUnits = "canvas_width_fraction",
    )

    private fun fixture(): List<FrameClip> {
        val left = listOf(
            clip("stand", "S", "S", true, "walk_start", "turn_to_right", "pet_head"),
            clip("walk_start", "S", "W", false, "walk"),
            clip("walk", "W", "W", true, "walk_stop", "walk_to_trot"),
            clip("walk_stop", "W", "S", false, "stand"),
            clip("walk_to_trot", "W", "T", false, "trot"),
            clip("trot", "T", "T", true, "trot_to_walk", "trot_to_run"),
            clip("trot_to_walk", "T", "W", false, "walk"),
            clip("trot_to_run", "T", "R", false, "run"),
            clip("run", "R", "R", true, "run_to_trot"),
            clip("run_to_trot", "R", "T", false, "trot"),
        ).map {
            if (it.id in setOf("walk", "trot", "run")) it.copy(
                projectedTravel = listOf(.01, .02, .03, 0.0), travelUnits = "canvas_width_fraction", travelEnabled = true,
            ) else if (it.id != "stand") it.copy(projectedTravel = listOf(.002, .003, .001, 0.0), travelEnabled = true) else it
        }
        val right = left.map { base ->
            val id = base.id + "_right"
            base.copy(id = id, frames = base.frames.indices.map { "pet3d/$id/$it.png" },
                entryPose = base.entryPose + "R", exitPose = base.exitPose + "R", variantOf = base.id,
                heading = ClipHeading.RIGHT, exitHeading = ClipHeading.RIGHT,
                transitions = if (base.id == "stand") listOf("walk_start_right", "turn_to_left") else base.transitions.map { it + "_right" })
        }.map { if (it.id == "stand_right") it.copy(frames = it.frames.take(2), projectedTravel = it.projectedTravel.take(2)) else it }
        return left + right + listOf(
            clip("turn_to_right", "S", "SR", false, "stand_right").copy(exitHeading = ClipHeading.RIGHT),
            clip("turn_to_left", "SR", "S", false, "stand").copy(heading = ClipHeading.RIGHT),
            clip("pet_head", "S", "S", false, "stand"),
        )
    }

    private fun manifest(clips: List<FrameClip> = fixture()) = ClipManifest(512, 512, "stand", clips, emptySet())

    @Test fun entryReservationRejectsAnUnstoppableStartupBeforeLeavingStanding() {
        val d = ClipDirector(manifest()); d.request("walk")
        repeat(3) { d.commit(d.peekNext()) }
        val before = d.current
        val reserve = d.travelReservationFor(d.peekNext())!!
        assertEquals(-1, reserve.heading); assertEquals(.072, reserve.fraction, .000001)
        assertNull(d.commitIfTravelAllowed(d.peekNext(), { true }, { _, _ -> false }))
        assertEquals(before, d.current); assertEquals("stand", d.requestedClip)
        assertEquals("walk", d.rejectedTravelTarget)
        assertEquals("stand", d.peekNext().clipId)
        assertEquals(0.0, d.commitIfTravelAllowed(d.peekNext(), { true }, { _, _ -> false })!!, 0.0)
        assertNull(d.rejectedTravelTarget)
    }

    @Test fun stationaryStartupBridgeIsCheckedBeforeItsNonreturnablePoseChange() {
        val m = manifest(fixture().map {
            if (it.id == "walk_start") it.copy(projectedTravel = List(4) { 0.0 }, travelEnabled = false) else it
        })
        val d = ClipDirector(m); d.request("walk")
        repeat(3) { d.commit(d.peekNext()) }
        assertEquals("walk_start", d.peekNext().clipId)
        assertEquals(.066, d.travelReservationFor(d.peekNext())!!.fraction, .000001)
        assertNull(d.commitIfTravelAllowed(d.peekNext(), { true }, { _, _ -> false }))
        assertEquals("stand", d.clipId); assertEquals("stand", d.requestedClip)
    }

    @Test fun rejectedRightVariantReportsTheCanonicalBrainTarget() {
        val d = ClipDirector(manifest()); d.request("walk_right")
        repeat(3) { d.commit(d.peekNext()) }
        assertEquals("turn_to_right", d.peekNext().clipId)
        assertEquals(1, d.travelReservationFor(d.peekNext())!!.heading)
        assertNull(d.commitIfTravelAllowed(d.peekNext(), { true }, { _, _ -> false }))
        assertEquals("walk", d.rejectedTravelTarget); assertEquals("stand", d.clipId)
    }

    @Test fun aMovingStandLoopCannotServeAsTheStoppingAnchor() {
        assertThrows(IllegalArgumentException::class.java) {
            manifest(fixture().map { if (it.id == "stand") it.copy(
                projectedTravel = listOf(.001, .001, .001, 0.0), travelEnabled = true) else it })
        }
    }

    @Test fun theNextWholeLoopRequiresSpaceAndCanFallBackToItsShorterStop() {
        val d = ClipDirector(manifest()); d.request("walk"); advanceTo(d, "walk")
        repeat(3) { d.commit(d.peekNext()) }
        assertEquals(0, d.peekNext().index)
        assertEquals(.066, d.travelReservationFor(d.peekNext())!!.fraction, .000001)
        assertNull(d.commitIfTravelAllowed(d.peekNext(), { true }, { _, distance -> distance <= .01 }))
        assertEquals(3, d.frameIndex); assertEquals("walk_stop", d.peekNext().clipId)
        var distance = 0.0
        repeat(5) { distance += d.commitIfTravelAllowed(d.peekNext(), { true }, { _, reserve -> reserve <= .01 })!! }
        assertEquals("stand", d.clipId); assertEquals(-.006, distance, .000001)
    }

    @Test fun legacyHeadinglessPoseCanReturnWithoutReattemptingTheRejectedMovement() {
        val clips = fixture().map { if (it.id == "stand") it.copy(transitions = it.transitions + "turn") else it } + listOf(
            clip("turn", "S", "B", false, "back_stand"),
            clip("back_stand", "B", "B", true, "turn_back"),
            clip("turn_back", "B", "S", false, "stand"),
        ).map { it.copy(heading = null, exitHeading = null) }
        val d = ClipDirector(manifest(clips)); d.request("back_stand"); advanceTo(d, "back_stand")
        d.request("run"); repeat(3) { d.commit(d.peekNext()) }
        assertNull(d.commitIfTravelAllowed(d.peekNext(), { true }, { _, _ -> false }))
        assertEquals("run", d.rejectedTravelTarget); assertEquals("stand", d.requestedClip)
        assertEquals(listOf("turn_back", "stand"), d.queuedClips)
        repeat(5) { assertNotNull(d.commitIfTravelAllowed(d.peekNext(), { true }, { _, _ -> false })) }
        assertEquals("stand", d.clipId)
    }

    private fun advanceTo(director: ClipDirector, target: String, whileRouting: (() -> Unit)? = null): List<String> {
        val seen = mutableListOf(director.clipId)
        repeat(300) {
            if (director.clipId == target) return seen
            whileRouting?.invoke()
            director.commit(director.peekNext())
            if (seen.last() != director.clipId) seen += director.clipId
        }
        error("Fixture could not reach $target: ${director.queuedClips}")
    }

    @Test fun reversalKeepsMeasuredOldStepsAndChangesHeadingOnlyAfterTheTurn() {
        val d = ClipDirector(manifest())
        assertTrue(d.request("run")); advanceTo(d, "run"); assertTrue(d.canTravel)
        assertTrue(d.requestHeading(1)); assertTrue(d.canTravel); assertEquals(-1, d.travelHeading)
        assertEquals(listOf("run", "run_to_trot", "trot", "trot_to_walk", "walk", "walk_stop", "stand",
            "turn_to_right", "stand_right", "walk_start_right", "walk_right", "walk_to_trot_right", "trot_right", "trot_to_run_right", "run_right"),
            advanceTo(d, "run_right") {
                val clip = d.manifest.clips.getValue(d.clipId)
                assertEquals(clip.travelEnabled, d.canTravel)
                if (d.canTravel) assertEquals(clip.heading!!.sign, d.travelHeading)
                else assertEquals(0.0, d.projectedTravelFor(d.peekNext()), 0.0)
            })
        assertEquals("run", d.canonicalClipId)
        assertEquals(1, d.travelHeading)
        assertTrue(d.canTravel)
    }

    @Test fun bridgeDestinationHeadingDoesNotBecomeTravelDirectionPartwayThroughTurn() {
        val d = ClipDirector(manifest()); d.requestHeading(1); advanceTo(d, "turn_to_right")
        repeat(3) {
            assertEquals(0, d.travelHeading); assertFalse(d.canTravel)
            assertEquals(0.0, d.projectedTravelFor(d.peekNext()), 0.0)
            d.commit(d.peekNext())
        }
        assertEquals("turn_to_right", d.clipId)
        assertEquals(0, d.travelHeading)
        d.commit(d.peekNext()); assertEquals("stand_right", d.clipId); assertFalse(d.canTravel)
    }

    @Test fun interactionOverridesPendingDirectionUsingTheCompleteReturnRoute() {
        val d = ClipDirector(manifest()); d.requestHeading(1); d.request("walk"); advanceTo(d, "turn_to_right")
        d.commit(d.peekNext()); assertTrue(d.request("pet_head"))
        assertEquals(listOf("turn_to_right", "stand_right", "turn_to_left", "stand", "pet_head"), advanceTo(d, "pet_head"))
        assertFalse(d.canTravel)
        // A repeating boundary hint must not cancel the current interaction.
        assertTrue(d.requestHeading(1)); assertEquals("pet_head", d.requestedClip)
        assertTrue(d.request("walk")); advanceTo(d, "walk_right"); assertEquals("walk", d.canonicalClipId)
    }

    @Test fun directionChangesDuringTurnCannotRewindOrHardCutItsIntermediateFrames() {
        val d = ClipDirector(manifest()); d.requestHeading(1); d.request("run"); advanceTo(d, "turn_to_right")
        d.commit(d.peekNext()); assertTrue(d.requestHeading(-1))
        assertEquals("turn_to_right", d.peekNext().clipId); assertEquals(2, d.peekNext().index)
        assertEquals(listOf("turn_to_right", "stand_right", "turn_to_left", "stand", "walk_start", "walk", "walk_to_trot", "trot", "trot_to_run", "run"), advanceTo(d, "run"))
        assertEquals(-1, d.travelHeading); assertTrue(d.canTravel)
    }

    @Test fun exactVariantsExposeCanonicalIdentityAndTravelUsesTheAuthoredFrameIndex() {
        val d = ClipDirector(manifest()); assertTrue(d.request("walk_right")); advanceTo(d, "walk_right")
        assertEquals("walk", d.canonicalClipId)
        for (fraction in listOf(.01, .02, .03, 0.0)) {
            val next = d.peekNext()
            repeat(3) { assertEquals(fraction, d.projectedTravelFor(next), 0.0) } // Reading/prefetching cannot advance state.
            d.commit(next)
        }
        assertEquals(0, d.frameIndex)
        d.requestHeading(-1); advanceTo(d, "walk")
        assertEquals(-.01, d.projectedTravelFor(d.peekNext()), 0.0)
        assertThrows(IllegalArgumentException::class.java) { d.projectedTravelFor(ClipFrame("walk", 3, "pet3d/walk/003.png")) }
    }

    @Test fun missingTravelMeasurementsAndNonmovingActionsNeverInventSpeed() {
        val m = manifest(fixture().map { it.copy(projectedTravel = emptyList(), travelUnits = null, travelEnabled = false) })
        val d = ClipDirector(m); d.requestHeading(1); d.request("run"); advanceTo(d, "run_right")
        assertFalse(d.canTravel); assertEquals(0, d.travelHeading); assertEquals(0.0, d.projectedTravelFor(d.peekNext()), 0.0)
        d.request("pet_head"); advanceTo(d, "pet_head"); assertFalse(d.canTravel)
    }

    @Test fun measuredButNotApprovedTravelRemainsDisabled() {
        val d = ClipDirector(manifest(fixture().map { it.copy(travelEnabled = false) }))
        d.request("walk"); advanceTo(d, "walk")
        assertFalse(d.canTravel); assertEquals(0.0, d.projectedTravelFor(d.peekNext()), 0.0)
        assertThrows(IllegalArgumentException::class.java) { manifest(fixture().map {
            if (it.id == "walk") it.copy(projectedTravel = emptyList(), travelUnits = null) else it }) }
    }

    @Test fun legacyMetadataStillPlaysButCannotClaimDirectionalTravel() {
        val plain = clip("stand", "S", "S", true, "stand").copy(heading = null, exitHeading = null, projectedTravel = emptyList(), travelUnits = null)
        val d = ClipDirector(ClipManifest(384, 384, "stand", listOf(plain), emptySet()))
        assertTrue(d.request("stand")); assertFalse(d.requestHeading(1)); assertFalse(d.requestHeading(0))
        assertEquals("stand", d.canonicalClipId); assertFalse(d.canTravel)
        d.commit(d.peekNext()); assertEquals(1, d.frameIndex)
    }

    @Test fun invalidVariantMetadataCannotAliasAnotherMotionOrDuplicateItsHeading() {
        for (mutate in listOf<(FrameClip) -> FrameClip>(
            { it.copy(variantOf = "missing") }, { it.copy(variantOf = "walk_right") },
            { it.copy(variantOf = "trot_right") }, { it.copy(heading = ClipHeading.LEFT, exitHeading = ClipHeading.LEFT) },
        )) assertThrows(IllegalArgumentException::class.java) {
            manifest(fixture().map { if (it.id == "walk_right") mutate(it) else it })
        }
    }

    @Test fun inconsistentHeadingBoundariesAndEarlyTurnExitsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { manifest(fixture().map {
            if (it.id == "turn_to_right") it.copy(exitHeading = ClipHeading.LEFT) else it }) }
        assertThrows(IllegalArgumentException::class.java) { manifest(fixture().map {
            if (it.id == "turn_to_right") it.copy(interrupt = ClipInterrupt.IMMEDIATE, safeExitFrames = setOf(1)) else it }) }
    }

    @Test fun wrongUnitsMissingFramesAndInvalidTravelNumbersAreRejected() {
        val mutations = listOf<(FrameClip) -> FrameClip>(
            { it.copy(travelUnits = "pixels") }, { it.copy(travelUnits = null) },
            { it.copy(projectedTravel = listOf(.01)) }, { it.copy(projectedTravel = listOf(Double.NaN, 0.0, 0.0, 0.0)) },
            { it.copy(projectedTravel = listOf(-.01, 0.0, 0.0, 0.0)) }, { it.copy(projectedTravel = listOf(2.0, 0.0, 0.0, 0.0)) },
        )
        for (mutate in mutations) assertThrows(IllegalArgumentException::class.java) {
            manifest(fixture().map { if (it.id == "walk") mutate(it) else it })
        }
        assertThrows(IllegalArgumentException::class.java) { manifest(fixture().map {
            if (it.id == "turn_to_right") it.copy(projectedTravel = listOf(.01, .01, .01, 0.0), travelUnits = "canvas_width_fraction") else it }) }
    }

    @Test fun parserRequiresExplicitTravelUnitsAndRejectsStringDisplacements() {
        val root = JSONObject().put("version", 1).put("width", 512).put("height", 512).put("initialClip", "stand")
        val clips = JSONArray()
        fixture().forEach { c ->
            val value = JSONObject().put("id", c.id).put("frames", JSONArray(c.frames)).put("fps", c.fps)
                .put("entryPose", c.entryPose).put("exitPose", c.exitPose).put("loop", c.loop)
                .put("interrupt", c.interrupt.name.lowercase()).put("transitions", JSONArray(c.transitions))
                .put("heading", c.heading!!.name.lowercase()).put("exitHeading", c.exitHeading!!.name.lowercase())
            c.variantOf?.let { value.put("variantOf", it) }
            value.put("canTravel", c.travelEnabled)
            if (c.projectedTravel.isNotEmpty()) value.put("projectedTravel", JSONArray(c.projectedTravel)).put("travelUnits", c.travelUnits)
            clips.put(value)
        }
        root.put("clips", clips)
        val parsed = ClipManifest.parse(root.toString(), emptySet())
        assertEquals("walk_right", parsed.resolveClip("walk", ClipHeading.RIGHT))
        assertEquals(.01, parsed.clips.getValue("walk").projectedTravel.first(), 0.0)
        clips.getJSONObject(2).getJSONArray("projectedTravel").put(0, "0.01")
        assertThrows(IllegalArgumentException::class.java) { ClipManifest.parse(root.toString(), emptySet()) }
    }

    @Test fun authoredSourceCatalogObeysTheRuntimeDirectionAndMotionContract() {
        val source = listOf(File("../art/character/motions.json"), File("art/character/motions.json")).firstOrNull { it.isFile }
            ?: error("The actual authored source catalog must be checked in with its consumer")
        val authored = JSONObject(source.readText())
        val clips = authored.getJSONArray("clips")
        val fixturePaths = JSONArray()
        for (index in 0 until clips.length()) {
            val value = JSONObject(clips.getJSONObject(index).toString())
            val id = value.getString("id")
            // Pure contract test only: synthetic path names are not asserted to be rendered files.
            val count = value.getInt("frames")
            value.put("frames", JSONArray((0 until count).map { "pet3d/$id/$it.png" }))
            value.put("fps", value.optInt("fps", authored.getInt("fps")))
            fixturePaths.put(value)
        }
        val input = JSONObject().put("version", 1).put("width", 512).put("height", 512).put("initialClip", "stand").put("clips", fixturePaths)
        val m = ClipManifest.parse(input.toString())
        for (canonical in listOf("stand", "walk", "trot", "run", "walk_start", "walk_stop", "walk_to_trot", "trot_to_run", "run_to_trot", "trot_to_walk")) {
            assertEquals(canonical + "_right", m.resolveClip(canonical, ClipHeading.RIGHT))
            assertEquals(canonical, m.canonicalClipId(canonical + "_right"))
        }
        val d = ClipDirector(m)
        assertTrue(d.requestHeading(1)); assertTrue(d.request("run"))
        assertTrue(d.queuedClips.contains("turn_to_right"))
        assertEquals("run_right", d.requestedClip)
    }

    @Test fun interactionRequestDoesNotEraseMeasuredWalkAndStopSteps() {
        val d = ClipDirector(manifest()); d.request("walk"); advanceTo(d, "walk")
        assertTrue(d.request("pet_head"))
        assertTrue(d.canTravel); assertEquals(-.01, d.projectedTravelFor(d.peekNext()), 0.0)
        var displacement = 0.0
        while (d.clipId != "pet_head") {
            val next = d.peekNext()
            displacement += d.commitIfTravelAllowed(next) { true }!!
        }
        assertEquals(-.066, displacement, 1e-9) // Remaining cycle .06 + real stop .006.
        assertFalse(d.canTravel)
    }

    @Test fun stoppingReservationIncludesRemainingCurrentCycleAndAuthoredStopRoute() {
        val d = ClipDirector(manifest()); d.request("walk"); advanceTo(d, "walk")
        assertEquals(.066, d.stoppingTravelFraction!!, 1e-9)
        d.commit(d.peekNext())
        assertEquals(.056, d.stoppingTravelFraction!!, 1e-9)
        assertTrue(d.requestStop()); assertEquals("stand", d.requestedClip)
        assertTrue(d.canTravel); assertEquals(-.02, d.projectedTravelFor(d.peekNext()), 0.0)
        advanceTo(d, "walk_stop"); assertTrue(d.canTravel)
    }

    @Test fun unavailableStartupOrStoppingMeasurementsRejectMovementActivation() {
        for (id in listOf("walk_start", "walk_stop")) assertThrows(IllegalArgumentException::class.java) {
            manifest(fixture().map { if (it.id == id) it.copy(projectedTravel = emptyList(), travelUnits = null, travelEnabled = false) else it })
        }
    }

    @Test fun blockedFullStepKeepsPoseAndRouteUntilTheWholeStepCanBeAccepted() {
        val d = ClipDirector(manifest()); d.request("walk"); advanceTo(d, "walk"); d.requestHeading(1)
        val before = d.current; val route = d.queuedClips
        repeat(3) {
            assertNull(d.commitIfTravelAllowed(d.peekNext()) { false })
            assertEquals(before, d.current); assertEquals(route, d.queuedClips)
        }
        assertEquals(-.01, d.commitIfTravelAllowed(d.peekNext()) { true }!!, 0.0)
        assertEquals(1, d.frameIndex); assertEquals(-1, d.travelHeading)
    }

    @Test fun intervalStartsRetainsTheRealLastStepAcrossSharedClipBoundary() {
        val d = ClipDirector(manifest(fixture().map { if (it.id == "walk_start")
            it.copy(sampling = "interval_starts", projectedTravel = listOf(.002, .003, .001, .004)) else it }))
        d.request("walk"); advanceTo(d, "walk_start")
        repeat(3) { d.commit(d.peekNext()) }
        assertEquals("walk", d.peekNext().clipId)
        assertEquals(-.004, d.commitIfTravelAllowed(d.peekNext()) { true }!!, 0.0)
        assertEquals("walk", d.clipId); assertEquals(0, d.frameIndex)
        assertThrows(IllegalArgumentException::class.java) { manifest(fixture().map { if (it.id == "walk_start")
            it.copy(projectedTravel = listOf(.002, .003, .001, .004)) else it }) }
    }
}
