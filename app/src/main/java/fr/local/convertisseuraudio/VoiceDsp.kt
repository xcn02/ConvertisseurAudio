package fr.local.convertisseuraudio

import android.content.SharedPreferences
import kotlin.math.*

/** A lightweight, causal four-band noise attenuator. Not a neural speech separator. */
data class VoiceProfile(val noise: Int, val clarity: Int, val gain: Boolean, val rumble: Boolean) {
    companion object {
        val labels = listOf("Original", "Léger", "Équilibré", "Renforcé", "Personnalisé")
        fun from(p: SharedPreferences): VoiceProfile = forIndex(p,p.getInt("voice_profile",0))
        fun forIndex(p: SharedPreferences,index:Int): VoiceProfile = when (index) {
            1 -> VoiceProfile(25, 20, false, true)
            2 -> VoiceProfile(50, 35, true, true)
            3 -> VoiceProfile(75, 45, true, true)
            4 -> VoiceProfile(p.getInt("voice_noise",50).coerceIn(0,100), p.getInt("voice_clarity",35).coerceIn(0,100), p.getBoolean("voice_gain",true), p.getBoolean("voice_rumble",true))
            else -> VoiceProfile(0,0,false,false)
        }
    }
}
class VoiceDsp(private val sampleRate: Int, private val profile: VoiceProfile) {
    private fun alpha(f: Double) = 1.0 - exp(-2.0 * PI * f / sampleRate)
    private val low = DoubleArray(3)
    private val coeff = doubleArrayOf(alpha(250.0), alpha(1000.0), alpha(min(3500.0,sampleRate*.4)))
    private val hp = alpha(if(profile.rumble) 110.0 + profile.noise*.7 else 25.0)
    private var dc = 0.0
    private val envelope = DoubleArray(4) { 0.002 }
    private val floor = DoubleArray(4) { 0.001 }
    private val gains = DoubleArray(4) { 1.0 }
    private var level = .05
    private var autoGain = 1.0
    private val strength = profile.noise / 100.0
    private val speed = 1.0 / (sampleRate * .025)
    private val rise = 1.0 / (sampleRate * 2.5)
    private val fall = 1.0 / (sampleRate * .12)
    fun process(data: ShortArray, count: Int) {
        if (profile.noise == 0 && profile.clarity == 0 && !profile.gain && !profile.rumble) return
        for (i in 0 until count) {
            val input = data[i] / 32768.0
            dc += hp * (input-dc)
            val x = input-dc
            for (b in 0..2) low[b] += coeff[b]*(x-low[b])
            val b0=low[0]; val b1=low[1]-low[0]; val b2=low[2]-low[1]; val b3=x-low[2]
            var output=0.0
            for(b in 0..3) {
                val v=when(b){0->b0;1->b1;2->b2;else->b3}
                val a=abs(v)
                envelope[b] += speed*(a-envelope[b])
                val e=envelope[b]
                floor[b] += (if(e<floor[b]) fall else rise)*(e-floor[b])
                val snr=e/(floor[b]+.0003)
                val attenuation=(1.0-strength*(1.0-((snr-1.0)/2.2).coerceIn(0.0,1.0))).coerceAtLeast(.18)
                gains[b] += speed*(attenuation-gains[b])
                val eq=when(b){0->1.0-.35*strength;1->1.0;2->1.0+profile.clarity*.006;else->1.0+profile.clarity*.002}
                output += v*gains[b]*eq
            }
            if(profile.gain) {
                level += speed*(abs(output)-level)
                val wanted=if(level>.012) (.075/level).coerceIn(.65,2.2) else 1.0
                autoGain += (1.0/(sampleRate*.3))*(wanted-autoGain)
                output *= autoGain
            }
            // Bounded soft limiter, preserving low-level samples.
            val magnitude=abs(output)
            if(magnitude>.82) output=sign(output)*(.82+.16*tanh((magnitude-.82)/.16))
            data[i]=(output*32767.0).roundToInt().coerceIn(-32768,32767).toShort()
        }
    }
}
