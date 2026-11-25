package fr.local.convertisseuraudio

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.media.session.MediaSession
import android.media.session.PlaybackState
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
        const val ACTION_STOP_AND_CLOSE = "fr.local.convertisseuraudio.STOP_AND_CLOSE"
        const val ACTION_CLOSE = "fr.local.convertisseuraudio.CLOSE"
        const val ACTION_STATE = "fr.local.convertisseuraudio.STATE"
        const val EXTRA_STATE = "state"
        @Volatile var state = "idle"
        @Volatile var routeLabel = "Source non vérifiée"
        @Volatile var seconds = 0L
        @Volatile var amplitude = 0
        val peaks = ArrayList<Int>()
        @Volatile var recordedMs = 0L
        const val CHANNEL_ID = "audio_recording"
        const val NOTIF_ID = 1024
        fun folder(context: Context): File = File(context.filesDir, "audio").apply { mkdirs() }
        fun stopAndClose(context: Context) {
            if (state == "idle") {
                context.sendBroadcast(Intent(ACTION_CLOSE).setPackage(context.packageName)); return
            }
            context.startService(Intent(context, RecorderService::class.java).setAction(ACTION_STOP_AND_CLOSE))
        }
    }
    private var recorder: MediaRecorder? = null
    private var routingBluetooth = false
    private var previousMode = AudioManager.MODE_NORMAL
    private var session: MediaSession? = null
    private var lastVolumeDown = 0L
    private var lastShownSecond = -1L
    private inner class RecorderVolumeProvider : VolumeProvider(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, 100, 50) {
        override fun onAdjustVolume(direction: Int) {
            when (direction) {
                AudioManager.ADJUST_LOWER -> bump(-1)
                AudioManager.ADJUST_RAISE -> bump(1)
            }
        }
        override fun onSetVolumeTo(volume: Int) {
            val value = volume.coerceIn(0, 100)
            setCurrentVolume(value)
            applyStreamVolume(value)
        }
    }
    private val volumeProvider = RecorderVolumeProvider()
    private fun applyStreamVolume(value: Int) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val steps = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(value / 100f * steps), 0)
    }
    private fun bump(direction: Int) {
        val value = (volumeProvider.currentVolume + direction).coerceIn(0, 100)
        volumeProvider.setCurrentVolume(value)
        applyStreamVolume(value)
        if (direction < 0) quickStop()
    }
    private fun quickStop() {
        val now = SystemClock.elapsedRealtime()
        if (lastVolumeDown != 0L && now - lastVolumeDown <= 650L) { lastVolumeDown = 0L; stopAndClose(this) }
        else lastVolumeDown = now
    }
    private fun accessibilityActive(): Boolean =
        (getSystemService(ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager)
            .getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    private fun ensureSession() {
        if (session != null || accessibilityActive()) return
        if (!getSharedPreferences("settings", MODE_PRIVATE).getBoolean("quick_stop_volume", false)) return
        session = MediaSession(this, "convertisseur-recorder").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setPlaybackToRemote(volumeProvider)
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { if (state == "paused") resumeRecording() else if (state == "idle") startRecording() }
                override fun onPause() { pauseRecording() }
                override fun onStop() { stopRecording() }
            })
            setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0L, 1f).build())
            isActive = true
        }
    }
    private fun setSessionState(st: Int) {
        session?.setPlaybackState(PlaybackState.Builder().setState(st, recordedMs, 1f).build())
        session?.isActive = true
    }
    private fun releaseSession() { session?.runCatching { isActive = false; release() }; session = null }

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
    private fun bluetoothFailure(message:String) { clearBluetooth(); state="idle"; releaseSession(); broadcastState(message); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }

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
                amplitude = if (state == "recording") (recorder?.runCatching { maxAmplitude }?.getOrNull() ?: 0) else 0
                recordedMs = accumulated + if (state == "recording") SystemClock.elapsedRealtime() - segmentStart else 0L
                if (state != "connecting" && seconds != lastShownSecond) { lastShownSecond = seconds; promote() }
                if (state == "recording") {
                    peaks.add(amplitude)
                    if(Build.VERSION.SDK_INT >= 28) routeLabel = runCatching { recorder?.routedDevice?.let { "Microphone : ${when(it.type) { AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Téléphone"; AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth"; AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> "Casque filaire"; else -> it.productName.toString() }}" } }.getOrNull() ?: "Source non vérifiée"
                }
                handler.postDelayed(this, 40)
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
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
            ACTION_STOP -> stopRecording()
            ACTION_STOP_AND_CLOSE -> { stopRecording(); sendBroadcast(Intent(ACTION_CLOSE).setPackage(packageName)) }
        }
        return START_NOT_STICKY
    }
    private fun startRecording(bluetoothReady:Boolean=false) {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if(prefs.getString("mic_source","auto")=="bluetooth" && !bluetoothReady) { prepareBluetooth(); return }
        limitSeconds = prefs.getLong("limit_seconds", 180L)
        timerMode = prefs.getString("timer_mode", "record") ?: "record"
        seconds = 0L; accumulated = 0L; recordedMs = 0L; peaks.clear(); lastShownSecond = -1L; lastVolumeDown = 0L
        val name = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(Date()) + ".m4a"
        target = null; sharedTarget = null
        try {
            // Promotion to foreground occurs while the activity is visible and recording is authorized.
            promote()
            @Suppress("DEPRECATION")
            val media = MediaRecorder()
            recorder = media
            media.setAudioSource(if(bluetoothReady) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC)
            routeLabel = "Source non vérifiée"
            val source = prefs.getString("mic_source","auto")
            if(source != "auto") {
                check(Build.VERSION.SDK_INT >= 28) { "Sélection du microphone : Android 9 minimum" }
                val types = when(source) {
                    "internal" -> setOf(AudioDeviceInfo.TYPE_BUILTIN_MIC)
                    "wired" -> setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET,AudioDeviceInfo.TYPE_USB_HEADSET)
                    else -> setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO,AudioDeviceInfo.TYPE_BLE_HEADSET)
                }
                val device=audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type in types } ?: error("Microphone choisi indisponible")
                check(media.setPreferredDevice(device)) { "Sélection du microphone refusée" }
            }
            media.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            media.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            media.setAudioChannels(1)
            media.setAudioEncodingBitRate(prefs.getInt("bitrate", 64000))
            media.setAudioSamplingRate(if(bluetoothReady) 16000 else 44100)
            if (Build.VERSION.SDK_INT >= 29) {
                val uri = AudioStorage.create(this, name)
                sharedTarget = uri
                val fd = contentResolver.openFileDescriptor(uri, "rw") ?: error("Fichier inaccessible")
                descriptor = fd
                media.setOutputFile(fd.fileDescriptor)
            } else {
                target = File(folder(this), name)
                media.setOutputFile(target!!.absolutePath)
            }
            media.prepare()
            media.start()
            wallStart = SystemClock.elapsedRealtime()
            segmentStart = wallStart
            state = "recording"
            ensureSession()
            handler.removeCallbacks(clock)
            handler.post(clock)
            promote()
            broadcastState()
        } catch (e: Exception) {
            recorder?.runCatching { release() }
            recorder = null
            finalizeTarget(false)
            clearBluetooth(); state = "idle"; releaseSession()
            broadcastState("Impossible de démarrer : ${e.localizedMessage}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }
    private fun pauseRecording() {
        if (state != "recording") return
        try {
            recorder?.pause()
            accumulated += SystemClock.elapsedRealtime() - segmentStart
            state = "paused"
            setSessionState(PlaybackState.STATE_PAUSED)
            promote(); broadcastState()
        } catch (e: Exception) { stopRecording() }
    }
    private fun resumeRecording() {
        if (state != "paused") return
        try {
            recorder?.resume()
            segmentStart = SystemClock.elapsedRealtime()
            state = "recording"
            setSessionState(PlaybackState.STATE_PLAYING)
            promote(); broadcastState()
        } catch (e: Exception) { stopRecording() }
    }
    private fun stopRecording() {
        if(state=="connecting") { bluetoothFailure("Connexion annulée"); return }
        if (state == "idle") { stopSelf(); return }
        handler.removeCallbacks(clock)
        val current = recorder
        recorder = null
        var valid = true
        try { current?.stop() } catch (_: RuntimeException) { valid = false }
        finally { current?.runCatching { release() } }
        clearBluetooth(); valid = finalizeTarget(valid)
        state = "idle"; seconds = 0L; amplitude = 0
        releaseSession()
        broadcastState(if (valid) "Fichier sauvegardé" else "Enregistrement trop court ou endommagé")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    private fun finalizeTarget(valid: Boolean): Boolean {
        runCatching { descriptor?.close() }; descriptor = null
        val uri = sharedTarget; sharedTarget = null
        val file = target; target = null
        if (uri != null) {
            return runCatching {
                if (valid) contentResolver.update(uri, ContentValues().apply { put(android.provider.MediaStore.Audio.Media.IS_PENDING, 0) }, null, null) > 0
                else { contentResolver.delete(uri, null, null); false }
            }.getOrDefault(false)
        }
        if (!valid) file?.delete()
        else if (file != null) runCatching { File(file.path + ".peaks").writeText(peaks.joinToString(",")) }
        return valid && file != null
    }
    private fun actionIntent(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(), Intent(this, RecorderService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    private fun promote() {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_dot)
            .setContentTitle(if (state == "paused") "Enregistrement · pause" else if (state == "connecting") "Enregistrement · connexion" else "Enregistrement")
            .setContentText(String.format(Locale.FRANCE, "%02d:%02d", recordedMs / 60000, recordedMs / 1000 % 60))
            .setShowWhen(false)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setDefaults(0)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .addAction(Notification.Action.Builder(null, "Arrêter", actionIntent(ACTION_STOP)).build())
        if (state == "paused") builder.addAction(Notification.Action.Builder(null, "Reprendre", actionIntent(ACTION_RESUME)).build())
        else builder.addAction(Notification.Action.Builder(null, "Pause", actionIntent(ACTION_PAUSE)).build())
        val notif = builder.build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIF_ID, notif)
    }
    private fun broadcastState(message: String? = null) {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra(EXTRA_STATE, state).putExtra("seconds", seconds).putExtra("message", message))
    }
    override fun onDestroy() {
        handler.removeCallbacks(clock)
        callback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        if (recorder != null) {
            val valid = runCatching { recorder?.stop() }.isSuccess
            runCatching { recorder?.release() }; recorder = null
            finalizeTarget(valid)
        }
        clearBluetooth(); state = "idle"; releaseSession()
        super.onDestroy()
    }
}