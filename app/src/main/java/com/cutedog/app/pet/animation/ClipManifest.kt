package com.cutedog.app.pet.animation

import org.json.JSONObject

enum class ClipInterrupt { IMMEDIATE, BOUNDARY, NEVER }
enum class ClipHeading(val sign: Int) {
    LEFT(-1), RIGHT(1);
    companion object {
        fun fromSign(sign: Int): ClipHeading? = entries.firstOrNull { it.sign == sign }
        fun parse(value: String): ClipHeading = when (value) {
            "left" -> LEFT
            "right" -> RIGHT
            else -> throw IllegalArgumentException("角色朝向必须是 left 或 right")
        }
    }
}

data class FrameClip(
    val id: String,
    val frames: List<String>,
    val fps: Int,
    val entryPose: String,
    val exitPose: String,
    val loop: Boolean,
    val interrupt: ClipInterrupt,
    val transitions: List<String>,
    val safeExitFrames: Set<Int> = emptySet(),
    val variantOf: String? = null,
    val heading: ClipHeading? = null,
    val exitHeading: ClipHeading? = heading,
    /** Current frame i -> next frame displacement, as a nonnegative fraction of canvas width. */
    val projectedTravel: List<Double> = emptyList(),
    val travelUnits: String? = null,
    val travelEnabled: Boolean = false,
    val sampling: String = "inclusive_endpoints",
    val frameSha256: List<String> = emptyList(),
    val entryStateSha256: String? = null,
    val exitStateSha256: String? = null,
    val phaseEntry: PhaseEntry? = null,
    val terminalProof: ClipTerminalProof? = null,
)

/** Only authored, independently rendered clips are accepted; an alias is not a motion. */
class ClipManifest(
    val width: Int,
    val height: Int,
    val initialClip: String,
    clips: List<FrameClip>,
    requiredClipIds: Set<String> = REQUIRED_ACTIONS,
    val version: Int = 1,
) {
    val clips: Map<String, FrameClip> = clips.associateBy { it.id }
    val frameBytes: Int get() = width * height * 4
    private val stoppingTravel = mutableMapOf<String, DoubleArray>()
    private val phaseIndex = clips.filter { it.phaseEntry != null }.groupBy { it.phaseEntry!!.sourceClip }
        .mapValues { (_, values) -> values.sortedBy { it.phaseEntry!!.atFrame } }
    val requestableClipIds: Set<String> = clips.filter { it.phaseEntry == null }.mapTo(linkedSetOf()) { it.id }
    val frameDigests: Map<String, String>
    val verificationDependencies: Map<String, List<AssetDigest>>

    init {
        require(version in 1..2) { "不支持的角色清单版本" }
        require(width in 16..1024 && height in 16..1024) { "角色帧尺寸无效" }
        require(clips.isNotEmpty() && clips.size <= 256 && this.clips.size == clips.size) { "动作 ID 为空、重复或数量无效" }
        require(this.clips.containsKey(initialClip) && this.clips.getValue(initialClip).loop && this.clips.getValue(initialClip).phaseEntry == null) { "初始姿态必须是实际存在的普通循环动作" }
        require(requestableClipIds.containsAll(requiredClipIds)) { "动作资源不完整：${(requiredClipIds - requestableClipIds).sorted().joinToString()}" }
        val paths = HashSet<String>()
        val poseHeadings = mutableMapOf<String, ClipHeading>()
        for (clip in clips) {
            require(clip.id.matches(Regex("[a-z][a-z0-9_]{0,63}"))) { "动作 ID 格式无效" }
            require(clip.fps in setOf(12, 24)) { "${clip.id} 必须声明实际采样的 12 或 24fps" }
            require(clip.frames.size in 2..2400) { "${clip.id} 必须提供完整动画帧，不能以单张图片冒充" }
            require(clip.sampling in setOf("inclusive_endpoints", "interval_starts")) { "${clip.id} 未知采样边界约定" }
            require(clip.entryPose.isNotBlank() && clip.exitPose.isNotBlank()) { "${clip.id} 缺少姿态边界" }
            if (version == 1) require(clip.phaseEntry == null && clip.terminalProof == null) { "v1 不支持相位桥" }
            if (version == 2) {
                require(clip.entryPose.matches(POSE_ID) && clip.exitPose.matches(POSE_ID)) { "v2 姿态标识格式无效" }
                require(clip.frameSha256.size == clip.frames.size && clip.frameSha256.all(ClipIntegrity.SHA256::matches)) { "${clip.id} 必须逐帧声明实际文件摘要" }
            }
            require(listOfNotNull(clip.entryStateSha256, clip.exitStateSha256).all(ClipIntegrity.SHA256::matches)) { "边界状态摘要无效" }
            require(!clip.loop || clip.entryPose == clip.exitPose) { "循环动作 ${clip.id} 首尾姿态不一致" }
            require(!(clip.loop && clip.interrupt == ClipInterrupt.NEVER)) { "循环动作 ${clip.id} 不可永久禁止退出" }
            require(clip.interrupt != ClipInterrupt.IMMEDIATE || (clip.entryPose == clip.exitPose && clip.safeExitFrames.isNotEmpty())) { "${clip.id} 的立即中断必须声明真实安全出口帧" }
            require(clip.safeExitFrames.all { it in clip.frames.indices }) { "${clip.id} 安全出口帧越界" }
            require(clip.exitHeading == null || clip.heading != null) { "${clip.id} 只有出口朝向而没有入口朝向" }
            require(clip.heading == null || clip.exitHeading != null) { "${clip.id} 缺少出口朝向" }
            if (clip.heading != clip.exitHeading) {
                require(!clip.loop && clip.interrupt == ClipInterrupt.NEVER && clip.safeExitFrames.isEmpty()) { "换向桥 ${clip.id} 必须完整播放，不可提前视作转身完成" }
            }
            require((clip.projectedTravel.isEmpty() && clip.travelUnits == null) ||
                (clip.projectedTravel.size == clip.frames.size && clip.travelUnits == "canvas_width_fraction")) { "${clip.id} 位移必须逐帧声明，单位只能为画幅宽度比例" }
            require(clip.projectedTravel.all { it.isFinite() && it in 0.0..1.0 }) { "${clip.id} 包含无效逐帧位移" }
            require(!clip.travelEnabled || clip.projectedTravel.any { it > 0.0 }) { "${clip.id} 未提供实测位移，不能启用平移" }
            require(!clip.travelEnabled || clip.interrupt != ClipInterrupt.IMMEDIATE) { "${clip.id} 的实测步幅不能经近姿态出口提前切走" }
            if (clip.projectedTravel.any { it > 0.0 }) {
                require(clip.heading != null && clip.heading == clip.exitHeading) { "${clip.id} 的位移需要已完成换向的真实朝向" }
                require(clip.loop || clip.sampling == "interval_starts" || clip.projectedTravel.last() == 0.0) { "${clip.id} 含端点的非循环末帧必须为0位移" }
            }
            listOf(clip.entryPose to clip.heading, clip.exitPose to clip.exitHeading).forEach { (pose, heading) ->
                if (heading != null) {
                    val previous = poseHeadings.putIfAbsent(pose, heading)
                    require(previous == null || previous == heading) { "姿态 $pose 被声明为不同朝向，不能直接相接" }
                }
            }
            clip.variantOf?.let { baseId ->
                val base = this.clips[baseId] ?: throw IllegalArgumentException("${clip.id} 指向不存在的原动作 $baseId")
                require(base.id != clip.id && base.variantOf == null) { "${clip.id} 不能自引用或建立变体链" }
                require(base.heading != null && clip.heading != null && base.heading != clip.heading) { "${clip.id} 必须声明与原动作不同的真实朝向" }
                require(clip.heading == clip.exitHeading && base.heading == base.exitHeading) { "换向桥不是同动作的方向变体" }
                require(base.loop == clip.loop) { "${clip.id} 与原动作的循环契约不一致" }
            }
            require(clip.transitions.isNotEmpty() && clip.transitions.distinct().size == clip.transitions.size) { "${clip.id} 没有有效回程或有重复转移" }
            for (path in clip.frames) {
                require(path.matches(Regex("pet3d/${clip.id}/[0-9]+\\.(png|webp)"))) { "${clip.id} 存在无效帧路径" }
                require(paths.add(path)) { "不同动作或时间帧不能引用同一个文件：$path" }
            }
            for (next in clip.transitions) {
                val target = this.clips[next] ?: throw IllegalArgumentException("${clip.id} 转移指向不存在动作 $next")
                require(target.phaseEntry == null) { "内部相位桥不能通过普通边进入" }
                require(clip.exitPose == target.entryPose) { "${clip.id} → $next 的接触姿态不连续" }
                if (clip.travelEnabled && clip.projectedTravel.last() > 0.0) {
                    require(clip.exitHeading == target.heading) { "${clip.id} → $next 的跨片末项必须保持实际朝向" }
                }
            }
        }
        val proofDigests = mutableMapOf<String, String>()
        val phaseKeys = mutableSetOf<Pair<String, Int>>()
        for (bridge in clips.filter { it.phaseEntry != null }) {
            val entry = bridge.phaseEntry!!
            val source = this.clips[entry.sourceClip] ?: throw IllegalArgumentException("相位桥源动作不存在")
            require(source.phaseEntry == null && source.loop && source.interrupt == ClipInterrupt.BOUNDARY &&
                (source.variantOf ?: source.id) in STATIC_PHASE_SOURCES) { "相位桥只能来自获准的静止循环" }
            require(!bridge.loop && bridge.interrupt == ClipInterrupt.NEVER && bridge.safeExitFrames.isEmpty() && bridge.variantOf == null) { "内部相位桥必须完整播放" }
            require(source.sampling == "interval_starts" && bridge.sampling == "interval_starts" && source.fps == bridge.fps) { "相位源与桥必须使用同帧率区间起点采样" }
            require(entry.atFrame in 1 until source.frames.size && entry.atFrame + bridge.frames.size < source.frames.size) { "相位入口越界或不比普通结束更快" }
            require(phaseKeys.add(entry.sourceClip to entry.atFrame)) { "相位入口重复" }
            require(bridge.entryPose == "phase_${source.id}_${entry.atFrame}" && bridge.exitPose == source.exitPose) { "相位姿态绑定不符" }
            require(source.heading != null && source.heading == source.exitHeading && source.heading == bridge.heading && bridge.heading == bridge.exitHeading) { "相位桥不能变更真实朝向" }
            for (part in listOf(source, bridge)) require(!part.travelEnabled && part.projectedTravel.size == part.frames.size && part.projectedTravel.all { it == 0.0 }) { "移动或未声明零位移的动作不能使用静止桥" }
            require(listOf(entry.sourceClipSha256, entry.sourceFrameSha256, entry.entryStateSha256, entry.proof.sha256).all(ClipIntegrity.SHA256::matches)) { "相位绑定摘要无效" }
            require(entry.sourceClipSha256 == ClipIntegrity.contentSha256(width, height, source)) { "相位桥绑定了不同版本的源动作" }
            require(entry.sourceFrameSha256 == source.frameSha256[entry.atFrame] && entry.sourceFrameSha256 == bridge.frameSha256.first()) { "相位桥入口图像绑定不符" }
            require(entry.entryStateSha256 == bridge.entryStateSha256) { "相位桥入口状态绑定不符" }
            require(entry.proof.path.matches(PROOF_JSON_PATH)) { "相位报告路径无效" }
            val oldProof = proofDigests.putIfAbsent(entry.proof.path, entry.proof.sha256)
            require(oldProof == null || oldProof == entry.proof.sha256) { "同一报告路径声明了不同摘要" }
            val terminal = requireNotNull(bridge.terminalProof) { "相位桥缺少省略终点证据" }
            require(terminal.path.matches(PROOF_FRAME_PATH) && ClipIntegrity.SHA256.matches(terminal.sha256) && ClipIntegrity.SHA256.matches(terminal.stateSha256)) { "相位终点证据无效" }
            require(terminal.path !in paths) { "审计终点不能冒充另一个运行帧路径" }
            require(terminal.stateSha256 == bridge.exitStateSha256) { "相位出口状态与终点不符" }
            for (id in bridge.transitions) {
                val target = this.clips.getValue(id)
                require(target.heading == bridge.exitHeading && target.frameSha256.first() == terminal.sha256 && target.entryStateSha256 == terminal.stateSha256) { "相位桥出口没有匹配目标的实际入口状态" }
            }
        }
        require(clips.filter { it.phaseEntry == null }.all { it.terminalProof == null }) { "普通动作不能声明相位终点证据" }
        for ((base, family) in clips.groupBy { it.variantOf ?: it.id }) {
            require(family.mapNotNull { it.heading }.distinct().size == family.count { it.heading != null }) { "$base 存在重复朝向变体" }
        }
        for (id in this.clips.keys) {
            val phase = this.clips.getValue(id).phaseEntry
            require(route(initialClip, phase?.sourceClip ?: id) != null) { "动作 $id 无法从初始姿态到达" }
            require(route(id, initialClip) != null) { "动作 $id 缺少返回初始姿态的合法路径" }
        }
        // Enabling a measured loop alone is insufficient: the real startup and stopping route
        // must also have measured arrays (explicit zero arrays are valid stationary transitions).
        for (clip in clips.filter { it.travelEnabled }) {
            val heading = requireNotNull(clip.heading)
            val idle = requireNotNull(headingIdle(heading)) { "${clip.id} 缺少同朝向站姿" }
            val entry = requireNotNull(route(idle, clip.id))
            val stop = requireNotNull(route(clip.id, idle))
            val measured = (entry + clip.id + stop.dropLast(1)).distinct().all { id ->
                this.clips.getValue(id).let { part ->
                    part.heading == heading && part.exitHeading == heading && part.projectedTravel.size == part.frames.size &&
                        (part.projectedTravel.none { it > 0.0 } || part.travelEnabled)
                }
            }
            require(measured) { "${clip.id} 未提供完整、已批准的实测启动及停步路径" }
            var remaining = stop.dropLast(1).sumOf { this.clips.getValue(it).projectedTravel.sum() }
            val suffix = DoubleArray(clip.frames.size)
            for (index in clip.frames.indices.reversed()) { remaining += clip.projectedTravel[index]; suffix[index] = remaining }
            stoppingTravel[clip.id] = suffix
        }
        val digests = linkedMapOf<String, String>()
        for (clip in clips) {
            clip.frames.zip(clip.frameSha256).forEach { (path, digest) -> digests[path] = digest }
            clip.terminalProof?.let { terminal ->
                val previous = digests.putIfAbsent(terminal.path, terminal.sha256)
                require(previous == null || previous == terminal.sha256) { "终点证据路径摘要冲突" }
            }
        }
        frameDigests = digests
        verificationDependencies = clips.filter { it.phaseEntry != null }.associate { bridge ->
            val entry = bridge.phaseEntry!!
            bridge.frames.first() to listOf(AssetDigest(this.clips.getValue(entry.sourceClip).frames[entry.atFrame], entry.sourceFrameSha256),
                bridge.terminalProof!!.let { AssetDigest(it.path, it.sha256) })
        }
    }

    fun phaseExits(sourceId: String): List<FrameClip> = phaseIndex[sourceId].orEmpty()

    fun travelReady(id: String?): Boolean = id != null && id in stoppingTravel
    fun stoppingTravelFraction(id: String, frame: Int): Double? = stoppingTravel[id]?.getOrNull(frame)

    /** Canonical identity is metadata, never inferred by stripping a name suffix. */
    fun canonicalClipId(id: String): String? = clips[id]?.let { it.variantOf ?: it.id }

    /** Explicit variant IDs remain exact requests; a canonical family resolves to authored pixels. */
    fun resolveClip(id: String, heading: ClipHeading?): String? {
        val clip = clips[id] ?: return null
        if (clip.phaseEntry != null) return null
        if (clip.variantOf != null || heading == null) return id
        val family = clips.values.filter { it.id == id || it.variantOf == id }
        if (family.size == 1) return id // Front-facing interaction clips need no mirrored substitute.
        return family.firstOrNull { it.heading == heading }?.id
    }

    fun headingIdle(heading: ClipHeading): String? = resolveClip("stand", heading)?.takeIf {
        clips.getValue(it).let { clip -> clip.loop && clip.heading == heading && clip.exitHeading == heading &&
            !clip.travelEnabled && clip.projectedTravel.none { distance -> distance > 0.0 } }
    }

    /** Returns the shortest authored route excluding [from], or null when unavailable. */
    fun route(from: String, to: String): List<String>? {
        if (from !in clips || to !in clips) return null
        if (clips.getValue(to).phaseEntry != null) return null
        if (from == to) return emptyList()
        val queue = java.util.ArrayDeque<String>()
        val previous = mutableMapOf<String, String?>()
        queue.add(from); previous[from] = null
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            for (next in clips.getValue(id).transitions) {
                if (next in previous) continue
                previous[next] = id
                if (next == to) {
                    val path = mutableListOf(next)
                    var cursor = previous[next]
                    while (cursor != null && cursor != from) { path += cursor; cursor = previous[cursor] }
                    return path.asReversed()
                }
                queue.add(next)
            }
        }
        return null
    }

    companion object {
        private val STATIC_PHASE_SOURCES = setOf("stand", "breathe", "tail_wag")
        private val POSE_ID = Regex("[A-Za-z0-9_:.-]{1,128}")
        private val PROOF_JSON_PATH = Regex("pet3d/[a-zA-Z0-9_-]+(?:/[a-zA-Z0-9_-]+)*\\.json")
        private val PROOF_FRAME_PATH = Regex("pet3d/proofs/[a-zA-Z0-9_-]+\\.(png|webp)")
        const val ASSET_PATH = "pet3d/manifest.json"
        val REQUIRED_ACTIONS = setOf(
            "stand", "sit", "sit_idle", "stand_up", "lie", "lie_idle", "lie_to_stand", "side_lie", "curl", "sleep", "fall_asleep", "wake", "stretch", "yawn", "tail_wag", "breathe", "blink", "look_left", "look_right", "tilt", "scratch", "lick_paw", "groom", "walk", "trot", "run", "walk_start", "walk_stop", "hop", "jump", "turn", "backstep", "pause", "slide_correct", "land", "rebound", "happy", "tongue", "excited", "cuddle", "curious", "sleepy", "sad", "shy", "expect", "relax", "startled", "soothe", "focus", "pet_head", "nuzzle", "follow", "wave", "belly_up", "request_pet", "treat", "play_ball", "seek", "call",
        )
        fun parse(json: String, requiredClipIds: Set<String> = REQUIRED_ACTIONS): ClipManifest {
            require(json.length <= 2_000_000) { "角色清单过大" }
            val root = JSONObject(json)
            val version = root.getInt("version")
            require(version in 1..2) { "不支持的角色清单版本" }
            val array = root.getJSONArray("clips")
            val clips = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val frames = item.getJSONArray("frames")
                val transitions = item.getJSONArray("transitions")
                val safe = item.optJSONArray("safeExitFrames")
                fun optionalString(name: String): String? = if (!item.has(name)) null else {
                    require(item.get(name) is String) { "$name 必须是字符串" }
                    item.getString(name).also { require(it.isNotBlank()) { "$name 不能为空" } }
                }
                val heading = optionalString("heading")?.let { ClipHeading.parse(it) }
                val travel = if (!item.has("projectedTravel")) emptyList() else item.getJSONArray("projectedTravel").let { values ->
                    require(values.length() > 0) { "projectedTravel 不能是空数组，未测量应省略字段" }
                    (0 until values.length()).map { index ->
                        require(values.get(index) is Number) { "projectedTravel 只接受数值，不能把字符串作为位移" }
                        values.getDouble(index)
                    }
                }
                val travelEnabled = if (!item.has("canTravel")) false else {
                    require(item.get("canTravel") is Boolean) { "canTravel 必须是布尔值" }
                    item.getBoolean("canTravel")
                }
                fun strictString(record: JSONObject, name: String): String {
                    require(record.get(name) is String) { "$name 必须是字符串" }
                    return record.getString(name)
                }
                fun asset(record: JSONObject) = AssetDigest(strictString(record, "path"), strictString(record, "sha256"))
                val phase = if (!item.has("phaseEntry")) null else item.getJSONObject("phaseEntry").let { phase ->
                    val at = phase.get("atFrame")
                    require(at is Number && at.toDouble().isFinite() && at.toDouble() == at.toInt().toDouble()) { "atFrame 必须是整数" }
                    PhaseEntry(strictString(phase, "sourceClip"), at.toInt(), strictString(phase, "sourceClipSha256"),
                        strictString(phase, "sourceFrameSha256"), strictString(phase, "entryStateSha256"), asset(phase.getJSONObject("proof")))
                }
                val terminal = if (!item.has("terminalProof")) null else item.getJSONObject("terminalProof").let {
                    ClipTerminalProof(strictString(it, "path"), strictString(it, "sha256"), strictString(it, "stateSha256"))
                }
                val hashes = if (!item.has("frameSha256")) emptyList() else item.getJSONArray("frameSha256").let { values ->
                    (0 until values.length()).map { i -> require(values.get(i) is String); values.getString(i) }
                }
                FrameClip(item.getString("id"), (0 until frames.length()).map(frames::getString), item.getInt("fps"),
                    item.getString("entryPose"), item.getString("exitPose"), item.getBoolean("loop"),
                    when (item.getString("interrupt")) {
                        "immediate" -> ClipInterrupt.IMMEDIATE
                        "boundary" -> ClipInterrupt.BOUNDARY
                        "never" -> ClipInterrupt.NEVER
                        else -> throw IllegalArgumentException("未知动作中断策略")
                    }, (0 until transitions.length()).map(transitions::getString),
                    if (safe == null) emptySet() else (0 until safe.length()).map(safe::getInt).toSet(),
                    optionalString("variantOf"), heading, optionalString("exitHeading")?.let { ClipHeading.parse(it) } ?: heading,
                    travel, optionalString("travelUnits"), travelEnabled, optionalString("sampling") ?: "inclusive_endpoints",
                    hashes, optionalString("entryStateSha256"), optionalString("exitStateSha256"), phase, terminal)
            }
            return ClipManifest(root.getInt("width"), root.getInt("height"), root.getString("initialClip"), clips, requiredClipIds, version)
        }
    }
}
