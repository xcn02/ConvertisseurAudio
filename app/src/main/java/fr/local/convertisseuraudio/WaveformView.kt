package fr.local.convertisseuraudio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.max

/** Live amplitude history (not an audio playback waveform). */
class WaveformView(context: Context) : View(context) {
    private val samples = ArrayDeque<Float>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(54, 101, 218); strokeWidth = 5f; strokeCap = Paint.Cap.ROUND }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(100, 116, 139); textSize = 30f }
    fun push(amplitude: Int) {
        val level = (amplitude.coerceIn(0, 32767) / 32767f).coerceIn(0f,1f)
        samples.addLast(level)
        while (samples.size > 70) samples.removeFirst()
        invalidate()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (120 * resources.displayMetrics.density).toInt())
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawText("Niveau micro · temps réel", 16f, 34f, label)
        val center = height * .64f
        val gap = width.toFloat() / 72f
        samples.forEachIndexed { index, level ->
            val half = max(3f, level * height * .28f)
            val x = 10f + index * gap
            canvas.drawLine(x, center - half, x, center + half, paint)
        }
    }
}
