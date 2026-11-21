package app.meh

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** What a page (static HTML or a live WebView) tells us about itself. */
data class Probe(
    val url: String,
    val title: String = "",
    val metas: Map<String, String> = emptyMap(),
    val ld: List<String> = emptyList(),
    val videos: List<ProbeVideo> = emptyList(),
    val iframes: List<String> = emptyList(),
    val text: String = "",
)

data class ProbeVideo(
    val src: String?,
    val sources: List<Pair<String, String>>, // src, type
    val poster: String?,
    val duration: Double?,
    val w: Double?,
    val h: Double?,
)

data class Candidate(
    val kind: String,
    val src: String,
    val origin: String,
    val referer: String,
    val provider: String? = null,
    val id: String? = null,
    val type: String = "",
    val title: String = "",
    val description: String = "",
    val thumbnail: String? = null,
    val duration: Double? = null,
    val size: Long = 0,
    val tooSmall: Boolean = false,
)

/**
 * Page inspection, part 1: pure parsing. Finds media declared in a page's HTML
 * (og:video, JSON-LD VideoObject, <video>/<source>, known player iframes) and
 * picks the best one. The live-browser part is in [WebInspector].
 */
object Inspector {
    const val MIN_DURATION = 20.0

    private val IC = RegexOption.IGNORE_CASE

    private fun attr(tag: String, name: String): String? {
        val m = Regex("\\b$name\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", IC).find(tag) ?: return null
        val g = m.groupValues
        return decodeEntities(g[2].ifEmpty { g[3] }.ifEmpty { g[4] })
    }

    fun parseStaticHtml(html: String, baseUrl: String): Probe {
        val title = Regex("<title[^>]*>([\\s\\S]*?)</title>", IC).find(html)?.let { stripTags(it.groupValues[1]) } ?: ""
        val metas = LinkedHashMap<String, String>()
        for (m in Regex("<meta\\b[^>]*>", IC).findAll(html)) {
            val tag = m.value
            val key = (attr(tag, "property") ?: attr(tag, "name") ?: attr(tag, "itemprop") ?: "").lowercase()
            val v = attr(tag, "content")
            if (key.isNotEmpty() && !v.isNullOrEmpty() && key !in metas) metas[key] = v
        }
        val ld = Regex("<script[^>]+type=[\"']application/ld\\+json[\"'][^>]*>([\\s\\S]*?)</script>", IC).findAll(html).map { it.groupValues[1] }.toList()
        val videos = Regex("<video\\b([^>]*)>([\\s\\S]*?)</video>", IC).findAll(html).map { m ->
            val sources = Regex("<source\\b[^>]*>", IC).findAll(m.groupValues[2]).mapNotNull { s ->
                absolute(attr(s.value, "src"), baseUrl)?.let { it to (attr(s.value, "type") ?: "") }
            }.toList()
            ProbeVideo(absolute(attr(m.groupValues[1], "src"), baseUrl), sources, absolute(attr(m.groupValues[1], "poster"), baseUrl), null, null, null)
        }.toList()
        val iframes = Regex("<iframe\\b[^>]*>", IC).findAll(html).mapNotNull { absolute(attr(it.value, "src") ?: attr(it.value, "data-src"), baseUrl) }.toList()
        val body = Regex("<body[\\s\\S]*$", IC).find(html)?.value ?: html
        val text = stripTags(body.replace(Regex("<(script|style|noscript)[\\s\\S]*?</\\1>", IC), " ")).take(3000)
        return Probe(baseUrl, title, metas, ld, videos, iframes, text)
    }

    /** Parse the JSON produced by [WebInspector.PROBE_JS] in a live page. */
    fun parseProbeJson(json: String): Probe? = try {
        val o = JSONObject(json)
        val metas = LinkedHashMap<String, String>()
        o.optJSONObject("metas")?.let { m -> m.keys().forEach { k -> metas[k] = m.optString(k) } }
        Probe(
            url = o.optString("url"),
            title = o.optString("title"),
            metas = metas,
            ld = o.optJSONArray("ld").strings(),
            videos = (0 until (o.optJSONArray("videos")?.length() ?: 0)).map { i ->
                val v = o.getJSONArray("videos").getJSONObject(i)
                val srcs = v.optJSONArray("sources") ?: JSONArray()
                ProbeVideo(
                    v.optString("src").ifEmpty { null },
                    (0 until srcs.length()).map { j -> srcs.getJSONObject(j).let { it.optString("src") to it.optString("type") } },
                    v.optString("poster").ifEmpty { null },
                    v.optDouble("duration").takeIf { !it.isNaN() && it > 0 },
                    v.optDouble("w").takeIf { !it.isNaN() },
                    v.optDouble("h").takeIf { !it.isNaN() },
                )
            },
            iframes = o.optJSONArray("iframes").strings(),
            text = o.optString("text"),
        )
    } catch (e: Exception) { null }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }

    // ------------------------------------------------------------------ candidates

    private fun flattenLd(node: Any?, acc: MutableList<JSONObject>) {
        when (node) {
            is JSONArray -> for (i in 0 until node.length()) flattenLd(node.opt(i), acc)
            is JSONObject -> {
                val t = node.opt("@type").let { if (it is JSONArray) it.strings().joinToString(",") else it?.toString() ?: "" }
                if (Regex("VideoObject|Clip|Movie|Episode", IC).containsMatchIn(t) && (node.has("contentUrl") || node.has("embedUrl"))) acc += node
                for (k in node.keys()) if (k != "@context") node.opt(k).let { if (it is JSONObject || it is JSONArray) flattenLd(it, acc) }
            }
        }
    }

    private fun firstString(v: Any?): String? = when (v) {
        null, JSONObject.NULL -> null
        is JSONArray -> firstString(v.opt(0))
        is JSONObject -> firstString(v.opt("url") ?: v.opt("contentUrl") ?: v.opt("@id"))
        else -> v.toString()
    }

    private fun candidateFromUrl(src: String?, base: String, origin: String, referer: String, type: String = "",
                                 title: String = "", description: String = "", thumbnail: String? = null,
                                 duration: Double? = null, tooSmall: Boolean = false): Candidate? {
        val url = absolute(src, base) ?: return null
        if (url.startsWith("blob:") || url.startsWith("data:") || isAdUrl(url)) return null
        embedFromUrl(url)?.let { return Candidate("embed", url, origin, referer, it.provider, it.id, type, title, description, thumbnail, duration) }
        val kind = mediaKindFromUrl(url, type)
        if (kind == "file" || kind == "hls") return Candidate(kind, url, origin, referer, null, null, type, title, description, thumbnail, duration, tooSmall = tooSmall)
        return null
    }

    fun candidatesFromProbe(p: Probe): List<Candidate> {
        val out = mutableListOf<Candidate>()
        val base = p.url
        for (block in p.ld) {
            val data = try { JSONTokener(block.trim()).nextValue() } catch (e: Exception) { continue }
            val vids = mutableListOf<JSONObject>()
            flattenLd(data, vids)
            for (v in vids) {
                val title = stripTags(v.optString("name").ifEmpty { v.optString("headline") })
                val desc = stripTags(v.optString("description")).take(400)
                val thumb = absolute(firstString(v.opt("thumbnailUrl") ?: v.opt("thumbnail") ?: v.opt("image")), base)
                val dur = parseDuration(v.opt("duration")?.takeIf { it != JSONObject.NULL })
                (candidateFromUrl(firstString(v.opt("contentUrl")), base, "jsonld", base, v.optString("encodingFormat"), title, desc, thumb, dur)
                    ?: candidateFromUrl(firstString(v.opt("embedUrl")), base, "jsonld", base, "", title, desc, thumb, dur))?.let { out += it }
            }
        }
        val m = p.metas
        val ogType = m["og:video:type"] ?: m["twitter:player:stream:content_type"] ?: ""
        val ogDur = parseDuration(m["video:duration"] ?: m["og:video:duration"] ?: m["duration"])
        for (k in listOf("og:video:secure_url", "og:video:url", "og:video", "twitter:player:stream", "twitter:player")) {
            candidateFromUrl(m[k], base, "og", base, ogType, duration = ogDur)?.let { out += it }
        }
        for (v in p.videos) {
            val small = v.w != null && v.w > 0 && (v.w < 200 || (v.h ?: 0.0) < 110)
            for ((src, type) in listOf((v.src ?: "") to "") + v.sources) {
                candidateFromUrl(src, base, "dom", base, type, thumbnail = absolute(v.poster, base), duration = v.duration, tooSmall = small)?.let { out += it }
            }
        }
        for (src in p.iframes) embedFromUrl(src)?.let { out += Candidate("embed", src, "iframe", base, it.provider, it.id) }
        return out
    }

    private fun scoreCandidate(c: Candidate): Int {
        if (c.duration != null && c.duration < MIN_DURATION) return -1
        if (c.tooSmall) return -1
        var s = when (c.origin) { "page" -> 60; "jsonld" -> 50; "og" -> 45; "dom" -> 40; "network" -> 35; "iframe" -> 30; else -> 20 }
        if (c.kind == "hls") s += 4
        if (c.size > 2_000_000) s += 6
        if (c.kind == "file" && c.size in 1..299_999) s -= 25
        if ((c.duration ?: 0.0) >= 60) s += 5
        return s
    }

    /** Pick the best distinct media on a page and attach page-level metadata. */
    fun finalize(hit: Hit, main: Probe, cands: List<Candidate>): List<Video> {
        val m = main.metas
        val pageTitle = m["og:title"] ?: m["twitter:title"] ?: main.title.ifEmpty { hit.title }
        val pageDesc = (m["og:description"] ?: m["description"] ?: m["twitter:description"] ?: hit.snippet).take(400)
        val pageThumb = absolute(m["og:image"] ?: m["twitter:image"] ?: m["thumbnailurl"], main.url)
        val siteName = m["og:site_name"] ?: domainOf(main.url.ifEmpty { hit.url })

        val byKey = LinkedHashMap<String, Pair<Candidate, Int>>()
        for (c in cands) {
            val key = if (c.kind == "embed") "${c.provider}:${c.id}" else c.src.substringBefore('?').substringBefore('#')
            val s = scoreCandidate(c)
            if (s < 0) continue
            val prev = byKey[key]
            byKey[key] = if (prev == null) c to s else {
                val p = prev.first
                p.copy(title = p.title.ifEmpty { c.title }, thumbnail = p.thumbnail ?: c.thumbnail, duration = p.duration ?: c.duration) to maxOf(prev.second, s)
            }
        }
        val sorted = byKey.values.sortedByDescending { it.second }.map { it.first }
        val multi = sorted.filter { it.origin == "jsonld" && it.title.isNotEmpty() }
        val chosen = if (multi.size > 1) multi.take(3) else sorted.take(1)
        return chosen.map { c ->
            Video(
                kind = c.kind, src = c.src, provider = c.provider, id = c.id,
                referer = c.referer.ifEmpty { hit.url },
                title = c.title.ifEmpty { pageTitle }, description = c.description.ifEmpty { pageDesc },
                thumbnail = c.thumbnail ?: pageThumb ?: (if (c.provider == "youtube") "https://i.ytimg.com/vi/${c.id}/hqdefault.jpg" else null),
                duration = c.duration ?: hit.duration, siteName = siteName,
                pageUrl = hit.url, pageTitle = pageTitle, pageText = main.text, via = c.origin,
            )
        }
    }

    /** Result of the fast, browser-free stage. */
    class StaticResult(val videos: List<Video>, val probe: Probe?)

    /** Stage 1: fetch raw HTML and read what the page declares. */
    fun staticStage(hit: Hit): StaticResult {
        val pageEmbed = embedFromUrl(hit.url)
        var probe: Probe? = null
        try {
            Http.client.newCall(Http.pageRequest(hit.url)).execute().use { res ->
                val ct = res.header("Content-Type") ?: ""
                val finalUrl = res.request.url.toString()
                val kind = mediaKindFromUrl(finalUrl, ct)
                if (res.isSuccessful && (kind == "file" || kind == "hls")) {
                    // The search hit *is* a media file.
                    return StaticResult(listOf(Video(
                        kind = kind, src = finalUrl, referer = hit.url,
                        title = hit.title.ifEmpty { decodeUrl(finalUrl.substringBefore('?').substringAfterLast('/')) },
                        description = hit.snippet, siteName = domainOf(finalUrl), pageUrl = hit.url, pageTitle = hit.title,
                        duration = hit.duration, via = "direct",
                    )), null)
                }
                if (res.isSuccessful && "html" in ct.lowercase()) {
                    val src = res.body?.source()
                    src?.request(3_000_000)
                    val html = src?.buffer?.let { b -> b.readUtf8(minOf(b.size, 3_000_000)) } ?: ""
                    probe = parseStaticHtml(html, finalUrl)
                }
            }
        } catch (e: Exception) { /* fall through to the browser */ }

        val p = probe
        if (p != null) {
            val cands = candidatesFromProbe(p).toMutableList()
            if (pageEmbed != null) cands.add(0, Candidate("embed", hit.url, "page", hit.url, pageEmbed.provider, pageEmbed.id, title = hit.title))
            val best = finalize(hit, p, cands)
            if (best.isNotEmpty()) return StaticResult(best, p)
        } else if (pageEmbed != null) {
            return StaticResult(finalize(hit, Probe(hit.url, hit.title), listOf(
                Candidate("embed", hit.url, "page", hit.url, pageEmbed.provider, pageEmbed.id, title = hit.title))), null)
        }
        return StaticResult(emptyList(), p)
    }
}
