package com.kestel.spamguard

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Tâche planifiée (WorkManager) : met à jour la base des organismes
 * connus une fois par semaine, en arrière-plan, sans que Florian
 * n'ait à y penser.
 *
 * Planifiée depuis l'écran principal si l'option "Mise à jour auto
 * hebdomadaire" est activée. Le téléchargement (~34 Mo) est de toute
 * façon remplacé de façon atomique : si la mise à jour échoue,
 * l'ancienne base reste en place et continue de fonctionner.
 */
class DbUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            WincallsDb.download(applicationContext)
            Result.success()
        } catch (e: Exception) {
            // Échec (pas de réseau, serveur indisponible...) : WorkManager
            // réessaiera à la prochaine fenêtre. On ne casse rien.
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "wincalls_weekly_update"
    }
}
