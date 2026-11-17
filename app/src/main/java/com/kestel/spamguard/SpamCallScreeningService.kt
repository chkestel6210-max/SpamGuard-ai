package com.kestel.spamguard

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
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
}
