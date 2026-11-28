package fr.local.convertisseuraudio

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class RecorderService : Service() {
    companion object {
        const val ACTION_START = "fr.local.convertisseuraudio.START"
        const val ACTION_PAUSE = "fr.local.convertisseuraudio.PAUSE"
        const val ACTION_RESUME = "fr.local.convertisseuraudio.RESUME"
        const val ACTION_STOP = "fr.local.convertisseuraudio.STOP"
        const val ACTION_STOP_EXIT = "fr.local.convertisseuraudio.STOP_EXIT"
        const val ACTION_MARK = "fr.local.convertisseuraudio.MARK"
        val liveMarkers = mutableListOf<Int>()
        const val ACTION_DISCARD = "fr.local.convertisseuraudio.DISCARD"
        const val ACTION_STATE = "fr.local.convertisseuraudio.STATE"
        const val EXTRA_STATE = "state"
        @Volatile var state = "idle"
        @Volatile var routeLabel = "Source non vérifiée"
        @Volatile var activeProfileLabel = "Original"
        @Volatile var seconds = 0L
        @Volatile var amplitude = 0
        val peaks = ArrayList<Int>()
        @Volatile var recordedMs = 0L
        const val CHANNEL_ID = "audio_recording"
        const val NOTIF_ID = 1024
        fun folder(context: Context): File = File(context.filesDir, "audio").apply { mkdirs() }
    }
    private var stopNavigation = "stay"
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF || state !in setOf("recording", "paused")) return
            val choice = getSharedPreferences("settings", MODE_PRIVATE).getString("lock_action", "stay")
            if (choice == "stop_home" || choice == "stop_exit") {
                stopNavigation = choice
                stopRecording()
            }
        }
    }
    private var recorder: MediaRecorder? = null
    private var pcmRecorder: PcmVoiceRecorder? = null
    private var routingBluetooth = false
    private var previousMode = AudioManager.MODE_NORMAL
    private var lastShownSecond = -1L
    private fun clearBluetooth() {
        if(!routingBluetooth) return
        routingBluetooth=false
        runCatching { if(Build.VERSION.SDK_INT>=31) audioManager.clearCommunicationDevice() else { @Suppress("DEPRECATION") audioManager.stopBluetoothSco(); @Suppress("DEPRECATION") audioManager.isBluetoothScoOn=false } }
        runCatching { audioManager.mode=previousMode }
    }
    private fun prepareBluetooth() {
        try {
            previousMode=audioManager.mode; routingBluetooth=true; state="connecting"; routeLabel="Connexion du microphone Bluetooth"; promote(); broadcastState()
            audioManager.mode=AudioManager.MODE_IN_COMMUNICATION
            if(Build.VERSION.SDK_INT>=31) {
                val device=audioManager.availableCommunicationDevices.firstOrNull { it.type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type==AudioDeviceInfo.TYPE_BLE_HEADSET } ?: error("Profil mains libres absent")
                check(audioManager.setCommunicationDevice(device)) { "Routage Bluetooth refusé" }
            } else { @Suppress("DEPRECATION") audioManager.startBluetoothSco(); @Suppress("DEPRECATION") audioManager.isBluetoothScoOn=true }
            val deadline=SystemClock.elapsedRealtime()+8000L
            handler.post(object:Runnable { override fun run() {
                if(state!="connecting") return
                val input=audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).any { it.type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type==AudioDeviceInfo.TYPE_BLE_HEADSET }
                val routed=if(Build.VERSION.SDK_INT>=31) audioManager.communicationDevice?.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO,AudioDeviceInfo.TYPE_BLE_HEADSET) else audioManager.isBluetoothScoOn
                if(input && routed) { state="idle"; startRecording(true) }
                else if(SystemClock.elapsedRealtime()<deadline) handler.postDelayed(this,250)
                else bluetoothFailure("Micro Bluetooth indisponible : délai de connexion dépassé")
            } })
        } catch(e:Exception) { bluetoothFailure(e.localizedMessage ?: "Connexion Bluetooth impossible") }
    }
    private fun bluetoothFailure(message:String) { clearBluetooth(); state="idle"; broadcastState(message); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }

    private var target: File? = null
    private var sharedTarget: android.net.Uri? = null
    private var descriptor: ParcelFileDescriptor? = null
    private val handler = Handler(Looper.getMainLooper())
    private var accumulated = 0L
    private var segmentStart = 0L
    private var wallStart = 0L
    private var limitSeconds = 0L
    private var timerMode = "record"
    private lateinit var audioManager: AudioManager
    private var callback: AudioDeviceCallback? = null
    private val clock = object : Runnable {
        override fun run() {
            if (state != "idle") {
                seconds = if (timerMode == "elapsed") {
                    (SystemClock.elapsedRealtime() - wallStart) / 1000L
                } else {
                    (accumulated + if (state == "recording") SystemClock.elapsedRealtime() - segmentStart else 0L) / 1000L
                }
                if (limitSeconds > 0L && seconds >= limitSeconds) { stopRecording(); return }
                if(pcmRecorder?.healthy == false) { stopRecording(); return }
                amplitude = if (state == "recording") (pcmRecorder?.amplitude ?: recorder?.runCatching { maxAmplitude }?.getOrNull() ?: 0) else 0
                recordedMs = accumulated + if (state == "recording") SystemClock.elapsedRealtime() - segmentStart else 0L
                if (state != "connecting" && seconds != lastShownSecond) { lastShownSecond = seconds; promote() }
                if (state == "recording") {
                    peaks.add(amplitude)
                    if(Build.VERSION.SDK_INT >= 28) routeLabel = runCatching { (pcmRecorder?.routedDevice ?: recorder?.routedDevice)?.let { "Microphone : ${when(it.type) { AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Téléphone"; AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth"; AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> "Casque filaire"; else -> it.productName.toString() }}" } }.getOrNull() ?: "Source non vérifiée"
                }
                handler.postDelayed(this, 40)
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        val channel = NotificationChannel(CHANNEL_ID, "Enregistrement audio", NotificationManager.IMPORTANCE_LOW).apply { setSound(null,null); enableVibration(false) }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) = handleDeviceChange(devices, false)
            override fun onAudioDevicesAdded(devices: Array<out AudioDeviceInfo>) = handleDeviceChange(devices, true)
        }
        audioManager.registerAudioDeviceCallback(callback, handler)
    }
    private fun handleDeviceChange(devices: Array<out AudioDeviceInfo>, added: Boolean) {
        if (state != "recording") return
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val wired = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET)
        val bluetooth = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET)
        val key = when {
            devices.any { it.type in wired } -> if (added) "jack_on" else "jack_off"
            devices.any { it.type in bluetooth } -> if (added) "bt_on" else "bt_off"
            else -> return
        }
        when (prefs.getString(key, if (!added) "pause" else "none")) {
            "pause" -> pauseRecording()
            "stop" -> stopRecording()
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (state == "idle") startRecording()
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP -> stopRecording(name = intent.getStringExtra("name"))
            ACTION_STOP_EXIT -> if (state == "recording" || state == "paused") { stopNavigation = "stop_exit"; stopRecording() }
            ACTION_DISCARD -> stopRecording(discard = true, name = intent.getStringExtra("name"))
            ACTION_MARK -> if(state == "recording" || state == "paused") { val ms=(accumulated + if(state=="recording") SystemClock.elapsedRealtime()-segmentStart else 0L).coerceIn(0L,Int.MAX_VALUE.toLong()).toInt(); if(ms !in liveMarkers) liveMarkers.add(ms); broadcastState() }
        }
        return START_NOT_STICKY
    }
    private fun startRecording(bluetoothReady:Boolean=false) {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if(prefs.getString("mic_source","auto")=="bluetooth" && !bluetoothReady) { prepareBluetooth(); return }
        limitSeconds = prefs.getLong("limit_seconds", 180L)
        timerMode = prefs.getString("timer_mode", "record") ?: "record"
        stopNavigation = "stay"
        seconds = 0L; accumulated = 0L; recordedMs = 0L; peaks.clear(); liveMarkers.clear(); lastShownSecond = -1L
        val name = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(Date()) + ".m4a"
        target = null; sharedTarget = null
        try {
            // Promotion to foreground occurs while the activity is visible and recording is authorized.
            promote()
            val profile = VoiceProfile.from(prefs)
            activeProfileLabel = VoiceProfile.labels.getOrElse(prefs.getInt("voice_profile",0)) { "Original" }
            val digital = prefs.getInt("voice_profile",0) != 0
            val rate = if(bluetoothReady) 16000 else 44100
            val bitrate = prefs.getInt("bitrate",64000)
            val audioSource = if(bluetoothReady || prefs.getBoolean("voice_capture",false)) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC
            routeLabel = "Source non vérifiée"
            val source = prefs.getString("mic_source","auto")
            val preferred = if(source != "auto") {
                check(digital || Build.VERSION.SDK_INT >= 28) { "Sélection du microphone : Android 9 minimum pour le mode original" }
                val types = when(source) {
                    "internal" -> setOf(AudioDeviceInfo.TYPE_BUILTIN_MIC)
                    "wired" -> setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET,AudioDeviceInfo.TYPE_USB_HEADSET)
                    else -> setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO,AudioDeviceInfo.TYPE_BLE_HEADSET)
                }
                audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type in types } ?: error("Microphone choisi indisponible")
            } else null
            if (Build.VERSION.SDK_INT >= 29) {
                val uri = AudioStorage.create(this,name); sharedTarget=uri
                descriptor=contentResolver.openFileDescriptor(uri,"rw") ?: error("Fichier inaccessible")
            } else target=File(folder(this),name)
            if(digital) {
                val pcm=PcmVoiceRecorder(rate,bitrate,audioSource,preferred,profile,descriptor?.fileDescriptor,target?.absolutePath)
                pcmRecorder=pcm; pcm.start()
            } else {
                @Suppress("DEPRECATION")
                val media=MediaRecorder(); recorder=media
                media.setAudioSource(audioSource)
                if(preferred!=null && Build.VERSION.SDK_INT>=28) check(media.setPreferredDevice(preferred)) { "Sélection du microphone refusée" }
                media.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                media.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                media.setAudioChannels(1); media.setAudioEncodingBitRate(bitrate); media.setAudioSamplingRate(rate)
                if(descriptor!=null) media.setOutputFile(descriptor!!.fileDescriptor) else media.setOutputFile(target!!.absolutePath)
                media.prepare(); media.start()
            }
            wallStart = SystemClock.elapsedRealtime()
            segmentStart = wallStart
            state = "recording"
            handler.removeCallbacks(clock)
            handler.post(clock)
            promote()
            broadcastState()
        } catch (e: Exception) {
            recorder?.runCatching { release() }
            recorder = null
            pcmRecorder?.runCatching { stop() }; pcmRecorder=null
            finalizeTarget(false)
            clearBluetooth(); state = "idle"
            broadcastState("Impossible de démarrer : ${e.localizedMessage}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }
    private fun pauseRecording() {
        if (state != "recording") return
        try {
            if(pcmRecorder!=null) pcmRecorder?.pause() else recorder?.pause()
            accumulated += SystemClock.elapsedRealtime() - segmentStart
            state = "paused"
            promote(); broadcastState()
        } catch (e: Exception) { stopRecording() }
    }
    private fun resumeRecording() {
        if (state != "paused") return
        try {
            if(pcmRecorder!=null) pcmRecorder?.resume() else recorder?.resume()
            segmentStart = SystemClock.elapsedRealtime()
            state = "recording"
            promote(); broadcastState()
        } catch (e: Exception) { stopRecording() }
    }
    private fun stopRecording(discard: Boolean = false, name: String? = null) {
        if(state=="connecting") { bluetoothFailure("Connexion annulée"); return }
        if (state == "idle") { stopSelf(); return }
        if (state == "saving") return
        handler.removeCallbacks(clock)
        val current = recorder
        recorder = null
        val pcm=pcmRecorder; pcmRecorder=null
        if(pcm!=null) {
            state="saving"; amplitude=0; promote(); broadcastState()
            Thread({
                val ok=runCatching { pcm.stop() }.getOrDefault(false)
                handler.post { finishStopped(ok,discard,name) }
            },"voice-save").start()
            return
        }
        var valid = true
        try { current?.stop() } catch (_: RuntimeException) { valid = false }
        finally { current?.runCatching { release() } }
        finishStopped(valid,discard,name)
    }
    private fun finishStopped(complete:Boolean, discard:Boolean, name:String?) {
        clearBluetooth(); val valid = finalizeTarget(complete, name, discard)
        state = "idle"; seconds = 0L; amplitude = 0
        val navigation = if (valid && !discard) stopNavigation else "stay"
        stopNavigation = "stay"
        broadcastState(if (discard && valid) "Enregistrement déplacé dans la corbeille" else if (valid) "Fichier sauvegardé" else "Enregistrement trop court ou endommagé", navigation)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    private fun finalizeTarget(valid: Boolean, name: String? = null, trash: Boolean = false): Boolean {
        runCatching { descriptor?.close() }; descriptor = null
        val uri = sharedTarget; sharedTarget = null
        val file = target; target = null
        if (uri != null) {
            return runCatching {
                if (valid) {
                    val ok=contentResolver.update(uri, ContentValues().apply { put(android.provider.MediaStore.Audio.Media.IS_PENDING, 0); if(!name.isNullOrBlank() && !name.contains('/') && !name.contains('\\')) put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME,name.removeSuffix(".m4a")+".m4a") }, null, null)>0
                    if(ok) Markers.write(this,uri.toString(),liveMarkers)
                    if(ok && trash) AudioStorage.Recording("",0,0,uri=uri).trash(this,true) else ok
                }
                else { contentResolver.delete(uri, null, null); false }
            }.getOrDefault(false)
        }
        if (!valid) file?.delete()
        else if (file != null) return runCatching { val dest = if(!name.isNullOrBlank() && !name.contains('/') && !name.contains('\\')) File(file.parentFile,name.removeSuffix(".m4a")+".m4a") else file; val saved=if(dest!=file && !dest.exists() && file.renameTo(dest)) dest else file; File(saved.path + ".peaks").writeText(peaks.joinToString(",")); Markers.write(this,saved.absolutePath,liveMarkers); if(trash) check(AudioStorage.Recording(saved.name,saved.length(),saved.lastModified(),file=saved).trash(this,true)); true }.getOrDefault(false)
        return valid && file != null
    }
    private fun actionIntent(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(), Intent(this, RecorderService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    private fun promote() {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_audio)
            .setContentTitle(if (state == "paused") "Enregistrement · pause" else if (state == "connecting") "Enregistrement · connexion" else if (state == "saving") "Enregistrement · sauvegarde" else "Enregistrement")
            .setContentText(String.format(Locale.FRANCE, "%02d:%02d", recordedMs / 60000, recordedMs / 1000 % 60))
            .setShowWhen(false)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setDefaults(0)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        if(state != "saving") {
            builder.addAction(Notification.Action.Builder(null, "Arrêter", actionIntent(ACTION_STOP)).build())
            if (state == "paused") builder.addAction(Notification.Action.Builder(null, "Reprendre", actionIntent(ACTION_RESUME)).build())
            else if(state=="recording") builder.addAction(Notification.Action.Builder(null, "Pause", actionIntent(ACTION_PAUSE)).build())
            if (state == "recording" || state == "paused") builder.addAction(Notification.Action.Builder(null, "Arrêter et quitter", actionIntent(ACTION_STOP_EXIT)).build())
        }
        val notif = builder.build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIF_ID, notif)
    }
    private fun broadcastState(message: String? = null, navigation: String = "stay") {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra(EXTRA_STATE, state).putExtra("seconds", seconds).putExtra("message", message).putExtra("navigation", navigation))
    }
    override fun onDestroy() {
        handler.removeCallbacks(clock)
        runCatching { unregisterReceiver(screenOffReceiver) }
        callback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        if(pcmRecorder!=null) { val valid=runCatching { pcmRecorder?.stop() == true }.getOrDefault(false); pcmRecorder=null; finalizeTarget(valid) }
        if (recorder != null) {
            val valid = runCatching { recorder?.stop() }.isSuccess
            runCatching { recorder?.release() }; recorder = null
            finalizeTarget(valid)
        }
        clearBluetooth(); state = "idle"
        super.onDestroy()
    }
}