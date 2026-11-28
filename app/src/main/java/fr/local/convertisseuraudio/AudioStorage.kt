package fr.local.convertisseuraudio
import android.content.*
import android.provider.MediaStore
import android.os.Build
import android.media.*
import java.io.File
import kotlin.math.abs
object AudioStorage {
    data class Recording(val name: String, val size: Long, val modified: Long, val uri: android.net.Uri? = null, val file: File? = null) {
        val key: String get() = uri?.toString() ?: file!!.absolutePath
        fun trash(context: Context, value: Boolean): Boolean {
            if(uri != null) {
                val values=ContentValues().apply {
                    if(Build.VERSION.SDK_INT>=30) put(MediaStore.MediaColumns.IS_TRASHED,if(value) 1 else 0)
                    else put(MediaStore.MediaColumns.RELATIVE_PATH,if(value) "Music/Convertisseur/Trash/" else "Music/Convertisseur/Recordings/")
                }
                return context.contentResolver.update(uri,values,null,null)>0
            }
            val src=file ?: return false
            val dir=if(value) File(context.filesDir,"audio-trash").apply { mkdirs() } else RecorderService.folder(context)
            var dest=File(dir,src.name)
            if(dest.exists()) dest=File(dir,src.nameWithoutExtension+"-"+java.util.UUID.randomUUID().toString().take(8)+".m4a")
            if(!src.renameTo(dest)) return false
            File(src.path+".peaks").renameTo(File(dest.path+".peaks")); Markers.move(context,src.absolutePath,dest.absolutePath); return true
        }
        val title: String get() = name.removeSuffix(".m4a")
        fun open(context: Context): java.io.InputStream = if (uri != null) context.contentResolver.openInputStream(uri) ?: error("Fichier inaccessible") else file!!.inputStream()
        fun rename(context: Context, name: String): Boolean {
            if (name.isBlank() || name.contains('/') || name.contains('\\')) return false
            val newName = name.removeSuffix(".m4a") + ".m4a"
            return if (uri != null) context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.DISPLAY_NAME, newName) }, null, null) > 0
            else { val dest = File(file!!.parentFile, newName); if (dest.exists()) false else if (file.renameTo(dest)) { File(file.path + ".peaks").renameTo(File(dest.path + ".peaks")); Markers.move(context,file.absolutePath,dest.absolutePath); true } else false }
        }
        fun delete(context: Context): Boolean = if (uri != null) context.contentResolver.delete(uri, null, null) > 0 else if (file!!.delete()) { File(file.path + ".peaks").delete(); true } else false
    }
    fun create(context: Context, name: String): android.net.Uri {
        return context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Convertisseur/Recordings/")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }) ?: error("Impossible de créer le fichier audio")
    }
    fun list(context: Context, trashed: Boolean = false): List<Recording> {
        val items = mutableListOf<Recording>()
        if (Build.VERSION.SDK_INT >= 29) {
            val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_MODIFIED)
            val query = if(Build.VERSION.SDK_INT >= 30) context.contentResolver.query(collection,projection,android.os.Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,"relative_path = ? AND is_pending = 0 AND is_trashed = ?")
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,arrayOf("Music/Convertisseur/Recordings/",if(trashed) "1" else "0"))
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED,MediaStore.MATCH_INCLUDE)
            },null) else context.contentResolver.query(collection,projection,"relative_path = ? AND is_pending = 0",arrayOf(if(trashed) "Music/Convertisseur/Trash/" else "Music/Convertisseur/Recordings/"),null)
            query?.use { c ->
                while (c.moveToNext()) items.add(Recording(c.getString(1), c.getLong(2), c.getLong(3) * 1000L, ContentUris.withAppendedId(collection, c.getLong(0))))
            }
        }
        (if(trashed) File(context.filesDir,"audio-trash") else RecorderService.folder(context)).listFiles()?.filter { it.extension == "m4a" }?.forEach { items.add(Recording(it.name, it.length(), it.lastModified(), file = it)) }
        return items.sortedByDescending { it.modified }
    }
    fun decodePeaks(context: Context, recording: Recording): List<Int> {
        val side = recording.file?.let { File(it.path + ".peaks") }
        if (side != null && side.exists()) return runCatching { side.readText().split(',').mapNotNull { it.toIntOrNull() } }.getOrDefault(emptyList())
        val extractor = MediaExtractor(); var codec: MediaCodec? = null; val result = ArrayList<Int>()
        try {
            if (recording.uri != null) extractor.setDataSource(context, recording.uri, null) else extractor.setDataSource(recording.file!!.path)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            extractor.selectTrack(track); val format = extractor.getTrackFormat(track); val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!); codec = decoder; decoder.configure(format,null,null,0); decoder.start()
            val info = MediaCodec.BufferInfo(); var inputDone = false; var outputDone = false; var peak = 0; var count = 0; val window = (format.getInteger(MediaFormat.KEY_SAMPLE_RATE)*.04).toInt().coerceAtLeast(1)
            while (!outputDone) {
                if (!inputDone) { val i = decoder.dequeueInputBuffer(10000); if (i >= 0) { val b = decoder.getInputBuffer(i)!!; b.clear(); val n = extractor.readSampleData(b,0); if(n<0) { decoder.queueInputBuffer(i,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone=true } else { decoder.queueInputBuffer(i,0,n,extractor.sampleTime,0); extractor.advance() } } }
                val o = decoder.dequeueOutputBuffer(info,10000); if(o>=0) { val b = decoder.getOutputBuffer(o)!!; b.position(info.offset); b.limit(info.offset+info.size); b.order(java.nio.ByteOrder.LITTLE_ENDIAN); while(b.remaining()>=2) { peak=maxOf(peak,abs(b.short.toInt())); if(++count>=window) { result.add(peak); peak=0; count=0 } }; outputDone=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0; decoder.releaseOutputBuffer(o,false) }
            }; if(count>0) result.add(peak)
        } catch(_: Exception) { } finally { runCatching { codec?.release() }; extractor.release() }; return result
    }
}
