package app.meh

import android.util.Base64
import java.net.URI
import java.net.URLEncoder

/**
 * Web search by reading public search-result pages (no API keys).
 * [fetch] gets a page's HTML; the app passes one that falls back to a real WebView
 * when a plain request is blocked.
 */
class Search(
    private val plain: (String) -> String,
    private val browser: ((String) -> String)? = null,
    /** GET with headers → (status, body); used for RedGIFs' data service. */
    private val getJson: (String, Map<String, String>) -> Pair<Int, String>? = Http::get,
) {

    /**
     * Fetch a results page and parse it. If the plain request was blocked, challenged or
     * parses to nothing (bot check, JS-only page), load it in a real WebView and parse again.
     */
    private fun fetchHits(url: String, parse: (String) -> List<Hit>): List<Hit> {
        val first = try { parse(plain(url)) } catch (e: Exception) { emptyList() }
        if (first.isNotEmpty() || browser == null) return first
        return try { parse(browser.invoke(url)) } catch (e: Exception) { emptyList() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun duckduckgo(query: String, page: Int): List<Hit> {
        val url = "https://html.duckduckgo.com/html/?q=${enc("$query video")}" +
            if (page > 0) "&s=${page * 30}&dc=${page * 30 + 1}" else ""
        return fetchHits(url) { html ->
        val hits = mutableListOf<Hit>()
        for (b in html.split(Regex("<div[^>]+class=\"[^\"]*\\bresult\\b[^\"]*\"", RegexOption.IGNORE_CASE)).drop(1)) {
            if ("result--ad" in b.take(200)) continue
            val a = Regex("<a[^>]+class=\"[^\"]*result__a[^\"]*\"[^>]+href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE).find(b)
                ?: Regex("<a[^>]+href=\"([^\"]+)\"[^>]+class=\"[^\"]*result__a[^\"]*\"[^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE).find(b)
                ?: continue
            val target = cleanHitUrl(a.groupValues[1]) ?: continue
            val sn = Regex("class=\"[^\"]*result__snippet[^\"]*\"[^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE).find(b)
            hits += Hit(target, stripTags(a.groupValues[2]), sn?.let { stripTags(it.groupValues[1]) } ?: "")
        }
        hits
        }
    }

    fun bing(query: String, page: Int): List<Hit> {
        return fetchHits("https://www.bing.com/search?q=${enc("$query video")}&count=30&first=${page * 30 + 1}") { html ->
        val hits = mutableListOf<Hit>()
        for (b in html.split(Regex("<li[^>]+class=\"b_algo\"", RegexOption.IGNORE_CASE)).drop(1)) {
            val a = Regex("<h2[^>]*>\\s*<a[^>]+href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE).find(b) ?: continue
            val target = cleanHitUrl(a.groupValues[1]) ?: continue
            val sn = Regex("<p[^>]*>([\\s\\S]*?)</p>", RegexOption.IGNORE_CASE).find(b)
            hits += Hit(target, stripTags(a.groupValues[2]), sn?.let { stripTags(it.groupValues[1]) } ?: "")
        }
        hits
        }
    }

    /** Bing Videos indexes video pages from all over the web. */
    fun bingVideos(query: String, page: Int): List<Hit> {
        return fetchHits("https://www.bing.com/videos/search?q=${enc(query)}&first=${page * 35 + 1}") { raw ->
        val html = decodeEntities(raw).replace(Regex("\\\\u002f", RegexOption.IGNORE_CASE), "/").replace("\\/", "/")
        val hits = mutableListOf<Hit>()
        val seen = HashSet<String>()
        for (m in Regex("\"murl\"\\s*:\\s*\"([^\"]+)\"").findAll(html)) {
            val target = cleanHitUrl(m.groupValues[1]) ?: continue
            if (!seen.add(target)) continue
            val ctx = html.substring(maxOf(0, m.range.first - 1500), minOf(html.length, m.range.last + 1500))
            val vt = Regex("\"vt\"\\s*:\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"").find(ctx)?.groupValues?.get(1)
            val du = Regex("\"du\"\\s*:\\s*\"([\\d:]+)\"").find(ctx)?.groupValues?.get(1)
            hits += Hit(target, vt?.let { stripTags(it) } ?: "", "", parseDuration(du))
        }
        hits
        }
    }

    /**
     * Google's Videos tab: video pages from all over the web. Google only serves results
     * to browsers that run JavaScript, so the plain request parses to nothing and
     * [fetchHits] retries in the WebView.
     */
    fun google(query: String, page: Int): List<Hit> =
        fetchHits("https://www.google.com/search?q=${enc(query)}&tbm=vid&hl=en&num=20" + if (page > 0) "&start=${page * 20}" else "", ::parseGoogle)

    /** Run every engine in parallel and merge round-robin so no engine dominates. */
    // ---------- RedGIFs (opt-in, 18+) ----------
    // RedGIFs' website is a JavaScript app that loads everything from its data service with a
    // guest token every visitor gets automatically (no account or key; documented by RedGIFs at
    // github.com/Redgifs/api/wiki/Temporary-tokens). The token is tied to the caller's address and
    // browser identity, so the phone fetches and uses its own. Results are the clips' watch pages,
    // which go through normal page inspection.
    @Volatile private var redgifsToken: Pair<String, Long>? = null

    private fun token(fresh: Boolean): String? {
        val cached = redgifsToken
        if (!fresh && cached != null && System.currentTimeMillis() - cached.second < 12 * 3600_000L) return cached.first
        val (code, body) = getJson("https://api.redgifs.com/v2/auth/temporary", emptyMap()) ?: return null
        if (code != 200) return null
        val t = try { org.json.JSONObject(body).optString("token") } catch (e: Exception) { "" }
        redgifsToken = if (t.isNotEmpty()) t to System.currentTimeMillis() else null
        return t.ifEmpty { null }
    }

    fun redgifs(query: String, page: Int): List<Hit> {
        if (Adult.depictsMinors(query)) return emptyList() // never search adult sites for that
        val url = "https://api.redgifs.com/v2/gifs/search?search_text=${enc(query)}&order=trending&count=40&page=${page + 1}"
        for (attempt in 0..1) {
            val t = token(fresh = attempt > 0) ?: return emptyList() // second try: expired or refused token
            val (code, body) = getJson(url, mapOf("Authorization" to "Bearer $t")) ?: return emptyList()
            if (code == 401 || code == 403) continue
            return if (code == 200) parseRedgifs(body).filter { !Adult.depictsMinors("${it.title} ${it.snippet}") } else emptyList()
        }
        return emptyList()
    }

    // ---------- rule34 sites (opt-in, 18+; content depicting minors always excluded) ----------

    /** rule34video.com: video-only site; search page lists each video's page, title and length. */
    fun rule34video(query: String, page: Int): List<Hit> {
        if (page > 0 || Adult.depictsMinors(query)) return emptyList()
        val slug = query.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        if (slug.isEmpty()) return emptyList()
        return fetchHits("https://rule34video.com/search/$slug/", ::parseRule34video).filter { !Adult.depictsMinors("${it.title} ${it.url}") }
    }

    /** rule34.paheal.net: each result says its file type and tags, so non-videos are skipped up front. */
    fun paheal(query: String, page: Int): List<Hit> {
        if (Adult.depictsMinors(query)) return emptyList()
        return fetchHits("https://rule34.paheal.net/post/list/${booruTags(query, "animated").replace(" ", "%20")}/${page + 1}", ::parsePaheal)
    }

    /** rule34.xxx: behind Cloudflare, so its pages are read through the hidden browser. */
    fun rule34xxx(query: String, page: Int): List<Hit> {
        if (Adult.depictsMinors(query)) return emptyList()
        return fetchHits("https://rule34.xxx/index.php?page=post&s=list&tags=${enc(booruTags(query, "video"))}&pid=${page * 42}", ::parseRule34xxx)
    }

    fun searchWeb(query: String, page: Int, includeRedgifs: Boolean = false, onEngine: (String, Int) -> Unit = { _, _ -> }): List<Hit> {
        val engines: List<Pair<String, (String, Int) -> List<Hit>>> = listOf(
            "Google" to ::google, "Bing" to ::bing, "Bing Videos" to ::bingVideos, "DuckDuckGo" to ::duckduckgo,
        ) + (if (includeRedgifs) listOf("RedGIFs" to ::redgifs, "Rule34Video" to ::rule34video, "Rule34 paheal" to ::paheal, "Rule34.xxx" to ::rule34xxx) else emptyList())
        val results = arrayOfNulls<List<Hit>>(engines.size)
        val threads = engines.mapIndexed { i, (name, fn) ->
            Thread {
                val hits = try { fn(query, page) } catch (e: Exception) { emptyList() }
                results[i] = hits.mapIndexed { r, h -> h.copy(engine = name, rank = r) }
                onEngine(name, hits.size)
            }.apply { start() }
        }
        threads.forEach { it.join(20_000) }
        val lists = results.map { it ?: emptyList() }
        val merged = mutableListOf<Hit>()
        val seen = HashSet<String>()
        for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) {
            for (l in lists) {
                val h = l.getOrNull(i) ?: continue
                if (isBlockedSite(h.url)) continue // excluded sites (YouTube, TikTok)
                if (!includeRedgifs && Adult.isAdultSite(h.url)) continue // adult sites switched off
                if (Adult.isAdultSite(h.url) && Adult.depictsMinors("${h.title} ${h.snippet} ${h.url}")) continue // always excluded
                if (Adult.unwanted("${h.title} ${h.snippet} ${h.url}", Adult.isAdultSite(h.url)) != null) continue // music videos / male focus
                if (seen.add(normalizeKey(h.url))) merged += h
            }
        }
        return merged
    }

    companion object {
        /** Words of a free-text query as booru tags, plus the minors minus-tags. */
        fun booruTags(query: String, extra: String): String {
            val words = query.lowercase().split(Regex("\\s+")).map { it.replace(Regex("[^a-z0-9_'.:-]"), "") }.filter { it.isNotEmpty() }.take(5)
            return (listOf(extra) + words + Adult.NEGATIVE_TAGS.map { "-$it" } + Adult.UNWANTED_NEGATIVE_TAGS.map { "-$it" }).joinToString(" ")
        }

        fun parseRule34video(html: String): List<Hit> {
            val hits = mutableListOf<Hit>()
            for (b in html.split(Regex("<div[^>]+class=\"item thumb\\b", RegexOption.IGNORE_CASE)).drop(1)) {
                val url = Regex("href=\"(https://rule34video\\.com/video/\\d+/[^\"]+/)\"", RegexOption.IGNORE_CASE).find(b)?.groupValues?.get(1) ?: continue
                val title = Regex("class=\"thumb_title\"[^>]*>([\\s\\S]*?)</div>", RegexOption.IGNORE_CASE).find(b)?.groupValues?.get(1)?.let(::stripTags)?.ifEmpty { null }
                    ?: decodeEntities(Regex("<img[^>]+alt=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(b)?.groupValues?.get(1) ?: "")
                val time = Regex("class=\"time\"[^>]*>\\s*([\\d:]+)\\s*<", RegexOption.IGNORE_CASE).find(b)?.groupValues?.get(1)
                hits += Hit(url, title, "", parseDuration(time))
            }
            return hits
        }

        fun parsePaheal(html: String): List<Hit> {
            val hits = mutableListOf<Hit>()
            for (m in Regex("<div[^>]+class=['\"][^'\"]*shm-thumb[^'\"]*['\"][^>]*>[\\s\\S]{0,3000}?</div>", RegexOption.IGNORE_CASE).findAll(html)) {
                val div = m.value
                val mime = Regex("data-mime=['\"]([^'\"]+)", RegexOption.IGNORE_CASE).find(div)?.groupValues?.get(1) ?: ""
                if (!mime.lowercase().startsWith("video/")) continue
                val tags = decodeEntities(Regex("data-tags=['\"]([^'\"]*)", RegexOption.IGNORE_CASE).find(div)?.groupValues?.get(1) ?: "")
                if (Adult.depictsMinors(tags)) continue
                val href = Regex("href=['\"](/post/view/\\d+)", RegexOption.IGNORE_CASE).find(div)?.groupValues?.get(1) ?: continue
                hits += Hit("https://rule34.paheal.net$href", tags.replace('_', ' ').take(200), tags)
            }
            return hits
        }

        fun parseRule34xxx(html: String): List<Hit> {
            val hits = mutableListOf<Hit>()
            for (m in Regex("<span[^>]+class=[\"']thumb[\"'][^>]*>([\\s\\S]{0,2000}?)</span>", RegexOption.IGNORE_CASE).findAll(html)) {
                val id = Regex("[?&;]id=(\\d+)").find(m.groupValues[1])?.groupValues?.get(1) ?: continue
                val tags = decodeEntities(Regex("\\b(?:title|alt)=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(m.groupValues[1])?.groupValues?.get(1) ?: "").trim()
                if (Adult.depictsMinors(tags)) continue
                val title = tags.replace(Regex("\\b(score|rating):\\S+"), "").replace('_', ' ').trim().take(200)
                hits += Hit("https://rule34.xxx/index.php?page=post&s=view&id=$id", title, tags)
            }
            return hits
        }

        fun parseRedgifs(json: String): List<Hit> {
            val gifs = try { org.json.JSONObject(json).optJSONArray("gifs") } catch (e: Throwable) { null } ?: return emptyList()
            val hits = mutableListOf<Hit>()
            for (i in 0 until gifs.length()) {
                val g = gifs.optJSONObject(i) ?: continue
                val id = g.optString("id")
                if (!Regex("^[A-Za-z0-9]+$").matches(id) || g.optJSONObject("urls")?.optString("hd").isNullOrEmpty()) continue
                if (g.optInt("type", 1) == 2) continue // 2 = still image
                val tags = g.optJSONArray("tags")?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String } } ?: emptyList()
                val d = g.optDouble("duration", 0.0)
                hits += Hit(
                    url = "https://www.redgifs.com/watch/${id.lowercase()}",
                    title = stripTags(g.optString("description")).take(200).ifEmpty { tags.take(6).joinToString(" ") },
                    snippet = tags.joinToString(", "),
                    duration = if (d > 0) d else null,
                )
            }
            return hits
        }

        private val GOOGLE_OWN = Regex("(^|\\.)(google\\.[a-z.]+|gstatic\\.com|googleusercontent\\.com|googleadservices\\.com|googlesyndication\\.com|doubleclick\\.net)$", RegexOption.IGNORE_CASE)
        private val ANCHOR = Regex("<a\\b([^>]{0,3000})>([\\s\\S]{0,6000}?)</a>", RegexOption.IGNORE_CASE)

        /** Read Google results by page structure (links leaving Google that carry a heading or label), not class names. */
        fun parseGoogle(html: String): List<Hit> {
            if (Regex("/sorry/index|unusual traffic from your computer|id=\"captcha-form\"", RegexOption.IGNORE_CASE).containsMatchIn(html)) return emptyList()
            val hits = mutableListOf<Hit>()
            val seen = HashSet<String>()
            for (m in ANCHOR.findAll(html)) {
                if (hits.size >= 40) break
                var href = Regex("\\bhref\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(m.groupValues[1])?.groupValues?.get(1)?.let(::decodeEntities) ?: continue
                if (href.startsWith("/url?")) href = "https://www.google.com$href"
                if (!href.startsWith("http://") && !href.startsWith("https://")) continue
                val host = try { URI(href).host ?: continue } catch (e: Exception) { continue }
                val target = (if (GOOGLE_OWN.containsMatchIn(host) && "/url?" !in href) null else cleanHitUrl(href)) ?: continue
                if (target in seen) continue
                val inner = m.groupValues[2]
                val heading = Regex("<h3\\b[^>]*>([\\s\\S]*?)</h3>", RegexOption.IGNORE_CASE).find(inner)?.groupValues?.get(1)
                    ?: Regex("role=\"heading\"[^>]*>([\\s\\S]{0,600}?)</div>", RegexOption.IGNORE_CASE).find(inner)?.groupValues?.get(1)
                val aria = Regex("\\baria-label\\s*=\\s*\"([^\"]{5,300})\"", RegexOption.IGNORE_CASE).find(m.groupValues[1])?.groupValues?.get(1)
                var title = heading?.let(::stripTags) ?: aria?.let(::decodeEntities) ?: ""
                if (title.isEmpty()) stripTags(inner).let { if (it.length in 12..200) title = it }
                if (title.length < 5) continue
                val dur = Regex("\\b(\\d{1,2}:\\d{2}(?::\\d{2})?)\\b").find(stripTags(inner))?.groupValues?.get(1)
                seen += target
                hits += Hit(target, title, "", parseDuration(dur))
            }
            return hits
        }

        private val ENGINE_DOMAINS = Regex("(^|\\.)(duckduckgo\\.com|bing\\.com|bing\\.net|microsoft\\.com|live\\.com|google\\.[a-z.]+|gstatic\\.com|googleapis\\.com|w3\\.org|schema\\.org|ytimg\\.com)$", RegexOption.IGNORE_CASE)
        private val JUNK_PATH = Regex("\\.(js|css|png|jpe?g|gif|svg|ico|webp|woff2?|pdf|xml|json)$", RegexOption.IGNORE_CASE)

        /** Unwrap search-engine redirect links and drop non-content URLs. */
        fun cleanHitUrl(raw: String): String? {
            var url = decodeEntities(raw).trim()
            if (url.startsWith("//")) url = "https:$url"
            val u = try { URI(url) } catch (e: Exception) { return null }
            val host = u.host ?: return null
            val q = queryParams(u.rawQuery)
            if (host.endsWith("duckduckgo.com") && u.path == "/l/") return q["uddg"]?.let { cleanHitUrl(it) }
            // Google redirect: google.<tld>/url?q=<target> (or &url=)
            if (Regex("(^|\\.)google\\.[a-z.]+$").containsMatchIn(host) && u.path == "/url") {
                return (q["q"] ?: q["url"])?.takeIf { it.startsWith("http") }?.let { cleanHitUrl(it) }
            }
            if (host.endsWith("bing.com") && u.path.startsWith("/ck/")) {
                val e = q["u"] ?: return null
                if (!e.startsWith("a1")) return null
                val decoded = try {
                    String(Base64.decode(e.substring(2), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
                } catch (ex: Throwable) {
                    try { String(java.util.Base64.getUrlDecoder().decode(e.substring(2))) } catch (ex2: Exception) { return null }
                }
                return if (decoded.startsWith("http")) cleanHitUrl(decoded) else null
            }
            if (u.scheme != "http" && u.scheme != "https") return null
            if (ENGINE_DOMAINS.containsMatchIn(host) || JUNK_PATH.containsMatchIn(u.path ?: "")) return null
            if (host.endsWith("youtube.com") && (u.path == "/results" || u.path.startsWith("/feed"))) return null
            return url.substringBefore('#')
        }

        fun normalizeKey(url: String): String = try {
            val u = URI(url)
            val host = (u.host ?: "").removePrefix("www.").removePrefix("m.")
            val v = queryParams(u.rawQuery)["v"]
            if (host.endsWith("youtube.com") && v != null) "yt:$v" else host + (u.rawPath ?: "").trimEnd('/') + (u.rawQuery?.let { "?$it" } ?: "")
        } catch (e: Exception) { url }
    }
}
