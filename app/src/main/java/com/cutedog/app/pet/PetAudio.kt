package com.cutedog.app.pet

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.PowerManager
import android.os.SystemClock

/** Optional quiet foley; silent by default, never competes with music, calls or DND. */
class PetAudio(private val context: Context) {
    private val settings = PetSettings(context)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private var pool: SoundPool? = null
    private val sounds = mutableMapOf<String, Int>()
    private val ready = mutableSetOf<Int>()
    private var last = -10_000L
    fun play(name: String) {
        if (!settings.soundEnabled || settings.soundVolume <= 0 || !power.isInteractive || audio.isMusicActive || audio.mode != AudioManager.MODE_NORMAL || audio.ringerMode != AudioManager.RINGER_MODE_NORMAL || notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
        val now = SystemClock.elapsedRealtime()
        if (now - last < 4000) return
        if (pool == null) {
            pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build().also { p ->
                p.setOnLoadCompleteListener { _, id, status -> if (status == 0) ready.add(id) }
                listOf("paw", "breath", "ball").forEach { clip -> context.assets.openFd("audio/$clip.wav").use { sounds[clip] = p.load(it, 1) } }
            }
        }
        val id = sounds[name] ?: return
        if (id !in ready) return
        last = now
        pool?.play(id, settings.soundVolume, settings.soundVolume, 1, 0, 1f)
    }
    fun release() { pool?.release(); pool = null; sounds.clear(); ready.clear() }
}
