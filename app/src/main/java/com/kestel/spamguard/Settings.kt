package com.kestel.spamguard

import android.content.Context

/**
 * Sauvegarde et lecture des réglages de l'app.
 * On utilise SharedPreferences : un petit fichier local, privé à l'app,
 * parfait pour stocker la clé API et les préférences.
 */
object Settings {

    private const val PREFS = "spamguard_settings"

    const val DEFAULT_BASE_URL = "https://api.mistral.ai/v1"
    const val DEFAULT_MODEL = "mistral-small-latest"

    /** Toute la configuration en un seul objet, facile à passer aux autres classes. */
    data class Config(
        val apiKey: String,
        val baseUrl: String,
        val model: String,
        val callsEnabled: Boolean,
        val smsEnabled: Boolean
    )

    fun read(context: Context): Config {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            apiKey = p.getString("api_key", "") ?: "",
            baseUrl = p.getString("base_url", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL,
            model = p.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL,
            callsEnabled = p.getBoolean("calls_enabled", true),
            smsEnabled = p.getBoolean("sms_enabled", true)
        )
    }

    fun save(context: Context, config: Config) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("api_key", config.apiKey.trim())
            .putString("base_url", config.baseUrl.trim())
            .putString("model", config.model.trim())
            .putBoolean("calls_enabled", config.callsEnabled)
            .putBoolean("sms_enabled", config.smsEnabled)
            .apply()
    }
}
