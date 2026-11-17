package com.kestel.spamguard

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * LE FILTRE D'APPELS, EN 3 COUCHES (du plus rapide au plus lent) :
 *
 *   Couche 0, INSTANTANÉE (aucun réseau, aucune IA) :
 *     1. Liste blanche de l'utilisateur -> toujours laisser passer
 *     2. Liste noire de l'utilisateur (ou numéros mémorisés spam par l'IA)
 *        -> toujours rejeter
 *     3. Préfixes ARCEP réservés au démarchage -> rejeter
 *
 *   Couche 1, LOCALE (~30 ms, aucune IA) :
 *     4. Base des organismes connus (hôpitaux, écoles...) -> laisser passer
 *     5. Numéro présent dans tes contacts -> laisser passer
 *
 *   Couche 2, IA (1 à 4 s) : tout le reste. Et si l'IA dit "spam",
 *   le numéro rejoint la liste noire : la prochaine fois il sera
 *   rejeté INSTANTANÉMENT, sans payer l'attente ni l'API.
 *
 * Ordre voulu : la décision la plus rapide gagne. C'est ce qui rend
 * SpamGuard "presque temps réel" : seul un numéro jamais vu fait appel
 * à l'IA, une seule fois dans sa vie.
 */
class SpamCallScreeningService : CallScreeningService() {

    // Une "coroutine scope" : permet de faire le travail en arrière-plan
    // sans bloquer le fil d'Android (qui doit rester réactif).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onScreenCall(details: Call.Details) {

        val config = Settings.read(this)

        // Filtrage désactivé, ou clé API absente : on laisse tout passer.
        if (!config.callsEnabled || config.apiKey.isEmpty()) {
            decide(details, "", block = false, source = VerdictLog.Source.DESACTIVE,
                spam = false, reason = "Filtrage désactivé")
            return
        }

        // Le numéro de l'appelant, par exemple "+33612345678"
        val number = details.handle?.schemeSpecificPart ?: ""

        // ---------- COUCHE 0 : décisions instantanées, sans réseau ----------

        // 1. Liste blanche : l'utilisateur a dit "celui-là, toujours le laisser passer".
        if (SpamList.isAllowed(this, number)) {
            decide(details, number, block = false, source = VerdictLog.Source.LISTE_BLANCHE,
                spam = false, reason = "Numéro autorisé manuellement")
            return
        }

        // 2. Liste noire : déjà signalé (par toi ou mémorisé par l'IA). Rejet immédiat.
        if (SpamList.isBlocked(this, number)) {
            decide(details, number, block = true, source = VerdictLog.Source.LISTE_NOIRE,
                spam = true, reason = "Numéro déjà signalé")
            return
        }

        // 3. Préfixe ARCEP réservé au démarchage (0162, 0270...).
        //    Un professionnel du démarchage est OBLIGÉ d'appeler depuis
        //    ces préfixes : aucun risque de bloquer un vrai particulier.
        if (config.blockDemarchage && PhoneBook.isDemarchage(number)) {
            decide(details, number, block = true, source = VerdictLog.Source.ARCEP,
                spam = true,
                reason = "Préfixe réservé au démarchage (${PhoneBook.demarchagePrefix(number)})")
            return
        }

        // ---------- COUCHES 1 et 2 : accès disque et réseau, en arrière-plan ----------
        scope.launch {
            // 4. Base des organismes connus (WinCalls, offline) :
            //    hôpital, école, gendarmerie... -> on laisse passer,
            //    pas besoin de consulter l'IA pour l'hôpital !
            val known = runCatching { WincallsDb.lookup(this@SpamCallScreeningService, number) }
                .getOrNull()
            if (known != null) {
                // NOUVEAUTÉ v0.2.5 : on affiche QUI appelle PENDANT la
                // sonnerie (hôpital, école, administration...), grâce à
                // la base offline. Le téléphone affiche juste un numéro
                // inconnu ; SpamGuard, lui, connaît le nom.
                runCatching { notifyCallerId(number, known.name, known.category) }

                decide(details, number, block = false, source = VerdictLog.Source.BASE,
                    spam = false, reason = "Organisme officiel reconnu", name = known.name)
                return@launch
            }

            // 5. Le numéro est dans tes contacts ? Laisse tranquille.
            if (isContact(number)) {
                decide(details, number, block = false, source = VerdictLog.Source.CONTACTS,
                    spam = false, reason = "Numéro présent dans les contacts")
                return@launch
            }

            // 6. Déjà jugé récemment par l'IA ? On répond instantanément depuis le cache.
            val cached = VerdictCache.get(number)
            if (cached != null) {
                decide(details, number, block = cached, source = VerdictLog.Source.CACHE,
                    spam = cached, reason = if (cached) "Spam (jugé récemment)" else "OK (jugé récemment)")
                return@launch
            }

            // 7. Numéro inconnu : on demande à l'IA, avec un garde-fou de 4,5 secondes.
            //    Android n'attend pas éternellement, et on préfère laisser sonner
            //    un vrai appel plutôt que de le faire patienter trop longtemps.
            val verdict = withTimeoutOrNull(4_500) {
                runCatching { LlmClient.classify(config, number, body = "") }.getOrNull()
            }

            if (verdict == null) {
                // Erreur réseau, IA trop lente : on laisse passer ("fail-open").
                decide(details, number, block = false, source = VerdictLog.Source.IA,
                    spam = false, reason = "IA injoignable : appel laissé passer par sécurité")
                return@launch
            }

            VerdictCache.put(number, verdict)

            // MÉMORISATION : si l'IA dit "spam", le numéro rejoint la liste
            // noire PERMANENTE. La prochaine fois : rejet instantané, gratuit.
            if (verdict.spam && config.autoBlacklist) {
                SpamList.block(this@SpamCallScreeningService, number)
            }

            decide(details, number, block = verdict.spam, source = VerdictLog.Source.IA,
                spam = verdict.spam, reason = verdict.reason)
        }
    }

    /**
     * Note l'événement dans le journal PUIS répond à Android.
     * Une seule fonction pour ne jamais oublier de tracer une décision.
     */
    private fun decide(
        details: Call.Details,
        number: String,
        block: Boolean,
        source: String,
        spam: Boolean,
        reason: String,
        name: String = ""
    ) {

        // ---------- FILET DE SÉCURITÉ "APPEL INSISTANT" (v0.2.5) ----------
        // Un numéro qu'on a rejeté il y a MOINS de 10 minutes et qui
        // RAPPELLE : on le laisse passer, avec une alerte. Un spammeur
        // attendra 10 minutes ; une école qui doit prévenir d'un
        // problème avec ton enfant, non. C'est notre "et si c'était
        // urgent ?" : l'IA peut se tromper, l'insistance, rarement.
        if (block && number.isNotEmpty() && RecentRejects.isRecent(this, number)) {
            val last = RecentRejects.lastRejectedMs(this, number)
            val minutes = if (last > 0) ((System.currentTimeMillis() - last) / 60_000 + 1).toInt() else 1

            VerdictLog.add(this, VerdictLog.Event(
                timestamp = System.currentTimeMillis(),
                number = number,
                kind = "APPEL",
                source = VerdictLog.Source.INSISTANT,
                spam = true,
                reason = "Rejeté il y a $minutes min mais il insiste : laissé passer (possible urgence)",
                name = name
            ))

            // On laisse l'appel sonner, SANS le rejeter.
            respondToCall(details, CallResponse.Builder()
                .setDisallowCall(false)
                .setRejectCall(false)
                .setSkipCallLog(false)
                .setSkipNotification(false)
                .build())

            // Et on prévient l'utilisateur de ce choix de sécurité.
            runCatching { notifyInsistent(number, minutes) }
            return
        }

        VerdictLog.add(this, VerdictLog.Event(
            timestamp = System.currentTimeMillis(),
            number = number,
            kind = "APPEL",
            source = source,
            spam = spam,
            reason = reason,
            name = name
        ))

        val response = CallResponse.Builder()
            .setDisallowCall(block)
            .setRejectCall(block)
            .setSkipCallLog(false)
            .setSkipNotification(block)
            .build()
        respondToCall(details, response)

        // NOUVEAUTÉ v0.2.4 : quand on REJETE un appel, on prévient
        // l'utilisateur par une notification. Sans ça, un rejet est
        // totalement silencieux : on ne le découvre qu'après coup dans
        // le journal. La notification porte le numéro, la raison, et
        // un bouton "TOUJOURS AUTORISER" pour corriger un faux positif.
        if (block && number.isNotEmpty() && source != VerdictLog.Source.DESACTIVE) {
            RecentRejects.record(this, number)
            runCatching { notifyRejection(number, source, reason) }
        }
    }

    /**
     * Affiche la notification "Appel rejeté : +33...".
     *
     * Le "runCatching" autour de l'appel protège le service : si les
     * notifications ne sont pas autorisées ou que le téléphone refuse
     * pour une raison exotique, on ne fait pas planter le filtrage.
     */
    private fun notifyRejection(number: String, source: String, reason: String) {
        val manager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Le canal : Android exige un "canal" par type de notification
        // depuis Android 8 ; c'est lui que l'utilisateur peut régler
        // (silence, vibration...) dans les paramètres du téléphone.
        val channel = NotificationChannel(
            NotificationActionReceiver.CHANNEL_ID,
            "Alertes SpamGuard",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Appels rejetés, appels insistants, identification des appelants" }
        manager.createNotificationChannel(channel)

        // Appuyer sur la notification ouvre l'écran "Journal & numéros".
        val openJournal = PendingIntent.getActivity(
            this, 0,
            Intent(this, JournalActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        // Le bouton "TOUJOURS AUTORISER" : il frappe NotificationActionReceiver
        // sans ouvrir l'application. FLAG_IMMUTABLE est obligatoire depuis
        // Android 12 (sécurité : personne d'autre ne peut modifier l'intent).
        val allowIntent = Intent(this, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_ALLOW
            putExtra("number", number)
        }
        val allowPending = PendingIntent.getBroadcast(
            this, number.hashCode(), allowIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NotificationActionReceiver.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("🚫 Appel rejeté : $number")
            .setContentText("$source : $reason")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$source : $reason"))
            .setContentIntent(openJournal)
            .addAction(0, "TOUJOURS AUTORISER", allowPending)
            .setAutoCancel(true) // disparaît quand on la touche
            .build()

        // Un identifiant par numéro : deux rejets différents = deux cartes.
        manager.notify(number.hashCode(), notification)
    }

    /**
     * NOUVEAUTÉ v0.2.5 : notification "appel insistant laissé passer".
     * C'est notre filet de sécurité : le numéro avait été rejeté il y a
     * moins de 10 minutes, il a rappelé, on a laissé passer. L'utilisateur
     * DOIT le savoir : c'est lui qui décide ensuite (rappeler, bloquer...).
     */
    private fun notifyInsistent(number: String, minutes: Int) {
        val manager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            NotificationActionReceiver.CHANNEL_ID,
            "Alertes SpamGuard",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Appels rejetés, appels insistants, identification des appelants" }
        manager.createNotificationChannel(channel)

        val openJournal = PendingIntent.getActivity(
            this, 0,
            Intent(this, JournalActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        // Bouton "BLOQUER QUAND MÊME" : si l'utilisateur reconnaît un
        // spammeur acharné, il peut le bloquer à vie en un seul geste.
        val blockIntent = Intent(this, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_BLOCK
            putExtra("number", number)
        }
        val blockPending = PendingIntent.getBroadcast(
            this, number.hashCode() + 1, blockIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NotificationActionReceiver.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("⚠️ Appel insistant laissé passer : $number")
            .setContentText("Rejeté il y a $minutes min, il a rappelé : appel autorisé (possible urgence).")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Rejeté il y a $minutes min, il a rappelé : appel autorisé (possible urgence). " +
                    "Si c'est un spammeur acharné, bloque-le ci-dessous."))
            .setContentIntent(openJournal)
            .addAction(0, "BLOQUER QUAND MÊME", blockPending)
            .setAutoCancel(true)
            .build()

        manager.notify(number.hashCode() + 1, notification)
    }

    /**
     * NOUVEAUTÉ v0.2.5 : le "caller-ID". Pendant que le téléphone sonne,
     * si la base offline connaît le numéro (hôpital, école, gendarmerie...),
     * on affiche son nom : le téléphone ne montre qu'un numéro inconnu,
     * SpamGuard montre qui c'est. Effet "Truecaller" en 100% local.
     */
    private fun notifyCallerId(number: String, name: String, category: String) {
        val manager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Canal dédié, pour que l'utilisateur puisse régler cette alerte
        // indépendamment des autres dans les paramètres du téléphone.
        val channel = NotificationChannel(
            CHANNEL_CALLER_ID,
            "Identification des appelants",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Affiche le nom des organismes connus pendant la sonnerie" }
        manager.createNotificationChannel(channel)

        val openJournal = PendingIntent.getActivity(
            this, 0,
            Intent(this, JournalActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_CALLER_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("☎️ $name")
            .setContentText("Appel d'un organisme connu ($category) — autorisé")
            .setContentIntent(openJournal)
            .setAutoCancel(true) // disparaît dès qu'on la touche
            .build()

        manager.notify(number.hashCode() + 2, notification)
    }

    /**
     * true si le numéro existe dans les contacts du téléphone.
     * Sans la permission READ_CONTACTS, on répond false (on ne bloque
     * jamais "par défaut" : en cas de doute, l'appel passe).
     */
    /**
     * Le numéro est-il dans les contacts de l'utilisateur ?
     *
     * Piège rencontré en vrai (17/11) : un contact enregistré au format
     * national ("07 82 38 38 64") n'était PAS retrouvé quand l'appel
     * arrivait au format international ("+33782383864"). La recherche
     * officielle d'Android (PhoneLookup) est censée tolérer ces écarts,
     * mais selon le téléphone elle échoue. On essaie donc 3 variantes :
     *   1. le numéro brut, tel qu'il arrive de l'appelant ;
     *   2. le format national français ("0...") si l'appel vient de +33 ;
     *   3. une comparaison exacte sur le numéro "normalisé" qu'Android
     *      stocke lui-même pour chaque contact (format E.164).
     * Si une seule variante trouve le contact, c'est gagné.
     */
    private fun isContact(number: String): Boolean {
        // Sans la permission, impossible de lire les contacts : on rend
        // "faux" et le numéro ira vers l'IA, qui reste là en secours.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return false

        // Variante 1 : le numéro tel qu'il arrive, par exemple "+33782383864".
        if (contactLookup(number)) return true

        // Variante 2 : "+33612345678" est la même personne que "0612345678".
        // On remplace l'indicatif "+33" par un "0" (format national).
        if (number.startsWith("+33") && number.length >= 12) {
            if (contactLookup("0" + number.substring(3))) return true
        }

        // Variante 3 : Android range chaque numéro de contact au format
        // international normalisé (colonne NORMALIZED_NUMBER). On demande :
        // "montre-moi les contacts dont le numéro normalisé est EXACTEMENT
        // celui de l'appelant". C'est la comparaison la plus fiable.
        try {
            val cursor = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone._ID),
                ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER + " = ?",
                arrayOf(number), null
            )
            cursor?.use { if (it.moveToFirst()) return true }
        } catch (_: Exception) {
            // Numéro non normalisable ou base inaccessible : on s'arrête là.
        }

        return false
    }

    /**
     * Recherche "floue" via PhoneLookup : c'est l'outil officiel d'Android
     * pour retrouver un numéro dans les contacts. Il ignore les espaces et
     * les tirets, mais peut rater les différences international/national.
     * Renvoie vrai si au moins un contact a ce numéro.
     */
    private fun contactLookup(number: String): Boolean {
        return try {
            val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI
                .buildUpon().appendPath(Uri.encode(number)).build()
            contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null, null, null
            )?.use { it.moveToFirst() } ?: false
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        /** Canal de notification dédié au caller-ID (identification pendant la sonnerie). */
        private const val CHANNEL_CALLER_ID = "appelant_identifie"
    }
}
