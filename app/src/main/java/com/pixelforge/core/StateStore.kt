package com.pixelforge.core

import android.content.Context
import com.google.gson.Gson

/** Simpan status kerja (rencana, agent yang belum selesai, progres CI) supaya bisa dilanjutkan setelah limit / app ditutup. */
class StateStore(context: Context) {
    private val prefs = context.getSharedPreferences("pixelforge_state", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun save(key: String, value: Any?) {
        val e = prefs.edit()
        if (value == null) e.remove(key) else e.putString(key, gson.toJson(value))
        e.apply()
    }

    fun saveRaw(key: String, value: String) = prefs.edit().putString(key, value).apply()
    fun loadRaw(key: String): String? = prefs.getString(key, null)

    fun <T> load(key: String, type: Class<T>): T? = runCatching {
        prefs.getString(key, null)?.let { gson.fromJson(it, type) }
    }.getOrNull()
}
