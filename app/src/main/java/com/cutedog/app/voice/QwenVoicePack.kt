package com.cutedog.app.voice

import android.content.Context

/** These are signed, uncompressed APK assets. The native engine maps them in place. */
internal object QwenVoicePack {
    const val TALKER = "a-voice/talker.gguf"
    const val CODEC = "a-voice/codec.gguf"

    fun ready(context: Context): Boolean = runCatching {
        listOf(TALKER, CODEC).all { path ->
            context.assets.openFd(path).use { it.length > 0 }
        }
    }.getOrDefault(false)
}
