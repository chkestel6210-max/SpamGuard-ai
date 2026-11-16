package com.kestel.spamguard

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * L'écran principal : configuration (clé API, modèle), activation du rôle
 * de filtrage d'appels, gestion de la base offline, et un bouton "Tester"
 * pour vérifier que l'IA répond bien, sans attendre un vrai spam.
 */
class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var etApiKey: EditText
    private lateinit var etBaseUrl: EditText
    private lateinit var etModel: EditText
    private lateinit var etTestMessage: EditText
    private lateinit var swCalls: SwitchMaterial
    private lateinit var swSms: SwitchMaterial
    private lateinit var swDemarchage: SwitchMaterial
    private lateinit var swAutoBlacklist: SwitchMaterial
    private lateinit var swWeeklyDb: SwitchMaterial
    private lateinit var tvStatus: TextView
    private lateinit var tvResult: TextView
    private lateinit var tvDbStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // On relie les variables aux éléments de l'écran (définis dans res/layout/activity_main.xml)
        etApiKey = findViewById(R.id.etApiKey)
        etBaseUrl = findViewById(R.id.etBaseUrl)
        etModel = findViewById(R.id.etModel)
        etTestMessage = findViewById(R.id.etTestMessage)
        swCalls = findViewById(R.id.swCalls)
        swSms = findViewById(R.id.swSms)
        swDemarchage = findViewById(R.id.swDemarchage)
        swAutoBlacklist = findViewById(R.id.swAutoBlacklist)
        swWeeklyDb = findViewById(R.id.swWeeklyDb)
        tvStatus = findViewById(R.id.tvStatus)
        tvResult = findViewById(R.id.tvResult)
        tvDbStatus = findViewById(R.id.tvDbStatus)

        // On affiche les réglages déjà enregistrés.
        val config = Settings.read(this)
        etApiKey.setText(config.apiKey)
        etBaseUrl.setText(config.baseUrl)
        etModel.setText(config.model)
        swCalls.isChecked = config.callsEnabled
        swSms.isChecked = config.smsEnabled
        swDemarchage.isChecked = config.blockDemarchage
        swAutoBlacklist.isChecked = config.autoBlacklist
        swWeeklyDb.isChecked = config.weeklyDbUpdate

        // À chaque modification, on sauvegarde automatiquement.
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = save()
        }
        etApiKey.addTextChangedListener(watcher)
        etBaseUrl.addTextChangedListener(watcher)
        etModel.addTextChangedListener(watcher)
        swCalls.setOnCheckedChangeListener { _, _ -> save() }
        swSms.setOnCheckedChangeListener { _, _ -> save() }
        swDemarchage.setOnCheckedChangeListener { _, _ -> save() }
        swAutoBlacklist.setOnCheckedChangeListener { _, _ -> save() }
        swWeeklyDb.setOnCheckedChangeListener { _, checked ->
            save()
            syncWeeklyWorker(checked)
        }

        findViewById<Button>(R.id.btnPermissions).setOnClickListener { requestNeededPermissions() }
        findViewById<Button>(R.id.btnEnableRole).setOnClickListener { requestCallScreeningRole() }
        findViewById<Button>(R.id.btnTest).setOnClickListener { runTest() }
        findViewById<Button>(R.id.btnJournal).setOnClickListener {
            startActivity(Intent(this, JournalActivity::class.java))
        }
        findViewById<Button>(R.id.btnDownloadDb).setOnClickListener { downloadDb() }

        requestNeededPermissions()
        refreshStatus()
        refreshDbStatus()

        // Si la mise à jour hebdo était déjà activée, on s'assure que la
        // tâche planifiée existe bien (elle survit aux redémarrages du
        // téléphone, mais autant la ré-affirmer à chaque ouverture).
        if (config.weeklyDbUpdate) syncWeeklyWorker(true)
    }

    /** Relit les champs de l'écran et les enregistre dans les préférences. */
    private fun save() {
        Settings.save(
            this,
            Settings.Config(
                apiKey = etApiKey.text.toString(),
                baseUrl = etBaseUrl.text.toString(),
                model = etModel.text.toString(),
                callsEnabled = swCalls.isChecked,
                smsEnabled = swSms.isChecked,
                blockDemarchage = swDemarchage.isChecked,
                autoBlacklist = swAutoBlacklist.isChecked,
                weeklyDbUpdate = swWeeklyDb.isChecked
            )
        )
        refreshStatus()
    }

    /**
     * Demande les permissions runtime : notifications (Android 13+),
     * SMS, téléphone, et contacts (pour ne jamais filtrer tes contacts).
     */
    private fun requestNeededPermissions() {
        val wanted = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) wanted += Manifest.permission.POST_NOTIFICATIONS

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) wanted += Manifest.permission.RECEIVE_SMS

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) wanted += Manifest.permission.READ_PHONE_STATE

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) wanted += Manifest.permission.READ_CONTACTS

        if (wanted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, wanted.toTypedArray(), 100)
        }
    }

    /**
     * Le point crucial pour les appels : demander à Android le rôle
     * "Filtered call screening" (Screening d'appels). Sans ça, le service
     * de filtrage n'est jamais consulté.
     */
    private fun requestCallScreeningRole() {
        if (Build.VERSION.SDK_INT >= 29) {
            val roleManager = getSystemService(RoleManager::class.java) ?: return
            if (!roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
                Toast.makeText(this, "Ce rôle n'existe pas sur cet appareil", Toast.LENGTH_LONG).show()
                return
            }
            if (roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
                Toast.makeText(this, "Le filtrage d'appels est déjà actif", Toast.LENGTH_SHORT).show()
                refreshStatus()
                return
            }
            // Android affiche sa propre boîte de dialogue de confirmation.
            @Suppress("DEPRECATION")
            startActivityForResult(
                roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING),
                REQUEST_ROLE
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ROLE) {
            Toast.makeText(
                this,
                if (resultCode == RESULT_OK) "Filtrage d'appels activé"
                else "Filtrage d'appels refusé",
                Toast.LENGTH_SHORT
            ).show()
            refreshStatus()
        }
    }

    /** Affiche l'état actuel : rôle accordé ? clé API remplie ? contacts lus ? */
    private fun refreshStatus() {
        val roleHeld = if (Build.VERSION.SDK_INT >= 29) {
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
        } else false

        val apiKeyOk = etApiKey.text.toString().isNotBlank()

        val contactsOk = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        val knownCount = SpamList.blockedNumbers(this).size

        tvStatus.text = buildString {
            append("État :\n")
            append(if (roleHeld) "• Filtrage d'appels : ACTIF\n" else "• Filtrage d'appels : inactif (bouton ci-dessus)\n")
            append(if (apiKeyOk) "• Clé API : renseignée\n" else "• Clé API : manquante !\n")
            append(if (contactsOk) "• Contacts : protégés\n" else "• Contacts : permission non accordée\n")
            append("• Numéros bloqués à vie : $knownCount")
        }
    }

    /** Affiche l'état de la base offline des organismes. */
    private fun refreshDbStatus() {
        tvDbStatus.text = if (WincallsDb.isPresent(this)) {
            val date = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)
                .format(Date(WincallsDb.downloadedAt(this)))
            "Base : présente (${WincallsDb.sizeMb(this)} Mo, téléchargée le $date)"
        } else {
            "Base : pas encore téléchargée"
        }
    }

    /** Télécharge la base des organismes connus (~34 Mo), en arrière-plan. */
    private fun downloadDb() {
        val btn = findViewById<Button>(R.id.btnDownloadDb)
        btn.isEnabled = false
        tvDbStatus.text = "Téléchargement en cours… (patiente, c'est ~34 Mo)"

        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { WincallsDb.download(this@MainActivity) }.isSuccess
            }
            btn.isEnabled = true
            if (ok) {
                Toast.makeText(this@MainActivity, "Base des organismes installée !", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@MainActivity, "Échec du téléchargement (réseau ?)", Toast.LENGTH_LONG).show()
            }
            refreshDbStatus()
        }
    }

    /**
     * Active ou désactive la mise à jour hebdomadaire de la base.
     * WorkManager : la bibliothèque Android qui exécute des tâches en
     * arrière-plan même si l'app est fermée (et survit aux redémarrages).
     * On ne télécharge qu'en Wi-Fi : 34 Mo, on est pas des barbares.
     */
    private fun syncWeeklyWorker(enabled: Boolean) {
        val wm = WorkManager.getInstance(this)
        if (enabled) {
            val request = PeriodicWorkRequestBuilder<DbUpdateWorker>(7, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .build()
                )
                .build()
            wm.enqueueUniquePeriodicWork(
                DbUpdateWorker.UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        } else {
            wm.cancelUniqueWork(DbUpdateWorker.UNIQUE_NAME)
        }
    }

    /** Bouton "Tester" : envoie un message exemple à l'IA et affiche le verdict. */
    private fun runTest() {
        val config = Settings.read(this)

        if (config.apiKey.isBlank()) {
            Toast.makeText(this, "Renseigne d'abord ta clé API", Toast.LENGTH_SHORT).show()
            return
        }

        val message = etTestMessage.text.toString().ifBlank {
            "URGENT : votre colis est bloqué. Rendez-vous sur colis-arnaque.fr/payement"
        }

        tvResult.text = "Analyse en cours…"

        scope.launch {
            val verdict = withContext(Dispatchers.IO) {
                runCatching { LlmClient.classify(config, "+33612345678", message) }.getOrNull()
            }
            tvResult.text = when {
                verdict == null ->
                    "Erreur : l'API n'a pas répondu. Vérifie la clé, l'URL et ta connexion."
                verdict.spam -> "Verdict : SPAM\n${verdict.reason}"
                else -> "Verdict : légitime\n${verdict.reason}"
            }
        }
    }

    companion object {
        private const val REQUEST_ROLE = 42
    }
}
