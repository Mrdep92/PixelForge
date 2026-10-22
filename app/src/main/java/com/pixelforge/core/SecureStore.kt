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

    /** Kosong = pilih otomatis dari daftar model gratis. Nilai lama "openrouter/auto" dianggap kosong. */
    var model: String
        get() = (prefs.getString("model", "") ?: "").trim().let { if (it == "openrouter/auto" || it == "auto") "" else it }
        set(v) = prefs.edit().putString("model", v.trim()).apply()

    /** Default mati: hanya model :free yang dipakai di OpenRouter. */
    var allowPaid: Boolean
        get() = prefs.getBoolean("allow_paid", false)
        set(v) = prefs.edit().putBoolean("allow_paid", v).apply()

    /** Pernah membeli kredit OpenRouter (≥ $10): jatah model :free naik dari 50 menjadi 1.000 permintaan per hari. */
    var orCredits: Boolean
        get() = prefs.getBoolean("or_credits", false)
        set(v) = prefs.edit().putBoolean("or_credits", v).apply()

    /** Penyedia tambahan di kolam (JSON). Key ikut tersimpan terenkripsi. */
    var extraProvidersJson: String
        get() = prefs.getString("extra_providers", "[]") ?: "[]"
        set(v) = prefs.edit().putString("extra_providers", v).apply()

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

    /** OmniRoute dianggap aman untuk project privat (default mati). */
    var omniTrusted: Boolean
        get() = prefs.getBoolean("omni_trusted", false)
        set(v) = prefs.edit().putBoolean("omni_trusted", v).apply()

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
}
