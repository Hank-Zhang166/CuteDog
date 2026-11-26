package com.cutedog.app.chat

import android.content.Context

/** Only encrypted API-key ciphertext is persisted; the AES key never leaves Android Keystore. */
class AiSettings private constructor(context: Context, preferenceName: String, keyAlias: String) {
    constructor(context: Context) : this(context, "ai_settings", KEY_ALIAS)
    internal constructor(context: Context, testNamespace: String) : this(context, "ai_settings_$testNamespace", "cutedog.ai.$testNamespace")
    private val prefs = context.applicationContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val credential = StoredCredential(keyAlias)

    var endpoint: String
        get() = prefs.getString("endpoint", "https://api.deepseek.com")!!
        set(value) {
            ChatPolicy.completionUrl(value)
            prefs.edit().putString("endpoint", value.trim().trimEnd('/')).apply()
        }
    var model: String
        get() = prefs.getString("model", "deepseek-flash")!!
        set(value) {
            val clean = value.trim()
            require(clean.isNotEmpty() && clean.length <= 128 && clean.none { it.isISOControl() }) {
                "请输入有效的模型名称"
            }
            prefs.edit().putString("model", clean).apply()
        }
    var memoryEnabled: Boolean
        get() = prefs.getBoolean("memory_enabled", false)
        set(value) { prefs.edit().putBoolean("memory_enabled", value).apply() }

    var apiKey: String
        get() {
            val encoded = prefs.getString("api_key_ciphertext", null) ?: return ""
            return try {
                credential.decrypt(encoded)
            } catch (_: Exception) {
                throw IllegalStateException("密钥无法解密，请在模型设置中重新输入 API Key")
            }
        }
        set(value) {
            val clean = value.trim()
            require(clean.length <= 4096 && clean.none { it.isISOControl() }) { "API Key 格式不正确" }
            if (clean.isEmpty()) {
                prefs.edit().remove("api_key_ciphertext").apply()
                return
            }
            prefs.edit().putString("api_key_ciphertext", credential.encrypt(clean)).apply()
        }

    /** Useful to show configuration status without decrypting or displaying the key. */
    val hasApiKey: Boolean get() = prefs.contains("api_key_ciphertext")

    private companion object {
        const val KEY_ALIAS = "cutedog.ai.credentials.v1"
    }
}
