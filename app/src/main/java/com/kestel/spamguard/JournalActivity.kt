package com.kestel.spamguard

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * L'écran "Journal & numéros".
 *
 * Ici, Florian peut voir TOUT ce que SpamGuard a analysé (appels et SMS,
 * verdict, raison, et quelle couche a décidé), gérer ses listes noire
 * et blanche, et signaler un spammeur au 33700 (la plateforme officielle
 * française : un SMS pré-rempli s'ouvre, il n'a plus qu'à envoyer).
 *
 * L'interface est construite en Kotlin (pas en XML) : chaque événement
 * devient une petite "carte". C'est plus simple à comprendre qu'un
 * RecyclerView pour un premier projet.
 */
class JournalActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_journal)

        container = findViewById(R.id.llJournal)
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            VerdictLog.clear(this)
            rebuild()
        }

        rebuild()
    }

    override fun onResume() {
        super.onResume()
        // Le journal peut avoir changé pendant qu'on était ailleurs
        // (un appel filtré, par exemple) : on rafraîchit à chaque retour.
        rebuild()
    }

    /** Reconstruit tout l'écran : listes, puis journal. */
    private fun rebuild() {
        container.removeAllViews()

        buildBlacklistSection()
        buildAllowlistSection()
        buildHistorySection()
    }

    // =====================================================================
    // LISTE NOIRE
    // =====================================================================

    private fun buildBlacklistSection() {
        val blocked = SpamList.blockedNumbers(this)
        addTitle("🚫 Numéros bloqués (${blocked.size})")
        addHint("Rejetés instantanément, sans IA. Les spams jugés par l'IA y entrent automatiquement.")

        // Ajout manuel : un champ + un bouton.
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val input = EditText(this).apply {
            hint = "Ex : 0612345678"
            inputType = InputType.TYPE_CLASS_PHONE
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnAdd = Button(this).apply {
            text = "Bloquer"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        btnAdd.setOnClickListener {
            if (input.text.isNotBlank()) {
                SpamList.block(this@JournalActivity, input.text.toString().trim())
                Toast.makeText(this, "Numéro bloqué à vie", Toast.LENGTH_SHORT).show()
                rebuild()
            }
        }
        row.addView(input)
        row.addView(btnAdd)
        container.addView(row)

        blocked.forEach { number ->
            addNumberRow(number, isBlacklist = true)
        }
    }

    // =====================================================================
    // LISTE BLANCHE
    // =====================================================================

    private fun buildAllowlistSection() {
        val allowed = SpamList.allowedNumbers(this)
        addTitle("✅ Numéros toujours autorisés (${allowed.size})")
        addHint("Jamais filtrés, jamais bloqués, quelle que soit l'opinion de l'IA.")
        allowed.forEach { number ->
            addNumberRow(number, isBlacklist = false)
        }
    }

    /**
     * Une ligne "numéro + bouton Retirer" pour les deux listes.
     */
    private fun addNumberRow(number: String, isBlacklist: Boolean) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tv = TextView(this).apply {
            text = number
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btn = Button(this).apply {
            text = "Retirer"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        btn.setOnClickListener {
            if (isBlacklist) SpamList.unblock(this@JournalActivity, number)
            else SpamList.disallow(this@JournalActivity, number)
            rebuild()
        }
        row.addView(tv)
        row.addView(btn)
        container.addView(row)
    }

    // =====================================================================
    // JOURNAL
    // =====================================================================

    private fun buildHistorySection() {
        val events = VerdictLog.all(this)
        addTitle("📜 Dernières analyses (${events.size})")

        if (events.isEmpty()) {
            addHint("Rien pour l'instant. Les appels et SMS analysés apparaîtront ici.")
            return
        }

        events.forEach { addEventCard(it) }
    }

    /** La "carte" d'un appel ou SMS analysé. */
    private fun addEventCard(e: VerdictLog.Event) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        // Ligne 1 : date, type d'événement.
        val date = java.text.SimpleDateFormat("dd/MM à HH:mm", java.util.Locale.FRANCE)
            .format(java.util.Date(e.timestamp))
        addText(card, "🕐 $date — ${e.kind} de ${e.number.ifBlank { "numéro masqué" }}", bold = true)

        // Ligne 2 : organisme reconnu (si la base le connaît).
        if (e.name.isNotBlank()) addText(card, "ℹ️ Identifié : ${e.name}")

        // Ligne 3 : verdict.
        val verdictText = if (e.spam) "🚫 SPAM" else "✅ légitime"
        addText(card, "$verdictText — décision : ${e.source}")
        if (e.reason.isNotBlank()) addText(card, "   ${e.reason}")

        // Boutons d'action : signaler au 33700, bloquer / débloquer.
        val number = e.number
        if (number.isNotBlank()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

            row.addView(smallButton("Signaler au 33700") {
                reportTo33700(number, e)
            })

            if (SpamList.isBlocked(this, number)) {
                row.addView(smallButton("Débloquer") {
                    SpamList.unblock(this, number)
                    Toast.makeText(this, "Numéro retiré de la liste noire", Toast.LENGTH_SHORT).show()
                    rebuild()
                })
            } else {
                row.addView(smallButton("Bloquer") {
                    SpamList.block(this, number)
                    Toast.makeText(this, "Numéro bloqué à vie", Toast.LENGTH_SHORT).show()
                    rebuild()
                })
            }

            row.addView(smallButton("Toujours autoriser") {
                SpamList.allow(this, number)
                Toast.makeText(this, "Numéro ajouté à la liste blanche", Toast.LENGTH_SHORT).show()
                rebuild()
            })

            container.addView(row)
        }

        container.addView(card)

        // Petite ligne de séparation entre les cartes.
        val sep = android.view.View(this).apply {
            setBackgroundColor(android.graphics.Color.LTGRAY)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * resources.displayMetrics.density).toInt()
            )
        }
        container.addView(sep)
    }

    /**
     * Ouvre l'app SMS avec un message pré-rempli pour le 33700, la
     * plateforme officielle française de signalement des spams
     * téléphoniques. Florian n'a plus qu'à appuyer sur "Envoyer".
     *
     * - Pour un APPEL indésirable : "spam vocal + numéro".
     * - Pour un SMS indésirable : on transmet l'expéditeur et le contenu,
     *   l'équivalent du transfert manuel recommandé par le 33700.
     */
    private fun reportTo33700(number: String, e: VerdictLog.Event) {
        val body = if (e.kind == "APPEL") {
            "spam vocal $number"
        } else {
            "spam sms - expediteur : $number"
        }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:33700")).apply {
            putExtra("sms_body", body)
        }
        try {
            startActivity(intent)
        } catch (e2: Exception) {
            Toast.makeText(this, "Aucune app SMS trouvée", Toast.LENGTH_SHORT).show()
        }
    }

    // =====================================================================
    // Petits helpers de construction d'interface
    // =====================================================================

    /** Un bouton compact pour les cartes du journal. */
    private fun smallButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = 8 }
            setOnClickListener { onClick() }
        }

    /** Un paragraphe de texte dans une carte. */
    private fun addText(parent: LinearLayout, text: String, bold: Boolean = false) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = if (bold) 15f else 13f
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        parent.addView(tv)
    }

    /** Un titre de section. */
    private fun addTitle(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            val top = (20 * resources.displayMetrics.density).toInt()
            setPadding(0, top, 0, 0)
        }
        container.addView(tv)
    }

    /** Une ligne d'explication grise. */
    private fun addHint(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(android.graphics.Color.GRAY)
        }
        container.addView(tv)
    }
}
