package com.cutedog.app.pet

import android.content.Context
import android.graphics.RectF
import org.json.JSONArray

class PetSettings(context: Context) {
    private val p = context.getSharedPreferences("pet", Context.MODE_PRIVATE)
    var name: String
        get() = p.getString("name", "糯米")!!
        set(v) { p.edit().putString("name", v.trim().take(20).ifEmpty { "糯米" }).apply() }
    var size: Int
        get() = p.getInt("size", 144)
        set(v) { p.edit().putInt("size", v.coerceIn(96, 220)).apply() }
    var opacity: Float
        get() = p.getFloat("opacity", 1f)
        set(v) { p.edit().putFloat("opacity", v.coerceIn(.3f, 1f)).apply() }
    var activity: String
        get() = p.getString("activity", "balanced")!!
        set(v) { p.edit().putString("activity", v).apply() }
    var locked: Boolean
        get() = p.getBoolean("locked", false)
        set(v) { p.edit().putBoolean("locked", v).apply() }
    var boot: Boolean
        get() = p.getBoolean("boot", false)
        set(v) { p.edit().putBoolean("boot", v).apply() }
    var reducedMotion: Boolean
        get() = p.getBoolean("reducedMotion", false)
        set(v) { p.edit().putBoolean("reducedMotion", v).apply() }
    var haptics: Boolean
        get() = p.getBoolean("haptics", false)
        set(v) { p.edit().putBoolean("haptics", v).apply() }
    var lowPower: Boolean
        get() = p.getBoolean("lowPower", false)
        set(v) { p.edit().putBoolean("lowPower", v).apply() }
    var xFraction: Float
        get() = p.getFloat("x", .85f)
        set(v) { p.edit().putFloat("x", v.coerceIn(0f, 1f)).apply() }
    var yFraction: Float
        get() = p.getFloat("y", .72f)
        set(v) { p.edit().putFloat("y", v.coerceIn(0f, 1f)).apply() }
    var tapAction: String
        get() = p.getString("tapAction", "menu")!!
        set(v) { require(v in listOf("menu", "chat", "wave")); p.edit().putString("tapAction", v).apply() }
    var doubleTapAction: String
        get() = p.getString("doubleTapAction", "cuddle")!!
        set(v) { require(v in listOf("cuddle", "jump", "wave", "none")); p.edit().putString("doubleTapAction", v).apply() }
    var pettingEnabled: Boolean
        get() = p.getBoolean("pettingEnabled", true)
        set(v) { p.edit().putBoolean("pettingEnabled", v).apply() }
    var dragHoldMs: Int
        get() = p.getInt("dragHoldMs", 450)
        set(v) { p.edit().putInt("dragHoldMs", v.coerceIn(300, 900)).apply() }
    var defaultX: Float
        get() = p.getFloat("defaultX", .85f)
        set(v) { p.edit().putFloat("defaultX", v.coerceIn(0f, 1f)).apply() }
    var defaultY: Float
        get() = p.getFloat("defaultY", .72f)
        set(v) { p.edit().putFloat("defaultY", v.coerceIn(0f, 1f)).apply() }
    var confinement: String
        get() = p.getString("confinement", "screen")!!
        set(v) { require(v in listOf("screen", "region")); p.edit().putString("confinement", v).apply() }
    var activityRect: RectF
        get() = runCatching { decodeRect(JSONArray(p.getString("activityRect", "[0,0.15,1,0.9]"))) }.getOrDefault(RectF(0f, .15f, 1f, .9f))
        set(v) { validateRect(v); p.edit().putString("activityRect", encodeRect(v).toString()).apply() }
    var forbiddenRects: List<RectF>
        get() = runCatching { val a = JSONArray(p.getString("forbiddenRects", "[]")); (0 until a.length().coerceAtMost(8)).map { decodeRect(a.getJSONArray(it)) } }.getOrDefault(emptyList())
        set(v) { require(v.size <= 8); v.forEach(::validateRect); p.edit().putString("forbiddenRects", JSONArray().apply { v.forEach { put(encodeRect(it)) } }.toString()).apply() }
    var soundEnabled: Boolean
        get() = p.getBoolean("soundEnabled", false)
        set(v) { p.edit().putBoolean("soundEnabled", v).apply() }
    var soundVolume: Float
        get() = p.getFloat("soundVolume", .2f)
        set(v) { p.edit().putFloat("soundVolume", v.coerceIn(0f, .7f)).apply() }
    private fun encodeRect(r: RectF) = JSONArray().put(r.left.toDouble()).put(r.top.toDouble()).put(r.right.toDouble()).put(r.bottom.toDouble())
    private fun decodeRect(a: JSONArray) = RectF(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat()).also(::validateRect)
    private fun validateRect(r: RectF) { require(r.left.isFinite() && r.top.isFinite() && r.right.isFinite() && r.bottom.isFinite() && r.left >= 0 && r.top >= 0 && r.right <= 1 && r.bottom <= 1 && r.width() >= .02f && r.height() >= .02f) }
}
