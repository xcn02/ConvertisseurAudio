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
    private lateinit var screenFrame: LinearLayout
    private var headerRow: LinearLayout? = null
    private var page = "converter"
    private var player: MediaPlayer? = null
    private var activeFile: String? = null
    private fun releasePlayer() {
        player?.let { m -> activeFile?.let { k -> runCatching { prefs.edit().putInt("position:$k", m.currentPosition).apply() } } }
        player?.release(); player = null; activeFile = null
    }
    private fun mediaFor(file: AudioStorage.Recording): MediaPlayer? {
        if (activeFile == file.key && player != null) return player
        releasePlayer()
        val m = MediaPlayer()
        return try {
            if (file.uri != null) m.setDataSource(this, file.uri) else m.setDataSource(file.file!!.absolutePath)
            m.prepare(); m.seekTo(prefs.getInt("position:${file.key}",0).coerceIn(0,m.duration.coerceAtLeast(0)))
            player=m; activeFile=file.key; m.setOnCompletionListener { if(player===m && page=="files") showFiles() }; m
        } catch (_: Exception) { m.release(); null }
    }
    private val durationCache = mutableMapOf<String, Pair<Long, Long>>()
    private fun durationOf(file: AudioStorage.Recording): Long {
        durationCache[file.key]?.takeIf { it.first == file.modified }?.let { return it.second }
        val r = android.media.MediaMetadataRetriever()
        val ms = try { if(file.uri!=null) r.setDataSource(this,file.uri) else r.setDataSource(file.file!!.path); r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L }
        catch (_: Exception) { -1L } finally { runCatching { r.release() } }
        if(ms >= 0) durationCache[file.key] = file.modified to ms
        return ms
    }
    private fun shortTime(ms: Long) = if(ms < 0) "—" else if(ms in 1L..999L) String.format(Locale.FRANCE,"%.2f s",ms/1000.0) else String.format(Locale.FRANCE,"%02d:%02d",ms/60000,ms/1000%60)
    private inner class GlyphButton(context: Context): Button(context) {
        private var glyph = 0
        private var art: android.graphics.drawable.Drawable? = null
        fun updateGlyph(value: Int) {
            if(glyph==value) return
            glyph=value; art=if(value!=0) context.getDrawable(value)?.mutate() else null; invalidate()
        }
        override fun onDraw(canvas:android.graphics.Canvas) {
            if(glyph==0) { super.onDraw(canvas); return }
            val icon=art ?: return
            val size=dp(if(width>=dp(64) && height>=dp(64)) 30 else 24)
            val left=(width-size)/2; val top=(height-size)/2
            icon.setBounds(left,top,left+size,top+size); icon.setTint(currentTextColor); icon.draw(canvas)
        }
    }
    private fun iconFor(label:String):Int=when(label) {
        "‹" -> R.drawable.ui_back; "⚙" -> R.drawable.ui_settings; "♲", "⌫", "Supprimer" -> R.drawable.ui_trash
        "☑" -> R.drawable.ui_select; "☐" -> R.drawable.ui_select_empty; "☷" -> R.drawable.ui_folder; "✓" -> R.drawable.ui_check; "⋮" -> R.drawable.ui_more
        "▶" -> R.drawable.ui_play; "Ⅱ" -> R.drawable.ui_pause; "■" -> R.drawable.ui_stop; "●" -> R.drawable.ui_record
        "↶", "↶ Restaurer" -> R.drawable.ui_restore; "↶ 10" -> R.drawable.ui_rewind; "10 ↷" -> R.drawable.ui_forward
        "Partager" -> R.drawable.ui_share; "Repères", "+ Repère" -> R.drawable.ui_bookmark; "Améliorer" -> R.drawable.ui_wave
        "Rechercher" -> R.drawable.ui_search; "×" -> R.drawable.ui_close; "⇅" -> R.drawable.ui_swap; "▢" -> R.drawable.ui_copy; "☆" -> R.drawable.ui_star
        else -> 0
    }
    private fun showGlyph(b:Button?,label:String) { if(b is GlyphButton) { b.updateGlyph(iconFor(label)); val displayed=if(iconFor(label)==0) label else ""; if(b.text.toString()!=displayed) b.text=displayed } else b?.text=label }
    private fun iconButton(label:String, description:String, action:()->Unit): Button = GlyphButton(this).apply {
        isAllCaps=false; minWidth=0; minimumWidth=0; minHeight=0; minimumHeight=0; textSize=15f; gravity=Gravity.CENTER; contentDescription=description; setTextColor(colors[0]); setPadding(0,0,0,0)
        val mask=android.graphics.drawable.GradientDrawable().apply { shape=android.graphics.drawable.GradientDrawable.OVAL; setColor(-1) }
        background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22596B8D),null,mask)
        showGlyph(this,label); setOnClickListener { action() }
    }
    private fun bottomSpace() { root.addView(Space(this),LinearLayout.LayoutParams(1,0,1f)) }
    private fun labeledControl(label:String, description:String, action:()->Unit):LinearLayout=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; addView(iconButton(label,description,action),LinearLayout.LayoutParams(dp(48),dp(48)))
        addView(text(description,11f).apply { gravity=Gravity.CENTER; setPadding(0,0,0,0) }); setOnClickListener { action() }
    }
    private fun labeledPrimary(label:String, description:String, action:()->Unit):LinearLayout=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; addView(primary(label,action),LinearLayout.LayoutParams(dp(72),dp(72)))
        addView(text(description,11f).apply { gravity=Gravity.CENTER; setPadding(0,0,0,0) })
    }


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
    private fun registerLocal(filter: String, r: android.content.BroadcastReceiver) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, IntentFilter(filter), RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(r, IntentFilter(filter))
    }
    private var profileText: TextView? = null
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
    private val surface get() = if (dark) 0xff292929.toInt() else 0xffFFFFFF.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (dark) R.style.AppThemeDark else R.style.AppTheme)
        super.onCreate(savedInstanceState)
        // Protects screen content in recent-app previews and screenshots.
        applyPrivacy()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 22)
        renderConverter()
    }
    override fun onStart() { super.onStart(); if (page == "audio") { uiHandler.removeCallbacks(meterTick); uiHandler.post(meterTick) }; if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, IntentFilter(RecorderService.ACTION_STATE), RECEIVER_NOT_EXPORTED) else @Suppress("DEPRECATION") registerReceiver(receiver, IntentFilter(RecorderService.ACTION_STATE)) }
    override fun onStop() { uiHandler.removeCallbacks(meterTick); super.onStop(); unregisterReceiver(receiver) }
    override fun onDestroy() { releasePlayer(); uiHandler.removeCallbacksAndMessages(null); super.onDestroy() }
    override fun onBackPressed() {
        when (page) { "converter" -> super.onBackPressed(); "settings" -> renderConverter(); "audio" -> leaveRecorder(); "audio_settings" -> showAudio(); "voice_settings" -> showAudioSettings(); "files" -> { if(selectionMode) { selectionMode=false; selected.clear(); showFiles(); return }; selected.clear(); if(trashMode) { trashMode=false; showFiles() } else showAudio() }; "player" -> showFiles(); else -> renderConverter() }
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
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(20)); if(page in listOf("audio","files","player","audio_settings","voice_settings")) setBackgroundColor(if(dark) 0xff191919.toInt() else 0xffF8F9FB.toInt()) }
        scroll.addView(root)
        headerRow=null
        screenFrame=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; if(page in listOf("audio","files","player","audio_settings","voice_settings")) setBackgroundColor(if(dark) 0xff191919.toInt() else 0xffF8F9FB.toInt()); addView(scroll,LinearLayout.LayoutParams(-1,0,1f)) }
        setContentView(screenFrame)
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
        root.addView(text("Version 0.9.1", 13f))
    }
    private fun toolbar(back: () -> Unit, action: String, next: () -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("‹ Retour", back)); row.addView(Space(this), LinearLayout.LayoutParams(0,1,1f)); row.addView(button(action,next)); root.addView(row)
    }
    private fun audioHeader(title: String, back: () -> Unit, action: String, description: String, next: () -> Unit) {
        root.removeAllViews()
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16),dp(6),dp(16),dp(4)) }
        bar.addView(iconButton(if(page=="files" && selectionMode) "×" else "‹", "Retour", back), LinearLayout.LayoutParams(dp(44),dp(48)))
        bar.addView(text(title,20f,true).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END },LinearLayout.LayoutParams(0,-2,1f))
        if(action.isNotEmpty()) bar.addView(iconButton(action,description,next),LinearLayout.LayoutParams(dp(48),dp(48))) else bar.addView(Space(this),LinearLayout.LayoutParams(dp(48),dp(48)))
        headerRow=bar; screenFrame.addView(bar,0,LinearLayout.LayoutParams(-1,dp(58))); space(10)
    }
    private fun leaveRecorder() {
        val st=RecorderService.state
        if(st=="idle") { showSettings(); return }
        if(st=="saving") { showSettings(); return }
        if(st=="connecting") { sendAction(RecorderService.ACTION_STOP); showSettings(); return }
        if(st=="recording") sendAction(RecorderService.ACTION_PAUSE)
        val dialog=android.app.Dialog(this)
        val panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(16),dp(20),dp(24)); background=android.graphics.drawable.GradientDrawable().apply { setColor(surface); cornerRadius=dp(24).toFloat() } }
        val bar=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        val name=field(java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss",Locale.US).format(java.util.Date()))
        bar.addView(iconButton("⌫","Abandonner") { android.app.AlertDialog.Builder(this).setTitle("Abandonner cet enregistrement ?").setNegativeButton("Annuler",null).setPositiveButton("Abandonner") { _,_ -> dialog.dismiss(); sendAction(RecorderService.ACTION_DISCARD); showSettings() }.show() },LinearLayout.LayoutParams(dp(48),dp(48)))
        bar.addView(text("Sauvegarder",18f,true),LinearLayout.LayoutParams(0,-2,1f))
        bar.addView(iconButton("✓","Sauvegarder") { val n=name.text.toString().trim(); if(n.isBlank() || n.contains('/') || n.contains('\\')) { name.error="Nom invalide"; return@iconButton }; val i=Intent(this,RecorderService::class.java).setAction(RecorderService.ACTION_STOP).putExtra("name",n); startService(i); dialog.dismiss(); showSettings() },LinearLayout.LayoutParams(dp(48),dp(48)))
        panel.addView(bar); panel.addView(text("Nom du fichier",12f)); panel.addView(name); panel.addView(iconButton("Reprendre l’enregistrement","Reprendre") { dialog.dismiss(); sendAction(RecorderService.ACTION_RESUME) })
        dialog.setContentView(panel); dialog.window?.apply { setBackgroundDrawableResource(android.R.color.transparent); setLayout(-1,-2); setGravity(Gravity.BOTTOM) }; dialog.show(); dialog.window?.setLayout(-1,-2)
    }
    private fun controlRow(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER
        views.forEach { addView(it,LinearLayout.LayoutParams(0,dp(70),1f).apply { marginStart=dp(3); marginEnd=dp(3) }) }
    }
    private fun controls(vararg views: View) { root.addView(controlRow(*views)) }
    private fun primary(label: String, action: () -> Unit): Button = iconButton(label,label,action).apply {
        val circle=android.graphics.drawable.GradientDrawable().apply { shape=android.graphics.drawable.GradientDrawable.OVAL; setColor(accent) }; background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF),circle,null)
        backgroundTintList=null; setTextColor(if(dark) 0xff182235.toInt() else -1); textSize=26f
    }
    private fun showAudio() {
        releasePlayer(); page = "audio"; screen("Enregistreur audio")
        audioHeader("Enregistreur", {leaveRecorder()}, "⚙", "Réglages", {showAudioSettings()})
        space(24)
        profileText=text("",13f).apply { gravity=Gravity.CENTER }.also { root.addView(it) }
        timerText = text("00:00,00", 44f, false).apply { gravity=Gravity.CENTER; typeface=android.graphics.Typeface.create("sans-serif-light",0); setTextColor(colors[0]) }.also { root.addView(it) }
        status = text("", 15f).apply { gravity=Gravity.CENTER }.also { root.addView(it) }
        routeText = text("", 13f).apply { gravity=Gravity.CENTER }.also { root.addView(it) }
        waveform = WaveformView(this).also { root.addView(it, LinearLayout.LayoutParams(-1,-2)) }
        bottomSpace(); space(24)
        val row = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER }
        row.addView(iconButton("☷","Fichiers") { showFiles() },LinearLayout.LayoutParams(0,dp(52),1f))
        startButton=primary("●") { if(RecorderService.state=="idle") startRecording() else sendAction(RecorderService.ACTION_STOP) }.also { row.addView(it,LinearLayout.LayoutParams(dp(76),dp(76)).apply { marginStart=dp(12); marginEnd=dp(12) }) }
        pauseButton=iconButton("Ⅱ","Pause ou reprise") { sendAction(if(RecorderService.state=="paused") RecorderService.ACTION_RESUME else RecorderService.ACTION_PAUSE) }.also { row.addView(it,LinearLayout.LayoutParams(0,dp(52),1f)) }
        root.addView(row); updateRecorderControls()
        uiHandler.removeCallbacks(meterTick); uiHandler.post(meterTick)
    }
    private fun showAudioSettings() {
        uiHandler.removeCallbacks(meterTick); waveform = null
        page = "audio_settings"; screen("Réglages audio", "Paramètres appliqués à la prochaine session")
        audioHeader("Réglages audio", {showAudio()}, "", "", {})
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
        heading("Stockage")
        root.addView(text(if (Build.VERSION.SDK_INT >= 29) "Nouveaux enregistrements : Music/Convertisseur/Recordings. Un seul original, accessible dans le gestionnaire de fichiers." else "Stockage interne sur Android 8 et 9 ; export manuel disponible.", 14f))
        heading("Règles de connexion")
        rule("Jack : débranchement", "jack_off", "pause")
        rule("Jack : branchement", "jack_on", "none")
        rule("Bluetooth : déconnexion", "bt_off", "pause")
        rule("Bluetooth : connexion", "bt_on", "none")
        heading("Qualité")
        val chosen=VoiceProfile.labels.getOrElse(prefs.getInt("voice_profile",0)) { "Original" }
        val voiceRow=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; minimumHeight=dp(54); setOnClickListener { showVoiceSettings() }; addView(text("Traitement vocal",16f,true),LinearLayout.LayoutParams(0,-2,1f)); addView(text("$chosen  ›",14f)) }; root.addView(voiceRow)
        root.addView(CheckBox(this).apply { text="Prétraitement vocal Android (selon appareil)"; isChecked=prefs.getBoolean("voice_capture",false); setOnCheckedChangeListener { _,v -> prefs.edit().putBoolean("voice_capture",v).apply() } })
        root.addView(text("Traitement fourni par le téléphone, efficacité variable selon le microphone.",13f))
        val quality = spinner(listOf("AAC mono • 64 kbit/s", "AAC mono • 128 kbit/s"), if (prefs.getInt("bitrate", 64000) == 128000) 1 else 0)
        root.addView(quality)
        quality.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { prefs.edit().putInt("bitrate", if (position == 0) 64000 else 128000).apply() }
        }
        root.addView(text("Les nouveaux paramètres prennent effet à la prochaine session.", 13f))
    }
    private fun showVoiceSettings() {
        page="voice_settings"; screen("Traitement vocal")
        audioHeader("Traitement vocal",{showAudioSettings()},"","",{})
        root.addView(text("Profil d’enregistrement",14f,true))
        var chosen=prefs.getInt("voice_profile",0).coerceIn(0,4)
        val choices=RadioGroup(this).apply { orientation=LinearLayout.VERTICAL }
        val summaries=listOf("Sans filtre numérique", "Voix naturelle · bruit léger", "Bruit ambiant · intelligibilité", "Atténuation plus forte · risque d’altération", "Réglages indépendants")
        VoiceProfile.labels.forEachIndexed { i,label -> choices.addView(RadioButton(this).apply { id=View.generateViewId(); text="$label\n${summaries[i]}"; textSize=15f; setPadding(0,dp(7),0,dp(7)); setTextColor(colors[0]); isChecked=i==chosen }) }
        root.addView(choices)
        val noiseLabel=text("",14f); root.addView(noiseLabel)
        val noise=SeekBar(this).apply { max=100 }; root.addView(noise)
        val clarityLabel=text("",14f); root.addView(clarityLabel)
        val clarity=SeekBar(this).apply { max=100 }; root.addView(clarity)
        val gain=CheckBox(this).apply { text="Gain automatique doux" }; root.addView(gain)
        val rumble=CheckBox(this).apply { text="Atténuer les grondements" }; root.addView(rumble)
        var updating=false
        fun refresh() {
            updating=true
            val profile=VoiceProfile.forIndex(prefs,chosen)
            noise.progress=profile.noise; clarity.progress=profile.clarity; gain.isChecked=profile.gain; rumble.isChecked=profile.rumble
            noiseLabel.text="Réduction du bruit : ${profile.noise} %"; clarityLabel.text="Clarté vocale : ${profile.clarity} %"
            noise.isEnabled=chosen==4; clarity.isEnabled=chosen==4; gain.isEnabled=chosen==4; rumble.isEnabled=chosen==4
            updating=false
        }
        fun saveCustom() {
            if(updating || chosen!=4) return
            prefs.edit().putInt("voice_noise",noise.progress).putInt("voice_clarity",clarity.progress).putBoolean("voice_gain",gain.isChecked).putBoolean("voice_rumble",rumble.isChecked).apply()
            noiseLabel.text="Réduction du bruit : ${noise.progress} %"; clarityLabel.text="Clarté vocale : ${clarity.progress} %"
        }
        choices.setOnCheckedChangeListener { group,id -> val i=(0 until group.childCount).indexOfFirst { group.getChildAt(it).id==id }; if(i>=0) { chosen=i; prefs.edit().putInt("voice_profile",i).apply(); refresh() } }
        val listener=object:SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar:SeekBar?,value:Int,user:Boolean) { if(user) saveCustom() }
            override fun onStartTrackingTouch(bar:SeekBar?) {}
            override fun onStopTrackingTouch(bar:SeekBar?) {}
        }
        noise.setOnSeekBarChangeListener(listener); clarity.setOnSeekBarChangeListener(listener)
        gain.setOnCheckedChangeListener { _,_ -> saveCustom() }; rumble.setOnCheckedChangeListener { _,_ -> saveCustom() }
        refresh()
        root.addView(text("Les filtres sont appliqués avant l’encodage AAC. Les pourcentages sont des intensités, pas une mesure de réduction du bruit. Le prétraitement Android reste indépendant.",13f))
        root.addView(text("Pour comparer les profils, réalise deux enregistrements d’essai dans les mêmes conditions.",13f))
        root.addView(button("Faire un essai dans l’enregistreur") { showAudio() })
    }
    private fun improveFile(file:AudioStorage.Recording) {
        val labels=(VoiceProfile.labels.drop(1)+listOf("RNNoise · léger", "RNNoise · équilibré", "RNNoise · renforcé")).toTypedArray()
        android.app.AlertDialog.Builder(this).setTitle("Améliorer l’audio").setItems(labels) { _,index ->
            val profile=VoiceProfile.forIndex(prefs,index+1)
            val neural=listOf(35,65,100).getOrNull(index-4)
            player?.runCatching { if(isPlaying) pause() }
            val progress=android.app.ProgressDialog(this).apply { setMessage("Traitement audio…"); setCancelable(false); show() }
            val app=applicationContext; val bitrate=prefs.getInt("bitrate",64000)
            Thread({
                val result=runCatching { VoiceFileProcessor.process(app,file,profile,bitrate,neural) }
                runOnUiThread {
                    if(isFinishing || isDestroyed) { result.getOrNull()?.delete(); return@runOnUiThread }
                    progress.dismiss()
                    result.onSuccess { previewProcessed(file,it) }.onFailure { android.app.AlertDialog.Builder(this).setTitle("Traitement impossible").setMessage(it.localizedMessage ?: "Erreur audio").setPositiveButton("OK",null).show() }
                }
            },"voice-file").start()
        }.setNegativeButton("Annuler",null).show()
    }
    private fun previewProcessed(file:AudioStorage.Recording,temp:File) {
        var preview:MediaPlayer?=null
        var position=0
        var current=""
        fun listen(processed:Boolean) {
            val name=if(processed) "processed" else "original"
            if(current==name && preview?.isPlaying==true) { preview?.pause(); return }
            if(current==name && preview!=null) { preview?.start(); return }
            preview?.let { position=runCatching { it.currentPosition }.getOrDefault(0); it.release() }; preview=null
            val m=MediaPlayer()
            try {
                if(processed) m.setDataSource(temp.path) else if(file.uri!=null) m.setDataSource(this,file.uri) else m.setDataSource(file.file!!.path)
                m.prepare(); m.seekTo(position.coerceIn(0,m.duration.coerceAtLeast(0))); m.start(); preview=m; current=name
            } catch(_:Exception) { m.release(); Toast.makeText(this,"Préécoute impossible",Toast.LENGTH_SHORT).show() }
        }
        val panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(12),dp(20),dp(12)) }
        panel.addView(text("Compare les deux versions. Le fichier original reste intact.",14f))
        panel.addView(button("Écouter / pause · Original") { listen(false) })
        panel.addView(button("Écouter / pause · Traité") { listen(true) })
        var keep=false
        val dialog=android.app.AlertDialog.Builder(this).setTitle("Préécoute").setView(panel).setNegativeButton("Annuler",null).setPositiveButton("Enregistrer une copie") { _,_ ->
            keep=true; val app=applicationContext
            Thread({ val result=runCatching { VoiceFileProcessor.saveCopy(app,temp,file.name) }; temp.delete(); runOnUiThread { if(!isFinishing && !isDestroyed) { Toast.makeText(this,if(result.isSuccess) "Version traitée enregistrée" else "Échec de la sauvegarde",Toast.LENGTH_LONG).show(); if(result.isSuccess) showFiles() } } },"voice-copy").start()
        }.create()
        dialog.setOnDismissListener { preview?.release(); preview=null; if(!keep) temp.delete() }; dialog.show()
    }
    private fun updateRecorderControls() {
        val state = RecorderService.state
        profileText?.text = if(state=="idle") VoiceProfile.labels.getOrElse(prefs.getInt("voice_profile",0)) { "Original" } else RecorderService.activeProfileLabel
        startButton?.isEnabled=state!="saving"
        status?.text = when (state) {
            "recording" -> "Enregistrement en cours"
            "paused" -> "En pause"
            "connecting" -> "Connexion Bluetooth…"
            "saving" -> "Sauvegarde…"
            else -> "Prêt à enregistrer"
        }
        timerText?.text = formatTime(if(state=="idle") 0L else RecorderService.recordedMs)
        routeText?.text = if(state=="idle") "" else RecorderService.routeLabel
        showGlyph(startButton,if(state=="idle") "●" else "■")
        startButton?.contentDescription = if(state=="idle") "Démarrer l’enregistrement" else "Arrêter et sauvegarder"
        pauseButton?.isEnabled = state == "recording" || state == "paused"
        pauseButton?.alpha=if(pauseButton?.isEnabled==true) 1f else .35f
        showGlyph(pauseButton,if (state == "paused") "▶" else "Ⅱ")
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
    private var selectionMode = false
    private var trashMode = false
    private var fileQuery = ""
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
                selectionMode=false; selected.clear(); showFiles()
            }.show()
    }
    private fun showFiles() {
        uiHandler.removeCallbacks(meterTick); waveform=null
        page = "files"; screen(if(trashMode) "Corbeille" else "Enregistrements")
        val allFiles = runCatching { AudioStorage.list(this,trashMode) }.getOrElse { root.addView(text("Bibliothèque inaccessible")); return }
        val files=if(fileQuery.isBlank() || trashMode) allFiles else allFiles.filter { it.title.contains(fileQuery,true) }
        audioHeader(if(selectionMode) "${selected.size} sélectionné(s)" else if(trashMode) "Corbeille" else "Enregistrements", {onBackPressed()}, if(selectionMode) (if(selected.size==files.size && files.isNotEmpty()) "☑" else "☐") else if(trashMode) "⋮" else "♲", if(selectionMode) "Tout sélectionner ou désélectionner" else if(trashMode) "Sélectionner" else "Corbeille") {
            if(selectionMode) { if(selected.size==files.size) selected.clear() else selected.addAll(files.map { it.key }); showFiles() }
            else if(trashMode) { selectionMode=true; showFiles() } else { selected.clear(); trashMode=true; showFiles() }
        }
        if(!selectionMode && !trashMode) {
            val header=requireNotNull(headerRow)
            header.addView(iconButton("Rechercher","Rechercher") { val query=field(fileQuery,"Nom du fichier"); android.app.AlertDialog.Builder(this).setTitle("Rechercher").setView(query).setNeutralButton("Effacer") { _,_ -> fileQuery=""; showFiles() }.setNegativeButton("Annuler",null).setPositiveButton("Rechercher") { _,_ -> fileQuery=query.text.toString().trim(); showFiles() }.show() },header.childCount-1,LinearLayout.LayoutParams(dp(48),dp(48)))
            if(fileQuery.isNotBlank()) root.addView(text("Recherche : $fileQuery",13f))
        }
        if(files.isEmpty()) root.addView(text("Aucun fichier."))
        val dates=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT)
        files.forEach { file ->
            val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(0,dp(7),0,dp(7)) }
            val toggle={ selectionMode=true; if(!selected.add(file.key)) selected.remove(file.key); showFiles() }
            val details=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; addView(text(file.title.replace('_',' '),16f,false).apply { setTextColor(if(activeFile==file.key) accent else colors[0]); maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END }); addView(text("${shortTime(durationOf(file))} · ${dates.format(java.util.Date(file.modified))}",12f)); setOnClickListener { if(selectionMode || trashMode) toggle() else showPlayer(file) }; setOnLongClickListener { toggle(); true } }
            row.addView(details,LinearLayout.LayoutParams(0,-2,1f))
            if(!trashMode && !selectionMode) row.addView(iconButton(if(activeFile==file.key && player?.isPlaying==true) "Ⅱ" else "▶","Lecture rapide") { val m=mediaFor(file); if(m!=null) { if(m.isPlaying) m.pause() else m.start(); showFiles() } },LinearLayout.LayoutParams(dp(48),dp(48)))
            if(selectionMode) row.addView(CheckBox(this).apply { isChecked=file.key in selected; setOnClickListener { toggle() } })
            else if(trashMode) row.addView(iconButton("↶","Restaurer") { fileAction(listOf(file),true) })
            root.addView(row)
            if(!trashMode && activeFile==file.key && !selectionMode) {
                val m=player!!; val bar=SeekBar(this).apply { max=m.duration.coerceAtLeast(1); progress=m.currentPosition; setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(b:SeekBar?,v:Int,user:Boolean){ if(user && player===m) m.seekTo(v) }; override fun onStartTrackingTouch(b:SeekBar?){}; override fun onStopTrackingTouch(b:SeekBar?){} }) }; root.addView(bar)
                val t=text("",12f); root.addView(t)
                uiHandler.post(object:Runnable { override fun run(){ if(page!="files" || player!==m || !bar.isAttachedToWindow) return; bar.progress=m.currentPosition; t.text="${shortTime(m.currentPosition.toLong())} / ${shortTime(m.duration.toLong())}"; uiHandler.postDelayed(this,150) } })
            }
            root.addView(View(this).apply { setBackgroundColor(if(dark) 0xff303030.toInt() else 0xffE5E8ED.toInt()) },LinearLayout.LayoutParams(-1,dp(1)))
        }
        if(selectionMode) {
            val chosen=allFiles.filter { it.key in selected }
            val footer=controlRow(labeledControl(if(trashMode) "↶ Restaurer" else "Partager",if(trashMode) "Restaurer" else "Partager") { if(trashMode) fileAction(chosen,true) else shareFiles(chosen) },labeledControl("Supprimer","Supprimer") { fileAction(chosen) }).apply { setPadding(dp(12),dp(4),dp(12),dp(8)) }
            screenFrame.addView(footer,LinearLayout.LayoutParams(-1,-2))
        }
    }
    private fun formatTime(ms: Long): String = String.format(Locale.FRANCE, "%02d:%02d,%02d", ms / 60000, ms / 1000 % 60, ms / 10 % 100)
    private fun showPlayer(file: AudioStorage.Recording) {
        page = "player"; screen(file.title)
        audioHeader(file.title,{showFiles()},"⋮","Actions") {
            android.app.AlertDialog.Builder(this).setItems(arrayOf("Renommer","Partager","Supprimer","Améliorer l’audio")) { _,i -> when(i) {
                0 -> { val input=field(file.title); android.app.AlertDialog.Builder(this).setTitle("Renommer").setView(input).setNegativeButton("Annuler",null).setPositiveButton("OK") { _,_ -> releasePlayer(); if(file.rename(this,input.text.toString().trim())) showFiles() }.show() }
                1 -> shareFiles(listOf(file)); 2 -> fileAction(listOf(file)); 3 -> improveFile(file)
            } }.show()
        }
        val media=mediaFor(file) ?: run { root.addView(text("Lecture impossible")); return }
        val duration = media.duration.coerceAtLeast(1)
        val time = text("", 42f, false).apply { gravity=Gravity.CENTER; setTextColor(colors[0]); typeface=android.graphics.Typeface.create("sans-serif-light",0) }.also { root.addView(it) }
        val totalTime=text(formatTime(duration.toLong()),14f).apply { gravity=Gravity.CENTER }.also { root.addView(it) }
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
        bottomSpace(); space(18)
        val play = primary("▶") { if (media.isPlaying) media.pause() else media.start() }
        val playbackRow=LinearLayout(this).apply { gravity=Gravity.CENTER }
        playbackRow.addView(labeledControl("↶ 10","−10 s") { media.seekTo((media.currentPosition-10000).coerceAtLeast(0)) },LinearLayout.LayoutParams(0,dp(64),1f))
        playbackRow.addView(play,LinearLayout.LayoutParams(dp(72),dp(72)))
        playbackRow.addView(labeledControl("10 ↷","+10 s") { media.seekTo((media.currentPosition+10000).coerceAtMost(duration)) },LinearLayout.LayoutParams(0,dp(64),1f)); root.addView(playbackRow)
        val speeds = listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f)
        var speedIndex=speeds.indexOf(runCatching { media.playbackParams.speed }.getOrDefault(1f)).takeIf { it>=0 } ?: 2
        var speedGlyph:Button?=null
        val speedButton=labeledControl("×1", "Vitesse") {
            android.app.AlertDialog.Builder(this).setTitle("Vitesse de lecture").setSingleChoiceItems(speeds.map { "×$it" }.toTypedArray(),speedIndex) { dlg, i ->
                runCatching { val wasPlaying=media.isPlaying; media.playbackParams=media.playbackParams.setSpeed(speeds[i]); if(!wasPlaying) media.pause(); speedIndex=i; showGlyph(speedGlyph,"×${speeds[i]}") }; dlg.dismiss()
            }.setNegativeButton("Annuler",null).show()
        }
        speedGlyph=speedButton.getChildAt(0) as? Button
        showGlyph(speedGlyph,"×${speeds[speedIndex]}")
        val markerKey="markers:${file.key}"
        space(16)
        val markerAction={ val marks=prefs.getString(markerKey,"").orEmpty().split(',').mapNotNull { it.toIntOrNull() }; prefs.edit().putString(markerKey,(marks+media.currentPosition).distinct().sorted().joinToString(",")).apply(); Toast.makeText(this,"Marqueur ajouté",Toast.LENGTH_SHORT).show() }
        val markerMenu={ android.app.AlertDialog.Builder(this).setTitle("Repères").setItems(arrayOf("Ajouter un repère","Liste des repères")) { _,i -> if(i==0) markerAction() else { val marks=prefs.getString(markerKey,"").orEmpty().split(',').mapNotNull { it.toIntOrNull() }; android.app.AlertDialog.Builder(this).setTitle("Marqueurs").setItems(marks.map { shortTime(it.toLong()) }.toTypedArray()) { _,j -> media.seekTo(marks[j]) }.setNegativeButton("Fermer",null).show() } }.show(); Unit }
        controls(labeledControl("Repères","Repères",markerMenu),speedButton,labeledControl("Améliorer","Améliorer") { improveFile(file) },labeledControl("Partager","Partager") { shareFiles(listOf(file)) })
        uiHandler.post(object : Runnable { override fun run() {
            if (player !== media || page != "player" || !wave.isAttachedToWindow) return
            showGlyph(play,if(media.isPlaying) "Ⅱ" else "▶"); play.contentDescription=if(media.isPlaying) "Pause" else "Lecture"; val current = media.currentPosition; time.text = formatTime(current.toLong()); seek.progress = current; wave.position(current); uiHandler.postDelayed(this, 40)
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