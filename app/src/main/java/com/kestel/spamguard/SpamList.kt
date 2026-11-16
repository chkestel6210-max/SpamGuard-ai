package com.kestel.spamguard

import android.content.Context

/**
 * LES LISTES DE L'UTILISATEUR : liste noire (numéros bloqués à vie)
 * et liste blanche (numéros toujours autorisés).
 *
 * C'est le cœur du "temps réel" de SpamGuard :
 * - numéro déjà signalé -> rejeté instantanément, sans IA ni réseau ;
 * - numéro autorisé -> laisse passer instantanément.
 *
 * On stocke les clés (9 derniers chiffres, voir PhoneBook.key) dans les
 * préférences : un StringSet suffit, c'est simple et rapide.
 */
object SpamList {

    private const val PREFS = "spamguard_lists"

    /** Nom affiché du numéro (ex : "+33612345678") pour le journal. */
    private data class Entry(val key: String, val display: String)

    // ------------------------------------------------- LISTE NOIRE

    /** Ajoute un numéro à la liste noire (rejeté avant même de sonner). */
    fun block(context: Context, number: String) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = p.getStringSet("blacklist", emptySet()) ?: emptySet()
        // NB : un StringSet est immuable, on en construit un nouveau.
        p.edit().putStringSet("blacklist", set + keyEntry(number)).apply()
    }

    /** Retire un numéro de la liste noire. */
    fun unblock(context: Context, number: String) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = p.getStringSet("blacklist", emptySet()) ?: emptySet()
        p.edit().putStringSet("blacklist", set - keyEntry(number)).apply()
    }

    /** true si ce numéro figure dans la liste noire. */
    fun isBlocked(context: Context, number: String): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = p.getStringSet("blacklist", emptySet()) ?: emptySet()
        return set.contains(keyEntry(number))
    }

    /** Toute la liste noire, format affichable (pour l'écran Journal). */
    fun blockedNumbers(context: Context): List<String> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = p.getStringSet("blacklist", emptySet()) ?: emptySet()
        return set.map { it.substringBefore('|') }.sorted()
    }

    // ------------------------------------------------- LISTE BLANCHE

    /** Ajoute un numéro à la liste blanche (jamais filtré, jamais bloqué). */
    fun allow(context: Context, number: String) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = p.getStringSet("allowlist", emptySet()) ?: emptySet()
        p.edit().putStringSet("allowlist", set + keyEntry(number)).apply()
    }

    /** Retire un numéro de la liste blanche. */
    fun disallow(context: Context, number: String) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = p.getStringSet("allowlist", emptySet()) ?: emptySet()
        p.edit().putStringSet("allowlist", set - keyEntry(number)).apply()
    }

    /** true si ce numéro figure dans la liste blanche. */
    fun isAllowed(context: Context, number: String): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = p.getStringSet("allowlist", emptySet()) ?: emptySet()
        return set.contains(keyEntry(number))
    }

    /** Toute la liste blanche, format affichable (pour l'écran Journal). */
    fun allowedNumbers(context: Context): List<String> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = p.getStringSet("allowlist", emptySet()) ?: emptySet()
        return set.map { it.substringBefore('|') }.sorted()
    }

    // ------------------------------------------------- interne

    /**
     * On mémorise "clé|numéroAffiché". La clé sert à reconnaître le numéro
     * quel que soit son format, le numéro affiché sert juste au journal.
     */
    private fun keyEntry(number: String): String =
        PhoneBook.key(number) + "|" + number
}
