package com.arthuralend.iptvpromax

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

data class Channel(
    val id: String,
    val name: String,
    val group: String,
    val logo: String?,
    val days: Int,
    /** direct stream url (M3U mode only); null in server mode */
    val url: String?,
    val number: Int = 0,
)

/** Russian or English, following the system language. */
object L {
    val ru: Boolean = Locale.getDefault().language in setOf("ru", "be", "uk", "kk", "hy")
    private val en = mapOf(
        "Поиск" to "Search", "Все каналы" to "All channels", "Избранное" to "Favorites", "Недавние" to "Recent",
        "Настройки" to "Settings", "Группы" to "Groups", "Без группы" to "Ungrouped", "каналов" to "channels",
        "Загрузка…" to "Loading…", "Нет каналов" to "No channels",
        "Источник не настроен" to "No source configured",
        "Откройте «Настройки» и введите код или ссылку на плейлист" to "Open Settings and enter a code or playlist link",
        "Код или адрес сервера / ссылка M3U" to "Code, server address or M3U link",
        "Пароль сайта (если включён)" to "Site password (if enabled)",
        "Сохранить" to "Save", "Очистить недавние" to "Clear recent", "Подключение…" to "Connecting…",
        "Ошибка воспроизведения" to "Playback error", "ЭФИР" to "LIVE",
        "Архив недоступен" to "Archive not available", "С начала передачи" to "Start over",
        "OK — список каналов · ▲▼ — переключение · ◀▶ — перемотка · долгое OK — меню" to
            "OK — channel list · ▲▼ — zap · ◀▶ — seek · long OK — menu",
        "Долгое нажатие OK — в избранное" to "Long press OK — add to favorites",
        "Не удалось загрузить список: " to "Could not load channels: ",
        "Неверный пароль" to "Wrong password", "Обновить список" to "Refresh list",
        "Продолжить просмотр" to "Continue watching", "Добавлено в избранное" to "Added to favorites",
        "Убрано из избранного" to "Removed from favorites", "Источник" to "Source",
        "Подключено" to "Connected",
    )
    fun t(s: String) = if (ru) s else en[s] ?: s
}

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("iptv", Context.MODE_PRIVATE)

    var source: String
        get() = p.getString("source", "") ?: ""
        set(v) = p.edit().putString("source", v).apply()
    var password: String
        get() = p.getString("password", "") ?: ""
        set(v) = p.edit().putString("password", v).apply()
    var cookie: String
        get() = p.getString("cookie", "") ?: ""
        set(v) = p.edit().putString("cookie", v).apply()
    var favorites: Set<String>
        get() = p.getStringSet("fav", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("fav", v).apply()
    var recents: List<String>
        get() = (p.getString("recent", "") ?: "").split('\n').filter { it.isNotEmpty() }
        set(v) = p.edit().putString("recent", v.joinToString("\n")).apply()
    var lastSection: String
        get() = p.getString("section", "all") ?: "all"
        set(v) = p.edit().putString("section", v).apply()
}

object Sources {
    /** Typing this code in Settings connects the owner's own server. */
    const val CODE = "22184"
    const val CODE_SERVER = "http://82.26.86.235:8090"
    const val UA = "VLC/3.0.21 LibVLC/3.0.21"

    fun normalize(input: String): String {
        val s = input.trim()
        if (s == CODE) return CODE_SERVER
        if (s.isEmpty()) return ""
        return if (s.startsWith("http://") || s.startsWith("https://")) s else "http://$s"
    }

    fun isM3U(source: String): Boolean {
        val path = source.substringBefore('?').lowercase()
        return path.endsWith(".m3u") || path.endsWith(".m3u8") || "/playlist" in path || "get.php" in path
    }
}

class Repository(private val ctx: Context, private val prefs: Prefs) {
    private val cacheFile = File(ctx.filesDir, "channels.json")

    fun cached(): List<Channel> = try {
        if (cacheFile.exists()) fromJson(JSONArray(cacheFile.readText())) else emptyList()
    } catch (_: Exception) { emptyList() }

    suspend fun load(): List<Channel> = withContext(Dispatchers.IO) {
        val src = prefs.source
        require(src.isNotEmpty()) { L.t("Источник не настроен") }
        val list = if (Sources.isM3U(src)) loadM3U(src) else loadServer(src)
        val numbered = list.mapIndexed { i, c -> c.copy(number = i + 1) }
        cacheFile.writeText(toJson(numbered).toString())
        numbered
    }

    // ── server mode (the IPTV Pro Max web server) ──
    private fun loadServer(base: String): List<Channel> {
        var (code, body) = get("$base/api/channels", prefs.cookie)
        if (code == 401) {
            login(base)
            val r = get("$base/api/channels", prefs.cookie)
            code = r.first; body = r.second
        }
        if (code == 401) throw IllegalStateException(L.t("Неверный пароль"))
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        val arr = JSONObject(body).getJSONArray("channels")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Channel(
                id = o.getString("id"),
                name = o.getString("name"),
                group = o.optString("group").ifEmpty { L.t("Без группы") },
                logo = o.optString("logo").ifEmpty { null },
                days = o.optInt("days"),
                url = null,
            )
        }
    }

    private fun login(base: String) {
        val c = URL("$base/api/login").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 10000
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(JSONObject().put("password", prefs.password).toString().toByteArray()) }
        if (c.responseCode == 200) {
            val cookie = c.headerFields["Set-Cookie"]?.firstOrNull()?.substringBefore(';') ?: ""
            prefs.cookie = cookie
        }
        c.disconnect()
    }

    // ── plain M3U link ──
    private fun loadM3U(url: String): List<Channel> {
        val (code, text) = get(url, null)
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        return parseM3U(text)
    }

    private fun get(url: String, cookie: String?): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", Sources.UA)
        if (!cookie.isNullOrEmpty()) c.setRequestProperty("Cookie", cookie)
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return code to body
    }

    /** Stream address for the player. */
    fun streamUrl(c: Channel, archiveStartSec: Long?, direct: Boolean): String {
        val src = prefs.source
        if (c.url != null) {
            if (archiveStartSec == null) return c.url
            val now = System.currentTimeMillis() / 1000
            return c.url + (if ('?' in c.url) "&" else "?") + "utc=$archiveStartSec&lutc=$now"
        }
        val q = mutableListOf<String>()
        if (archiveStartSec != null) q += "start=$archiveStartSec"
        if (direct) q += "direct=1"
        return "$src/play/${URLEncoder.encode(c.id, "UTF-8")}.m3u8" + if (q.isEmpty()) "" else "?" + q.joinToString("&")
    }

    val isServer: Boolean get() = prefs.source.isNotEmpty() && !Sources.isM3U(prefs.source)

    companion object {
        private val attrRe = Regex("""([\w-]+)="([^"]*)"""")

        fun parseM3U(text: String): List<Channel> {
            val out = mutableListOf<Channel>()
            var attrs = mapOf<String, String>()
            var name = ""
            var grp: String? = null
            for (raw in text.replace("\r", "").split('\n')) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                when {
                    line.startsWith("#EXTINF") -> {
                        attrs = attrRe.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                        var q = false; var last = -1
                        line.forEachIndexed { i, ch -> if (ch == '"') q = !q else if (ch == ',' && !q) last = i }
                        name = if (last >= 0) line.substring(last + 1).trim() else ""
                        grp = null
                    }
                    line.startsWith("#EXTGRP:") -> grp = line.substring(8).trim()
                    line.startsWith("#") -> {}
                    else -> {
                        val days = (attrs["catchup-days"] ?: attrs["tvg-rec"] ?: attrs["timeshift"])?.toIntOrNull() ?: 0
                        out += Channel(
                            id = sha1(line).take(12),
                            name = name.ifEmpty { attrs["tvg-name"] ?: "Channel" },
                            group = (attrs["group-title"] ?: grp)?.ifEmpty { null } ?: L.t("Без группы"),
                            logo = attrs["tvg-logo"]?.ifEmpty { null },
                            days = days,
                            url = line,
                        )
                        attrs = emptyMap(); name = ""; grp = null
                    }
                }
            }
            return out
        }

        private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        fun toJson(list: List<Channel>) = JSONArray().apply {
            list.forEach {
                put(JSONObject().put("id", it.id).put("name", it.name).put("group", it.group)
                    .put("logo", it.logo ?: "").put("days", it.days).put("url", it.url ?: "").put("n", it.number))
            }
        }

        fun fromJson(a: JSONArray) = (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Channel(o.getString("id"), o.getString("name"), o.getString("group"),
                o.optString("logo").ifEmpty { null }, o.optInt("days"), o.optString("url").ifEmpty { null }, o.optInt("n"))
        }
    }
}
