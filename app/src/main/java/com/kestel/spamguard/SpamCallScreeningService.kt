package com.kestel.spamguard

import android.telecom.Call
import android.telecom.CallScreeningService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * LE filtre d'appels. Android appelle onScreenCall() pour chaque appel entrant
 * et attend notre réponse : laisser sonner, ou rejeter.
 *
 * Important : le rôle "Screening d'appels" doit être accordé à l'app
 * (bouton dans l'écran principal). Sinon Android ne consulte jamais ce service.
 */
class SpamCallScreeningService : CallScreeningService() {

    // Une "coroutine scope" : permet de faire le travail réseau en arrière-plan
    // sans bloquer le fil d'Android (qui doit rester réactif).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onScreenCall(details: Call.Details) {

        val config = Settings.read(this)

        // Filtrage désactivé, ou clé API absente : on laisse tout passer.
        if (!config.callsEnabled || config.apiKey.isEmpty()) {
            respond(details, block = false)
            return
        }

        // Le numéro de l'appelant, par exemple "+33612345678"
        val number = details.handle?.schemeSpecificPart ?: ""

        // Déjà jugé récemment ? On répond instantanément depuis le cache.
        VerdictCache.get(number)?.let { cachedSpam ->
            respond(details, block = cachedSpam)
            return
        }

        // Sinon on demande à l'IA, mais avec un garde-fou de 4,5 secondes :
        // Android n'attend pas éternellement, et on préfère laisser sonner
        // un vrai appel plutôt que de le faire patienter trop longtemps.
        scope.launch {
            val verdict = withTimeoutOrNull(4_500) {
                runCatching { LlmClient.classify(config, number, body = "") }.getOrNull()
            }

            if (verdict == null) {
                // Erreur réseau, IA trop lente, réponse illisible : on laisse passer.
                // C'est un choix "fail-open" : en cas de doute, on ne bloque pas.
                respond(details, block = false)
                return@launch
            }

            VerdictCache.put(number, verdict)
            respond(details, block = verdict.spam)
        }
    }

    /**
     * Dit à Android quoi faire de l'appel.
     * block=true : rejeté, il ne sonne même pas (et pas de notification manqué).
     * block=false : comportement normal, il sonne.
     */
    private fun respond(details: Call.Details, block: Boolean) {
        val response = CallResponse.Builder()
            .setDisallowCall(block)
            .setRejectCall(block)
            .setSkipCallLog(false)
            .setSkipNotification(block)
            .build()
        respondToCall(details, response)
    }
}
