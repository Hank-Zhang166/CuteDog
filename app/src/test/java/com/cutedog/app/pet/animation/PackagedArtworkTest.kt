package com.cutedog.app.pet.animation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackagedArtworkTest {
    private fun assets() = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    @Test fun actualPackCanPlayEveryUserActionAndEveryRecoveryThroughTheAndroidVerifier() {
        val assets = assets()
        val manifest = ClipManifest.parse(File(assets, ClipManifest.ASSET_PATH).readText(), ReleasedArtwork.REQUIRED_ACTIONS)
        val source = FrameAssetSource { File(assets, it).inputStream() }
        PhaseProofVerifier.verify(manifest, source)
        assertEquals(ReleasedArtwork.REQUIRED_ACTIONS, manifest.requestableClipIds)
        assertTrue(manifest.phaseExits("stand").isNotEmpty())
        assertTrue(manifest.phaseExits("stand_right").isNotEmpty())
        assertTrue(manifest.phaseExits("tail_wag").isNotEmpty())
        for ((path, digest) in manifest.frameDigests) {
            ClipIntegrity.verifyWhileReading(source.open(path), digest) { }
        }
    }

    @Test fun playerUsesRealDirectionChangeAndBacksUpOppositeItsNose() {
        val manifest = ClipManifest.parse(File(assets(), ClipManifest.ASSET_PATH).readText(), ReleasedArtwork.REQUIRED_ACTIONS)
        for ((id, sign) in listOf("backstep" to 1, "backstep_right" to -1)) {
            val player = ClipDirector(manifest)
            assertTrue(player.request(id))
            val path = mutableSetOf<String>()
            repeat(800) {
                if (player.clipId != id) {
                    path += player.clipId
                    player.commit(player.peekNext())
                }
            }
            assertEquals(id, player.clipId)
            assertEquals(sign, player.travelHeading)
            assertTrue(player.projectedTravelFor(player.peekNext()) * sign > 0.0)
            if (id.endsWith("_right")) assertTrue("A real turn must precede right-facing art", "turn" in path)
        }
        assertEquals(listOf("jump_takeoff", "land"), manifest.route("stand", "land"))
    }

    @Test fun fallingAsleepHasItsOwnEntryAndWakePrecedesReturningToStand() {
        val manifest = ClipManifest.parse(File(assets(), ClipManifest.ASSET_PATH).readText(), ReleasedArtwork.REQUIRED_ACTIONS)
        val sleep = requireNotNull(manifest.route("stand", "sleep"))
        assertTrue("Falling asleep must play before the sleeping hold", "fall_asleep" in sleep)
        assertTrue("Waking must play before getting up", "wake" in requireNotNull(manifest.route("sleep", "stand")))
        assertTrue("Curling up has its own hold", "curl" in requireNotNull(manifest.route("stand", "curl_idle")))
    }

    @Test fun ordinaryMovementOffersWalkingAndJoggingAndMigratesOldSavedPace() {
        val manifest = ClipManifest.parse(File(assets(), ClipManifest.ASSET_PATH).readText(), ReleasedArtwork.REQUIRED_ACTIONS)
        val director = ClipDirector(manifest)
        for (old in listOf("run", "run_right", "trot_to_run", "run_to_trot", "trot_to_run_right", "run_to_trot_right")) {
            assertTrue(old !in manifest.clips)
            assertTrue(director.request(old))
            assertEquals(if (old.endsWith("_right")) "trot_right" else "trot", manifest.requestableName(old))
        }
        assertTrue("chase" in manifest.clips && "chase_right" in manifest.clips && "play_ball" in manifest.clips)
        for (old in listOf("tongue", "sad")) assertTrue(!director.request(old))
    }

    @Test fun approvedBallPlayRetainsTheSameFramesCadenceAndTravel() {
        val manifest = ClipManifest.parse(File(assets(), ClipManifest.ASSET_PATH).readText(), ReleasedArtwork.REQUIRED_ACTIONS)
        val reference = mapOf(
            "chase" to "76d01cb2c1d237855344669682658c5eb6fd7eddcc581b4e1ac0527e538bfa22",
            "chase_right" to "3c74b76b486d9ec1009a65fbe533815ab196ef9f714ba5c5963d96b1f4ad1332",
            "play_ball" to "74ed5f59534b6674b64efa0b20658bce5e6e13f3e5819f6303349539fb41a51f",
        )
        for ((name, digest) in reference) {
            val clip = manifest.clips.getValue(name)
            assertEquals(24, clip.fps)
            val samples = ClipIntegrity.canonicalJson(listOf(clip.frameSha256, clip.projectedTravel))
            assertEquals(digest, ClipIntegrity.sha256(samples.toByteArray(Charsets.UTF_8)))
        }
    }
}
