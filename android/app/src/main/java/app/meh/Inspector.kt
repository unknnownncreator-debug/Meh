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
    /** Read from the live page in a WebView (vs. the raw HTML). */
    val live: Boolean = false,
    /** Video links the page keeps inside its scripts. */
    val scriptMedia: List<String> = emptyList(),
)

data class ProbeVideo(
    val src: String?,
    val sources: List<Pair<String, String>>, // src, type
    val poster: String?,
    val duration: Double?,
    val w: Double?,
    val h: Double?,
    /** Sits inside an ad container (IMA / VAST / ad slot) on the live page. */
    val ad: Boolean = false,
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
    val ad: Boolean = false,
    /** Order in which the page requested it (network sniffing): players load the ad first. */
    val seq: Int = 0,
)

/**
 * Page inspection, part 1: pure parsing. Finds media declared in a page's HTML
 * (og:video, JSON-LD VideoObject, <video>/<source>, known player iframes) and
 * picks the best one. The live-browser part is in [WebInspector].
 */
object Inspector {
    // Shortest video we accept (seconds): only skips blank placeholders and one-frame
    // decorations. Ads are handled by the ad blocker, ad-container detection, request order
    // and the page's own declared length — not by a length floor, so short real clips play.
    const val MIN_DURATION = 5.0

    @Suppress("UNUSED_PARAMETER")
    fun minDuration(c: Candidate) = MIN_DURATION

    private val IC = RegexOption.IGNORE_CASE

    private fun attr(tag: String, name: String): String? {
        val m = Regex("\\b$name\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", IC).find(tag) ?: return null
        val g = m.groupValues
        return decodeEntities(g[2].ifEmpty { g[3] }.ifEmpty { g[4] })
    }

    private class Tag(val tag: String, val attrs: String, val inner: String, val index: Int)

    // Elements whose class/id marks them as an ad slot (same idea as the live-page check).
    private val AD_BOX_ATTR = Regex("\\b(?:class|id)\\s*=\\s*[\"'][^\"']*(?:ad-container|ima-|preroll|advert|ad-slot|adslot|ad-player|adplayer|\\bvast\\b|vpaid|sponsor)[^\"']*[\"']|\\bdata-ad(?:-slot)?\\b|\\bid\\s*=\\s*[\"']google_ads", IC)
    private val BOX_OPEN = Regex("<(div|section|aside|figure|span|ins)\\b([^>]{0,800})>")

    /** Is [idx] (a <video> tag) inside a still-open ad-slot element? */
    private fun insideAdBox(lower: String, idx: Int): Boolean {
        val before = lower.substring(maxOf(0, idx - 4000), idx)
        var lastTag: String? = null
        var lastAt = 0
        for (m in BOX_OPEN.findAll(before)) if (AD_BOX_ATTR.containsMatchIn(m.groupValues[2])) { lastTag = m.groupValues[1]; lastAt = m.range.last + 1 }
        val tag = lastTag ?: return false
        // Walk the same-name tags after it in order: once the ad element's own close tag is
        // reached (nesting depth back to 0), the video is outside it.
        var depth = 1
        for (m in Regex("<(/?)$tag\\b").findAll(before.substring(lastAt))) {
            depth += if (m.groupValues[1].isNotEmpty()) -1 else 1
            if (depth == 0) return false
        }
        return true
    }

    /**
     * Linear-time tag scanner. Regexes like <video[^>]*>([\s\S]*?)</video> go quadratic on
     * pages with many unclosed tags, so attributes are length-bounded and the next closing
     * tag is looked up once and reused.
     */
    private fun scanTags(html: String, lower: String, tag: String, maxInner: Int = 0, limit: Int = 2000): List<Tag> {
        val out = ArrayList<Tag>()
        val open = Regex("<$tag\\b([^>]{0,4000})>", IC)
        val closeTag = "</$tag"
        var close = -2
        var m = open.find(html)
        while (m != null && out.size < limit) {
            var inner = ""
            if (maxInner > 0) {
                val from = m.range.last + 1
                if (close != -1 && close < from) close = lower.indexOf(closeTag, from)
                if (close >= 0 && close - from <= maxInner) inner = html.substring(from, close)
            }
            out += Tag(m.value, m.groupValues[1], inner, m.range.first)
            m = m.next()
        }
        return out
    }

    /** Remove <script>/<style>/<noscript> blocks in one pass. */
    private fun stripBlocks(html: String, lower: String): String {
        val sb = StringBuilder()
        val re = Regex("<(script|style|noscript)\\b", IC)
        val nextClose = HashMap<String, Int>()
        var pos = 0
        var m = re.find(html)
        while (m != null) {
            if (m.range.first >= pos) {
                val name = m.groupValues[1].lowercase()
                sb.append(html, pos, m.range.first).append(' ')
                var c = nextClose[name]
                if (c == null || (c != -1 && c < m.range.first)) { c = lower.indexOf("</$name", m.range.first); nextClose[name] = c }
                if (c == -1) { pos = html.length; break }
                pos = c + name.length + 2
                if (pos >= html.length) break
                m = re.find(html, pos)
            } else m = m.next()
        }
        if (pos < html.length) sb.append(html, pos, html.length)
        return sb.toString()
    }

    private val SCRIPT_MEDIA = Regex("https?://[^\"'\\s<>()\\\\]+?\\.(?:m3u8|mp4)\\b[^\"'\\s<>()\\\\]*", IC)
    private val PREVIEWISH = Regex("/(?:previews?|thumbs?|thumbnails?|trailers?|samples?|teasers?|videopreview)[/._-]", IC)

    /**
     * Video links that a page keeps inside its scripts (players set up by code: XVideos' setVideoHLS,
     * Pornhub's mediaDefinitions, xHamster's initials, SpankBang's stream data, KVS flashvars…).
     * Escaped slashes are undone first; previews/thumbnails/trailers are skipped.
     */
    fun scriptMediaUrls(js: String): List<String> {
        val text = js.take(1_000_000).replace("\\/", "/").replace(Regex("\\\\u0026", IC), "&").replace(Regex("\\\\u002f", IC), "/")
        return SCRIPT_MEDIA.findAll(text).map { it.value }.filter { !PREVIEWISH.containsMatchIn(it) }.distinct().take(20).toList()
    }

    private fun px(v: String?): Double? =
        if (v != null && Regex("^\\s*\\d+(\\.\\d+)?(px)?\\s*$", IC).matches(v)) v.trim().removeSuffix("px").toDoubleOrNull()?.takeIf { it > 0 } else null

    fun parseStaticHtml(rawHtml: String, baseUrl: String): Probe {
        val html = if (rawHtml.length > 3_000_000) rawHtml.substring(0, 3_000_000) else rawHtml
        val lower = html.lowercase()
        var title = ""
        val ti = lower.indexOf("<title")
        if (ti >= 0) {
            val start = lower.indexOf('>', ti) + 1
            val end = lower.indexOf("</title", start)
            if (start > 0 && end > start && end - start < 5000) title = stripTags(html.substring(start, end))
        }
        val metas = LinkedHashMap<String, String>()
        for (t in scanTags(html, lower, "meta")) {
            val key = (attr(t.tag, "property") ?: attr(t.tag, "name") ?: attr(t.tag, "itemprop") ?: "").lowercase()
            val v = attr(t.tag, "content")
            if (key.isNotEmpty() && !v.isNullOrEmpty() && key !in metas) metas[key] = v
        }
        val scripts = scanTags(html, lower, "script", 2_000_000)
        val scriptMedia = scripts.filter { it.inner.isNotEmpty() && !Regex("\\bsrc\\s*=", IC).containsMatchIn(it.attrs) && !Regex("ld\\+json", IC).containsMatchIn(it.attrs) }
            .flatMap { scriptMediaUrls(it.inner) }.distinct().take(20)
        val ld = scripts
            .filter { Regex("type\\s*=\\s*[\"']?application/ld\\+json", IC).containsMatchIn(it.attrs) && it.inner.isNotEmpty() }
            .take(30).map { it.inner }
        val videos = scanTags(html, lower, "video", 20_000, 300).map { t ->
            val sources = scanTags(t.inner, t.inner.lowercase(), "source").mapNotNull { s ->
                absolute(attr(s.tag, "src"), baseUrl)?.let { it to (attr(s.tag, "type") ?: "") }
            }
            ProbeVideo(absolute(attr(t.attrs, "src"), baseUrl), sources, absolute(attr(t.attrs, "poster"), baseUrl), null, px(attr(t.attrs, "width")), px(attr(t.attrs, "height")), insideAdBox(lower, t.index))
        }
        val iframes = scanTags(html, lower, "iframe", 0, 300).mapNotNull { absolute(attr(it.tag, "src") ?: attr(it.tag, "data-src"), baseUrl) }
        val bodyAt = lower.indexOf("<body")
        val body = (if (bodyAt >= 0) html.substring(bodyAt) else html).let { if (it.length > 600_000) it.substring(0, 600_000) else it }
        val text = stripTags(stripBlocks(body, body.lowercase())).take(3000)
        return Probe(baseUrl, title, metas, ld, videos, iframes, text, scriptMedia = scriptMedia)
    }

    /** Parse the JSON produced by [WebInspector.PROBE_JS] in a live page. */
    fun parseProbeJson(json: String): Probe? = if (json.length > 8_000_000) null else try {
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
                    v.optBoolean("ad", false),
                )
            },
            iframes = o.optJSONArray("iframes").strings(),
            text = o.optString("text"),
            live = true,
        )
    } catch (e: Throwable) { null }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }

    // ------------------------------------------------------------------ candidates

    private fun flattenLd(node: Any?, acc: MutableList<JSONObject>, depth: Int = 0) {
        // Hostile or broken pages can nest data thousands of levels deep.
        if (depth > 40 || acc.size > 50) return
        when (node) {
            is JSONArray -> for (i in 0 until minOf(node.length(), 500)) flattenLd(node.opt(i), acc, depth + 1)
            is JSONObject -> {
                val t = node.opt("@type").let { if (it is JSONArray) it.strings().joinToString(",") else it?.toString() ?: "" }
                if (Regex("VideoObject|Clip|Movie|Episode", IC).containsMatchIn(t) && (node.has("contentUrl") || node.has("embedUrl"))) acc += node
                for (k in node.keys().asSequence().take(200)) if (k != "@context") node.opt(k).let { if (it is JSONObject || it is JSONArray) flattenLd(it, acc, depth + 1) }
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
                                 duration: Double? = null, tooSmall: Boolean = false, ad: Boolean = false): Candidate? {
        val url = absolute(src, base)?.let(::withSound) ?: return null
        if (url.startsWith("blob:") || url.startsWith("data:") || isAdUrl(url)) return null
        embedFromUrl(url)?.let { return Candidate("embed", url, origin, referer, it.provider, it.id, type, title, description, thumbnail, duration) }
        val kind = mediaKindFromUrl(url, type)
        if (kind == "file" || kind == "hls") return Candidate(kind, url, origin, referer, null, null, type, title, description, thumbnail, duration, tooSmall = tooSmall, ad = ad)
        return null
    }

    fun candidatesFromProbe(p: Probe): List<Candidate> {
        val out = mutableListOf<Candidate>()
        val base = p.url
        for (block in p.ld.take(30)) {
            if (block.length > 2_000_000) continue
            // Throwable, not Exception: a deeply nested document overflows the parser's stack.
            val data = try { JSONTokener(block.trim()).nextValue() } catch (e: Throwable) { continue }
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
        val ogDur = parseDuration(m["video:duration"] ?: m["og:video:duration"] ?: m["duration"])
        for (k in listOf("og:video:secure_url", "og:video:url", "og:video", "twitter:player:stream", "twitter:player")) {
            // Each type label describes only its own address (twitter:player is a web page, not a file).
            val type = when { k.startsWith("og:") -> m["og:video:type"]; k == "twitter:player:stream" -> m["twitter:player:stream:content_type"]; else -> null } ?: ""
            candidateFromUrl(m[k], base, "og", base, type, duration = ogDur)?.let { out += it }
        }
        for (v in p.videos) {
            // Only sizes we actually know count: a missing height is unknown, not zero.
            val small = (v.w != null && v.w > 0 && v.w < 200) || (v.h != null && v.h > 0 && v.h < 110)
            for ((src, type) in listOf((v.src ?: "") to "") + v.sources) {
                candidateFromUrl(src, base, if (p.live) "live" else "dom", base, type, thumbnail = absolute(v.poster, base), duration = v.duration, tooSmall = small, ad = v.ad)?.let { out += it }
            }
        }
        // Video links set up by the page's scripts
        for (src in p.scriptMedia) candidateFromUrl(src, base, "script", base)?.let { out += it }
        for (src in p.iframes) embedFromUrl(src)?.let { out += Candidate("embed", src, "iframe", base, it.provider, it.id) }
        return out
    }

    private fun scoreCandidate(c: Candidate): Int {
        if (c.duration != null && c.duration < minDuration(c)) return -1
        if (c.tooSmall || c.ad) return -1
        var s = when (c.origin) { "page" -> 60; "jsonld" -> 50; "og" -> 45; "dom", "live" -> 40; "script" -> 38; "network" -> 35; "iframe" -> 30; else -> 20 }
        // Players load the ad first and the real video after it: later network requests win ties.
        if (c.origin == "network" && c.seq > 0) s += minOf(c.seq, 4)
        if (c.kind == "hls") s += 4
        if (c.size > 2_000_000) s += 6
        if (c.kind == "file" && c.size in 1..299_999) s -= 25
        if ((c.duration ?: 0.0) >= 60) s += 5
        return s
    }

    /**
     * Pick the best distinct media on a page and attach page-level metadata. Candidates whose
     * length is unknown get it read from the file itself ([prober]), so a short pre-roll ad
     * can't pass for the real video. Blocking when probing: call off the main thread.
     */
    fun finalize(hit: Hit, main: Probe, cands: List<Candidate>, prober: ((Candidate) -> Double?)? = MediaProbe::probeDuration): List<Video> {
        val m = main.metas
        val pageTitle = m["og:title"] ?: m["twitter:title"] ?: main.title.ifEmpty { hit.title }
        val pageDesc = (m["og:description"] ?: m["description"] ?: m["twitter:description"] ?: hit.snippet).take(400)
        val pageThumb = absolute(m["og:image"] ?: m["twitter:image"] ?: m["thumbnailurl"], main.url)
        val siteName = m["og:site_name"] ?: domainOf(main.url.ifEmpty { hit.url })

        val byKey = LinkedHashMap<String, Candidate>()
        for (c in cands) {
            if (c.provider == "youtube" || isBlockedSite(c.src)) continue // e.g. a YouTube/TikTok player embedded in an article
            val key = if (c.kind == "embed") "${c.provider}:${c.id}" else c.src.substringBefore('?').substringBefore('#')
            val prev = byKey[key]
            // Merge duplicates, keeping the most trustworthy origin and any metadata either has.
            byKey[key] = if (prev == null) c else (if (scoreCandidate(c) > scoreCandidate(prev)) c else prev).copy(
                title = prev.title.ifEmpty { c.title }, thumbnail = prev.thumbnail ?: c.thumbnail, duration = prev.duration ?: c.duration,
                ad = prev.ad || c.ad, seq = maxOf(prev.seq, c.seq),
            )
        }
        // How long the page itself says its video is (JSON-LD / og:video:duration).
        val declared = (byKey.values.filter { (it.origin == "jsonld" || it.origin == "og") && it.duration != null }.mapNotNull { it.duration } +
            listOfNotNull(parseDuration(m["og:video:duration"] ?: m["video:duration"]))).maxOrNull() ?: 0.0

        val ranked = byKey.values.map { it to scoreCandidate(it) }.filter { it.second >= 0 }.sortedByDescending { it.second }.map { it.first }
        var probes = 0
        fun acceptable(c0: Candidate): Candidate? {
            if (c0.kind == "embed") return c0
            var c = c0
            if (c.duration == null && prober != null && probes < 6) { probes++; c = c.copy(duration = prober(c)) }
            val d = c.duration
            if (d != null && d < minDuration(c)) return null                     // too short: ad / teaser
            if (declared >= 60 && d != null && d < declared * 0.5) return null   // page says its video is much longer
            return c
        }
        // Several distinct JSON-LD videos = a page that lists many videos; otherwise one per page.
        val multi = ranked.filter { it.origin == "jsonld" && it.title.isNotEmpty() }
        val want = if (multi.size > 1) 3 else 1
        val chosen = mutableListOf<Candidate>()
        for (c in (if (multi.size > 1) multi else ranked).take(8)) {
            acceptable(c)?.let { chosen += it }
            if (chosen.size >= want) break
        }
        return chosen.map { c ->
            Video(
                kind = c.kind, src = c.src, provider = c.provider, id = c.id,
                referer = c.referer.ifEmpty { hit.url },
                title = c.title.ifEmpty { pageTitle }, description = c.description.ifEmpty { pageDesc },
                thumbnail = c.thumbnail ?: pageThumb,
                duration = c.duration ?: hit.duration, siteName = siteName,
                // Keywords / tag metadata count as page text (relevance, and the adult-site safety check).
                pageUrl = hit.url, pageTitle = pageTitle, via = c.origin,
                pageText = listOfNotNull(m["keywords"], m["article:tag"], m["video:tag"], main.text).joinToString(" ").take(4000),
                keywords = listOfNotNull(m["keywords"], m["article:tag"], m["video:tag"]).joinToString(" ").take(1000),
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
