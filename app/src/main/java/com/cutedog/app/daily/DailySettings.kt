package com.cutedog.app.daily

import android.content.Context

/** Quiet start == quiet end means no quiet interval. */
class DailySettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("daily_settings", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }
    var hour: Int
        get() = prefs.getInt("hour", 9)
        set(value) { require(value in 0..23); prefs.edit().putInt("hour", value).apply() }
    var quietStart: Int
        get() = prefs.getInt("quiet_start", 22)
        set(value) { require(value in 0..23); prefs.edit().putInt("quiet_start", value).apply() }
    var quietEnd: Int
        get() = prefs.getInt("quiet_end", 8)
        set(value) { require(value in 0..23); prefs.edit().putInt("quiet_end", value).apply() }
}
