package com.cutedog.app.pet.animation

import org.json.JSONObject

enum class ClipInterrupt { IMMEDIATE, BOUNDARY, NEVER }

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
)

/** Only authored, independently rendered clips are accepted; an alias is not a motion. */
class ClipManifest(
    val width: Int,
    val height: Int,
    val initialClip: String,
    clips: List<FrameClip>,
    requiredClipIds: Set<String> = REQUIRED_ACTIONS,
) {
    val clips: Map<String, FrameClip> = clips.associateBy { it.id }
    val frameBytes: Int get() = width * height * 4

    init {
        require(width in 16..1024 && height in 16..1024) { "角色帧尺寸无效" }
        require(clips.isNotEmpty() && clips.size <= 256 && this.clips.size == clips.size) { "动作 ID 为空、重复或数量无效" }
        require(this.clips.containsKey(initialClip) && this.clips.getValue(initialClip).loop) { "初始姿态必须是实际存在的循环动作" }
        require(this.clips.keys.containsAll(requiredClipIds)) { "动作资源不完整：${(requiredClipIds - this.clips.keys).sorted().joinToString()}" }
        val paths = HashSet<String>()
        for (clip in clips) {
            require(clip.id.matches(Regex("[a-z][a-z0-9_]{0,63}"))) { "动作 ID 格式无效" }
            require(clip.fps in setOf(12, 24)) { "${clip.id} 必须声明实际采样的 12 或 24fps" }
            require(clip.frames.size in 2..2400) { "${clip.id} 必须提供完整动画帧，不能以单张图片冒充" }
            require(clip.entryPose.isNotBlank() && clip.exitPose.isNotBlank()) { "${clip.id} 缺少姿态边界" }
            require(!clip.loop || clip.entryPose == clip.exitPose) { "循环动作 ${clip.id} 首尾姿态不一致" }
            require(!(clip.loop && clip.interrupt == ClipInterrupt.NEVER)) { "循环动作 ${clip.id} 不可永久禁止退出" }
            require(clip.interrupt != ClipInterrupt.IMMEDIATE || (clip.entryPose == clip.exitPose && clip.safeExitFrames.isNotEmpty())) { "${clip.id} 的立即中断必须声明真实安全出口帧" }
            require(clip.safeExitFrames.all { it in clip.frames.indices }) { "${clip.id} 安全出口帧越界" }
            require(clip.transitions.isNotEmpty() && clip.transitions.distinct().size == clip.transitions.size) { "${clip.id} 没有有效回程或有重复转移" }
            for (path in clip.frames) {
                require(path.matches(Regex("pet3d/${clip.id}/[0-9]+\\.(png|webp)"))) { "${clip.id} 存在无效帧路径" }
                require(paths.add(path)) { "不同动作或时间帧不能引用同一个文件：$path" }
            }
            for (next in clip.transitions) {
                val target = this.clips[next] ?: throw IllegalArgumentException("${clip.id} 转移指向不存在动作 $next")
                require(clip.exitPose == target.entryPose) { "${clip.id} → $next 的接触姿态不连续" }
            }
        }
        for (id in this.clips.keys) {
            require(route(initialClip, id) != null) { "动作 $id 无法从初始姿态到达" }
            require(route(id, initialClip) != null) { "动作 $id 缺少返回初始姿态的合法路径" }
        }
    }

    /** Returns the shortest authored route excluding [from], or null when unavailable. */
    fun route(from: String, to: String): List<String>? {
        if (from !in clips || to !in clips) return null
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
        const val ASSET_PATH = "pet3d/manifest.json"
        val REQUIRED_ACTIONS = setOf(
            "stand", "sit", "sit_idle", "stand_up", "lie", "lie_idle", "lie_to_stand", "side_lie", "curl", "sleep", "fall_asleep", "wake", "stretch", "yawn", "tail_wag", "breathe", "blink", "look_left", "look_right", "tilt", "scratch", "lick_paw", "groom", "walk", "trot", "run", "walk_start", "walk_stop", "hop", "jump", "turn", "backstep", "pause", "slide_correct", "land", "rebound", "happy", "tongue", "excited", "cuddle", "curious", "sleepy", "sad", "shy", "expect", "relax", "startled", "soothe", "focus", "pet_head", "nuzzle", "follow", "wave", "belly_up", "request_pet", "treat", "play_ball", "seek", "call",
        )
        fun parse(json: String, requiredClipIds: Set<String> = REQUIRED_ACTIONS): ClipManifest {
            require(json.length <= 2_000_000) { "角色清单过大" }
            val root = JSONObject(json)
            require(root.getInt("version") == 1) { "不支持的角色清单版本" }
            val array = root.getJSONArray("clips")
            val clips = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val frames = item.getJSONArray("frames")
                val transitions = item.getJSONArray("transitions")
                val safe = item.optJSONArray("safeExitFrames")
                FrameClip(item.getString("id"), (0 until frames.length()).map(frames::getString), item.getInt("fps"),
                    item.getString("entryPose"), item.getString("exitPose"), item.getBoolean("loop"),
                    when (item.getString("interrupt")) {
                        "immediate" -> ClipInterrupt.IMMEDIATE
                        "boundary" -> ClipInterrupt.BOUNDARY
                        "never" -> ClipInterrupt.NEVER
                        else -> throw IllegalArgumentException("未知动作中断策略")
                    }, (0 until transitions.length()).map(transitions::getString),
                    if (safe == null) emptySet() else (0 until safe.length()).map(safe::getInt).toSet())
            }
            return ClipManifest(root.getInt("width"), root.getInt("height"), root.getString("initialClip"), clips, requiredClipIds)
        }
    }
}
