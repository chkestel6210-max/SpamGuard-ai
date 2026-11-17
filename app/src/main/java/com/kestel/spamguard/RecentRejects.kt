package com.kestel.spamguard

import android.content.Context

/**
 * LE FILET DE SÉCURITÉ "APPEL INSISTANT" (anti-faux-positif, v0.2.5).
 *
 * Le scénario qui fait peur : SpamGuard rejette un appel, et c'était
 * en fait une vraie personne pressée (hôpital, école, banquier urgent).
 * Une IA peut se tromper ; un humain dans l'urgence, lui, RAPPELLE.
 *
 * Règle simple : si un numéro rejeté rappelle MOINS DE 10 MINUTES
 * après son rejet, on le laisse passer ET on prévient l'utilisateur.
 * Un spammeur ne perd rien à attendre 10 minutes ; un proche avec
 * une urgence, si.
 *
 * On mémorise juste "clé du numéro -> timestamp du dernier rejet"
 * dans les préférences : deux lignes de stockage, aucune base.
 */
object RecentRejects {

    private const val PREFS = "spamguard_recent_rejects"

    /** La fenêtre de grâce : 10 minutes après un rejet. */
    private const val WINDOW_MS = 10 * 60 * 1000L

    /**
     * Mémorise qu'on vient de rejeter ce numéro (timestamp actuel).
     * Appelé à CHAQUE rejet effectif, toutes couches confondues.
     */
    fun record(context: Context, number: String) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putLong(PhoneBook.key(number), System.currentTimeMillis()).apply()
    }

    /** true si ce numéro a été rejeté il y a moins de 10 minutes. */
    fun isRecent(context: Context, number: String): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = p.getLong(PhoneBook.key(number), 0L)
        return last > 0 && System.currentTimeMillis() - last < WINDOW_MS
    }

    /** Combien de millisecondes depuis le dernier rejet (0 si jamais rejeté). */
    fun lastRejectedMs(context: Context, number: String): Long {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getLong(PhoneBook.key(number), 0L)
    }
}
