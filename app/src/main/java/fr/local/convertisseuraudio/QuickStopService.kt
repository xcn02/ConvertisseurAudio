package fr.local.convertisseuraudio

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class QuickStopService : AccessibilityService() {
    private var lastDown = 0L
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (!getSharedPreferences("settings", MODE_PRIVATE).getBoolean("quick_stop_volume", false)) return false
        val st = RecorderService.state
        if (st != "recording" && st != "paused") return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val now = SystemClock.elapsedRealtime()
            if (lastDown != 0L && now - lastDown <= 650L) {
                lastDown = 0L
                RecorderService.stopAndClose(this)
            } else {
                lastDown = now
                stepDown()
            }
        }
        return true
    }
    private fun stepDown() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val v = (am.getStreamVolume(AudioManager.STREAM_MUSIC) - 1).coerceAtLeast(0)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
    }
}