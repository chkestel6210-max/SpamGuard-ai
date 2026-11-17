package com.kestel.spamguard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
 * Même stratégie en couches que pour les appels : liste blanche,
 * liste noire, préfixes ARCEP, base des organismes, cache, puis IA.
 *
 * Limite honnête d'Android : une app qui n'est pas l'app SMS par défaut
 * ne peut PAS supprimer un SMS ni empêcher sa notification. On peut
 * seulement le lire, le faire analyser, et afficher une alerte.
 * (Voir README, section Limitations.)
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

        // ---------- COUCHE 0 : instantané, sans réseau ni IA ----------

        // Liste blanche : numéro autorisé, on ne fait aucun bruit.
        if (SpamList.isAllowed(context, sender)) {
            VerdictLog.add(context, event(context, sender, "SMS",
                VerdictLog.Source.LISTE_BLANCHE, false, "Numéro autorisé manuellement"))
            return
        }

        // Liste noire : numéro déjà signalé.
        if (SpamList.isBlocked(context, sender)) {
            VerdictLog.add(context, event(context, sender, "SMS",
                VerdictLog.Source.LISTE_NOIRE, true, "Numéro déjà signalé"))
            notifySpam(context, sender, body, "Numéro déjà signalé comme spam")
            return
        }

        // Préfixe ARCEP réservé au démarchage.
        if (config.blockDemarchage && PhoneBook.isDemarchage(sender)) {
            val reason = "Démarchage : préfixe réservé (${PhoneBook.demarchagePrefix(sender)})"
            VerdictLog.add(context, event(context, sender, "SMS",
                VerdictLog.Source.ARCEP, true, reason))
            notifySpam(context, sender, body, reason)
            return
        }

        // L'analyse suivante est asynchrone : on ne bloque pas le récepteur.
        scope.launch {
            // ---------- COUCHE 1 : locale ----------

            // Expéditeur connu (hôpital, école, administration) ?
            val known = runCatching { WincallsDb.lookup(context, sender) }.getOrNull()
            if (known != null) {
                VerdictLog.add(context, event(context, sender, "SMS",
                    VerdictLog.Source.BASE, false, "Organisme officiel reconnu", known.name))
                return@launch
            }

            // Déjà jugé récemment ?
            val cached = VerdictCache.get(sender)
            if (cached != null) {
                VerdictLog.add(context, event(context, sender, "SMS",
                    VerdictLog.Source.CACHE, cached,
                    if (cached) "Spam (jugé récemment)" else "OK (jugé récemment)"))
                if (cached) notifySpam(context, sender, body, "Numéro déjà jugé spam récemment")
                return@launch
            }

            // ---------- COUCHE 2 : IA ----------
            val verdict = runCatching {
                LlmClient.classify(config, sender, body)
            }.getOrNull() ?: return@launch // fail-open : pas de notification en cas d'erreur

            VerdictCache.put(sender, verdict)

            // Mémorisation : un spammeur SMS ne repayera pas d'analyse la prochaine fois.
            if (verdict.spam && config.autoBlacklist) {
                SpamList.block(context, sender)
            }

            VerdictLog.add(context, event(context, sender, "SMS",
                VerdictLog.Source.IA, verdict.spam, verdict.reason))

            if (verdict.spam) {
                notifySpam(context, sender, body, verdict.reason)
            }
        }
    }

    /** Fabrique l'événement de journal correspondant. */
    private fun event(
        context: Context, number: String, kind: String,
        source: String, spam: Boolean, reason: String, name: String = ""
    ) = VerdictLog.Event(
        timestamp = System.currentTimeMillis(),
        number = number,
        kind = kind,
        source = source,
        spam = spam,
        reason = reason,
        name = name
    )

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

        // NOUVEAUTÉ v0.2.5 : des boutons d'action DIRECTEMENT sur la
        // notification. Plus besoin d'ouvrir l'app pour trancher :
        //   - "BLOQUER" : l'expéditeur rejoint la liste noire ;
        //   - "PAS DU SPAM" : l'expéditeur rejoint la liste blanche
        //     (si l'IA s'est trompée, elle ne le reverra plus).
        // Les deux boutons frappent NotificationActionReceiver, le même
        // récepteur que les notifications d'appels rejetés.
        val blockIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_BLOCK
            putExtra("number", sender)
        }
        val blockPending = PendingIntent.getBroadcast(
            context, sender.hashCode() + 1, blockIntent, PendingIntent.FLAG_IMMUTABLE
        )
        val allowIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_ALLOW
            putExtra("number", sender)
        }
        val allowPending = PendingIntent.getBroadcast(
            context, sender.hashCode() + 2, allowIntent, PendingIntent.FLAG_IMMUTABLE
        )

        // Toucher la notification ouvre le journal (pour signaler au 33700...).
        val openJournal = PendingIntent.getActivity(
            context, 0,
            Intent(context, JournalActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("SMS suspecté spam : $sender")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openJournal)
            .addAction(0, "BLOQUER", blockPending)
            .addAction(0, "PAS DU SPAM", allowPending)
            .setAutoCancel(true)
            .build()

        manager.notify(sender.hashCode(), notification)
    }

    companion object {
        private const val CHANNEL_ID = "spam_sms_alerts"
    }
}
