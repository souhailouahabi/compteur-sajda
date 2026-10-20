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
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView

/**
 * Compteur de sajdas par capteur de proximité (v2).
 *
 * Poser le téléphone au sol, écran vers le haut, le haut du téléphone
 * (où se trouve le capteur) sous l'endroit du front. 2 sajdas = 1 rak'a.
 *
 * v2 : diagnostic en direct du capteur + réglage de sensibilité.
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
    /** Durée minimale "tête proche" pour compter une sajda (réglable, en ms). */
    private var dureeMinProche = 300L
    /** Un éloignement plus court que ça pendant une sajda est ignoré. */
    private val dureeMinLoin = 700L
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
    private var derniereValeur = Float.NaN
    private var nbChangements = 0
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
    private lateinit var txtDiag: TextView
    private lateinit var txtSensibilite: TextView
    private lateinit var btnStart: Button
    private lateinit var spinner: Spinner
    private lateinit var seek: SeekBar

    private val prefs by lazy { getSharedPreferences("reglages", Context.MODE_PRIVATE) }

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
        dureeMinProche = prefs.getLong("dureeMinProche", 300L)
        setContentView(construireInterface())
        if (capteur == null) {
            txtEtat.text = "⚠️ Ce téléphone n'a pas de capteur de proximité.\nVous pouvez compter manuellement avec +1 / −1."
            btnStart.isEnabled = false
        }
        majAffichage()
        majDiag()
    }

    // ------------------------------------------------------------------
    // Interface (construite en code, pas de XML)
    // ------------------------------------------------------------------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun bouton(texte: String, couleur: Int, action: () -> Unit) = Button(this).apply {
        text = texte
        textSize = 17f
        setTextColor(Color.WHITE)
        isAllCaps = false
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(couleur)
        }
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setOnClickListener { action() }
    }

    private fun construireInterface(): View {
        val racine = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#0E1A12"))
            setPadding(dp(20), dp(28), dp(20), dp(20))
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
        ).apply { topMargin = dp(12) })

        txtRakat = TextView(this).apply {
            textSize = 100f
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
            setPadding(0, dp(4), 0, dp(4))
        }
        racine.addView(txtDetail)

        txtEtat = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#9E9E9E"))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(8))
        }
        racine.addView(txtEtat)

        // Diagnostic en direct du capteur
        txtDiag = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#FFE082"))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#1C2A20"))
            }
        }
        racine.addView(txtDiag, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })

        // Sensibilité
        txtSensibilite = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#C8E6C9"))
            gravity = Gravity.CENTER
        }
        racine.addView(txtSensibilite)
        seek = SeekBar(this).apply {
            max = 14 // 0,1 s à 1,5 s par pas de 0,1 s
            progress = ((dureeMinProche / 100L) - 1).toInt().coerceIn(0, 14)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    dureeMinProche = (p + 1) * 100L
                    prefs.edit().putLong("dureeMinProche", dureeMinProche).apply()
                    majSensibilite()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        racine.addView(seek, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })
        majSensibilite()

        // Correction manuelle
        val poidsLp = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(4); marginEnd = dp(4)
        } }
        val ligneCorrection = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
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
            nbChangements = 0
            majAffichage()
            majDiag()
        }, poidsLp())
        racine.addView(ligneAction, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        return racine
    }

    private fun majSensibilite() {
        val s = String.format("%.1f", dureeMinProche / 1000f).replace('.', ',')
        txtSensibilite.text = "Tête baissée au moins $s s pour compter\n(gauche = plus sensible, droite = moins sensible)"
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
        dernierLoin = 0L
        nbChangements = 0
        debutEcoute = SystemClock.elapsedRealtime()
        spinner.isEnabled = false
        btnStart.text = "Arrêter"
        // Écran allumé et sombre pendant la prière
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 0.1f }
        majAffichage()
        majDiag()
    }

    private fun arreter() {
        actif = false
        handler.removeCallbacks(confirmation)
        spinner.isEnabled = true
        btnStart.text = "Démarrer"
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        majAffichage()
    }

    // Le capteur est écouté dès que l'appli est visible (pour le diagnostic).
    override fun onResume() {
        super.onResume()
        val c = capteur ?: return
        sensorManager?.registerListener(this, c, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
    }

    // ------------------------------------------------------------------
    // Détection
    // ------------------------------------------------------------------
    private fun seuil(c: Sensor): Float = minOf(c.maximumRange, 5f)

    override fun onSensorChanged(event: SensorEvent) {
        val c = capteur ?: return
        val valeur = event.values[0]
        val proche = valeur < seuil(c)
        if (derniereValeur.isNaN() || (valeur < seuil(c)) != (derniereValeur < seuil(c))) nbChangements++
        derniereValeur = valeur

        val enAttente = actif && SystemClock.elapsedRealtime() - debutEcoute < delaiDemarrage
        if (actif && !enAttente) {
            if (proche) quandProche() else quandLoin()
        } else {
            // Hors comptage : on suit quand même l'état pour le diagnostic
            estProche = proche
        }
        majDiag()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun quandProche() {
        if (estProche) return
        estProche = true
        val maintenant = SystemClock.elapsedRealtime()
        // Éloignement très bref pendant une sajda déjà comptée : on ignore
        if (sajdaEnCours && maintenant - dernierLoin < dureeMinLoin) return
        sajdaEnCours = false
        handler.removeCallbacks(confirmation)
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
                vibrer(longArrayOf(0, 80)) // 1ère sajda
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
    private fun majDiag() {
        val c = capteur
        if (c == null) {
            txtDiag.text = "Aucun capteur de proximité"
            return
        }
        val v = if (derniereValeur.isNaN()) "—" else String.format("%.1f", derniereValeur)
        val etat = if (derniereValeur.isNaN()) "en attente…" else if (derniereValeur < seuil(c)) "● PROCHE" else "○ LOIN"
        txtDiag.text = "Capteur : ${c.name}\n" +
            "max ${String.format("%.1f", c.maximumRange)} cm · seuil ${String.format("%.1f", seuil(c))} cm\n" +
            "valeur $v cm  →  $etat\n" +
            "changements détectés : $nbChangements"
    }

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
                !actif -> "Test : passez la main au-dessus du haut de l'écran,\nle cadre jaune doit passer à PROCHE."
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
