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
    private var activeFile: String? = null
    private var equalizer: android.media.audiofx.Equalizer? = null
    private fun releasePlayer() {
        player?.let { m -> activeFile?.let { k -> runCatching { prefs.edit().putInt("position:$k", m.currentPosition).apply() } } }
        equalizer?.release(); equalizer = null
        player?.release(); player = null; activeFile = null
    }
    private fun mediaFor(file: AudioStorage.Recording): MediaPlayer? {
        if (activeFile == file.key && player != null) return player
        releasePlayer()
        val m = MediaPlayer()
        return try {
            if (file.uri != null) m.setDataSource(this, file.uri) else m.setDataSource(file.file!!.absolutePath)
            m.prepare(); m.seekTo(prefs.getInt("position:${file.key}",0).coerceIn(0,m.duration.coerceAtLeast(0)))
            player=m; activeFile=file.key; m
        } catch (_: Exception) { m.release(); null }
    }
    private fun durationOf(file: AudioStorage.Recording): Long {
        val r = android.media.MediaMetadataRetriever()
        return try { if(file.uri!=null) r.setDataSource(this,file.uri) else r.setDataSource(file.file!!.path); r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L }
        catch (_: Exception) { 0L } finally { runCatching { r.release() } }
    }
    private fun shortTime(ms: Long) = String.format(Locale.FRANCE,"%02d:%02d",ms/60000,ms/1000%60)
    private fun iconButton(label:String, description:String, action:()->Unit): Button = button(label,action).apply { background=null; minWidth=0; minimumWidth=0; textSize=22f; contentDescription=description }

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
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (RecorderService.state == "idle" || !prefs.getBoolean("hide_on_lock", true)) return
            // Léger différé : laisse le verrouillage s'installer avant de sortir la tâche.
            uiHandler.postDelayed({ if (!isFinishing && RecorderService.state != "idle") finishAndRemoveTask() }, 500)
        }
    }
    private val closeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            uiHandler.postDelayed({ if (!isFinishing) finishAndRemoveTask() }, 250)
        }
    }
    private fun registerLocal(filter: String, r: android.content.BroadcastReceiver) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, IntentFilter(filter), RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(r, IntentFilter(filter))
    }
    private var status: TextView? = null
    private var timerText: TextView? = null
    private var routeText: TextView? = null
    private var pauseButton: Button? = null
    private var waveform: WaveformView? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private val meterTick = object : Runnable { override fun run() { if (page == "audio") { updateRecorderControls(); waveform?.live(RecorderService.peaks, RecorderService.recordedMs); uiHandler.postDelayed(this, 40) } } }
    private var startButton: Button? = null
    private val dark get() = resources.configuration.uiMode and 0x30 == 0x20
    private val colors get() = if (dark) intArrayOf(0xffE4E8EF.toInt(), 0xffADB8C8.toInt()) else intArrayOf(0xff263244.toInt(), 0xff596579.toInt())
    private val accent get() = if (dark) 0xffA9BADA.toInt() else 0xff596B8D.toInt()
    private val surface get() = if (dark) 0xff252B35.toInt() else 0xffFFFFFF.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (dark) R.style.AppThemeDark else R.style.AppTheme)
        super.onCreate(savedInstanceState)
        // Protects screen content in recent-app previews and screenshots.
        applyPrivacy()
        registerLocal(Intent.ACTION_SCREEN_OFF, screenReceiver)
        registerLocal(RecorderService.ACTION_CLOSE, closeReceiver)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 22)
        renderConverter()
    }
    override fun onStart() { super.onStart(); if (page == "audio") { uiHandler.removeCallbacks(meterTick); uiHandler.post(meterTick) }; if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, IntentFilter(RecorderService.ACTION_STATE), RECEIVER_NOT_EXPORTED) else @Suppress("DEPRECATION") registerReceiver(receiver, IntentFilter(RecorderService.ACTION_STATE)) }
    override fun onStop() { uiHandler.removeCallbacks(meterTick); super.onStop(); unregisterReceiver(receiver) }
    override fun onDestroy() { releasePlayer(); runCatching { unregisterReceiver(screenReceiver); unregisterReceiver(closeReceiver) }; uiHandler.removeCallbacksAndMessages(null); super.onDestroy() }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Fonctionne sur n'importe quelle page ; écran verrouillé, voir QuickStopService / la MediaSession.
        val enabled = prefs.getBoolean("quick_stop_volume", false)
        val active = RecorderService.state == "recording" || RecorderService.state == "paused"
        if (enabled && active && event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val now = SystemClock.elapsedRealtime()
                if (lastVolumeDownMs != 0L && now - lastVolumeDownMs <= volumeDoublePressWindowMs) {
                    lastVolumeDownMs = 0L
                    sendAction(RecorderService.ACTION_STOP_AND_CLOSE)
                } else lastVolumeDownMs = now
            }
            return true
        }
        lastVolumeDownMs = 0L
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        when (page) { "converter" -> super.onBackPressed(); "settings" -> renderConverter(); "audio" -> showSettings(); "audio_settings" -> showAudio(); "files" -> { selected.clear(); if(trashMode) { trashMode=false; showFiles() } else showAudio() }; "player" -> showFiles(); else -> renderConverter() }
    }
    private fun applyPrivacy() {
        if (prefs.getBoolean("secure_screen", false)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun text(s: String, size: Float = 16f, bold: Boolean = false): TextView = TextView(this).apply {
        setText(s); textSize = size; setTextColor(if (bold) colors[0] else colors[1]); if (bold) setTypeface(null, 1); setPadding(0, dp(6), 0, dp(6))
    }
    private fun button(label: String, f: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 15f
        setTextColor(colors[0]); backgroundTintList = android.content.res.ColorStateList.valueOf(if (dark) 0xff384353.toInt() else 0xffE5EAF2.toInt())
        setPadding(dp(8),dp(8),dp(8),dp(8)); setOnClickListener { f() }
    }
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18),dp(16),dp(18),dp(16))
        background = android.graphics.drawable.GradientDrawable().apply { setColor(surface); cornerRadius = dp(22).toFloat() }
        elevation = 0f
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
        page = "converter"; screen("Convertisseur"); releasePlayer()
        root.removeAllViews()
        root.setBackgroundColor(if(dark) 0xff1C1C1C.toInt() else 0xffF6F6F6.toInt())
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(text("Convertisseur",28f,true),LinearLayout.LayoutParams(0,-2,1f))
        top.addView(iconButton("⚙","Paramètres") { showSettings() },LinearLayout.LayoutParams(dp(48),dp(48))); root.addView(top)
        val search=field("","Rechercher une grandeur").apply { background=android.graphics.drawable.GradientDrawable().apply { setColor(if(dark) 0xff272727.toInt() else 0xffEAEAEA.toInt()); cornerRadius=dp(18).toFloat() }; setPadding(dp(16),dp(12),dp(16),dp(12)) }
        root.addView(search); space(12); root.addView(text("Catégorie",14f))
        val categoryNames=categories.keys.toList()
        val cat=spinner(categoryNames,prefs.getInt("category",0)); root.addView(cat); space(12)
        val source=field("1","Valeur").apply { textSize=32f; background=null; setPadding(0,0,0,0); inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED }
        val from=spinner(listOf("m")); val to=spinner(listOf("km")); val answer=text("",32f,true)
        fun conversionBlock(label:String,value:View,unit:View,outlined:Boolean) {
            val block=card().apply { background=android.graphics.drawable.GradientDrawable().apply { cornerRadius=dp(22).toFloat(); setColor(if(dark) (if(outlined) 0xff1C1C1C.toInt() else 0xff272727.toInt()) else (if(outlined) 0xffF6F6F6.toInt() else 0xffEAEAEA.toInt())); if(outlined) setStroke(dp(1),if(dark) 0xff414141.toInt() else 0xffC7C7C7.toInt()) } }
            block.addView(text(label,14f)); val row=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }; row.addView(value,LinearLayout.LayoutParams(0,-2,1f)); row.addView(unit,LinearLayout.LayoutParams(dp(110),-2)); block.addView(row); root.addView(block)
        }
        conversionBlock("De",source,from,true); space(8)
        val swapRow=LinearLayout(this).apply { gravity=Gravity.CENTER }; swapRow.addView(iconButton("⇅","Inverser les unités") { val a=from.selectedItemPosition; from.setSelection(to.selectedItemPosition); to.setSelection(a) },LinearLayout.LayoutParams(dp(54),dp(54))); root.addView(swapRow); space(8)
        conversionBlock("Vers",answer,to,false)
        val actions=LinearLayout(this).apply { gravity=Gravity.END }; actions.addView(iconButton("☆","Mémoriser les unités") { prefs.edit().putString("favorite_from",from.selectedItem.toString()).putString("favorite_to",to.selectedItem.toString()).putInt("favorite_category",cat.selectedItemPosition).apply(); Toast.makeText(this,"Conversion mémorisée",Toast.LENGTH_SHORT).show() }); actions.addView(iconButton("▢","Copier le résultat") { (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(ClipData.newPlainText("Conversion", "${answer.text} ${to.selectedItem}")) }); root.addView(actions)
        root.addView(text("Autres équivalences",14f)); val equivalents=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }; root.addView(equivalents)
        search.addTextChangedListener(object:TextWatcher { override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}; override fun afterTextChanged(s:Editable?){}; override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){ val q=s.toString().trim(); if(q.isNotEmpty()) categoryNames.indexOfFirst { it.contains(q,true) }.takeIf { it>=0 }?.let { cat.setSelection(it) } } })
        val format = DecimalFormat("#,##0.##########")
        var refreshing = false
        fun calculate() {
            val n = source.text.toString().replace(" ","").replace("\u202f","").replace(',', '.').toDoubleOrNull()
            if (n == null) { answer.text = "—"; equivalents.removeAllViews(); return }
            val category = categoryNames.getOrNull(cat.selectedItemPosition) ?: return
            val units = categories.getValue(category)
            val left = from.selectedItem.toString(); val right = to.selectedItem.toString()
            if(left !in units || right !in units) return
            fun convert(right: String): Double = if (category == "Température") {
                val kelvin = when (left) { "°C" -> n + 273.15; "°F" -> (n - 32) * 5.0 / 9 + 273.15; else -> n }
                when (right) { "°C" -> kelvin - 273.15; "°F" -> (kelvin - 273.15) * 9.0 / 5 + 32; else -> kelvin }
            } else n * units.getValue(left) / units.getValue(right)
            val result=convert(right)
            answer.text = if (result.isFinite()) format.format(result) else "—"
            equivalents.removeAllViews()
            units.keys.filter { it!=left && it!=right }.forEach { u -> val row=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }; row.addView(text(u,16f),LinearLayout.LayoutParams(0,-2,1f)); row.addView(text(format.format(convert(u)),16f,true)); equivalents.addView(row) }
        }
        fun refreshUnits() {
            refreshing = true
            val names = categories.getValue(categoryNames.getOrNull(cat.selectedItemPosition) ?: return).keys.toList()
            from.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
            to.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
            if(prefs.getInt("favorite_category",-1)==cat.selectedItemPosition) {
                from.setSelection(names.indexOf(prefs.getString("favorite_from",null)).coerceAtLeast(0))
                to.setSelection(names.indexOf(prefs.getString("favorite_to",null)).coerceAtLeast(0))
            } else to.setSelection(if (names.size > 2) 2 else 1)
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
        refreshUnits()
    }
    private fun showSettings() {
        uiHandler.removeCallbacks(meterTick); waveform = null
        page = "settings"; screen("Paramètres", "Convertisseur et modules")
        root.addView(button("← Convertisseur") { renderConverter() })
        root.addView(CheckBox(this).apply { text="Bloquer les captures d’écran"; isChecked=prefs.getBoolean("secure_screen",false); setOnCheckedChangeListener { _,checked -> prefs.edit().putBoolean("secure_screen",checked).apply(); applyPrivacy() } })
        heading("Modules")
        root.addView(button("Enregistreur audio  ›") { showAudio() })
        heading("Application")
        root.addView(text("Stockage local uniquement. Pas de connexion réseau, compte ou suivi publicitaire."))
        root.addView(text("Version 0.8.0", 13f))
    }
    private fun toolbar(back: () -> Unit, action: String, next: () -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("‹ Retour", back)); row.addView(Space(this), LinearLayout.LayoutParams(0,1,1f)); row.addView(button(action,next)); root.addView(row)
    }
    private fun controls(vararg views: View) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        views.forEach { row.addView(it, LinearLayout.LayoutParams(0,dp(64),1f).apply { marginStart=dp(3); marginEnd=dp(3) }) }; root.addView(row)
    }
    private fun primary(label: String, action: () -> Unit): Button = button(label,action).apply {
        background = android.graphics.drawable.GradientDrawable().apply { shape=android.graphics.drawable.GradientDrawable.OVAL; setColor(accent) }
        backgroundTintList=null; setTextColor(if(dark) 0xff182235.toInt() else -1); textSize=26f
    }
    private fun showAudio() {
        releasePlayer(); page = "audio"; screen("Enregistreur audio")
        toolbar({showSettings()}, "⚙ Réglages", {showAudioSettings()})
        space(30)
        status = text("", 15f).apply { gravity=Gravity.CENTER }.also { root.addView(it) }
        timerText = text("00:00,00", 44f, true).apply { gravity=Gravity.CENTER; typeface=android.graphics.Typeface.MONOSPACE }.also { root.addView(it) }
        routeText = text("", 13f).apply { gravity=Gravity.CENTER }.also { root.addView(it) }
        waveform = WaveformView(this).also { root.addView(it, LinearLayout.LayoutParams(-1,-2)) }
        space(32)
        val row = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER }
        row.addView(button("Fichiers") { showFiles() },LinearLayout.LayoutParams(0,dp(52),1f))
        startButton=primary("●") { if(RecorderService.state=="idle") startRecording() else sendAction(RecorderService.ACTION_STOP) }.also { row.addView(it,LinearLayout.LayoutParams(dp(76),dp(76)).apply { marginStart=dp(12); marginEnd=dp(12) }) }
        pauseButton=button("Pause") { sendAction(if(RecorderService.state=="paused") RecorderService.ACTION_RESUME else RecorderService.ACTION_PAUSE) }.also { row.addView(it,LinearLayout.LayoutParams(0,dp(52),1f)) }
        root.addView(row); updateRecorderControls()
        uiHandler.removeCallbacks(meterTick); uiHandler.post(meterTick)
    }
    private fun showAudioSettings() {
        uiHandler.removeCallbacks(meterTick); waveform = null
        page = "audio_settings"; screen("Réglages audio", "Paramètres appliqués à la prochaine session")
        root.addView(button("← Enregistreur") { showAudio() })
        heading("Enregistrement")
        root.addView(text("Source audio", 15f))
        val sources = listOf("auto", "internal", "wired", "bluetooth")
        val mic = spinner(listOf("Automatique", "Microphone du téléphone", "Casque filaire", "Microphone Bluetooth"), sources.indexOf(prefs.getString("mic_source", "auto")).coerceAtLeast(0))
        root.addView(mic)
        mic.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { prefs.edit().putString("mic_source",sources[position]).apply() }
        }
        root.addView(text("Durée maximale", 15f))
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
        root.addView(text("Comportement du minuteur",15f))
        val modes = listOf("Temps effectivement enregistré", "Temps écoulé, pauses comprises")
        val timer = spinner(modes, if (prefs.getString("timer_mode", "record") == "elapsed") 1 else 0)
        root.addView(timer)
        timer.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { prefs.edit().putString("timer_mode", if (position == 0) "record" else "elapsed").apply() }
        }
        heading("Périphériques et commandes")
        val quickStop = CheckBox(this).apply {
            text = "Double appui Volume − : arrêter et sauvegarder"
            isChecked = prefs.getBoolean("quick_stop_volume", false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("quick_stop_volume", checked).apply()
                lastVolumeDownMs = 0L
            }
        }
        root.addView(quickStop)
        root.addView(CheckBox(this).apply {
            text = "Quitter l’application au verrouillage de l’écran pendant un enregistrement"
            isChecked = prefs.getBoolean("hide_on_lock", true)
            setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("hide_on_lock", v).apply() }
        })
        val am = getSystemService(ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        val accessibilityOn = am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).any { it.resolveInfo.serviceInfo.packageName == packageName }
        root.addView(text(
            if (accessibilityOn) "Double appui hors application : actif (service d’accessibilité autorisé)."
            else "Double appui écran verrouillé : nécessite le service d’accessibilité ci-dessous. Sans lui, l’appui ne fonctionne qu’application ouverte.",
            13f))
        root.addView(button(if (accessibilityOn) "Réglages d’accessibilité" else "Activer le double appui hors application") {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        heading("Stockage")
        root.addView(text(if (Build.VERSION.SDK_INT >= 29) "Nouveaux enregistrements : Music/Convertisseur/Recordings. Un seul original, accessible dans le gestionnaire de fichiers." else "Stockage interne sur Android 8 et 9 ; export manuel disponible.", 14f))
        heading("Règles de connexion")
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
            "recording" -> "Enregistrement en cours"
            "paused" -> "En pause"
            "connecting" -> "Connexion Bluetooth…"
            else -> "Prêt à enregistrer"
        }
        timerText?.text = formatTime(if(state=="idle") 0L else RecorderService.recordedMs)
        routeText?.text = if(state=="idle") "" else RecorderService.routeLabel
        startButton?.text = if(state=="idle") "●" else "■"
        startButton?.contentDescription = if(state=="idle") "Démarrer l’enregistrement" else "Arrêter et sauvegarder"
        pauseButton?.isEnabled = state == "recording" || state == "paused"
        pauseButton?.text = if (state == "paused") "Reprendre" else "Pause"
    }
    private fun startRecording() {
        if (Build.VERSION.SDK_INT >= 31 && prefs.getString("mic_source","auto")=="bluetooth" && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT),18); return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 17)
        } else {
            sendAction(RecorderService.ACTION_START)
            Handler(Looper.getMainLooper()).postDelayed({ updateRecorderControls() }, 600)
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode in listOf(17,18) && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startRecording()
    }
    private fun sendAction(action: String) {
        val intent = Intent(this, RecorderService::class.java).setAction(action)
        if (action == RecorderService.ACTION_START) startForegroundService(intent) else startService(intent)
    }
    private val selected = linkedSetOf<String>()
    private var trashMode = false
    private fun shareFiles(files: List<AudioStorage.Recording>) {
        if(files.isEmpty()) return
        val uris = ArrayList(files.mapNotNull { it.uri })
        if(uris.size != files.size) {
            if(files.size != 1) { Toast.makeText(this,"Export groupé indisponible pour ces fichiers internes",Toast.LENGTH_SHORT).show(); return }
            pendingExport=files.first(); startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="audio/mp4"; putExtra(Intent.EXTRA_TITLE,files.first().name) },101); return
        }
        val intent=Intent(if(uris.size==1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type="audio/mp4"; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if(uris.size==1) putExtra(Intent.EXTRA_STREAM,uris.first()) else putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris)
            clipData=ClipData.newRawUri("Audio",uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        }; startActivity(Intent.createChooser(intent,"Partager"))
    }
    private fun fileAction(files: List<AudioStorage.Recording>, restore: Boolean = false) {
        if(files.isEmpty()) return
        if(files.any { it.key==activeFile }) releasePlayer()
        val permanent = trashMode && !restore
        android.app.AlertDialog.Builder(this).setTitle(if(restore) "Restaurer ?" else if(permanent) "Supprimer définitivement ?" else "Retirer les fichiers ?")
            .setMessage(if(!restore && !permanent) "Corbeille sur Android 11 et ultérieur pour les fichiers partagés ; suppression définitive sinon." else "${files.size} fichier(s)")
            .setNegativeButton("Annuler",null).setPositiveButton("Confirmer") { _,_ ->
                val failures=files.count { f -> !runCatching { if(restore) f.trash(this,false) else if(!permanent && Build.VERSION.SDK_INT>=30 && f.uri!=null) f.trash(this,true) else f.delete(this) }.getOrDefault(false) }
                if(failures>0) Toast.makeText(this,"$failures opération(s) impossible(s)",Toast.LENGTH_LONG).show()
                selected.clear(); showFiles()
            }.show()
    }
    private fun showFiles() {
        uiHandler.removeCallbacks(meterTick); waveform=null
        page = "files"; screen(if(trashMode) "Corbeille" else "Enregistrements")
        val bar=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        bar.addView(iconButton("‹","Retour") { onBackPressed() }); bar.addView(Space(this),LinearLayout.LayoutParams(0,1,1f))
        if(!trashMode) bar.addView(iconButton("🗑","Corbeille") { selected.clear(); trashMode=true; showFiles() }); root.addView(bar)
        val files = runCatching { AudioStorage.list(this,trashMode) }.getOrElse { root.addView(text("Bibliothèque inaccessible")); return }
        if(selected.isNotEmpty()) {
            val chosen=files.filter { it.key in selected }
            controls(button("Annuler") { selected.clear(); showFiles() },button(if(trashMode) "Restaurer" else "Partager") { if(trashMode) fileAction(chosen,true) else shareFiles(chosen) },button("Supprimer") { fileAction(chosen) })
        }
        if(files.isEmpty()) root.addView(text("Aucun fichier."))
        val dates=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT)
        files.forEach { file ->
            val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(0,dp(7),0,dp(7)) }
            val toggle={ if(!selected.add(file.key)) selected.remove(file.key); showFiles() }
            if(selected.isNotEmpty()) row.addView(CheckBox(this).apply { isChecked=file.key in selected; setOnClickListener { toggle() } })
            val details=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; addView(text(file.title.replace('_',' '),16f,true)); addView(text("${shortTime(durationOf(file))} · ${dates.format(java.util.Date(file.modified))}",12f)); setOnClickListener { if(selected.isNotEmpty() || trashMode) toggle() else showPlayer(file) }; setOnLongClickListener { toggle(); true } }
            row.addView(details,LinearLayout.LayoutParams(0,-2,1f))
            if(!trashMode && selected.isEmpty()) row.addView(iconButton(if(activeFile==file.key && player?.isPlaying==true) "Ⅱ" else "▶","Lecture rapide") { val m=mediaFor(file); if(m!=null) { if(m.isPlaying) m.pause() else m.start(); showFiles() } },LinearLayout.LayoutParams(dp(48),dp(48)))
            row.addView(button("⋮") { val menu=if(trashMode) arrayOf("Restaurer","Supprimer définitivement") else arrayOf("Renommer","Partager","Supprimer","Sélectionner"); android.app.AlertDialog.Builder(this).setItems(menu) { _,i ->
                if(trashMode) fileAction(listOf(file),i==0) else when(i) {
                    0 -> { val input=field(file.title); android.app.AlertDialog.Builder(this).setTitle("Renommer").setView(input).setNegativeButton("Annuler",null).setPositiveButton("OK") { _,_ -> if(runCatching { file.rename(this,input.text.toString().trim()) }.getOrDefault(false)) showFiles() else Toast.makeText(this,"Renommage impossible",Toast.LENGTH_SHORT).show() }.show() }
                    1 -> shareFiles(listOf(file)); 2 -> fileAction(listOf(file)); 3 -> toggle()
                }
            }.show() },LinearLayout.LayoutParams(dp(48),dp(48)))
            root.addView(row)
            if(!trashMode && activeFile==file.key && selected.isEmpty()) {
                val m=player!!; val bar=SeekBar(this).apply { max=m.duration.coerceAtLeast(1); progress=m.currentPosition; setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(b:SeekBar?,v:Int,user:Boolean){ if(user && player===m) m.seekTo(v) }; override fun onStartTrackingTouch(b:SeekBar?){}; override fun onStopTrackingTouch(b:SeekBar?){} }) }; root.addView(bar)
                val t=text("",12f); root.addView(t)
                uiHandler.post(object:Runnable { override fun run(){ if(page!="files" || player!==m || !bar.isAttachedToWindow) return; bar.progress=m.currentPosition; t.text="${shortTime(m.currentPosition.toLong())} / ${shortTime(m.duration.toLong())}"; uiHandler.postDelayed(this,150) } })
            }
            root.addView(View(this).apply { setBackgroundColor(if(dark) 0xff39404B.toInt() else 0xffE2E6ED.toInt()) },LinearLayout.LayoutParams(-1,dp(1)))
        }
    }
    private fun formatTime(ms: Long): String = String.format(Locale.FRANCE, "%02d:%02d,%02d", ms / 60000, ms / 1000 % 60, ms / 10 % 100)
    private fun showPlayer(file: AudioStorage.Recording) {
        page = "player"; screen(file.title)
        toolbar({showFiles()}, "Partager", { shareFiles(listOf(file)) })
        val media=mediaFor(file) ?: run { root.addView(text("Lecture impossible")); return }
        val duration = media.duration.coerceAtLeast(1)
        val time = text("", 32f, true).apply { gravity=Gravity.CENTER }.also { root.addView(it) }
        val wave = WaveformView(this).also { root.addView(it) }
        wave.playback(emptyList(), duration, 0) { media.seekTo(it) }
        Thread {
            val peaks = AudioStorage.decodePeaks(this, file)
            runOnUiThread { if (player === media) wave.playback(peaks, duration, media.currentPosition) { media.seekTo(it) } }
        }.start()
        val seek = SeekBar(this).apply { max = duration; setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) { if (user) media.seekTo(value) }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        }) }; root.addView(seek)
        val play = primary("▶") { if (media.isPlaying) media.pause() else media.start() }
        controls(button("−10 s") { media.seekTo((media.currentPosition - 10000).coerceAtLeast(0)) },play,button("+10 s") { media.seekTo((media.currentPosition + 10000).coerceAtMost(duration)) })
        val speeds = listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f)
        val speed = spinner(speeds.map { "×$it" }, 2); root.addView(speed)
        speed.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { runCatching { val playing = media.isPlaying; media.playbackParams = media.playbackParams.setSpeed(speeds[position]); if (!playing) media.pause() } }
        }
        val markerKey="markers:${file.key}"
        controls(button("Marqueur +") { val marks=prefs.getString(markerKey,"").orEmpty().split(',').mapNotNull { it.toIntOrNull() }; prefs.edit().putString(markerKey,(marks+media.currentPosition).distinct().sorted().joinToString(",")).apply(); Toast.makeText(this,"Marqueur ajouté",Toast.LENGTH_SHORT).show() },button("Repères") { val marks=prefs.getString(markerKey,"").orEmpty().split(',').mapNotNull { it.toIntOrNull() }; android.app.AlertDialog.Builder(this).setTitle("Marqueurs").setItems(marks.map { shortTime(it.toLong()) }.toTypedArray()) { _,i -> media.seekTo(marks[i]) }.setNegativeButton("Fermer",null).show() })
        root.addView(text("Amélioration vocale (égalisation)",14f))
        val eqMode=spinner(listOf("Original","Voix claire","Bruits extérieurs — filtre léger")); root.addView(eqMode)
        eqMode.onItemSelectedListener=object:AdapterView.OnItemSelectedListener { override fun onNothingSelected(p:AdapterView<*>?){}; override fun onItemSelected(p:AdapterView<*>?,v:View?,position:Int,id:Long){ equalizer?.release(); equalizer=null; if(position==0) return; runCatching { val eq=android.media.audiofx.Equalizer(0,media.audioSessionId); equalizer=eq; val range=eq.bandLevelRange; for(i in 0 until eq.numberOfBands.toInt()){ val hz=eq.getCenterFreq(i.toShort())/1000; val gain=when { hz<250 -> if(position==2) -900 else -400; hz in 1000..4000 -> 250; else -> 0 }; eq.setBandLevel(i.toShort(),gain.coerceIn(range[0].toInt(),range[1].toInt()).toShort()) }; eq.enabled=true }.onFailure { Toast.makeText(this@MainActivity,"Égaliseur indisponible",Toast.LENGTH_SHORT).show() } } }
        uiHandler.post(object : Runnable { override fun run() {
            if (player !== media || page != "player" || !wave.isAttachedToWindow) return
            play.text = if(media.isPlaying) "Ⅱ" else "▶"; val current = media.currentPosition; time.text = formatTime(current.toLong()) + " / " + formatTime(duration.toLong()); seek.progress = current; wave.position(current); uiHandler.postDelayed(this, 40)
        } })
    }
    private var pendingExport: AudioStorage.Recording? = null
    @Deprecated("Document export result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 101 && resultCode == RESULT_OK) {
            val src = pendingExport; val uri = data?.data
            if (src != null && uri != null) {
                try { contentResolver.openOutputStream(uri)?.use { out -> src.open(this).use { it.copyTo(out) } }; Toast.makeText(this, "Fichier exporté", Toast.LENGTH_SHORT).show() }
                catch (_: Exception) { Toast.makeText(this, "Échec de l'export", Toast.LENGTH_SHORT).show() }
            }
            pendingExport = null
        }
    }
}