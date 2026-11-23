package app.meh

import java.net.URI
import java.net.URLDecoder

const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

private val IC = setOf(RegexOption.IGNORE_CASE)

/** Hosts/paths that serve ads, trackers or analytics — never real content. */
private val AD_PATTERNS = listOf(
    "doubleclick\\.net", "googlesyndication\\.com", "googleadservices\\.com", "imasdk\\.googleapis\\.com",
    "2mdn\\.net", "adnxs\\.com", "springserve", "teads\\.", "taboola", "outbrain", "spotx",
    "adsrvr\\.org", "amazon-adsystem", "moatads", "innovid", "serving-sys\\.com", "pubmatic",
    "rubiconproject", "criteo", "adform", "smartadserver", "connatix", "primis\\.tech",
    "aniview", "vidazoo", "exco\\.com", "jwpltx\\.com", "/ads?/", "[?&/._-]vast[?&/._-]",
    "preroll", "pre-roll", "/adserver", "adsystem",
).map { Regex(it, IC) }

fun isAdUrl(url: String) = AD_PATTERNS.any { it.containsMatchIn(url) }

private val SEGMENT_EXT = Regex("\\.(ts|m4s|aac|m4a|mp3|vtt|key)(\\?|#|$)", IC)
fun isSegment(url: String) = SEGMENT_EXT.containsMatchIn(url.substringBefore('?'))

/** "hls", "dash", "file" or null. */
fun mediaKindFromUrl(url: String, contentType: String = ""): String? {
    val ct = contentType.lowercase()
    return when {
        "mpegurl" in ct || Regex("\\.m3u8(\\?|#|$)", IC).containsMatchIn(url) -> "hls"
        "dash+xml" in ct || Regex("\\.mpd(\\?|#|$)", IC).containsMatchIn(url) -> "dash"
        ct.startsWith("video/") || Regex("\\.(mp4|m4v|webm|ogv|mov)(\\?|#|$)", IC).containsMatchIn(url) -> "file"
        else -> null
    }
}

data class Embed(val provider: String, val id: String)

/** Pages we play with the site's own embedded player instead of sniffing their streams. */
fun embedFromUrl(raw: String): Embed? {
    val u = try { URI(raw) } catch (e: Exception) { return null }
    val host = (u.host ?: return null).lowercase().removePrefix("www.").removePrefix("m.")
    val path = u.rawPath ?: ""
    val query = queryParams(u.rawQuery)
    val ytId = Regex("^[\\w-]{11}$")
    when (host) {
        "youtube.com", "youtube-nocookie.com" -> {
            val id = if (path == "/watch") query["v"]
            else Regex("^/(?:embed|shorts|v|live)/([\\w-]{11})").find(path)?.groupValues?.get(1)
            if (id != null && ytId.matches(id)) return Embed("youtube", id)
        }
        "youtu.be" -> {
            val id = path.drop(1).take(11)
            if (ytId.matches(id)) return Embed("youtube", id)
        }
        "vimeo.com", "player.vimeo.com" -> {
            Regex("(?:^|/)(?:video/)?(\\d{5,})(?:/|$)").find(path)?.let { return Embed("vimeo", it.groupValues[1]) }
        }
        "dailymotion.com", "geo.dailymotion.com", "dai.ly" -> {
            val id = Regex("/(?:embed/)?video/([a-z0-9]+)", IC).find(path)?.groupValues?.get(1)
                ?: (if (host == "dai.ly") Regex("/([a-z0-9]+)", IC).find(path)?.groupValues?.get(1) else null)
                ?: query["video"]
            if (!id.isNullOrEmpty()) return Embed("dailymotion", id)
        }
    }
    return null
}

fun queryParams(raw: String?): Map<String, String> {
    if (raw.isNullOrEmpty()) return emptyMap()
    return raw.split('&').mapNotNull {
        val i = it.indexOf('=')
        if (i <= 0) null else decodeUrl(it.substring(0, i)) to decodeUrl(it.substring(i + 1))
    }.toMap()
}

fun decodeUrl(s: String): String = try { URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }

/** YouTube is excluded entirely: its pages, embeds, streams and thumbnails. */
private val YOUTUBE_HOST = Regex("(^|\\.)(youtube\\.com|youtu\\.be|youtube-nocookie\\.com|googlevideo\\.com|ytimg\\.com)$", IC)
fun isYouTube(url: String?): Boolean = try {
    url != null && YOUTUBE_HOST.containsMatchIn(URI(url.trim()).host ?: "")
} catch (e: Exception) { false }

fun domainOf(url: String): String = try {
    (URI(url).host ?: "").removePrefix("www.")
} catch (e: Exception) { "" }

/** Resolve a possibly-relative URL against a base. */
fun absolute(url: String?, base: String): String? {
    if (url.isNullOrBlank()) return null
    return try {
        val r = URI(base).resolve(url.trim().replace(" ", "%20"))
        if (r.scheme == null) null else r.toString()
    } catch (e: Exception) {
        if (url.startsWith("http")) url else null
    }
}

private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")
fun decodeEntities(s: String): String =
    Regex("&(#\\d+|#x[0-9a-fA-F]+|[a-zA-Z]+);").replace(s) { m ->
        val n = m.groupValues[1]
        try {
            when {
                n.startsWith("#x") || n.startsWith("#X") -> String(Character.toChars(n.substring(2).toInt(16)))
                n.startsWith("#") -> String(Character.toChars(n.substring(1).toInt()))
                else -> NAMED[n.lowercase()] ?: m.value
            }
        } catch (e: Exception) { m.value }
    }

fun stripTags(s: String): String =
    decodeEntities(s.replace(Regex("<[^<>]{0,2000}>"), " ")).replace(Regex("\\s+"), " ").trim()

/** ISO-8601 duration (PT1H2M3S), plain seconds, or clock time (4:13) → seconds. */
fun parseDuration(d: Any?): Double? {
    if (d == null) return null
    if (d is Number) return d.toDouble().takeIf { it > 0 }
    val s = d.toString().trim()
    s.toDoubleOrNull()?.let { return it.takeIf { v -> v > 0 } }
    if (Regex("^\\d+(:\\d+)+$").matches(s)) {
        return s.split(':').fold(0.0) { acc, p -> acc * 60 + p.toInt() }.takeIf { it > 0 }
    }
    val m = Regex("^P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?$", IC).find(s) ?: return null
    fun g(i: Int) = m.groupValues[i].toDoubleOrNull() ?: 0.0
    val secs = g(1) * 86400 + g(2) * 3600 + g(3) * 60 + g(4)
    return secs.takeIf { it > 0 }
}

fun unescapeJsonString(s: String): String = try {
    org.json.JSONTokener("\"$s\"").nextValue() as String
} catch (e: Exception) { s }
