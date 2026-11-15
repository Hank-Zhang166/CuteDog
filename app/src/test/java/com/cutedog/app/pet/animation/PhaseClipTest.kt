package com.cutedog.app.pet.animation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/** Explicit metadata/hash fixtures; none of these stand in for shipped art or physical QA. */
class PhaseClipTest {
    private val canonical = "a".repeat(64)
    private val canonicalPixels = "c".repeat(64)
    private fun hash(value: String) = ClipIntegrity.sha256(value.toByteArray())
    private fun clip(id: String, count: Int, entry: String = "stand_left", exit: String = entry,
        loop: Boolean = false, next: List<String> = listOf("stand"), moving: Double = 0.0): FrameClip {
        val frames = (0 until count).map { "pet3d/$id/$it.png" }
        return FrameClip(id, frames, 24, entry, exit, loop, if (loop) ClipInterrupt.BOUNDARY else ClipInterrupt.NEVER, next,
            heading = ClipHeading.LEFT, projectedTravel = List(count) { moving }, travelUnits = "canvas_width_fraction",
            travelEnabled = moving > 0, sampling = "interval_starts", frameSha256 = frames.mapIndexed { index, path ->
                if (index == 0 && entry == "stand_left") canonicalPixels else hash(path)
            }, entryStateSha256 = if (entry == "stand_left") canonical else hash(entry),
            exitStateSha256 = if (exit == "stand_left") canonical else hash(exit))
    }
    private fun clips(): List<FrameClip> {
        val stand = clip("stand", 24, loop = true, next = listOf("wave", "happy", "walk_start"))
        val normal = listOf(stand, clip("wave", 4), clip("happy", 4),
            clip("walk_start", 4, exit = "walk_left", next = listOf("walk"), moving = .001),
            clip("walk", 4, entry = "walk_left", loop = true, next = listOf("walk_stop"), moving = .01),
            clip("walk_stop", 4, entry = "walk_left", exit = "stand_left", moving = .002))
        val bridges = listOf(6, 12).map { index ->
            val state = hash("phase:$index")
            val bridge = clip("stand_exit_$index", 8, entry = "phase_stand_$index", exit = "stand_left",
                next = listOf("stand", "wave", "happy", "walk_start"))
            bridge.copy(frameSha256 = listOf(stand.frameSha256[index]) + bridge.frameSha256.drop(1), entryStateSha256 = state,
                phaseEntry = PhaseEntry("stand", index, ClipIntegrity.contentSha256(512, 512, stand), stand.frameSha256[index], state,
                    AssetDigest("pet3d/phase-proof.json", "f".repeat(64))),
                terminalProof = ClipTerminalProof("pet3d/proofs/${bridge.id}.png", canonicalPixels, canonical))
        }
        return normal + bridges
    }
    private fun manifest(values: List<FrameClip> = clips()) = ClipManifest(512, 512, "stand", values, emptySet(), 2)
    private fun toFrame(director: ClipDirector, index: Int) { while (director.frameIndex < index) director.commit(director.peekNext()) }
    private fun proof(values: List<FrameClip>): ByteArray = JSONObject().put("version", 1).put("continuityOrder", 1)
        .put("inputs", JSONObject(listOf("motionSourceSha256", "rigSha256", "atlasSha256", "rendererSha256", "continuityReportSha256").associateWith { hash(it) }))
        .put("bindings", JSONArray(values.filter { it.phaseEntry != null }.map { bridge ->
            val entry = bridge.phaseEntry!!; val terminal = bridge.terminalProof!!
            JSONObject().put("bridge", bridge.id).put("sourceClip", entry.sourceClip).put("atFrame", entry.atFrame)
                .put("sourceClipSha256", entry.sourceClipSha256).put("bridgeClipSha256", ClipIntegrity.contentSha256(512, 512, bridge))
                .put("sourceFrameSha256", entry.sourceFrameSha256).put("entryStateSha256", entry.entryStateSha256)
                .put("terminalFrameSha256", terminal.sha256).put("exitStateSha256", terminal.stateSha256)
        })).toString().toByteArray()
    private fun withProof(values: List<FrameClip>, bytes: ByteArray) = values.map {
        it.phaseEntry?.let { entry -> it.copy(phaseEntry = entry.copy(proof = entry.proof.copy(sha256 = ClipIntegrity.sha256(bytes)))) } ?: it
    }

    @Test fun exactSourceIntervalBranchesOnceAndDoesNotRepeatEntryOrTerminal() {
        val d = ClipDirector(manifest()); d.request("wave")
        val submitted = mutableListOf(d.current)
        repeat(14) { val plan = d.planNext { true }; assertNotNull(d.commitIfTravelAllowed(plan, { true }, { _, _ -> true })); submitted += d.current }
        assertEquals((0..5).map { "stand" to it } + (0..7).map { "stand_exit_6" to it } + listOf("wave" to 0), submitted.map { it.clipId to it.index })
        assertEquals(14.0 / 24, (submitted.size - 1).toDouble() / 24, 0.0)
        assertEquals("wave", d.canonicalClipId)
    }

    @Test fun anAlreadyDisplayedAnchorIsNotReenteredAndLateDecodingWaitsForANewPhase() {
        val d = ClipDirector(manifest()); d.request("wave"); toFrame(d, 5)
        val late = d.planNext { false }; assertNull(late.phaseBridge); d.commit(late)
        assertEquals(6, d.frameIndex)
        repeat(5) { val plan = d.planNext { true }; assertNull(plan.phaseBridge); d.commit(plan) }
        val next = d.planNext { true }; assertEquals("stand_exit_12", next.phaseBridge)
        d.commit(next); assertEquals(0, d.frameIndex)
        val atAnchor = ClipDirector(manifest()); toFrame(atAnchor, 6); atAnchor.request("wave")
        assertEquals("stand_exit_12", atAnchor.upcomingPhaseBridge()!!.id)
    }

    @Test fun currentSourceAndOldRequestBothInvalidateAComputedPhasePlan() {
        val d = ClipDirector(manifest()); d.request("wave"); toFrame(d, 5)
        val oldRequest = d.planNext { true }; d.request("happy")
        assertThrows(IllegalArgumentException::class.java) { d.commit(oldRequest) }
        val oldFrame = d.planNext { true }; d.commit(d.peekNext())
        assertThrows(IllegalArgumentException::class.java) { d.commit(oldFrame) }
        assertEquals("stand", d.clipId); assertEquals(6, d.frameIndex)
    }

    @Test fun internalBridgesCannotBeRequestedOrUsedAsOrdinaryShortcuts() {
        val m = manifest(); val d = ClipDirector(m)
        assertFalse(d.request("stand_exit_6")); assertNull(m.route("stand", "stand_exit_6"))
        assertFalse("stand_exit_6" in m.requestableClipIds)
        assertThrows(IllegalArgumentException::class.java) { manifest(clips().map {
            if (it.id == "wave") it.copy(transitions = listOf("stand_exit_6")) else it
        }) }
    }

    @Test fun latestInputDuringTheBridgeChangesOnlyItsSafeExitGoal() {
        val d = ClipDirector(manifest()); d.request("wave"); toFrame(d, 5); d.commit(d.planNext { true })
        assertEquals("stand_exit_6", d.canonicalClipId); assertFalse(d.canTravel)
        d.request("happy")
        for (index in 1..7) { val plan = d.planNext { true }; assertEquals("stand_exit_6", plan.next.clipId); assertEquals(index, plan.next.index); d.commit(plan) }
        d.commit(d.planNext { true }); assertEquals("happy", d.clipId); assertEquals(0, d.frameIndex)
    }

    @Test fun movingGoalsReserveTheirWholeStartAndStopBeforeEnteringTheZeroTravelBridge() {
        val d = ClipDirector(manifest()); d.request("walk"); toFrame(d, 5)
        val plan = d.planNext { true }
        assertEquals("stand_exit_6", plan.phaseBridge)
        val reserve = d.travelReservationFor(plan)!!
        assertEquals(-1, reserve.heading); assertEquals(.052, reserve.fraction, .000001)
        assertNull(d.commitIfTravelAllowed(plan, { fail("No real moving step exists here"); true }, { _, _ -> false }))
        assertEquals("stand", d.clipId); assertFalse(d.hasReservedTravelSequence)
        d.request("walk"); val accepted = d.planNext { true }
        assertEquals(0.0, d.commitIfTravelAllowed(accepted, { fail("Entry approval is not a step"); true }, { _, _ -> true })!!, 0.0)
        assertTrue(d.hasReservedTravelSequence)
        // Actual release start-contact semantics need bridge -> stand -> walk_start. The extra
        // full standing cycle is slower, so do not enter a bridge or create a repeated-exit loop.
        val indirect = ClipDirector(manifest(clips().map {
            if (it.phaseEntry != null) it.copy(transitions = it.transitions.filter { next -> next != "walk_start" }) else it
        }))
        indirect.request("walk")
        repeat(24) { val next = indirect.planNext { true }; assertNull(next.phaseBridge); indirect.commit(next) }
        assertEquals("walk_start", indirect.clipId)
    }

    @Test fun cancellingMovementInsideAStationaryPhaseBridgeDoesNotAuthorizeANewWalk() {
        val d = ClipDirector(manifest()); d.request("walk"); toFrame(d, 5)
        assertNotNull(d.commitIfTravelAllowed(d.planNext { true }, { true }, { _, _ -> true }))
        assertTrue(d.hasReservedTravelSequence)
        assertTrue(d.request("wave")); assertFalse(d.hasReservedTravelSequence)
        assertEquals("stand_exit_6", d.clipId) // Finish the genuine bridge; do not jump to stand.
        assertTrue(d.request("walk")); assertFalse(d.hasReservedTravelSequence)
        repeat(7) { d.commit(d.planNext { true }) }
        assertNull(d.commitIfTravelAllowed(d.planNext { true }, { true }, { _, _ -> false }))
        assertEquals("stand_exit_6", d.clipId); assertFalse(d.hasReservedTravelSequence)
    }

    @Test fun movingSourcesAndInvalidPhaseOrStateBindingsAreRejected() {
        for (change in listOf<(FrameClip) -> FrameClip>(
            { it.copy(phaseEntry = it.phaseEntry!!.copy(atFrame = 0)) },
            { it.copy(phaseEntry = it.phaseEntry!!.copy(atFrame = 16)) },
            { it.copy(phaseEntry = it.phaseEntry!!.copy(sourceClipSha256 = "0".repeat(64))) },
            { it.copy(phaseEntry = it.phaseEntry!!.copy(sourceFrameSha256 = "0".repeat(64))) },
            { it.copy(entryStateSha256 = "0".repeat(64)) },
            { it.copy(terminalProof = it.terminalProof!!.copy(stateSha256 = "0".repeat(64))) },
            { it.copy(heading = ClipHeading.RIGHT, exitHeading = ClipHeading.RIGHT) },
            { it.copy(interrupt = ClipInterrupt.IMMEDIATE, safeExitFrames = setOf(0)) },
            { it.copy(projectedTravel = List(8) { .01 }) },
        )) assertThrows(IllegalArgumentException::class.java) { manifest(clips().map { if (it.id == "stand_exit_6") change(it) else it }) }
        assertThrows(IllegalArgumentException::class.java) { manifest(clips().map { if (it.id == "stand") it.copy(projectedTravel = List(24) { .01 }) else it }) }
        assertThrows(IllegalArgumentException::class.java) { manifest(clips().map { if (it.id == "stand_exit_6") it.copy(phaseEntry = it.phaseEntry!!.copy(sourceClip = "walk")) else it }) }
    }

    @Test fun reportVerificationReadsOnlySmallReportsAndRejectsReboundProofs() {
        val original = clips(); val bytes = proof(original); val bound = withProof(original, bytes)
        val opened = mutableListOf<String>()
        PhaseProofVerifier.verify(manifest(bound), FrameAssetSource { path -> opened += path; ByteArrayInputStream(bytes) })
        assertEquals(listOf("pet3d/phase-proof.json"), opened)
        val bad = JSONObject(bytes.toString(Charsets.UTF_8)).apply {
            getJSONArray("bindings").getJSONObject(0).put("bridgeClipSha256", "0".repeat(64))
        }.toString().toByteArray()
        assertThrows(IllegalArgumentException::class.java) { PhaseProofVerifier.verify(manifest(withProof(original, bad)), FrameAssetSource { ByteArrayInputStream(bad) }) }
        assertThrows(IllegalArgumentException::class.java) { PhaseProofVerifier.verify(manifest(bound), FrameAssetSource { ByteArrayInputStream(bad) }) }
    }

    @Test fun streamingDigestIncludesSkippedBytesAndTheUnreadTailAndEnforcesBounds() {
        val bytes = ByteArray(40_000) { (it % 251).toByte() }
        val expected = ClipIntegrity.sha256(bytes)
        val result = ClipIntegrity.verifyWhileReading(ByteArrayInputStream(bytes), expected) {
            it.mark(256); val first = it.read(); it.reset(); assertEquals(first, it.read()); it.skip(25_000); first
        }
        assertEquals(0, result)
        assertThrows(IllegalArgumentException::class.java) { ClipIntegrity.verifyWhileReading(ByteArrayInputStream(bytes + 1), expected) { it.read() } }
        assertThrows(IllegalArgumentException::class.java) { ClipIntegrity.verifyWhileReading(ByteArrayInputStream(bytes), expected, 39_999) { it.read() } }
        assertEquals(40_000, ClipIntegrity.readLimited(ByteArrayInputStream(bytes), 40_000).size)
        assertThrows(IllegalArgumentException::class.java) { ClipIntegrity.readLimited(ByteArrayInputStream(bytes), 39_999) }
    }

    @Test fun canonicalContentMatchesTheIndependentProducerGoldenVector() {
        val file = listOf(File("docs/phase-v2-golden.json"), File("../docs/phase-v2-golden.json")).first { it.isFile }
        val fixture = JSONObject(file.readText()); val record = fixture.getJSONObject("record")
        val clip = FrameClip("stand", listOf("pet3d/stand/000.png", "pet3d/stand/001.png"), 24, "stand_left", "stand_left", true,
            ClipInterrupt.BOUNDARY, listOf("stand"), heading = ClipHeading.LEFT, projectedTravel = listOf(0.0, -0.0),
            travelUnits = "canvas_width_fraction", sampling = "interval_starts", frameSha256 = listOf("0".repeat(64), "1".repeat(64)),
            entryStateSha256 = "a".repeat(64), exitStateSha256 = "a".repeat(64))
        assertEquals(fixture.getString("expectedSha256"), ClipIntegrity.contentSha256(record.getInt("width"), record.getInt("height"), clip))
        assertEquals(fixture.getString("expectedSha256"), ClipIntegrity.sha256(fixture.getString("expectedCanonicalUtf8").toByteArray()))
        assertEquals("[0,0.00001,1000000]", ClipIntegrity.canonicalJson(listOf(-0.0, 1e-5, 1e6)))
    }

    @Test fun v1CannotClaimPhaseSupportAndReleaseScopeIsCompiledNotSelfDeclared() {
        assertThrows(IllegalArgumentException::class.java) { ClipManifest(512, 512, "stand", clips(), emptySet(), 1) }
        assertEquals(29, ReleasedArtwork.REQUIRED_ACTIONS.size)
        assertTrue(ReleasedArtwork.REQUIRED_ACTIONS.containsAll(setOf("walk", "trot", "run", "walk_to_trot", "trot_to_walk", "trot_to_run", "run_to_trot", "play_ball", "treat", "hop")))
        assertThrows(IllegalArgumentException::class.java) { ClipManifest(512, 512, "stand", clips(), ReleasedArtwork.REQUIRED_ACTIONS, 2) }
    }
}
