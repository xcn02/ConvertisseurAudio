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
        val dark = resources.configuration.uiMode and 0x30 == 0x20
        val fg = if(dark) Color.rgb(169,186,218) else Color.rgb(89,107,141)
        paint.color = if(dark) Color.rgb(65,76,91) else Color.rgb(216,222,232)
        paint.strokeWidth = density; c.drawLine(0f,mid,width.toFloat(),mid,paint)
        val step = 3*density
        val center = width/2f
        val current = if(duration>0) cursor.toLong() else elapsed
        val msPerPixel = 40f/step
        val columns = (width/step).toInt()
        for(i in 0..columns) {
            val x=i*step; val t=current+(x-center)*msPerPixel
            val index=if(duration>0) (t/duration*samples.size).toInt() else samples.size-1+((x-center)/step).toInt()
            if(t<0 || (duration>0 && t>duration) || index !in samples.indices || (duration==0 && x>center)) continue
            val half=(samples[index]/32768f).coerceIn(0f,1f).pow(.65f)*height*.31f
            paint.color=fg; paint.strokeWidth=1.5f*density; c.drawLine(x,mid-max(1f,half),x,mid+max(1f,half),paint)
        }
        paint.color=fg; paint.strokeWidth=2*density; c.drawLine(center,height*.19f,center,height*.91f,paint)
        paint.textSize=11*density; paint.strokeWidth=density
        val first=((current-width/2*msPerPixel)/1000).toLong().coerceAtLeast(0)
        for(sec in first..(first+12)) {
            val x=center+(sec*1000-current)/msPerPixel
            if(x<0 || x>width) continue
            c.drawLine(x,height*.12f,x,height*.16f,paint)
            c.drawText(String.format(java.util.Locale.FRANCE,"%02d:%02d",sec/60,sec%60),x+2*density,height*.09f,paint)
        }
    }
    private var lastX=0f
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val callback=seek ?: return false
        when(event.action) {
            MotionEvent.ACTION_DOWN -> { lastX=event.x; parent.requestDisallowInterceptTouchEvent(true); return true }
            MotionEvent.ACTION_MOVE -> { val delta=((lastX-event.x)*40f/(3*resources.displayMetrics.density)).toInt(); cursor=(cursor+delta).coerceIn(0,duration); lastX=event.x; callback(cursor); invalidate(); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { parent.requestDisallowInterceptTouchEvent(false); return true }
        }; return true
    }
}
