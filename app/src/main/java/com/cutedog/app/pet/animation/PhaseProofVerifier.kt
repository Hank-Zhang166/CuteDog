package com.cutedog.app.pet.animation

import org.json.JSONObject

/** Small report verification only; the image library is checked lazily by the frame decoder. */
object PhaseProofVerifier {
    fun verify(manifest: ClipManifest, source: FrameAssetSource) {
        val groups = manifest.clips.values.filter { it.phaseEntry != null }.groupBy { it.phaseEntry!!.proof.path }
        var totalBytes = 0
        for ((path, bridges) in groups) {
            val proof = bridges.first().phaseEntry!!.proof
            val bytes = ClipIntegrity.readLimited(source.open(path), ClipIntegrity.MAX_REPORT_BYTES)
            totalBytes += bytes.size
            require(totalBytes <= 4 * 1024 * 1024) { "相位报告总大小超出限制" }
            require(ClipIntegrity.sha256(bytes) == proof.sha256) { "相位报告实际摘要不符" }
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            require(strictInt(root, "version") == 1 && strictInt(root, "continuityOrder") == 1) { "相位报告版本或连续性合同无效" }
            val inputs = root.getJSONObject("inputs")
            require(INPUTS.all(inputs::has)) { "相位报告缺少实际输入集合" }
            for (key in inputs.keys()) require(inputs.get(key) is String && ClipIntegrity.SHA256.matches(inputs.getString(key))) { "相位报告输入摘要无效" }
            val rows = root.getJSONArray("bindings")
            require(rows.length() == bridges.size) { "相位报告不能遗漏或添加未绑定的内部桥" }
            val byId = bridges.associateBy { it.id }
            val seen = mutableSetOf<String>()
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                val id = strictString(row, "bridge")
                require(seen.add(id)) { "相位报告重复绑定内部桥" }
                val clip = byId[id] ?: throw IllegalArgumentException("相位报告包含清单之外的内部桥")
                val entry = clip.phaseEntry!!
                val terminal = clip.terminalProof!!
                require(strictString(row, "sourceClip") == entry.sourceClip && strictInt(row, "atFrame") == entry.atFrame) { "相位报告入口映射不符" }
                val expected = mapOf(
                    "sourceClipSha256" to entry.sourceClipSha256,
                    "bridgeClipSha256" to ClipIntegrity.contentSha256(manifest.width, manifest.height, clip),
                    "sourceFrameSha256" to entry.sourceFrameSha256,
                    "entryStateSha256" to entry.entryStateSha256,
                    "terminalFrameSha256" to terminal.sha256,
                    "exitStateSha256" to terminal.stateSha256,
                )
                for ((key, value) in expected) require(strictString(row, key) == value) { "相位报告 $key 绑定不符" }
            }
        }
    }

    private fun strictString(item: JSONObject, name: String): String {
        require(item.get(name) is String) { "$name 必须是字符串" }
        return item.getString(name)
    }
    private fun strictInt(item: JSONObject, name: String): Int {
        val value = item.get(name)
        require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toInt().toDouble()) { "$name 必须是整数" }
        return value.toInt()
    }
    private val INPUTS = setOf("motionSourceSha256", "rigSha256", "atlasSha256", "rendererSha256", "continuityReportSha256")
}
