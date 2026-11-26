package fr.local.convertisseuraudio

import android.media.*
import java.io.FileDescriptor
import java.nio.ByteOrder
import kotlin.math.abs

/** AAC/M4A writer with streaming PCM input. All codec operations are performed on one thread. */
internal class PcmAacWriter(private val rate:Int, bitrate:Int, private val muxer:MediaMuxer) {
    private val encoder=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val info=MediaCodec.BufferInfo()
    private var track=-1
    private var started=false
    private var samples=0L
    private var closed=false
    init {
        try {
            val format=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,rate,1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE,bitrate)
            }
            encoder.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE); encoder.start()
        } catch(e:Exception) { runCatching { encoder.release() }; runCatching { muxer.release() }; throw e }
    }
    private fun drain(wait:Long=0L):Boolean {
        while(true) {
            val o=encoder.dequeueOutputBuffer(info,wait)
            when {
                o==MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                o==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { check(!started); track=muxer.addTrack(encoder.outputFormat); muxer.start(); started=true }
                o>=0 -> {
                    val eos=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    try {
                        if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(started); val b=encoder.getOutputBuffer(o)!!; b.position(info.offset); b.limit(info.offset+info.size); muxer.writeSampleData(track,b,info)
                        }
                    } finally { encoder.releaseOutputBuffer(o,false) }
                    if(eos) return true
                }
            }
        }
    }
    fun write(data:ShortArray,count:Int) {
        var at=0
        var attempts=0
        while(at<count) {
            drain()
            val i=encoder.dequeueInputBuffer(10000)
            if(i<0) { check(++attempts<300) { "Encodeur audio bloqué" }; continue }
            attempts=0
            val b=encoder.getInputBuffer(i)!!.apply { clear(); order(ByteOrder.LITTLE_ENDIAN) }
            val n=minOf(count-at,b.remaining()/2); check(n>0)
            for(j in 0 until n) b.putShort(data[at+j])
            encoder.queueInputBuffer(i,0,n*2,samples*1000000L/rate,0)
            samples+=n; at+=n
        }
        drain()
    }
    fun finish():Boolean {
        if(samples==0L) return false
        var sent=false
        repeat(300) {
            if(!sent) { drain(); val i=encoder.dequeueInputBuffer(10000); if(i>=0) { encoder.queueInputBuffer(i,0,0,samples*1000000L/rate,MediaCodec.BUFFER_FLAG_END_OF_STREAM); sent=true } }
            if(sent && drain(10000)) { check(started); muxer.stop(); started=false; return true }
        }
        return false
    }
    fun close() {
        if(closed) return; closed=true
        runCatching { encoder.stop() }; runCatching { encoder.release() }
        if(started) runCatching { muxer.stop() }
        runCatching { muxer.release() }; started=false
    }
}

internal class PcmVoiceRecorder(rate:Int, bitrate:Int, source:Int, preferred:AudioDeviceInfo?, profile:VoiceProfile, fd:FileDescriptor?, path:String?) {
    private val bufferBytes=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT).also { check(it>0) }
    private val input=AudioRecord.Builder().setAudioSource(source).setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(maxOf(bufferBytes*2,4096)).build()
    private val writer:PcmAacWriter
    private val dsp=VoiceDsp(rate,profile)
    @Volatile private var running=false
    @Volatile private var paused=false
    @Volatile private var failed=false
    @Volatile private var saved=false
    @Volatile var amplitude=0; private set
    val routedDevice: AudioDeviceInfo? get()=runCatching { input.routedDevice }.getOrNull()
    val healthy:Boolean get()=!failed
    private var thread:Thread?=null
    init {
        try {
            check(input.state==AudioRecord.STATE_INITIALIZED) { "Microphone indisponible" }
            if(preferred!=null) check(input.setPreferredDevice(preferred)) { "Routage refusé" }
            val muxer=if(fd!=null) MediaMuxer(fd,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) else MediaMuxer(requireNotNull(path),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            writer=PcmAacWriter(rate,bitrate,muxer)
        } catch(e:Exception) { input.release(); throw e }
    }
    fun start() {
        try { input.startRecording(); check(input.recordingState==AudioRecord.RECORDSTATE_RECORDING) }
        catch(e:Exception) { input.release(); writer.close(); failed=true; throw e }
        running=true
        thread=Thread({
            val block=ShortArray(1024)
            try {
                while(running) {
                    val n=input.read(block,0,block.size,AudioRecord.READ_BLOCKING)
                    if(!running) break
                    check(n>=0) { "Lecture microphone : $n" }
                    if(n==0 || paused) continue
                    dsp.process(block,n)
                    var peak=0; for(i in 0 until n) peak=maxOf(peak,abs(block[i].toInt())); amplitude=peak
                    writer.write(block,n)
                }
                saved=writer.finish()
            } catch(_:Exception) { failed=true }
            finally { running=false; runCatching { input.stop() }; input.release(); writer.close() }
        },"voice-capture").apply { start() }
    }
    fun pause() { paused=true; amplitude=0 }
    fun resume() { paused=false }
    fun stop():Boolean {
        running=false; runCatching { input.stop() }
        val t=thread
        if(t!=null) { t.join(8000); if(t.isAlive) { failed=true; t.interrupt(); return false } }
        return saved && !failed
    }
}
