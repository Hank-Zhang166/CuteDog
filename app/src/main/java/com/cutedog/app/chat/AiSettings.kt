package com.cutedog.app.chat

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only encrypted API-key ciphertext is persisted; the AES key never leaves Android Keystore. */
class AiSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)

    var endpoint: String
        get() = prefs.getString("endpoint", "https://api.openai.com/v1")!!
        set(value) {
            ChatPolicy.completionUrl(value)
            prefs.edit().putString("endpoint", value.trim().trimEnd('/')).apply()
        }
    var model: String
        get() = prefs.getString("model", "gpt-4.1-mini")!!
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
                val bytes = Base64.decode(encoded, Base64.NO_WRAP)
                require(bytes.size > 12)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
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
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val bytes = cipher.iv + cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
            prefs.edit().putString("api_key_ciphertext", Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
        }

    /** Useful to show configuration status without decrypting or displaying the key. */
    val hasApiKey: Boolean get() = prefs.contains("api_key_ciphertext")

    private fun secretKey(): SecretKey = synchronized(KEY_LOCK) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore",
        ).apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "cutedog.ai.credentials.v1"
        val KEY_LOCK = Any()
    }
}
