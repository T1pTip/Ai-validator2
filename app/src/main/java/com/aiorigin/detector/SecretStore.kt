package com.aiorigin.detector

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the Sightengine credentials encrypted with an AES-GCM key held in the Android Keystore
 * (the key never leaves secure hardware). Backups are disabled for the app.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    fun save(credentials: SightengineClient.Credentials) {
        prefs.edit()
            .putString("se_user", encrypt(credentials.user.trim()))
            .putString("se_secret", encrypt(credentials.secret.trim()))
            .apply()
    }

    fun load(): SightengineClient.Credentials? = try {
        val user = prefs.getString("se_user", null)?.let(::decrypt)
        val secret = prefs.getString("se_secret", null)?.let(::decrypt)
        if (user.isNullOrBlank() || secret.isNullOrBlank()) null else SightengineClient.Credentials(user, secret)
    } catch (_: Exception) {
        null
    }

    /** Removes the Sightengine keys only. */
    fun clear() = prefs.edit().remove("se_user").remove("se_secret").apply()

    fun saveGemini(apiKey: String) = prefs.edit().putString("gemini_key", encrypt(apiKey.trim())).apply()

    fun loadGemini(): String? = try {
        prefs.getString("gemini_key", null)?.let(::decrypt)?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    fun clearGemini() = prefs.edit().remove("gemini_key").apply()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val data = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        return String(cipher.doFinal(data, 12, data.size - 12))
    }

    private companion object { const val ALIAS = "sightengine-credentials" }
}
