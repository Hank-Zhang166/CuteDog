package com.cutedog.app.pet

import android.content.Context
import android.content.Intent

/** Package-scoped messages to an already running overlay; never starts a background service. */
object PetEvents {
    const val MODEL_ACTION = "com.cutedog.app.MODEL_PET_ACTION"
    private val allowed get() = com.cutedog.app.chat.ChatPolicy.allowedIntents
    fun action(intent: Intent): String? = intent.getStringExtra("intent")?.takeIf { it in allowed }
    fun modelAction(context: Context, intent: String) {
        if (intent in allowed) context.sendBroadcast(Intent(MODEL_ACTION).setPackage(context.packageName).putExtra("intent", intent))
    }
}
