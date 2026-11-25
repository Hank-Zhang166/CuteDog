package com.cutedog.app.pet.animation

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CompactFrameIndexTest {
    private val sourceA = "a".repeat(64)
    private val sourceB = "b".repeat(64)
    private val encoded = "c".repeat(64)
    private val manifest = "d".repeat(64)
    private val paths = mapOf("left" to sourceA, "right" to sourceB)
    private val files = setOf("$encoded.webp")

    private fun index() = JSONObject().put("version", 1).put("manifestSha256", manifest).put("frames",
        JSONObject().put(sourceA, JSONArray().put(encoded).put(false)).put(sourceB, JSONArray().put(encoded).put(true)))

    @Test fun installedLibraryContainsExactlyTheIndexedPayloadsAndMatchesEveryDigest() {
        val assets = File(System.getProperty("cuteDog.compactAssets") ?: "build/generated/compact-artwork/assets")
        val originals = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
        val manifestBytes = File(assets, ClipManifest.ASSET_PATH).readBytes()
        assertArrayEquals(File(originals, ClipManifest.ASSET_PATH).readBytes(), manifestBytes)
        val parsed = ClipManifest.parse(manifestBytes.toString(Charsets.UTF_8), ReleasedArtwork.REQUIRED_ACTIONS)
        val frames = File(assets, "pet3d/frames")
        val table = CompactFrameIndex.parse(File(assets, "pet3d/" + CompactFrameIndex.FILE).readText(),
            ClipIntegrity.sha256(manifestBytes), parsed.frameDigests, frames.list()!!.toSet())
        for (payload in table.values.distinctBy { it.sha256 }) {
            ClipIntegrity.verifyWhileReading(File(assets, payload.path).inputStream(), payload.sha256) { }
        }
    }

    @Test fun aSinglePayloadCanRepresentTwoGenuinelyReflectedSourceFrames() {
        val result = CompactFrameIndex.parse(index().toString(), manifest, paths, files)
        assertEquals(StoredFrame(encoded, false), result["left"])
        assertEquals(StoredFrame(encoded, true), result["right"])
    }

    @Test fun staleManifestAndMissingOrUnrecognizedPhysicalFramesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { CompactFrameIndex.parse(index().toString(), sourceA, paths, files) }
        assertThrows(IllegalArgumentException::class.java) { CompactFrameIndex.parse(index().toString(), manifest, paths, emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { CompactFrameIndex.parse(index().toString(), manifest, paths, files + "unexpected.webp") }
        val changed = index().apply { getJSONObject("frames").remove(sourceA) }
        assertThrows(IllegalArgumentException::class.java) { CompactFrameIndex.parse(changed.toString(), manifest, paths, files) }
    }
}
