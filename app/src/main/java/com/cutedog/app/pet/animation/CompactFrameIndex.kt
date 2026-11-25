package com.cutedog.app.pet.animation

import android.content.res.AssetManager
import org.json.JSONObject

/** Resolves an approved source frame to its independently decodable, installed payload. */
internal data class StoredFrame(val sha256: String, val mirror: Boolean) {
    val path = "pet3d/frames/$sha256.webp"
}

internal object CompactFrameIndex {
    const val FILE = "frame-index.json"

    fun parse(json: String, manifestSha256: String, sources: Map<String, String>,
              files: Set<String>): Map<String, StoredFrame> {
        val root = JSONObject(json)
        require(root.get("version") == 1 && root.getString("manifestSha256") == manifestSha256) {
            "紧凑帧索引与动作清单不符"
        }
        val rows = root.getJSONObject("frames")
        val expected = sources.values.toSet()
        require(rows.keys().asSequence().toSet() == expected) { "紧凑帧索引缺少源图或包含额外源图" }
        val unique = expected.associateWith { key ->
            val pair = rows.getJSONArray(key)
            require(pair.length() == 2 && pair.get(0) is String && pair.get(1) is Boolean)
            val hash = pair.getString(0)
            require(ClipIntegrity.SHA256.matches(hash)) { "无效的安装帧摘要" }
            StoredFrame(hash, pair.getBoolean(1))
        }
        require(unique.values.mapTo(mutableSetOf()) { it.sha256 + ".webp" } == files) { "安装帧文件与索引不符" }
        return sources.mapValues { unique.getValue(it.value) }
    }

    fun openLoader(assets: AssetManager, manifest: ClipManifest, manifestBytes: ByteArray): BitmapFrameLoader {
        if (FILE !in assets.list("pet3d").orEmpty()) {
            for (clip in manifest.clips.values) {
                val files = assets.list("pet3d/${clip.id}").orEmpty().toSet()
                require(clip.frames.all { it.substringAfterLast('/') in files }) { "${clip.id} 缺少声明的动画帧" }
            }
            return BitmapFrameLoader(FrameAssetSource { assets.open(it) }, manifest.width, manifest.height,
                frameDigests = manifest.frameDigests, verificationDependencies = manifest.verificationDependencies)
        }

        val encoded = ClipIntegrity.readLimited(assets.open("pet3d/$FILE"), ClipIntegrity.MAX_MANIFEST_BYTES)
        val frames = parse(encoded.toString(Charsets.UTF_8), ClipIntegrity.sha256(manifestBytes), manifest.frameDigests,
            assets.list("pet3d/frames").orEmpty().toSet())
        val dependencies = manifest.verificationDependencies.mapValues { (_, items) ->
            items.map { frame -> AssetDigest(frame.path, frames.getValue(frame.path).sha256) }
        }
        return BitmapFrameLoader(FrameAssetSource { path -> assets.open(frames.getValue(path).path) },
            manifest.width, manifest.height, frameDigests = frames.mapValues { it.value.sha256 },
            verificationDependencies = dependencies, mirroredPaths = frames.filterValues { it.mirror }.keys)
    }
}
