package fr.local.convertisseuraudio

import android.content.Context
import android.content.ContentValues
import android.media.*
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.nio.ByteOrder
import java.util.UUID

/** Reprocesses an existing mono recording into a separate temporary AAC file. */
internal object VoiceFileProcessor {
    fun process(context:Context, source:AudioStorage.Recording, profile:VoiceProfile, bitrate:Int, rnnoiseStrength:Int?=null, selection:LongRange?=null, removeSelection:Boolean=false, progress:((Int)->Unit)?=null):File {
        val output=File.createTempFile("voice-", ".m4a", context.cacheDir)
        val extractor=MediaExtractor()
        var decoder:MediaCodec?=null
        var writer:PcmAacWriter?=null
        var success=false
        var neural:RnnoiseFileFilter?=null
        try {
            if(source.uri!=null) extractor.setDataSource(context,source.uri,null) else extractor.setDataSource(source.file!!.path)
            val track=(0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true }
            val format=extractor.getTrackFormat(track)
            check(format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)==1) { "Seuls les fichiers mono sont pris en charge" }
            val rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            extractor.selectTrack(track)
            val codec=MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME))); decoder=codec
            codec.configure(format,null,null,0); codec.start()
            val out=PcmAacWriter(if(rnnoiseStrength!=null) 48000 else rate,bitrate,MediaMuxer(output.path,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)); writer=out
            val dsp=VoiceDsp(rate,profile)
            var sampleIndex=0L
            val durationUs=if(format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val from=selection?.first?.times(rate)?.div(1000L) ?: 0L
            val to=selection?.last?.times(rate)?.div(1000L) ?: Long.MAX_VALUE
            if(rnnoiseStrength!=null) neural=RnnoiseFileFilter(rate,rnnoiseStrength) { pcm,n -> out.write(pcm,n) }
            val info=MediaCodec.BufferInfo(); val block=ShortArray(1024)
            var inputDone=false; var outputDone=false; var stalls=0
            while(!outputDone) {
                var advanced=false
                if(!inputDone) {
                    val i=codec.dequeueInputBuffer(10000)
                    if(i>=0) {
                        val b=codec.getInputBuffer(i)!!.apply { clear() }; val n=extractor.readSampleData(b,0)
                        if(n<0) { codec.queueInputBuffer(i,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone=true }
                        else { codec.queueInputBuffer(i,0,n,extractor.sampleTime,0); extractor.advance() }
                        advanced=true
                    }
                }
                val o=codec.dequeueOutputBuffer(info,10000)
                if(o==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f=codec.outputFormat
                    check(f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)==1 && f.getInteger(MediaFormat.KEY_SAMPLE_RATE)==rate) { "Format PCM non pris en charge" }
                    if(f.containsKey(MediaFormat.KEY_PCM_ENCODING)) check(f.getInteger(MediaFormat.KEY_PCM_ENCODING)==AudioFormat.ENCODING_PCM_16BIT) { "Format PCM non pris en charge" }
                    advanced=true
                } else if(o>=0) {
                    try {
                        if(info.size>0) {
                            val b=codec.getOutputBuffer(o)!!; b.position(info.offset); b.limit(info.offset+info.size); b.order(ByteOrder.LITTLE_ENDIAN)
                            while(b.remaining()>=2) {
                                val n=minOf(block.size,b.remaining()/2)
                                for(i in 0 until n) block[i]=b.short
                                var count=0
                                for(i in 0 until n) { val inside=sampleIndex>=from && sampleIndex<to; sampleIndex++; if(selection==null || inside!=removeSelection) block[count++]=block[i] }
                                if(count>0) { dsp.process(block,count); if(neural!=null) neural.process(block,count) else out.write(block,count) }
                                if(durationUs>0) progress?.invoke((sampleIndex*100000000L/rate/durationUs).coerceIn(0,100).toInt())
                            }
                        }
                        outputDone=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { codec.releaseOutputBuffer(o,false) }
                    advanced=true
                }
                if(advanced) stalls=0 else check(++stalls<300) { "Décodage audio bloqué" }
                check(!Thread.currentThread().isInterrupted) { "Traitement annulé" }
            }
            neural?.finish()
            check(out.finish()) { "Encodage incomplet" }; success=true; return output
        } finally {
            runCatching { decoder?.stop() }; runCatching { decoder?.release() }; extractor.release(); neural?.close(); writer?.close()
            if(!success) output.delete()
        }
    }
    /** Explicitly creates a new recording; never overwrites the source. */
    fun saveCopy(context:Context, temp:File, name:String, suffix:String="traité") {
        val title=name.removeSuffix(".m4a")+" - $suffix "+UUID.randomUUID().toString().take(6)+".m4a"
        if(Build.VERSION.SDK_INT>=29) {
            val uri=AudioStorage.create(context,title)
            try {
                val stream=context.contentResolver.openOutputStream(uri,"w") ?: error("Fichier inaccessible")
                stream.use { out -> temp.inputStream().use { it.copyTo(out) } }
                check(context.contentResolver.update(uri,ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING,0) },null,null)>0) { "Publication impossible" }
            } catch(e:Exception) { runCatching { context.contentResolver.delete(uri,null,null) }; throw e }
        } else {
            val dest=File(RecorderService.folder(context),title)
            check(dest.createNewFile()) { "Nom de fichier déjà utilisé" }
            try { temp.inputStream().use { input -> dest.outputStream().use { output -> input.copyTo(output) } } } catch(e:Exception) { dest.delete(); throw e }
        }
    }
}
