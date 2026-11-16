package com.kestel.spamguard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Le client qui parle à l'IA. Il fonctionne avec toute API compatible
 * avec le format OpenAI "chat/completions" : Mistral, OpenRouter, Ollama, etc.
 * On envoie le numéro + le texte, l'IA répond SPAM ou PAS SPAM.
 */
object LlmClient {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Le "prompt système" : les instructions données à l'IA.
     * On la force à répondre en JSON strict pour pouvoir parser facilement.
     */
    private val SYSTEM_PROMPT = """
        Tu es un filtre anti-spam sur un téléphone Android en France.
        On te donne un numéro de téléphone et éventuellement le texte d'un SMS.
        Réponds UNIQUEMENT avec un objet JSON, sans texte autour :
        {"spam": true, "reason": "explication courte en français"}
        ou {"spam": false, "reason": "explication courte en français"}.
        Marque spam=true pour : démarchage téléphonique, arnaque, robot vocal,
        numéro surtaxé, phishing, faux colis, faux impôts, faux SAV.
        Marque spam=false pour le reste, même inconnu : livraison, médecin,
        banque, école, particulier. En cas de doute, réponds spam=false.
    """.trimIndent()

    /**
     * Demande à l'IA si ce numéro / message est du spam.
     * Doit être appelée depuis une coroutine (elle fait une requête réseau).
     * @throws IOException en cas d'erreur réseau ou de réponse invalide.
     */
    suspend fun classify(
        config: Settings.Config,
        sender: String,
        body: String
    ): SpamVerdict = withContext(Dispatchers.IO) {

        val userMessage = if (body.isBlank()) {
            "Numéro de l'appelant : $sender\n(Appel entrant, aucun texte disponible. " +
                "Juge à partir du format du numéro seul.)"
        } else {
            "Numéro de l'expéditeur : $sender\nTexte du SMS :\n$body"
        }

        // Construction de la requête au format OpenAI/Mistral "chat completions".
        val requestBody = JSONObject().apply {
            put("model", config.model)
            put("temperature", 0) // 0 = réponses déterministes, pas de créativité
            put("max_tokens", 80)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                put(JSONObject().put("role", "user").put("content", userMessage))
            })
        }

        val url = config.baseUrl.trimEnd('/') + "/chat/completions"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string()
                ?: throw IOException("Réponse vide de l'API")

            if (!response.isSuccessful) {
                throw IOException("Erreur API ${response.code} : ${raw.take(200)}")
            }

            // Extraction du texte écrit par l'IA.
            val content = JSONObject(raw)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            parseVerdict(content)
        }
    }

    /**
     * L'IA est censée répondre {"spam": true, "reason": "..."}.
     * On essaie de lire ce JSON ; si elle a été bavarde (balises markdown, texte
     * autour), on retombe sur une recherche par expression régulière.
     */
    private fun parseVerdict(content: String): SpamVerdict {
        // Tentative 1 : JSON propre (on retire d'éventuelles balises ```json ... ```)
        val cleaned = content
            .replace("```json", "")
            .replace("```", "")
            .trim()
        try {
            val json = JSONObject(cleaned)
            return SpamVerdict(
                spam = json.getBoolean("spam"),
                reason = json.optString("reason", "")
            )
        } catch (_: Exception) {
            // Tentative 2 : on cherche "spam": true/false n'importe où dans le texte
            val match = Regex("\"spam\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE)
                .find(cleaned)
            return when (match?.groupValues?.get(1)?.lowercase()) {
                "true" -> SpamVerdict(true, "Détecté comme spam")
                "false" -> SpamVerdict(false, "")
                else -> throw IOException("Réponse de l'IA illisible : ${cleaned.take(100)}")
            }
        }
    }
}
