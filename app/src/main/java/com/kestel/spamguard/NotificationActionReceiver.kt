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
 * Les notifications de SpamGuard portent des boutons d'action qui
 * s'exécutent SANS ouvrir l'application :
 *   - "TOUJOURS AUTORISER" (notification d'appel rejeté, alerte SMS)
 *   - "BLOQUER" / "BLOQUER QUAND MÊME" (alertes SMS, appel insistant)
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
            //   3. on remplace la notification par une confirmation.
            ACTION_ALLOW -> {
                SpamList.allow(context, number)
                SpamList.unblock(context, number)
                confirm(context, number,
                    "Numéro autorisé",
                    "$number ne sera plus jamais filtré ni bloqué.")
            }

            // "Bloquer" (SMS spam, appel insistant) : le numéro rejoint
            // la liste noire, rejeté instantanément à l'avenir.
            // NB : les expéditeurs alphanumériques ("PROMO-XYZ") n'ont
            // pas de chiffres : la clé utilisée est alors vide. En
            // France, les SMS de spam viennent quasi toujours de
            // numéros courts chiffrés, donc ce cas reste rare.
            ACTION_BLOCK -> {
                SpamList.block(context, number)
                confirm(context, number,
                    "Numéro bloqué",
                    "$number sera rejeté instantanément (appels et SMS).")
            }
        }
    }

    /** Petite notification "c'est fait", pour ne pas laisser l'utilisateur dans le doute. */
    private fun confirm(context: Context, number: String, title: String, text: String) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Alertes SpamGuard",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Appels rejetés, appels insistants, identification des appelants" }
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()

        // Même identifiant que la notification d'origine : on la REMPLACE.
        manager.notify(number.hashCode(), notification)
    }

    companion object {
        /** Identifiant du canal de notification des alertes SpamGuard. */
        const val CHANNEL_ID = "appels_rejetes"

        /** L'action "toujours autoriser" du bouton de la notification. */
        const val ACTION_ALLOW = "com.kestel.spamguard.TOUJOURS_AUTORISER"

        /** L'action "bloquer" du bouton de la notification. */
        const val ACTION_BLOCK = "com.kestel.spamguard.BLOQUER"
    }
}
