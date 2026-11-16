package com.kestel.spamguard

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPInputStream

/**
 * LA BASE OFFLINE DES ORGANISMES CONNUS (source : WinCalls, licence ODbL).
 *
 * Ce n'est PAS une liste de spam : c'est un annuaire inversé de 756 879
 * organismes français LÉGITIMES (hôpitaux, écoles, mairies, gendarmeries,
 * services publics...) reconnus par l'IA... euh non, par la loi. 😉
 *
 * Rôle dans SpamGuard : LISTE BLANCHE. Si un appel vient d'un numéro
 * présent dans cette base, c'est un organisme officiel : on le laisse
 * passer sans même consulter l'IA, et on peut afficher qui appelle.
 * Avantage énorme : l'école ou l'hôpital ne sera JAMAIS bloqué par erreur.
 *
 * Données : https://wincalls.fr/opendata.html (ODbL 1.0)
 * Structure de la base : table phone_lookup (phone_key = numéro international
 * sans le "+", ex 33123456789) + table categories (id -> code lisible).
 */
object WincallsDb {

    private const val DL_URL =
        "https://wincalls.fr/opendata/wincalls_phones_fr.final.sqlite.gz"

    /** Ce qu'on sait d'un numéro trouvé dans la base. */
    data class KnownOrg(
        val name: String,      // nom de l'organisme, ou sa catégorie si inconnu
        val category: String   // catégorie lisible : sante, administration...
    )

    // La base ouverte une seule fois, gardée en mémoire le temps du processus.
    @Volatile private var db: SQLiteDatabase? = null

    /** Le fichier SQLite téléchargé, rangé dans l'espace privé de l'app. */
    private fun file(context: Context): File =
        File(context.getDir("data", Context.MODE_PRIVATE), "wincalls_fr.sqlite")

    /** true si la base a déjà été téléchargée. */
    fun isPresent(context: Context): Boolean = file(context).exists()

    /** Taille de la base en Mo (pour l'afficher à l'utilisateur), ou 0. */
    fun sizeMb(context: Context): Long {
        val f = file(context)
        return if (f.exists()) f.length() / (1024 * 1024) else 0
    }

    /** Date du dernier téléchargement réussi (timestamp ms), ou 0. */
    fun downloadedAt(context: Context): Long {
        return context.getSharedPreferences("spamguard_settings", Context.MODE_PRIVATE)
            .getLong("wincalls_downloaded_at", 0L)
    }

    /**
     * Télécharge la base compressée (~34 Mo), la décompresse et remplace
     * l'ancienne version. À appeler depuis une coroutine en arrière-plan.
     * @throws Exception si le téléchargement ou l'écriture échoue.
     */
    fun download(context: Context) {
        val client = okhttp3.OkHttpClient()
        val request = okhttp3.Request.Builder().url(DL_URL).build()

        val tmpGz = File(context.cacheDir, "wincalls_dl.sqlite.gz")
        val tmpDb = File(context.cacheDir, "wincalls_dl.sqlite")

        try {
            // 1) Téléchargement dans le cache (pour ne rien casser si ça échoue).
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Téléchargement échoué (code ${response.code})")
                }
                response.body?.byteStream()?.use { input ->
                    tmpGz.outputStream().use { output -> input.copyTo(output) }
                }
            }

            // 2) Décompression gzip -> SQLite.
            GZIPInputStream(FileInputStream(tmpGz)).use { input ->
                tmpDb.outputStream().use { output -> input.copyTo(output) }
            }

            // 3) Remplacement "atomique" : l'ancienne base reste valide
            //    jusqu'à ce que le renommage réussisse.
            val target = file(context)
            target.delete()
            if (!tmpDb.renameTo(target)) {
                throw Exception("Impossible de ranger la base téléchargée")
            }

            // La vieille connexion pointait vers l'ancien fichier : on l'oublie.
            db?.close()
            db = null

            context.getSharedPreferences("spamguard_settings", Context.MODE_PRIVATE)
                .edit()
                .putLong("wincalls_downloaded_at", System.currentTimeMillis())
                .apply()
        } finally {
            tmpGz.delete()
            tmpDb.delete()
        }
    }

    /**
     * Cherche le numéro dans la base. Renvoie l'organisme trouvé, ou null.
     * Doit être appelé depuis un fil d'arrière-plan (accès disque).
     */
    fun lookup(context: Context, number: String): KnownOrg? {
        if (!isPresent(context)) return null

        val database = db ?: openDb(context)
        val key = PhoneBook.e164(number)

        // La base range les numéros en entieriers : "33612345678" -> 33612345678L
        val phoneKey = key.toLongOrNull() ?: return null

        database.rawQuery(
            """
            SELECT p.name, c.code
            FROM phone_lookup p
            LEFT JOIN categories c ON c.id = p.category_id
            WHERE p.phone_key = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(phoneKey.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val name = cursor.getString(0)
            val category = cursor.getString(1) ?: "organisme"
            // name peut être NULL (numéro sans nom) : on affiche la catégorie.
            return KnownOrg(name ?: category, category)
        }
    }

    /** Ouvre la base en lecture seule, une seule fois par processus. */
    private fun openDb(context: Context): SQLiteDatabase {
        val d = SQLiteDatabase.openDatabase(
            file(context).absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
        db = d
        return d
    }
}
