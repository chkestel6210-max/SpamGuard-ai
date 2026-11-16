package com.kestel.spamguard

/**
 * Boîte à outils pour "lire" un numéro de téléphone.
 * Toutes les couches de SpamGuard utilisent la même clé : les 9 derniers
 * chiffres du numéro. Comme ça, "+33 6 12 34 56 78", "0612345678" et
 * "0033612345678" donnent tous la clé "612345678".
 */
object PhoneBook {

    /**
     * Normalise un numéro en clé : on ne garde que les chiffres,
     * puis les 9 derniers. Exemple : "+33612345678" -> "612345678".
     */
    fun key(number: String): String =
        number.filter { it.isDigit() }.takeLast(9)

    /**
     * Le numéro au format international complet, sans le "+".
     * Exemple : "+33612345678" -> "33612345678", "0612345678" -> "33612345678".
     * Sert pour la base WinCalls, qui range les numéros ainsi.
     */
    fun e164(number: String): String {
        val digits = number.filter { it.isDigit() }
        return when {
            // Déjà au format international (commence par 33, 11 chiffres pour la France)
            digits.length >= 11 && digits.startsWith("33") -> digits
            // Format national français : 0 + 9 chiffres -> on remplace le 0 par 33
            digits.length == 10 && digits.startsWith("0") -> "33" + digits.substring(1)
            // Autre cas (numéro étranger court, etc.) : on renvoie les chiffres tels quels
            else -> digits
        }
    }

    /**
     * LES PRÉFIXES DU DÉMARCHAGE (décision ARCEP n° 2022-1583).
     *
     * La loi oblige les entreprises qui font du démarchage téléphonique à
     * appeler depuis des numéros UNIQUEMENT réservés à cet usage. Un appel
     * venant de ces préfixes est donc, par définition, du démarchage :
     * 0162, 0163, 0270, 0271, 0377, 0378, 0424, 0425, 0568, 0569, 0948, 0949.
     *
     * On teste la clé (9 derniers chiffres) : "0162123456" -> "162123456"
     * commence par "162". Zéro base de données, zéro réseau : instantané.
     */
    private val DEMARCHAGE_PREFIXES = listOf(
        "162", "163", "270", "271", "377", "378",
        "424", "425", "568", "569", "948", "949"
    )

    /** true si ce numéro est un numéro réservé au démarchage (ARCEP). */
    fun isDemarchage(number: String): Boolean {
        val k = key(number)
        return DEMARCHAGE_PREFIXES.any { k.startsWith(it) }
    }

    /** Le préfixe ARCEP trouvé (pour l'afficher à l'utilisateur), ou null. */
    fun demarchagePrefix(number: String): String? {
        val k = key(number)
        return DEMARCHAGE_PREFIXES.firstOrNull { k.startsWith(it) }?.let { "0$it" }
    }
}
