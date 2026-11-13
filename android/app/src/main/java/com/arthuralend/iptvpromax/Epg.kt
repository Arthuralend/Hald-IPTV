package com.arthuralend.iptvpromax

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.TimeZone
import java.util.zip.GZIPInputStream

data class Prog(val start: Long, val stop: Long, val title: String) {   // unix seconds
    fun progress(now: Long = System.currentTimeMillis() / 1000): Float =
        if (stop <= start) 0f else ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
}

/** TV guide (XMLTV). Matched to channels by normalized name, like the logos. */
object Epg {
    /** iLook TV guide first, then a spare source */
    val defaultSources = listOf("http://epg.it999.ru/epg2.xml.gz", "https://iptvx.one/epg/epg.xml.gz")

    @Volatile var byChannel: Map<String, List<Prog>> = emptyMap(); private set
    @Volatile var icons: Map<String, String> = emptyMap(); private set

    fun nowNext(id: String): Pair<Prog?, Prog?> {
        val list = byChannel[id] ?: return null to null
        val now = System.currentTimeMillis() / 1000
        val i = list.indexOfFirst { it.start <= now && it.stop > now }
        return if (i < 0) null to list.firstOrNull { it.start > now } else list[i] to list.getOrNull(i + 1)
    }

    fun schedule(id: String): List<Prog> = byChannel[id] ?: emptyList()

    private fun compactFile(ctx: Context) = File(ctx.filesDir, "epg-compact.bin")

    /** Instant start: the guide saved after the last download. Returns its age in seconds, or null. */
    suspend fun restore(ctx: Context): Long? = withContext(Dispatchers.IO) {
        val f = compactFile(ctx)
        if (!f.exists()) return@withContext null
        try {
            java.io.DataInputStream(java.io.BufferedInputStream(f.inputStream(), 65536)).use { inp ->
                val savedAt = inp.readLong()
                val nCh = inp.readInt()
                val map = HashMap<String, List<Prog>>(nCh * 2)
                repeat(nCh) {
                    val id = inp.readUTF()
                    val n = inp.readInt()
                    map[id] = List(n) { Prog(inp.readLong(), inp.readLong(), inp.readUTF()) }
                }
                val nIc = inp.readInt()
                val ic = HashMap<String, String>(nIc * 2)
                repeat(nIc) { ic[inp.readUTF()] = inp.readUTF() }
                byChannel = map; icons = ic
                System.currentTimeMillis() / 1000 - savedAt
            }
        } catch (_: Exception) { null }
    }

    private fun saveCompact(ctx: Context) {
        try {
            val tmp = File(ctx.filesDir, "epg-compact.tmp")
            java.io.DataOutputStream(java.io.BufferedOutputStream(tmp.outputStream(), 65536)).use { out ->
                out.writeLong(System.currentTimeMillis() / 1000)
                val m = byChannel
                out.writeInt(m.size)
                m.forEach { (id, list) ->
                    out.writeUTF(id); out.writeInt(list.size)
                    list.forEach { out.writeLong(it.start); out.writeLong(it.stop); out.writeUTF(it.title.take(200)) }
                }
                val ic = icons
                out.writeInt(ic.size)
                ic.forEach { (k, v) -> out.writeUTF(k); out.writeUTF(v) }
            }
            tmp.renameTo(compactFile(ctx))
        } catch (_: Exception) {}
    }

    suspend fun load(ctx: Context, channels: List<Channel>, sources: List<String>): Boolean = withContext(Dispatchers.IO) {
        // normalized name -> our channel ids
        val keyToIds = HashMap<String, MutableList<String>>()
        channels.forEach { c -> LogoIndex.keys(c.name).forEach { k -> keyToIds.getOrPut(k) { mutableListOf() } += c.id } }
        for (src in sources) {
            try {
                val file = download(ctx, src)
                val ok = parse(file, keyToIds)
                if (ok) { saveCompact(ctx); return@withContext true }
            } catch (_: Exception) {}
        }
        false
    }

    private fun download(ctx: Context, src: String): File {
        val f = File(ctx.cacheDir, "epg-" + (src.hashCode() and 0x7fffffff) + ".bin")
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 8 * 3600_000L && f.length() > 1000) return f
        val c = URL(src).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 120000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
        val tmp = File(f.path + ".tmp")
        c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
        c.disconnect()
        tmp.renameTo(f)
        return f
    }

    private fun open(f: File): InputStream {
        val b = BufferedInputStream(f.inputStream(), 65536)
        b.mark(2)
        val m1 = b.read(); val m2 = b.read()
        b.reset()
        return if (m1 == 0x1f && m2 == 0x8b) GZIPInputStream(b, 65536) else b
    }

    private fun parse(f: File, keyToIds: Map<String, List<String>>): Boolean {
        val now = System.currentTimeMillis() / 1000
        val from = now - 12 * 3600; val to = now + 36 * 3600
        val xmlToOurs = HashMap<String, List<String>>()
        val iconOf = HashMap<String, String>()
        val progs = HashMap<String, MutableList<Prog>>()
        open(f).use { input ->
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(input, null)
            var chId: String? = null
            var chIcon: String? = null
            val chNames = mutableListOf<String>()
            var progCh: String? = null; var ps = 0L; var pe = 0L; var title: String? = null
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "channel" -> { chId = p.getAttributeValue(null, "id"); chNames.clear(); chIcon = null }
                        "display-name" -> if (chId != null) chNames += p.nextText().trim()
                        "icon" -> if (chId != null && chIcon == null) chIcon = p.getAttributeValue(null, "src")
                        "programme" -> {
                            val c = p.getAttributeValue(null, "channel")
                            progCh = if (c != null && xmlToOurs.containsKey(c)) c else null
                            if (progCh != null) {
                                ps = time(p.getAttributeValue(null, "start")); pe = time(p.getAttributeValue(null, "stop"))
                                title = null
                                if (pe < from || ps > to) progCh = null
                            }
                        }
                        "title" -> if (progCh != null && title == null) title = p.nextText().trim()
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    when (p.name) {
                        "channel" -> {
                            val id = chId
                            if (id != null) {
                                val ours = chNames.flatMap { n -> LogoIndex.keys(n).flatMap { keyToIds[it] ?: emptyList() } }.distinct()
                                if (ours.isNotEmpty()) {
                                    xmlToOurs[id] = ours
                                    chIcon?.let { ic -> ours.forEach { iconOf.putIfAbsent(it, ic) } }
                                }
                            }
                            chId = null
                        }
                        "programme" -> {
                            val c = progCh
                            if (c != null && title != null) {
                                val pr = Prog(ps, pe, title!!)
                                xmlToOurs[c]?.forEach { progs.getOrPut(it) { mutableListOf() } += pr }
                            }
                            progCh = null
                        }
                    }
                }
                ev = p.next()
            }
        }
        if (progs.isEmpty()) return false
        progs.values.forEach { l -> l.sortBy { it.start } }
        byChannel = progs
        icons = iconOf
        return true
    }

    /** "20261114003000 +0300" -> unix seconds */
    private fun time(s: String?): Long {
        if (s == null || s.length < 14) return 0
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(s.substring(0, 4).toInt(), s.substring(4, 6).toInt() - 1, s.substring(6, 8).toInt(),
            s.substring(8, 10).toInt(), s.substring(10, 12).toInt(), s.substring(12, 14).toInt())
        var t = cal.timeInMillis / 1000
        val tz = s.substring(14).trim()
        if (tz.length >= 5 && (tz[0] == '+' || tz[0] == '-')) {
            val off = tz.substring(1, 3).toInt() * 3600 + tz.substring(3, 5).toInt() * 60
            t -= if (tz[0] == '+') off else -off
        }
        return t
    }
}
