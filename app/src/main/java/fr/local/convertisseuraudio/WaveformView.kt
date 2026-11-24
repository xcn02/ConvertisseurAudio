package fr.local.convertisseuraudio
import android.content.Context
import android.graphics.*
import android.view.*
import kotlin.math.*
class WaveformView(context: Context): View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var samples: List<Int> = emptyList()
    private var elapsed = 0L
    private var duration = 0
    private var cursor = 0
    private var seek: ((Int)->Unit)? = null
    fun live(values: List<Int>, ms: Long) { samples = values.takeLast(250); elapsed = ms; invalidate() }
    fun playback(values: List<Int>, total: Int, current: Int, callback: (Int)->Unit) { samples = values; duration = total; cursor = current; seek = callback; invalidate() }
    fun position(ms: Int) { cursor = ms; invalidate() }
    override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(MeasureSpec.getSize(w), (190 * resources.displayMetrics.density).toInt()) }
    override fun onDraw(c: Canvas) {
        val mid = height * .56f; val density = resources.displayMetrics.density
        c.drawColor(Color.rgb(24,27,34)); paint.color = Color.GRAY; paint.strokeWidth = 1f; c.drawLine(0f,mid,width.toFloat(),mid,paint)
        val columns = (width / (2*density)).toInt().coerceAtLeast(1)
        val count = if (duration > 0) columns else columns/2
        for (i in 0 until count) {
            val start = if (duration > 0) i*samples.size/count else (samples.size-count+i)
            val end = if (duration > 0) ((i+1)*samples.size/count).coerceAtLeast(start+1) else start+1
            if (start !in samples.indices) continue
            var peak = 0; for (j in start until min(end,samples.size)) peak = max(peak,samples[j])
            val half = (peak/32768f).coerceIn(0f,1f).pow(.65f)*height*.36f
            val x = i*width.toFloat()/columns; paint.color = Color.rgb(158,183,239); paint.strokeWidth = density; c.drawLine(x,mid-half,x,mid+half,paint)
        }
        val x = if (duration > 0) width*cursor.toFloat()/duration else width/2f
        paint.color = Color.rgb(240,100,100); paint.strokeWidth = 2*density; c.drawLine(x,height*.2f,x,height*.94f,paint)
        paint.color = Color.LTGRAY; paint.textSize = 12*density
        val ms = if (duration > 0) cursor.toLong() else elapsed
        c.drawText(String.format(java.util.Locale.FRANCE,"%02d:%02d",ms/60000,ms/1000%60), 12*density,20*density,paint)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean { val callback = seek ?: return false; if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE || event.action == MotionEvent.ACTION_UP) { callback((event.x/width*duration).toInt().coerceIn(0,duration)); return true }; return true }
}
