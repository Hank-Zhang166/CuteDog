package com.cutedog.app.voice.doubao

import android.content.Context
import com.cutedog.app.chat.StoredCredential

internal class DoubaoSettings(context: Context, namespace: String? = null) {
    private val preferenceName = "doubao_settings" + (namespace?.let { "_$it" } ?: "")
    private val prefs = context.applicationContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val voiceVault = StoredCredential("cutedog.$preferenceName.voice.v1")
    private val searchVault = StoredCredential("cutedog.$preferenceName.search.v1")

    val hasVoiceKey: Boolean get() = prefs.contains(VOICE_KEY)
    val hasSearchKey: Boolean get() = prefs.contains(SEARCH_KEY)
    val voiceApiKey: String get() = readKey(VOICE_KEY, voiceVault, "豆包实时语音")
    val searchApiKey: String get() = readKey(SEARCH_KEY, searchVault, "豆包搜索")

    var microphoneUploadAccepted: Boolean
        get() = hasVoiceKey && prefs.getBoolean(MICROPHONE_UPLOAD, false)
        set(value) { prefs.edit().putBoolean(MICROPHONE_UPLOAD, value).apply() }

    /** An empty field retains its previous key. Encrypt both before applying any changes. */
    fun save(voiceApiKey: String, searchApiKey: String) {
        val voice = cleanKey(voiceApiKey, "豆包实时语音")
        val search = cleanKey(searchApiKey, "豆包搜索")
        require(voice.isNotEmpty() || hasVoiceKey) { "请填写豆包实时语音 API Key。" }
        val encryptedVoice = voice.takeIf(String::isNotEmpty)?.let(voiceVault::encrypt)
        val encryptedSearch = search.takeIf(String::isNotEmpty)?.let(searchVault::encrypt)
        prefs.edit().apply {
            if (encryptedVoice != null) {
                putString(VOICE_KEY, encryptedVoice)
                remove(MICROPHONE_UPLOAD)
            }
            if (encryptedSearch != null) putString(SEARCH_KEY, encryptedSearch)
        }.apply()
    }

    fun clear() {
        prefs.edit().remove(VOICE_KEY).remove(SEARCH_KEY).remove(MICROPHONE_UPLOAD).apply()
    }

    private fun readKey(preference: String, vault: StoredCredential, label: String): String {
        val encoded = prefs.getString(preference, null) ?: return ""
        return try {
            vault.decrypt(encoded)
        } catch (_: Exception) {
            throw IllegalStateException("无法读取${label}密钥，请在设置中重新填写。")
        }
    }

    private fun cleanKey(key: String, label: String): String = key.trim().also { value ->
        require(value.length <= 512 && value.all { it in '!'..'~' }) { "${label} API Key 格式不正确。" }
    }

    private companion object {
        const val VOICE_KEY = "voice_key_ciphertext"
        const val SEARCH_KEY = "search_key_ciphertext"
        const val MICROPHONE_UPLOAD = "microphone_upload_accepted"
    }
}
