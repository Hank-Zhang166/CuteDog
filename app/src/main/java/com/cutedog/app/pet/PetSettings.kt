package com.cutedog.app.pet

import android.content.Context

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
}
