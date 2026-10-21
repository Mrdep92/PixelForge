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

    // OmniRoute / endpoint kompatibel OpenAI lainnya
    var omniUrl: String
        get() = prefs.getString("omni_url", "") ?: ""
        set(v) = prefs.edit().putString("omni_url", v.trim()).apply()
    var omniKey: String
        get() = prefs.getString("omni_key", "") ?: ""
        set(v) = prefs.edit().putString("omni_key", v.trim()).apply()
    var omniModel: String
        get() = prefs.getString("omni_model", "") ?: ""
        set(v) = prefs.edit().putString("omni_model", v.trim()).apply()
    var omniFirst: Boolean
        get() = prefs.getBoolean("omni_first", false)
        set(v) = prefs.edit().putBoolean("omni_first", v).apply()

    // GitHub sebagai wadah kode (commit + build)
    var ghToken: String
        get() = prefs.getString("gh_token", "") ?: ""
        set(v) = prefs.edit().putString("gh_token", v.trim()).apply()
    var ghRepo: String
        get() = prefs.getString("gh_repo", "") ?: ""
        set(v) = prefs.edit().putString("gh_repo", v.trim()).apply()
    var ghBranch: String
        get() = prefs.getString("gh_branch", "main") ?: "main"
        set(v) = prefs.edit().putString("gh_branch", v.trim().ifBlank { "main" }).apply()

    companion object {
        const val DEFAULT_MODEL = "openrouter/auto"
    }
}
