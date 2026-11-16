package com.kestel.spamguard

/**
 * Le verdict rendu par l'IA pour un appel ou un SMS.
 * @param spam  true = c'est du spam, false = c'est légitime
 * @param reason courte explication en français, affichée à l'utilisateur
 */
data class SpamVerdict(val spam: Boolean, val reason: String)
