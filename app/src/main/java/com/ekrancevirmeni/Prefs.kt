package com.ekrancevirmeni

import android.content.Context
import android.content.SharedPreferences

/** Uygulama ayarları (telefonda kalıcı olarak saklanır). */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("ayarlar", Context.MODE_PRIVATE)

    var engine: String
        get() = sp.getString("engine", ENGINE_GEMINI) ?: ENGINE_GEMINI
        set(v) = sp.edit().putString("engine", v).apply()

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v.trim()).apply()

    var model: String
        get() = (sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL).ifBlank { DEFAULT_MODEL }
        set(v) = sp.edit().putString("model", v.trim()).apply()

    /** Ekranın kaç milisaniyede bir okunacağı. */
    var intervalMs: Int
        get() = sp.getInt("interval", 500).coerceIn(MIN_INTERVAL, MAX_INTERVAL)
        set(v) = sp.edit().putInt("interval", v.coerceIn(MIN_INTERVAL, MAX_INTERVAL)).apply()

    var textSizeSp: Int
        get() = sp.getInt("text_size", 20).coerceIn(12, 48)
        set(v) = sp.edit().putInt("text_size", v.coerceIn(12, 48)).apply()

    /** Metin iki okumada aynı kalınca çevir (yazı efektli altyazılarda gereksiz istekleri önler). */
    var waitStable: Boolean
        get() = sp.getBoolean("wait_stable", true)
        set(v) = sp.edit().putBoolean("wait_stable", v).apply()

    var showOriginal: Boolean
        get() = sp.getBoolean("show_original", false)
        set(v) = sp.edit().putBoolean("show_original", v).apply()

    /** Gemini hata verirse cihaz içi çeviriye geç. */
    var useFallback: Boolean
        get() = sp.getBoolean("use_fallback", true)
        set(v) = sp.edit().putBoolean("use_fallback", v).apply()

    // Seçim çerçevesi, ekran boyutuna oranla saklanır (ekran dönünce de doğru kalsın diye).
    var regionLeft: Float
        get() = sp.getFloat("r_left", 0.15f)
        set(v) = sp.edit().putFloat("r_left", v).apply()
    var regionTop: Float
        get() = sp.getFloat("r_top", 0.66f)
        set(v) = sp.edit().putFloat("r_top", v).apply()
    var regionWidth: Float
        get() = sp.getFloat("r_width", 0.70f)
        set(v) = sp.edit().putFloat("r_width", v).apply()
    var regionHeight: Float
        get() = sp.getFloat("r_height", 0.16f)
        set(v) = sp.edit().putFloat("r_height", v).apply()

    var panelX: Int
        get() = sp.getInt("panel_x", -1)
        set(v) = sp.edit().putInt("panel_x", v).apply()
    var panelY: Int
        get() = sp.getInt("panel_y", -1)
        set(v) = sp.edit().putInt("panel_y", v).apply()

    companion object {
        const val ENGINE_GEMINI = "gemini"
        const val ENGINE_DEVICE = "device"
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
        const val MIN_INTERVAL = 250
        const val MAX_INTERVAL = 3000
    }
}
