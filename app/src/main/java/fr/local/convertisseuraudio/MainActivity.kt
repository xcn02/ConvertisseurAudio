package fr.local.convertisseuraudio

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.*
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import android.text.*
import java.io.File
import java.text.DecimalFormat
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private var page = "converter"
    private var player: MediaPlayer? = null
    private var lastVolumeDownMs = 0L
    private val volumeDoublePressWindowMs = 650L
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    // Conversion factors expressed relative to the SI/reference unit of each family.
    private val categories = linkedMapOf(
        "Longueur" to linkedMapOf("mm" to .001, "cm" to .01, "m" to 1.0, "km" to 1000.0, "in" to .0254, "ft" to .3048, "yd" to .9144, "mile" to 1609.344),
        "Surface" to linkedMapOf("mm²" to .000001, "cm²" to .0001, "m²" to 1.0, "km²" to 1e6, "ha" to 10000.0, "ft²" to .09290304),
        "Volume" to linkedMapOf("mL" to .001, "cL" to .01, "L" to 1.0, "m³" to 1000.0, "ft³" to 28.316846592, "gal US" to 3.785411784),
        "Masse" to linkedMapOf("mg" to 1e-6, "g" to .001, "kg" to 1.0, "t" to 1000.0, "lb" to .45359237, "oz" to .028349523125),
        "Température" to linkedMapOf("°C" to 1.0, "°F" to 1.0, "K" to 1.0),
        "Écart de température" to linkedMapOf("K" to 1.0, "°C" to 1.0, "°F" to 5.0/9.0),
        "Vitesse" to linkedMapOf("m/s" to 1.0, "km/h" to 1.0/3.6, "mph" to .44704, "nœud" to .514444444),
        "Débit volumique • CVC" to linkedMapOf("m³/h" to 1.0, "m³/s" to 3600.0, "L/s" to 3.6, "L/min" to .06, "CFM" to 1.69901079552, "ft³/h" to .028316846592),
        "Débit massique • CVC" to linkedMapOf("kg/s" to 1.0, "kg/h" to 1.0/3600.0, "t/h" to 1000.0/3600.0, "lb/h" to .45359237/3600.0),
        "Pression • CVC" to linkedMapOf("Pa" to 1.0, "hPa" to 100.0, "kPa" to 1000.0, "bar" to 1e5, "mbar" to 100.0, "mmCE" to 9.80665, "mCE" to 9806.65, "inH₂O" to 249.08891, "psi" to 6894.757293),
        "Puissance • CVC" to linkedMapOf("W" to 1.0, "kW" to 1000.0, "MW" to 1e6, "BTU/h" to .29307107017, "kBTU/h" to 293.07107017, "TR" to 3516.8528421, "kcal/h" to 1.163),
        "Énergie • CVC" to linkedMapOf("J" to 1.0, "kJ" to 1000.0, "MJ" to 1e6, "Wh" to 3600.0, "kWh" to 3.6e6, "BTU" to 1055.05585262, "kcal" to 4184.0),
        "Coefficient U" to linkedMapOf("W/(m²·K)" to 1.0, "BTU/(h·ft²·°F)" to 5.678263337),
        "Résistance thermique R" to linkedMapOf("m²·K/W" to 1.0, "h·ft²·°F/BTU" to .1761101838),
        "Conductivité thermique" to linkedMapOf("W/(m·K)" to 1.0, "mW/(m·K)" to .001, "BTU/(h·ft·°F)" to 1.730734666),
        "Chaleur massique" to linkedMapOf("J/(kg·K)" to 1.0, "kJ/(kg·K)" to 1000.0, "BTU/(lb·°F)" to 4186.8),
        "Masse volumique" to linkedMapOf("kg/m³" to 1.0, "g/cm³" to 1000.0, "lb/ft³" to 16.01846337),
        "Viscosité dynamique" to linkedMapOf("Pa·s" to 1.0, "mPa·s" to .001, "cP" to .001),
        "Viscosité cinématique" to linkedMapOf("m²/s" to 1.0, "mm²/s" to 1e-6, "cSt" to 1e-6),
        "Fréquence" to linkedMapOf("Hz" to 1.0, "kHz" to 1000.0, "MHz" to 1e6, "tr/min" to 1.0/60.0),
        "Temps" to linkedMapOf("s" to 1.0, "min" to 60.0, "h" to 3600.0, "jour" to 86400.0),
        "Angle" to linkedMapOf("°" to 1.0, "rad" to 180.0/Math.PI, "tour" to 360.0)
    )
    private val receiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (page == "audio") {
                updateRecorderControls()
                intent?.getStringExtra("message")?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show(); showAudio() }
            }
        }
    }
    private var status: TextView? = null
    private var pauseButton: Button? = null
    private var waveform: WaveformView? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private val meterTick = object : Runnable { override fun run() { if (page == "audio") { updateRecorderControls(); waveform?.push(RecorderService.amplitude); uiHandler.postDelayed(this, 180) } } }
    private var startButton: Button? = null
    private val colors = intArrayOf(0xff152238.toInt(), 0xff536174.toInt())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Protects screen content in recent-app previews and screenshots.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 22)
        renderConverter()
    }
    override fun onStart() { super.onStart(); if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, IntentFilter(RecorderService.ACTION_STATE), RECEIVER_NOT_EXPORTED) else @Suppress("DEPRECATION") registerReceiver(receiver, IntentFilter(RecorderService.ACTION_STATE)) }
    override fun onStop() { uiHandler.removeCallbacks(meterTick); super.onStop(); unregisterReceiver(receiver) }
    override fun onDestroy() { player?.release(); super.onDestroy() }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // This shortcut only works while the recorder screen is in the foreground.
        // Never intercept hardware keys when the app is backgrounded or the screen is locked.
        val enabled = prefs.getBoolean("quick_stop_volume", false)
        val active = RecorderService.state == "recording" || RecorderService.state == "paused"
        if (enabled && page == "audio" && active && event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val now = SystemClock.elapsedRealtime()
                if (lastVolumeDownMs != 0L && now - lastVolumeDownMs <= volumeDoublePressWindowMs) {
                    lastVolumeDownMs = 0L
                    sendAction(RecorderService.ACTION_STOP)
                } else {
                    lastVolumeDownMs = now
                }
            }
            return true
        }
        lastVolumeDownMs = 0L
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        when (page) { "converter" -> super.onBackPressed(); "settings" -> renderConverter(); "audio" -> showSettings(); "audio_settings" -> showAudio(); "files" -> showAudio(); else -> renderConverter() }
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun text(s: String, size: Float = 16f, bold: Boolean = false): TextView = TextView(this).apply {
        setText(s); textSize = size; setTextColor(if (bold) colors[0] else colors[1]); if (bold) setTypeface(null, 1); setPadding(0, dp(6), 0, dp(6))
    }
    private fun button(label: String, f: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 15f
        setTextColor(0xff17305D.toInt()); backgroundTintList = android.content.res.ColorStateList.valueOf(0xffE1E9FA.toInt())
        setPadding(dp(8),dp(8),dp(8),dp(8)); setOnClickListener { f() }
    }
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18),dp(16),dp(18),dp(16))
        background = android.graphics.drawable.GradientDrawable().apply { setColor(0xffffffff.toInt()); cornerRadius = dp(22).toFloat() }
        elevation = dp(2).toFloat()
    }
    private fun cardAdd(view: View) { root.addView(view, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(14) }) }

    private fun field(value: String, hint: String = ""): EditText = EditText(this).apply {
        setText(value); setSingleLine(true); setSelectAllOnFocus(true); this.hint = hint
    }
    private fun screen(title: String, subtitle: String? = null) {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(32)) }
        scroll.addView(root)
        setContentView(scroll)
        root.addView(text(title, 28f, true))
        if (subtitle != null) root.addView(text(subtitle, 13f))
        space(14)
    }
    private fun space(h: Int) { root.addView(Space(this).apply { minimumHeight = dp(h) }) }
    private fun spinner(items: List<String>, initial: Int = 0): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(initial.coerceIn(0, items.lastIndex))
    }
    private fun heading(s: String) { space(12); root.addView(text(s, 18f, true)) }
    private fun rule(label: String, key: String, default: String) {
        root.addView(text(label, 15f))
        val choices = listOf("Ne rien faire", "Pause", "Arrêt et sauvegarde")
        val codes = listOf("none", "pause", "stop")
        val sp = spinner(choices, codes.indexOf(prefs.getString(key, default)).coerceAtLeast(0))
        root.addView(sp)
        sp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { prefs.edit().putString(key, codes[position]).apply() }
        }
    }
    private fun renderConverter() {
        uiHandler.removeCallbacks(meterTick); waveform = null
        page = "converter"; screen("Convertisseur", "Toutes vos unités, au même endroit")
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(text("Conversions", 19f, true), LinearLayout.LayoutParams(0,-2,1f))
        top.addView(button("⋮") { showSettings() }, LinearLayout.LayoutParams(dp(58),dp(48)))
        root.addView(top); space(12)
        root.addView(text("Catégorie", 15f, true))
        val categoryNames = categories.keys.toList()
        val cat = spinner(categoryNames, prefs.getInt("category", 0))
        root.addView(cat); space(15)
        val source = field("1", "Valeur")
        source.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        root.addView(text("Valeur à convertir", 15f, true)); root.addView(source)
        val from = spinner(listOf("m")); val to = spinner(listOf("km"))
        root.addView(text("De", 15f, true)); root.addView(from)
        root.addView(text("Vers", 15f, true)); root.addView(to)
        val answer = text("", 26f, true)
        space(18); val resultCard = card(); resultCard.addView(text("RÉSULTAT", 12f, true)); resultCard.addView(answer); cardAdd(resultCard)
        val format = DecimalFormat("0.##########")
        var refreshing = false
        fun calculate() {
            val n = source.text.toString().replace(',', '.').toDoubleOrNull()
            if (n == null) { answer.text = "Saisissez un nombre"; return }
            val category = categoryNames[cat.selectedItemPosition]
            val units = categories.getValue(category)
            val left = from.selectedItem.toString(); val right = to.selectedItem.toString()
            val result = if (category == "Température") {
                val kelvin = when (left) { "°C" -> n + 273.15; "°F" -> (n - 32) * 5.0 / 9 + 273.15; else -> n }
                when (right) { "°C" -> kelvin - 273.15; "°F" -> (kelvin - 273.15) * 9.0 / 5 + 32; else -> kelvin }
            } else n * units.getValue(left) / units.getValue(right)
            answer.text = if (result.isFinite()) "${format.format(result)} $right" else "Valeur hors plage"
        }
        fun refreshUnits() {
            refreshing = true
            val names = categories.getValue(categoryNames[cat.selectedItemPosition]).keys.toList()
            from.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
            to.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
            to.setSelection(if (names.size > 2) 2 else 1)
            refreshing = false
            calculate()
        }
        val selections = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { if (!refreshing && from.selectedItem != null && to.selectedItem != null) calculate() }
        }
        from.onItemSelectedListener = selections; to.onItemSelectedListener = selections
        cat.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { prefs.edit().putInt("category", position).apply(); refreshUnits() }
        }
        source.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = calculate()
            override fun afterTextChanged(s: Editable?) {}
        })
        root.addView(button("⇄  Inverser les unités") {
            val a = from.selectedItemPosition; from.setSelection(to.selectedItemPosition); to.setSelection(a); calculate()
        })
    }
    private fun showSettings() {
        uiHandler.removeCallbacks(meterTick); waveform = null
        page = "settings"; screen("Paramètres", "Convertisseur et modules")
        root.addView(button("← Convertisseur") { renderConverter() })
        heading("Modules")
        root.addView(button("Enregistreur audio  ›") { showAudio() })
        heading("Application")
        root.addView(text("Stockage local uniquement. Pas de connexion réseau, compte ou suivi publicitaire."))
        root.addView(text("Les indicateurs et notifications d'enregistrement d'Android restent actifs."))
        root.addView(text("Version 0.5.2 • Projet de développement", 13f))
    }
    private fun showAudio() {
        page = "audio"; screen("Enregistreur audio", "La capture est signalée par Android et la notification de l'application.")
        root.addView(button("← Paramètres") { showSettings() })
        heading("Enregistrement")
        status = text("", 22f, true).also { root.addView(it) }
        startButton = button("● Démarrer") { startRecording() }.also { root.addView(it) }
        pauseButton = button("Pause") {
            sendAction(if (RecorderService.state == "paused") RecorderService.ACTION_RESUME else RecorderService.ACTION_PAUSE)
            Handler(Looper.getMainLooper()).postDelayed({ updateRecorderControls() }, 200)
        }.also { root.addView(it) }
        root.addView(button("■ Arrêter et sauvegarder") { sendAction(RecorderService.ACTION_STOP); Handler(Looper.getMainLooper()).postDelayed({ updateRecorderControls() }, 300) })
        root.addView(button("Arrêter, sauvegarder et fermer") { sendAction(RecorderService.ACTION_STOP); renderConverter(); finish() })
        root.addView(button("Ouvrir les enregistrements ›") { showFiles() })
        updateRecorderControls()
        waveform = WaveformView(this).also { cardAdd(it) }
        root.addView(button("⚙  Réglages de l’enregistreur") { showAudioSettings() })
        uiHandler.removeCallbacks(meterTick); uiHandler.post(meterTick)
    }
    private fun showAudioSettings() {
        uiHandler.removeCallbacks(meterTick); waveform = null
        page = "audio_settings"; screen("Réglages audio", "Paramètres appliqués à la prochaine session")
        root.addView(button("← Enregistreur") { showAudio() })
        heading("Durée maximale")
        val choices = listOf("Sans limite", "1 minute", "2 minutes", "3 minutes", "5 minutes", "10 minutes", "15 minutes", "30 minutes", "Personnalisée (minutes)")
        val limits = listOf(0L, 60L, 120L, 180L, 300L, 600L, 900L, 1800L)
        val saved = prefs.getLong("limit_seconds", 180L)
        val choiceIdx = limits.indexOf(saved).takeIf { it >= 0 } ?: limits.size
        val duration = spinner(choices, choiceIdx)
        root.addView(duration)
        val custom = field(if (choiceIdx == limits.size) (saved / 60L).toString() else "4", "Minutes")
        custom.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        root.addView(custom)
        root.addView(button("Enregistrer la durée") {
            val selected = duration.selectedItemPosition
            val secs = if (selected == limits.size) custom.text.toString().toLongOrNull()?.takeIf { it in 1L..1440L }?.times(60L) else limits[selected]
            if (secs == null) Toast.makeText(this, "Durée invalide", Toast.LENGTH_SHORT).show()
            else { prefs.edit().putLong("limit_seconds", secs).apply(); Toast.makeText(this, "Durée mémorisée pour la prochaine session", Toast.LENGTH_SHORT).show() }
        })
        heading("Minuteur")
        val modes = listOf("Temps effectivement enregistré", "Temps écoulé, pauses comprises")
        val timer = spinner(modes, if (prefs.getString("timer_mode", "record") == "elapsed") 1 else 0)
        root.addView(timer)
        timer.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { prefs.edit().putString("timer_mode", if (position == 0) "record" else "elapsed").apply() }
        }
        heading("Commandes rapides")
        val quickStop = CheckBox(this).apply {
            text = "Double appui Volume − : arrêter et sauvegarder"
            isChecked = prefs.getBoolean("quick_stop_volume", false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("quick_stop_volume", checked).apply()
                lastVolumeDownMs = 0L
            }
        }
        root.addView(quickStop)
        root.addView(text("Fonctionne sur l'écran Enregistreur lorsque l'application est visible et qu'un enregistrement est actif. Avec l'écran verrouillé, utilisez l'action Arrêter de la notification Android.", 13f))
        heading("Écouteurs et périphériques")
        rule("Jack : débranchement", "jack_off", "pause")
        rule("Jack : branchement", "jack_on", "none")
        rule("Bluetooth : déconnexion", "bt_off", "pause")
        rule("Bluetooth : connexion", "bt_on", "none")
        heading("Qualité")
        val quality = spinner(listOf("AAC mono • 64 kbit/s", "AAC mono • 128 kbit/s"), if (prefs.getInt("bitrate", 64000) == 128000) 1 else 0)
        root.addView(quality)
        quality.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { prefs.edit().putInt("bitrate", if (position == 0) 64000 else 128000).apply() }
        }
        root.addView(text("Les nouveaux paramètres prennent effet à la prochaine session.", 13f))
    }
    private fun updateRecorderControls() {
        val state = RecorderService.state
        status?.text = when (state) {
            "recording" -> "● Enregistrement : ${RecorderService.seconds} s"
            "paused" -> "Ⅱ En pause : ${RecorderService.seconds} s"
            else -> "Prêt à enregistrer"
        }
        startButton?.isEnabled = state == "idle"
        pauseButton?.isEnabled = state != "idle"
        pauseButton?.text = if (state == "paused") "Reprendre" else "Pause"
    }
    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 17)
        } else {
            sendAction(RecorderService.ACTION_START)
            Handler(Looper.getMainLooper()).postDelayed({ updateRecorderControls() }, 600)
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 17 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startRecording()
    }
    private fun sendAction(action: String) {
        val intent = Intent(this, RecorderService::class.java).setAction(action)
        if (action == RecorderService.ACTION_START) startForegroundService(intent) else startService(intent)
    }
    private fun showFiles() {
        page = "files"; screen("Enregistrements", "Fichiers M4A stockés uniquement sur cet appareil")
        root.addView(button("← Enregistreur") { showAudio() })
        val files = RecorderService.folder(this).listFiles()?.filter { it.extension == "m4a" }?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) { root.addView(text("Aucun fichier enregistré.")); return }
        files.forEach { file ->
            heading(file.nameWithoutExtension.replace('_', ' '))
            root.addView(text("${file.length() / 1024L} Ko", 13f))
            root.addView(button("▶ Écouter / arrêter") {
                player?.release(); player = null
                try {
                    player = MediaPlayer().apply { setDataSource(file.absolutePath); prepare(); start(); setOnCompletionListener { it.release(); player = null } }
                } catch (e: Exception) { Toast.makeText(this, "Lecture impossible", Toast.LENGTH_SHORT).show() }
            })
            root.addView(button("Partager le fichier") {
                // No FileProvider dependency: use document export through ACTION_CREATE_DOCUMENT.
                pendingExport = file
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "audio/mp4"; putExtra(Intent.EXTRA_TITLE, file.name) }
                @Suppress("DEPRECATION") startActivityForResult(intent, 101)
            })
            root.addView(button("Supprimer") {
                android.app.AlertDialog.Builder(this).setTitle("Supprimer ce fichier ?")
                    .setMessage(file.name).setNegativeButton("Annuler", null)
                    .setPositiveButton("Supprimer") { _, _ -> if (file.delete()) showFiles() else Toast.makeText(this, "Suppression impossible", Toast.LENGTH_SHORT).show() }.show()
            })
        }
    }
    private var pendingExport: File? = null
    @Deprecated("Document export result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 101 && resultCode == RESULT_OK) {
            val src = pendingExport; val uri = data?.data
            if (src != null && uri != null) {
                try { contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }; Toast.makeText(this, "Fichier exporté", Toast.LENGTH_SHORT).show() }
                catch (_: Exception) { Toast.makeText(this, "Échec de l'export", Toast.LENGTH_SHORT).show() }
            }
            pendingExport = null
        }
    }
}
