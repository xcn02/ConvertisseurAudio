package fr.local.convertisseuraudio

import android.content.Context
internal object Markers {
    private fun prefs(c:Context)=c.getSharedPreferences("settings",Context.MODE_PRIVATE)
    fun read(c:Context,key:String):List<Int> = prefs(c).getString("markers:$key", "").orEmpty().split(',').mapNotNull { it.toIntOrNull() }.distinct().sorted()
    fun write(c:Context,key:String,marks:List<Int>) { prefs(c).edit().putString("markers:$key",marks.distinct().sorted().joinToString(",")).apply() }
    fun name(c:Context,key:String,ms:Int,default:String):String = prefs(c).getString("marker-name:$key:$ms",default) ?: default
    fun rename(c:Context,key:String,ms:Int,name:String) { prefs(c).edit().putString("marker-name:$key:$ms",name).apply() }
    fun remove(c:Context,key:String,ms:Int) { write(c,key,read(c,key)-ms); prefs(c).edit().remove("marker-name:$key:$ms").apply() }
    fun move(c:Context,old:String,new:String) {
        if(old==new) return
        val p=prefs(c); val e=p.edit(); val marks=read(c,old)
        e.putString("markers:$new",marks.joinToString(",")).remove("markers:$old")
        for(ms in marks) { val a="marker-name:$old:$ms"; p.getString(a,null)?.let { e.putString("marker-name:$new:$ms",it) }; e.remove(a) }
        if(p.contains("position:$old")) e.putInt("position:$new",p.getInt("position:$old",0)).remove("position:$old")
        e.apply()
    }
}
