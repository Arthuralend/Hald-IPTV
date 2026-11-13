package com.arthuralend.iptvpromax

import android.content.Context
import java.util.Locale

/**
 * Channel logos from the open iptv-org database, bundled as assets/logos.tsv
 * (built by tools/gen_logo_index.py — the normalization here must match it).
 */
object LogoIndex {
    @Volatile private var map: HashMap<String, List<Pair<String, String>>>? = null

    private fun load(ctx: Context): HashMap<String, List<Pair<String, String>>> {
        map?.let { return it }
        synchronized(this) {
            map?.let { return it }
            val m = HashMap<String, List<Pair<String, String>>>(60000)
            try {
                ctx.assets.open("logos.tsv").bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val parts = line.split('\t')
                        if (parts.size >= 2) m[parts[0]] = parts.drop(1).mapNotNull {
                            val sp = it.indexOf(' ')
                            if (sp > 0) it.substring(0, sp) to it.substring(sp + 1) else null
                        }
                    }
                }
            } catch (_: Exception) {}
            map = m
            return m
        }
    }

    private val QUALITY = setOf("uhd", "fhd", "hd", "4k", "sd", "hevc", "orig", "original", "50", "60", "hq")
    private val GENERIC = setOf("телеканал", "канал", "tv", "тв", "channel")
    private val COUNTRY = Regex("[a-z]{2}")
    private val BRACKETS = Regex("""\[[^\]]*]|\([^)]*\)""")
    private val TIMESHIFT = Regex("""\s\+\s*\d+.*$""")
    private val SPLIT = Regex("""[^\p{L}\p{N}_+!]+""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}_]""")
    private val TR = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "zh", 'з' to "z",
        'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r",
        'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch",
        'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya", 'і' to "i", 'ї' to "yi", 'є' to "ye", 'ґ' to "g",
    )

    private fun rawWords(s: String): List<String> {
        var t = s.lowercase(Locale.ROOT).replace('ё', 'е')
        t = BRACKETS.replace(t, " ")
        t = TIMESHIFT.replace(t, " ")
        return t.split(SPLIT).filter { it.isNotEmpty() }.map { if (it == "+") "plus" else it }
    }

    private fun words(s: String): List<String> {
        val w = rawWords(s).toMutableList()
        while (w.size > 1 && (w.last() in QUALITY || COUNTRY.matches(w.last()))) w.removeAt(w.lastIndex)
        if (w.isNotEmpty() && w[0] == "vf" && w.size > 1) w.removeAt(0)
        return w
    }

    private fun join(w: List<String>) = NON_WORD.replace(w.joinToString(""), "")
    private fun translit(s: String) = buildString { s.forEach { append(TR[it] ?: it.toString()) } }

    fun keys(name: String): List<String> {
        val w = words(name)
        val out = mutableListOf(join(w))
        val g = w.filter { it !in GENERIC }
        if (g.isNotEmpty() && g != w) out += join(g)
        return out.filter { it.isNotEmpty() }.flatMap { listOf(it, translit(it)) }.distinct()
    }

    /** Country hint from the channel name: trailing "DE", "TR", "PL"… or Cyrillic -> RU/AM/BY/KZ/UA. */
    private fun preferred(name: String): List<String> {
        val raw = rawWords(name)
        val cc = raw.lastOrNull { COUNTRY.matches(it) && it !in QUALITY }?.takeIf { raw.size > 1 && raw.last() == it }
        val cyr = name.any { it in 'Ѐ'..'ӿ' }
        val base = if (cyr) listOf("ru", "am", "by", "kz", "ua") else listOf("am", "us", "uk", "ru")
        return (listOfNotNull(cc?.let { if (it == "uk") "uk" else it }) + base).distinct()
    }

    fun find(ctx: Context, ch: Channel): String? {
        val m = load(ctx)
        for (k in keys(ch.name)) {
            val cands = m[k] ?: continue
            for (cc in preferred(ch.name)) {
                cands.firstOrNull { it.first.lowercase(Locale.ROOT).endsWith(".$cc") }?.let { return it.second }
            }
            return cands.first().second
        }
        return null
    }
}
