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
    private var zoom=1f
    private val scale=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector:ScaleGestureDetector):Boolean { zoom=(zoom*detector.scaleFactor).coerceIn(.2f,8f); invalidate(); return true }
    })
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
        val msPerPixel = (if(duration>0) 200f/zoom else 40f)/step
        selected?.let { range ->
            val left=(center+(range.first-current)/msPerPixel).coerceIn(0f,width.toFloat()); val right=(center+(range.last-current)/msPerPixel).coerceIn(0f,width.toFloat())
            paint.color=Color.argb(45,100,145,210); c.drawRect(left,height*.19f,right,height*.91f,paint)
        }
        val columns = (width/step).toInt()
        for(i in 0..columns) {
            val x=i*step; val t=current+(x-center)*msPerPixel
            val index=if(duration>0) (t/duration*samples.size).toInt() else samples.size-1+((x-center)/step).toInt()
            if(t<0 || (duration>0 && t>duration) || index !in samples.indices || (duration==0 && x>center)) continue
            val peak=if(duration>0) { val end=(((t+200/zoom)/duration)*samples.size).toInt().coerceIn(index+1,samples.size); var maxPeak=0; for(j in index until end) maxPeak=max(maxPeak,samples[j]); maxPeak } else samples[index]
            val half=(peak/32768f).coerceIn(0f,1f).pow(.65f)*height*.31f
            paint.color=if(duration>0 && x>center) (if(dark) Color.rgb(102,102,102) else Color.rgb(180,187,199)) else fg; paint.strokeWidth=1.5f*density; paint.strokeCap=Paint.Cap.ROUND; c.drawLine(x,mid-max(1f,half),x,mid+max(1f,half),paint)
        }
        paint.strokeCap=Paint.Cap.BUTT; paint.color=fg; paint.strokeWidth=1.5f*density; c.drawLine(center,height*.19f,center,height*.91f,paint)
        paint.textSize=11*density; paint.strokeWidth=density
        val tick=if(duration>0) (if(zoom>=2f) 1L else if(zoom<.5f) 10L else 5L) else 1L
        val first=(((current-width/2*msPerPixel)/1000).toLong().coerceAtLeast(0)/tick)*tick
        val last=((current+width/2*msPerPixel)/1000).toLong().coerceAtLeast(first)
        for(sec in first..last step tick) {
            val x=center+(sec*1000-current)/msPerPixel
            if(x<0 || x>width) continue
            c.drawLine(x,height*.12f,x,height*.16f,paint)
            c.drawText(String.format(java.util.Locale.FRANCE,"%02d:%02d",sec/60,sec%60),x+2*density,height*.09f,paint)
        }
        for(ms in marks) {
            val x=center+(ms-current)/msPerPixel
            if(x<0 || x>width) continue
            paint.color=fg; paint.style=Paint.Style.FILL
            val flag=Path().apply { moveTo(x,height*.18f); lineTo(x+9*density,height*.18f); lineTo(x+6*density,height*.22f); lineTo(x,height*.22f); close() }
            c.drawPath(flag,paint); c.drawLine(x,height*.18f,x,height*.29f,paint)
        }
    }
    private var selected:IntRange?=null
    fun selection(from:Int,to:Int) { selected=from..to; invalidate() }
    private var marks:List<Int> = emptyList()
    fun markers(values:List<Int>) { marks=values.toList(); invalidate() }
    private var lastX=0f
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val callback=seek ?: return false
        scale.onTouchEvent(event)
        if(event.pointerCount>1 || scale.isInProgress) { lastX=event.x; parent.requestDisallowInterceptTouchEvent(true); return true }
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX=event.x; parent.requestDisallowInterceptTouchEvent(true); return true }
            MotionEvent.ACTION_MOVE -> { val delta=((lastX-event.x)*200f/(zoom*3*resources.displayMetrics.density)).toInt(); cursor=(cursor+delta).coerceIn(0,duration); lastX=event.x; callback(cursor); invalidate(); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { parent.requestDisallowInterceptTouchEvent(false); return true }
        }; return true
    }
}
