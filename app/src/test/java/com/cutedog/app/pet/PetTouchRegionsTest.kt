package com.cutedog.app.pet

import java.io.File
import com.cutedog.app.pet.animation.ClipManifest
import com.cutedog.app.pet.animation.ReleasedArtwork
import org.junit.Assert.*
import org.junit.Test

class PetTouchRegionsTest {
    private val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private val regions = PetTouchRegions(File(main, "res/raw/pet_touch_regions.gz").inputStream())

    @Test fun touchesFollowThePaintedDogThroughTurnsLyingDownAndRollingOver() {
        fun point(clip: String, frame: Int, x: Int, y: Int, expected: PetPart?) =
            assertEquals("$clip/$frame at $x,$y", expected, regions.at(clip, frame, x / 512f, y / 512f))
        point("stand", 0, 138, 159, PetPart.HEAD)
        point("stand", 0, 189, 394, PetPart.PAW)
        point("stand", 0, 348, 154, PetPart.TAIL)
        point("stand_right", 0, 374, 159, PetPart.HEAD)
        point("stand_right", 0, 323, 394, PetPart.PAW)
        point("stand_right", 0, 164, 154, PetPart.TAIL)
        point("turn", 28, 256, 171, PetPart.HEAD)
        point("turn", 28, 350, 180, null) // hidden tail in the front view
        point("belly_up", 40, 202, 270, PetPart.PAW)
        point("side_lie_idle", 0, 127, 366, PetPart.HEAD)
        point("groom", 63, 248, 190, PetPart.HEAD)
        point("stand", 0, 92, 410, null)
        point("stand", 0, 12, 12, null)
        point("treat", 25, 36, 200, null)
        point("play_ball", 75, 82, 215, null)
    }

    @Test fun everyDisplayedFrameHasTouchableCoatAndClearCorners() {
        val manifest = ClipManifest.parse(File(main, "assets/${ClipManifest.ASSET_PATH}").readText(), ReleasedArtwork.REQUIRED_ACTIONS)
        for (clip in manifest.clips.values) for (frame in clip.frames.indices) {
            assertNull(regions.at(clip.id, frame, .02f, .02f))
            assertTrue("Missing mask for ${clip.id}/$frame", (1..7).any { y ->
                (1..7).any { x -> regions.at(clip.id, frame, x / 8f, y / 8f) != null }
            })
        }
    }
}
