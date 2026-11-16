package com.kestel.spamguard

import java.util.concurrent.ConcurrentHashMap

/**
 * Petit cache en mémoire : si l'IA a déjà jugé un numéro il y a moins de 24 h,
 * on ne la redemande pas. Ça économise des appels API et ça rend le filtrage
 * instantané pour les récidivistes (les spammeurs rappellent souvent !).
 */
object VerdictCache {

    private const val TTL_MS = 24 * 60 * 60 * 1000L // 24 heures

    private data class Entry(val spam: Boolean, val at: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    /** Renvoie le verdict en cache pour ce numéro, ou null si inconnu/expiré. */
    fun get(number: String): Boolean? {
        val entry = cache[key(number)] ?: return null
        if (System.currentTimeMillis() - entry.at > TTL_MS) {
            cache.remove(key(number))
            return null
        }
        return entry.spam
    }

    fun put(number: String, verdict: SpamVerdict) {
        cache[key(number)] = Entry(verdict.spam, System.currentTimeMillis())
    }

    /**
     * Normalise le numéro : on ne garde que les 9 derniers chiffres,
     * comme ça "+33 6 12 34 56 78" et "0612345678" donnent la même clé.
     */
    private fun key(number: String): String =
        number.filter { it.isDigit() }.takeLast(9)
}
