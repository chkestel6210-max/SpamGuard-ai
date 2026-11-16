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
        val smsEnabled: Boolean,
        // Nouveautés v0.2 :
        val blockDemarchage: Boolean,   // rejeter les préfixes ARCEP du démarchage
        val autoBlacklist: Boolean,     // mémoriser à vie les numéros jugés spam par l'IA
        val weeklyDbUpdate: Boolean     // mise à jour auto hebdo de la base des organismes
    )

    fun read(context: Context): Config {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            apiKey = p.getString("api_key", "") ?: "",
            baseUrl = p.getString("base_url", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL,
            model = p.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL,
            callsEnabled = p.getBoolean("calls_enabled", true),
            smsEnabled = p.getBoolean("sms_enabled", true),
            blockDemarchage = p.getBoolean("block_demarchage", true),
            autoBlacklist = p.getBoolean("auto_blacklist", true),
            weeklyDbUpdate = p.getBoolean("weekly_db_update", false)
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
            .putBoolean("block_demarchage", config.blockDemarchage)
            .putBoolean("auto_blacklist", config.autoBlacklist)
            .putBoolean("weekly_db_update", config.weeklyDbUpdate)
            .apply()
    }
}
