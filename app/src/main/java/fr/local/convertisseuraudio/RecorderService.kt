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
        const val ACTION_STATE = "fr.local.convertisseuraudio.STATE"
        const val EXTRA_STATE = "state"
        @Volatile var state = "idle"
        @Volatile var seconds = 0L
        @Volatile var amplitude = 0
        const val CHANNEL_ID = "audio_recording"
        const val NOTIF_ID = 1024
        fun folder(context: Context): File = File(context.filesDir, "audio").apply { mkdirs() }
    }
    private var recorder: MediaRecorder? = null
    private var target: File? = null
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
                broadcastState()
                handler.postDelayed(this, 180)
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "Enregistrement audio", NotificationManager.IMPORTANCE_LOW)
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
        }
        return START_NOT_STICKY
    }
    private fun startRecording() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        limitSeconds = prefs.getLong("limit_seconds", 180L)
        timerMode = prefs.getString("timer_mode", "record") ?: "record"
        seconds = 0L; accumulated = 0L
        target = File(folder(this), SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) + ".m4a")
        try {
            // Promotion to foreground occurs while the activity is visible and recording is authorized.
            promote()
            @Suppress("DEPRECATION")
            val media = MediaRecorder()
            recorder = media
            media.setAudioSource(MediaRecorder.AudioSource.MIC)
            media.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            media.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            media.setAudioChannels(1)
            media.setAudioEncodingBitRate(prefs.getInt("bitrate", 64000))
            media.setAudioSamplingRate(44100)
            media.setOutputFile(target!!.absolutePath)
            media.prepare()
            media.start()
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
            target?.delete()
            state = "idle"
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
            promote(); broadcastState()
        } catch (e: Exception) { stopRecording() }
    }
    private fun resumeRecording() {
        if (state != "paused") return
        try {
            recorder?.resume()
            segmentStart = SystemClock.elapsedRealtime()
            state = "recording"
            promote(); broadcastState()
        } catch (e: Exception) { stopRecording() }
    }
    private fun stopRecording() {
        if (state == "idle") { stopSelf(); return }
        handler.removeCallbacks(clock)
        val current = recorder
        recorder = null
        var valid = true
        try { current?.stop() } catch (_: RuntimeException) { valid = false }
        finally { current?.runCatching { release() } }
        if (!valid) target?.delete()
        state = "idle"; seconds = 0L; amplitude = 0
        broadcastState(if (valid) "Fichier sauvegardé" else "Enregistrement trop court ou endommagé")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    private fun actionIntent(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(), Intent(this, RecorderService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    private fun promote() {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val label = if (state == "paused") "Enregistrement en pause" else "Enregistrement audio en cours"
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_audio)
            .setContentTitle(label)
            .setContentText("Appuyez pour ouvrir l'application")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
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
            try { recorder?.stop() } catch (_: Exception) { target?.delete() }
            recorder?.release(); recorder = null
        }
        state = "idle"
        super.onDestroy()
    }
}
