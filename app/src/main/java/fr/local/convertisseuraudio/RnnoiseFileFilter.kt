package fr.local.convertisseuraudio

internal object RnnoiseNative {
    init { System.loadLibrary("voice_rnnoise") }
    external fun create(): Long
    external fun destroy(handle: Long)
    external fun process(handle: Long, samples: ShortArray)
}

/** Offline mono PCM converter. RNNoise accepts 480 samples at 48 kHz. */
internal class RnnoiseFileFilter(private val sourceRate: Int, private val strength: Int,
                                private val sink: (ShortArray, Int) -> Unit) : AutoCloseable {
    private var handle = RnnoiseNative.create().also { check(it != 0L) { "RNNoise indisponible" } }
    private val frame = ShortArray(480)
    private var dryPrevious = ShortArray(480)
    private var frameCount = 0
    private var fill = 0
    private var sourceIndex = 0L
    private var next = 0L
    private var previous = 0
    private var total = 0L
    private var written = 0L
    private fun emit(v: Int) {
        frame[fill++] = v.coerceIn(-32768,32767).toShort(); total++
        if (fill == 480) runFrame()
    }
    private fun runFrame() {
        val dry = frame.copyOf()
        RnnoiseNative.process(handle,frame)
        // RNNoise overlap-add has a one-frame delay. Match dry signal to delayed output.
        if(frameCount++ > 0) {
            val count = minOf(480L, total-written).toInt()
            for(i in 0 until count) frame[i] = ((frame[i].toInt()*strength + dryPrevious[i].toInt()*(100-strength))/100).toShort()
            if(count>0) { sink(frame,count); written+=count }
        }
        dryPrevious=dry; fill=0
    }
    fun process(data:ShortArray,count:Int) {
        for(i in 0 until count) {
            val current=data[i].toInt()
            if(sourceIndex==0L) { emit(current); next=sourceRate.toLong() }
            else {
                val right=sourceIndex*48000L
                val left=right-48000L
                while(next<=right) {
                    emit((previous + (current-previous)*(next-left)/48000L).toInt())
                    next+=sourceRate
                }
            }
            previous=current; sourceIndex++
        }
    }
    fun finish() {
        if(sourceIndex==0L) return
        val end=sourceIndex*48000L
        while(next<end) { emit(previous); next+=sourceRate }
        if(fill>0) { frame.fill(0,fill); runFrame() }
        frame.fill(0); runFrame()
    }
    override fun close() { if(handle!=0L) { RnnoiseNative.destroy(handle); handle=0L } }
}
