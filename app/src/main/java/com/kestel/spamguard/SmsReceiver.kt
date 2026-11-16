package com.kestel.spamguard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Récepteur SMS : Android le prévient à chaque SMS reçu.
 *
 * Limite honnête d'Android : une app qui n'est pas l'app SMS par défaut
 * ne peut PAS supprimer un SMS ni empêcher sa notification. On peut
 * seulement le lire, le faire analyser par l'IA, et afficher une alerte
 * "SMS suspecté spam" avec l'explication. (Voir README, section Limitations.)
 */
class SmsReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val config = Settings.read(context)
        if (!config.smsEnabled || config.apiKey.isEmpty()) return

        // Un long SMS peut être découpé en plusieurs morceaux (PDU),
        // Android nous les donne tous d'un coup.
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val sender = messages.firstOrNull()?.originatingAddress ?: return
        val body = messages.joinToString("") { it.displayMessageBody ?: "" }
        if (body.isBlank()) return

        // L'analyse IA est asynchrone : on ne bloque pas le récepteur.
        scope.launch {
            val verdict = runCatching {
                LlmClient.classify(config, sender, body)
            }.getOrNull() ?: return@launch

            VerdictCache.put(sender, verdict)

            if (verdict.spam) {
                notifySpam(context, sender, body, verdict.reason)
            }
        }
    }

    /** Affiche la notification "SMS suspecté spam". */
    private fun notifySpam(context: Context, sender: String, body: String, reason: String) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            CHANNEL_ID,
            "SMS indésirables",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Alertes quand un SMS est suspecté d'être du spam" }
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("SMS suspecté spam : $sender")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()

        manager.notify(sender.hashCode(), notification)
    }

    companion object {
        private const val CHANNEL_ID = "spam_sms_alerts"
    }
}
