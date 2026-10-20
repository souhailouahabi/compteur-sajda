package com.sajda.counter

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

/**
 * Compteur de sajdas par capteur de proximité.
 *
 * Utilisation : poser le téléphone au sol, écran vers le haut, à l'endroit
 * de la prosternation. Quand la tête s'approche du haut du téléphone
 * (où se trouve le capteur), une sajda est comptée.
 * 2 sajdas = 1 rak'a.
 */
class MainActivity : Activity(), SensorEventListener {

    private data class Priere(val nom: String, val rakat: Int)

    private val prieres = listOf(
        Priere("Fajr – الفجر (2)", 2),
        Priere("Dhuhr – الظهر (4)", 4),
        Priere("Asr – العصر (4)", 4),
        Priere("Maghrib – المغرب (3)", 3),
        Priere("Isha – العشاء (4)", 4),
        Priere("Libre (sans objectif)", 0)
    )

    // --- Réglages de détection ---
    /** La tête doit rester proche au moins ce temps pour compter une sajda. */
    private val dureeMinProche = 500L
    /** Un éloignement plus court que ça est considéré comme un tremblement. */
    private val dureeMinLoin = 900L
    /** Temps laissé pour poser le téléphone après avoir appuyé sur Démarrer. */
    private val delaiDemarrage = 3000L
    private var debutEcoute = 0L

    // --- État ---
    private var sensorManager: SensorManager? = null
    private var capteur: Sensor? = null
    private var actif = false
    private var sajdas = 0
    private var objectifRakat = 2

    private var estProche = false
    private var sajdaEnCours = false
    private var dernierLoin = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val confirmation = Runnable {
        if (actif && estProche && !sajdaEnCours) {
            sajdaEnCours = true
            compterSajda()
        }
    }

    // --- Vues ---
    private lateinit var txtRakat: TextView
    private lateinit var txtObjectif: TextView
    private lateinit var txtDetail: TextView
    private lateinit var txtEtat: TextView
    private lateinit var btnStart: Button
    private lateinit var spinner: Spinner

    private val vibreur: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        capteur = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        setContentView(construireInterface())
        if (capteur == null) {
            txtEtat.text = "⚠️ Ce téléphone n'a pas de capteur de proximité.\nVous pouvez compter manuellement avec +1 / −1."
            btnStart.isEnabled = false
        }
        majAffichage()
    }

    // ------------------------------------------------------------------
    // Interface (construite en code, pas de XML)
    // ------------------------------------------------------------------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun bouton(texte: String, couleur: Int, action: () -> Unit) = Button(this).apply {
        text = texte
        textSize = 18f
        setTextColor(Color.WHITE)
        isAllCaps = false
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(couleur)
        }
        setPadding(dp(16), dp(14), dp(16), dp(14))
        setOnClickListener { action() }
    }

    private fun construireInterface(): View {
        val racine = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#0E1A12"))
            setPadding(dp(20), dp(32), dp(20), dp(24))
        }

        racine.addView(TextView(this).apply {
            text = "Compteur de Sajdas"
            textSize = 22f
            setTextColor(Color.parseColor("#A5D6A7"))
            gravity = Gravity.CENTER
        })

        spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                prieres.map { it.nom }
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    objectifRakat = prieres[pos].rakat
                    majAffichage()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        racine.addView(spinner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })

        txtRakat = TextView(this).apply {
            textSize = 110f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        racine.addView(txtRakat, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        txtObjectif = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.parseColor("#C8E6C9"))
            gravity = Gravity.CENTER
        }
        racine.addView(txtObjectif)

        txtDetail = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#81C784"))
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
        }
        racine.addView(txtDetail)

        txtEtat = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#9E9E9E"))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(16))
        }
        racine.addView(txtEtat)

        // Correction manuelle
        val ligneCorrection = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val poidsLp = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(4); marginEnd = dp(4)
        } }
        ligneCorrection.addView(bouton("−1 sajda", Color.parseColor("#37474F")) {
            if (sajdas > 0) sajdas--
            majAffichage()
        }, poidsLp())
        ligneCorrection.addView(bouton("+1 sajda", Color.parseColor("#37474F")) {
            compterSajda()
        }, poidsLp())
        racine.addView(ligneCorrection, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val ligneAction = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnStart = bouton("Démarrer", Color.parseColor("#2E7D32")) { basculer() }
        ligneAction.addView(btnStart, poidsLp())
        ligneAction.addView(bouton("Remettre à 0", Color.parseColor("#6D4C41")) {
            sajdas = 0
            sajdaEnCours = false
            majAffichage()
        }, poidsLp())
        racine.addView(ligneAction, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        return racine
    }

    // ------------------------------------------------------------------
    // Démarrage / arrêt
    // ------------------------------------------------------------------
    private fun basculer() {
        if (actif) arreter() else demarrer()
    }

    private fun demarrer() {
        if (capteur == null) return
        actif = true
        sajdas = 0
        sajdaEnCours = false
        estProche = false
        dernierLoin = 0L
        debutEcoute = SystemClock.elapsedRealtime()
        spinner.isEnabled = false
        btnStart.text = "Arrêter"
        ecouterCapteur(true)
        // Écran allumé et très sombre pendant la prière
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 0.05f }
        majAffichage()
    }

    private fun arreter() {
        actif = false
        handler.removeCallbacks(confirmation)
        ecouterCapteur(false)
        spinner.isEnabled = true
        btnStart.text = "Démarrer"
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        majAffichage()
    }

    private fun ecouterCapteur(oui: Boolean) {
        val sm = sensorManager ?: return
        val c = capteur ?: return
        if (oui) sm.registerListener(this, c, SensorManager.SENSOR_DELAY_UI)
        else sm.unregisterListener(this)
    }

    override fun onResume() {
        super.onResume()
        if (actif) ecouterCapteur(true)
    }

    override fun onPause() {
        super.onPause()
        if (actif) ecouterCapteur(false)
    }

    // ------------------------------------------------------------------
    // Détection
    // ------------------------------------------------------------------
    override fun onSensorChanged(event: SensorEvent) {
        if (!actif) return
        // Délai au démarrage pour avoir le temps de poser le téléphone
        if (SystemClock.elapsedRealtime() - debutEcoute < delaiDemarrage) return
        val c = capteur ?: return
        val seuil = minOf(c.maximumRange, 5f)
        val proche = event.values[0] < seuil
        if (proche) quandProche() else quandLoin()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun quandProche() {
        if (estProche) return
        estProche = true
        val maintenant = SystemClock.elapsedRealtime()
        // Éloignement très bref pendant une sajda déjà comptée : on ignore
        if (sajdaEnCours && maintenant - dernierLoin < dureeMinLoin) return
        sajdaEnCours = false
        handler.postDelayed(confirmation, dureeMinProche)
    }

    private fun quandLoin() {
        if (!estProche) return
        estProche = false
        dernierLoin = SystemClock.elapsedRealtime()
        handler.removeCallbacks(confirmation)
    }

    private fun compterSajda() {
        sajdas++
        val objectifSajdas = objectifRakat * 2
        when {
            objectifRakat > 0 && sajdas == objectifSajdas ->
                vibrer(longArrayOf(0, 600, 200, 600)) // prière terminée
            sajdas % 2 == 0 ->
                vibrer(longArrayOf(0, 120, 120, 120)) // rak'a terminée
            else ->
                vibrer(longArrayOf(0, 60)) // 1ère sajda
        }
        majAffichage()
    }

    private fun vibrer(motif: LongArray) {
        val v = vibreur ?: return
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createWaveform(motif, -1))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(motif, -1)
        }
    }

    // ------------------------------------------------------------------
    // Affichage
    // ------------------------------------------------------------------
    private fun majAffichage() {
        val rakatFinies = sajdas / 2
        txtRakat.text = rakatFinies.toString()

        txtObjectif.text = if (objectifRakat > 0)
            "rak'a terminées sur $objectifRakat"
        else
            "rak'a terminées"

        txtDetail.text = buildString {
            append("Sajdas : $sajdas")
            if (sajdas % 2 == 1) append("  ·  1ʳᵉ sajda de la rak'a ${rakatFinies + 1} faite")
            if (objectifRakat > 0 && sajdas > objectifRakat * 2) append("\n⚠️ Plus de sajdas que prévu")
        }

        if (capteur != null) {
            txtEtat.text = when {
                !actif -> "Choisissez la prière, appuyez sur Démarrer,\npuis posez le téléphone au sol, écran vers le haut,\nhaut du téléphone vers vous, là où se pose votre front."
                objectifRakat > 0 && sajdas >= objectifRakat * 2 -> "✅ Prière terminée — appuyez sur Arrêter"
                else -> "En écoute… (vibration à chaque sajda)"
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(confirmation)
        sensorManager?.unregisterListener(this)
        super.onDestroy()
    }
}
