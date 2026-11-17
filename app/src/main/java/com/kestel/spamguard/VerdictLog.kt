package com.kestel.spamguard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * LE JOURNAL : chaque appel ou SMS analysé laisse une trace
 * (qui, quand, verdict, pourquoi, par quelle couche).
 *
 * C'est ce qui s'affiche dans l'écran "Journal & numéros", et c'est
 * là que Florian peut signaler un numéro au 33700 ou le bloquer à vie.
 *
 * On garde les 100 derniers événements, sous forme de JSON dans les
 * préférences. Pas besoin d'une vraie base de données pour ça.
 */
object VerdictLog {

    private const val PREFS = "spamguard_log"
    private const val MAX_ENTRIES = 100

    /** Un événement du journal. */
    data class Event(
        val timestamp: Long,     // quand (timestamp ms)
        val number: String,      // numéro affiché, ex "+33612345678"
        val kind: String,        // "APPEL" ou "SMS"
        val source: String,      // quelle couche a décidé (IA, ARCEP, liste noire...)
        val spam: Boolean,       // verdict : spam ou pas
        val reason: String,      // explication courte
        val name: String         // nom de l'organisme si la base le connaît
    )

    /** Les sources possibles, dans l'ordre du pipeline de filtrage. */
    object Source {
        const val LISTE_BLANCHE = "Liste blanche"
        const val LISTE_NOIRE = "Liste noire"
        const val ARCEP = "Démarchage (ARCEP)"
        const val BASE = "Base organismes"
        const val CONTACTS = "Contact"
        const val CACHE = "Cache 24h"
        const val IA = "IA"
        const val INSISTANT = "Insistant (sécurité)"
        const val DESACTIVE = "Filtrage off"
    }

    /** Ajoute un événement en tête de journal. */
    fun add(context: Context, event: Event) {
        val json = JSONObject().apply {
            put("ts", event.timestamp)
            put("number", event.number)
            put("kind", event.kind)
            put("source", event.source)
            put("spam", event.spam)
            put("reason", event.reason)
            put("name", event.name)
        }

        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = JSONArray(p.getString("log", "[]"))
        val newArray = JSONArray()
        newArray.put(json) // le plus récent en premier
        // On recopie l'ancien journal sans dépasser la taille maximale.
        val limit = minOf(all.length(), MAX_ENTRIES - 1)
        for (i in 0 until limit) {
            newArray.put(all.getJSONObject(i))
        }
        p.edit().putString("log", newArray.toString()).apply()
    }

    /** Tous les événements, du plus récent au plus ancien. */
    fun all(context: Context): List<Event> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = JSONArray(p.getString("log", "[]"))
        val events = mutableListOf<Event>()
        for (i in 0 until all.length()) {
            val o = all.getJSONObject(i)
            events.add(
                Event(
                    timestamp = o.getLong("ts"),
                    number = o.getString("number"),
                    kind = o.getString("kind"),
                    source = o.getString("source"),
                    spam = o.getBoolean("spam"),
                    reason = o.optString("reason", ""),
                    name = o.optString("name", "")
                )
            )
        }
        return events
    }

    /** Efface tout le journal. */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("log", "[]").apply()
    }
}
