package com.kestel.spamguard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * LE RÉCEPTEUR DES BOUTONS DE NOTIFICATION.
 *
 * Quand SpamGuard rejette un appel, il affiche une notification avec
 * un bouton "TOUJOURS AUTORISER" (au cas où le rejet était une erreur :
 * un vrai correspondant pris pour un spammeur). Ce récepteur est le
 * morceau de code qui s'exécute quand on appuie sur ce bouton, SANS
 * ouvrir l'application.
 *
 * Un "BroadcastReceiver" = une petite porte que Android frappe quand
 * un événement arrive. Ici l'événement, c'est notre propre bouton.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Le numéro concerné, transmis par la notification.
        val number = intent.getStringExtra("number") ?: return

        when (intent.action) {
            // "Toujours autoriser" : on fait trois choses, pour couvrir
            // tous les cas de figure d'un faux positif :
            //   1. on l'ajoute à la LISTE BLANCHE (plus jamais filtré) ;
            //   2. on le retire de la LISTE NOIRE (au cas où l'IA l'y
            //      aurait placé automatiquement) ;
            //   3. on efface la notification de rejet, remplacée par une
            //      petite confirmation.
            ACTION_ALLOW -> {
                SpamList.allow(context, number)
                SpamList.unblock(context, number)
                confirmAllowed(context, number)
            }
        }
    }

    /** Petite notification "c'est fait", pour ne pas laisser l'utilisateur dans le doute. */
    private fun confirmAllowed(context: Context, number: String) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Appels rejetés",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Alertes quand un appel est rejeté par SpamGuard" }
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Numéro autorisé")
            .setContentText("$number ne sera plus jamais filtré ni bloqué.")
            .setAutoCancel(true)
            .build()

        // On remplace la notification de rejet par la confirmation.
        manager.notify(number.hashCode(), notification)
    }

    companion object {
        /** Identifiant du canal de notification (voir SpamCallScreeningService). */
        const val CHANNEL_ID = "appels_rejetes"

        /** L'action "toujours autoriser" du bouton de la notification. */
        const val ACTION_ALLOW = "com.kestel.spamguard.TOUJOURS_AUTORISER"
    }
}
