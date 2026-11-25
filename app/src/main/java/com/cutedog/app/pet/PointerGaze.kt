package com.cutedog.app.pet

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tanh

/** Project the same single painted head surface as the authored HeadTail model, around the neck. */
internal class PointerGaze {
    val divisions = 32
    private val stride = divisions + 1
    val vertices = FloatArray(stride * stride * 2)

    private data class SurfacePoint(val x: Float, val y: Float, val depth: Float, val neckWeight: Float)
    private data class Feature(val x: Float, val y: Float, val rx: Float, val ry: Float, val depth: Float)
    // The feature depths and neck pivot use the 512px drawing coordinates from performance.py / Face.
    private val features = listOf(
        Feature(89f, 170f, 21f, 17f, 56f), Feature(133f, 155f, 17f, 13f, 50f),
        Feature(108f, 150f, 13f, 12f, 46f), Feature(104f, 194f, 24f, 18f, 54f),
        Feature(138f, 91f, 16f, 17f, 29f), Feature(180f, 94f, 17f, 19f, 44f),
    )
    private val surface = Array(stride * stride) { index ->
        val x = (index % stride) * 512f / divisions
        val y = (index / stride) * 512f / divisions
        var depth = 39f + 8f * tanh((x - 125f) / 35f) - .11f * (x - 150f) + .08f * (y - 145f)
        for (feature in features) {
            val radius = hypot((x - feature.x) / feature.rx, (y - feature.y) / feature.ry)
            val weight = 1f - smooth((radius - .55f) / 1.10f)
            depth += (feature.depth - depth) * weight
        }
        SurfacePoint(x, y, depth, smooth((272f - y) / 59f) * smooth((270f - x) / 68f))
    }

    private var targetYaw = 0f
    private var targetPitch = 0f
    private var yaw = 0f
    private var pitch = 0f
    val active get() = yaw != 0f || pitch != 0f

    fun target(u: Float, v: Float) {
        if (!u.isFinite() || !v.isFinite()) return
        targetYaw = (u.coerceIn(0f, 1f) - .5f) * .42f
        targetPitch = (v.coerceIn(0f, 1f) - .5f) * .36f
    }
    fun release() { targetYaw = 0f; targetPitch = 0f }
    fun reset() { release(); yaw = 0f; pitch = 0f }

    fun step(seconds: Float): Boolean {
        if (yaw == targetYaw && pitch == targetPitch) return false
        val gain = 1f - exp(-12f * seconds.coerceIn(0f, .25f))
        yaw += (targetYaw - yaw) * gain
        pitch += (targetPitch - pitch) * gain
        if (abs(targetYaw - yaw) < .00015f) yaw = targetYaw
        if (abs(targetPitch - pitch) < .00015f) pitch = targetPitch
        return true
    }

    fun position(left: Float, top: Float, side: Float, mirrored: Boolean = false) {
        val angle = if (mirrored) -yaw else yaw
        val cy = cos(angle); val sy = sin(angle)
        val cp = cos(pitch); val sp = sin(pitch)
        val cr = cos(.16f * angle); val sr = sin(.16f * angle)
        val scale = side / 512f
        for ((i, point) in surface.withIndex()) {
            val dx = point.x - 172f
            val dy = point.y - 211f
            val hx = cy * dx + sy * point.depth
            val hz = -sy * dx + cy * point.depth
            val hy = cp * dy + sp * hz
            val offsetX = (cr * hx - sr * hy - dx) * point.neckWeight
            val offsetY = (sr * hx + cr * hy - dy) * point.neckWeight
            val column = i % stride
            val index = if (mirrored) i - column + divisions - column else i
            val px = if (mirrored) 512f - point.x - offsetX else point.x + offsetX
            vertices[2 * index] = left + scale * px
            vertices[2 * index + 1] = top + scale * (point.y + offsetY)
        }
    }

    private fun smooth(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
