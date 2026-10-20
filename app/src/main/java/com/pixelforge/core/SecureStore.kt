package com.pixelforge.core

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** API key & setting disimpan terenkripsi (Android Keystore). */
class SecureStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "pixelforge_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(v) = prefs.edit().putString("api_key", v.trim()).apply()

    var model: String
        get() = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = prefs.edit().putString("model", v.trim().ifBlank { DEFAULT_MODEL }).apply()

    companion object {
        const val DEFAULT_MODEL = "openrouter/auto"
    }
}
