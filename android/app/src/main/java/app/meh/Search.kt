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

    /** An adult video site: its search page is read for links in its video-page address format. */
    fun site(key: String, query: String, page: Int): List<Hit> {
        if (Adult.depictsMinors(query)) return emptyList() // never search adult sites for that
        val cfg = SITES.getValue(key)
        return fetchHits(cfg.search(query, page)) { html -> parseSiteLinks(key, html) }
    }

    fun searchWeb(query: String, page: Int, includeRedgifs: Boolean = false, onEngine: (String, Int) -> Unit = { _, _ -> }): List<Hit> {
        val adult: List<Pair<String, (String, Int) -> List<Hit>>> = SITES.map { (key, cfg) -> cfg.label to { q: String, p: Int -> site(key, q, p) } } +
            listOf("RedGIFs" to ::redgifs, "Rule34Video" to ::rule34video, "Rule34 paheal" to ::paheal, "Rule34.xxx" to ::rule34xxx)
        val engines: List<Pair<String, (String, Int) -> List<Hit>>> = listOf("DuckDuckGo" to ::duckduckgo) + (if (includeRedgifs) adult else emptyList())
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
        // ---------- adult video sites (opt-in, 18+) ----------
        // Each site's search page is read for links in that site's video-page address format; titles
        // come from the link (title attribute, image alt, text) or else the address's slug. The video
        // pages go through normal page inspection. Mirrors SITES in lib/search.js.
        class Site(val label: String, val base: String, val search: (String, Int) -> String, val video: Regex)

        private fun plus(q: String) = java.net.URLEncoder.encode(q.trim(), "UTF-8")
        private fun dashes(q: String) = q.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        private val IC = RegexOption.IGNORE_CASE

        val SITES: Map<String, Site> = linkedMapOf(
            "xvideos" to Site("XVideos", "https://www.xvideos.com", { q, p -> "https://www.xvideos.com/?k=${plus(q)}" + if (p > 0) "&p=$p" else "" }, Regex("^/video[.\\d][\\w.]*/[^/?#]+/?$", IC)),
            "xnxx" to Site("XNXX", "https://www.xnxx.com", { q, p -> "https://www.xnxx.com/search/${plus(q)}" + if (p > 0) "/$p" else "" }, Regex("^/video-[a-z0-9]+/[^/?#]+/?$", IC)),
            "xhamster" to Site("xHamster", "https://xhamster.com", { q, p -> "https://xhamster.com/search/${java.net.URLEncoder.encode(q.trim(), "UTF-8").replace("+", "%20")}?page=${p + 1}" }, Regex("^/videos/[\\w-]+-[a-z0-9]+/?$", IC)),
            "eporner" to Site("Eporner", "https://www.eporner.com", { q, p -> "https://www.eporner.com/search/${dashes(q)}/${p + 1}/" }, Regex("^/(?:video-\\w+|hd-porn/\\w+)/[^/?#]+/?$", IC)),
            "spankbang" to Site("SpankBang", "https://spankbang.com", { q, p -> "https://spankbang.com/s/${plus(q)}/${p + 1}/" }, Regex("^/[a-z0-9]+/video/[^/?#]+/?$", IC)),
            "porntrex" to Site("PornTrex", "https://www.porntrex.com", { q, p -> "https://www.porntrex.com/search/${dashes(q)}/" + if (p > 0) "${p + 1}/" else "" }, Regex("^/video/\\d+/[^/?#]+/?$", IC)),
            "hqporner" to Site("HQporner", "https://hqporner.com", { q, p -> "https://hqporner.com/?q=${plus(q)}" + if (p > 0) "&p=${p + 1}" else "" }, Regex("^/hdporn/\\d+-[^/?#]+\\.html$", IC)),
            "youjizz" to Site("YouJizz", "https://www.youjizz.com", { q, p -> "https://www.youjizz.com/search/${dashes(q)}-${p + 1}.html" }, Regex("^/videos/[^/?#]+-\\d+\\.html$", IC)),
            "fourtube" to Site("4tube", "https://www.4tube.com", { q, p -> "https://www.4tube.com/search?q=${plus(q)}&p=${p + 1}" }, Regex("^/videos/\\d+/[^/?#]+/?$", IC)),
            "tnaflix" to Site("TNAFlix", "https://www.tnaflix.com", { q, p -> "https://www.tnaflix.com/search?what=${plus(q)}&page=${p + 1}" }, Regex("^/[\\w-]+/[^/?#]+/video\\d+/?$", IC)),
            "gelbooru" to Site("Gelbooru", "https://gelbooru.com", { q, p -> "https://gelbooru.com/index.php?page=post&s=list&tags=${plus(booruTags(q, "video"))}&pid=${p * 42}" }, Regex("^/?index\\.php\\?page=post&s=view&id=\\d+$", IC)),
            "rule34us" to Site("Rule34.us", "https://rule34.us", { q, p -> "https://rule34.us/index.php?r=posts/index&q=${plus(booruTags(q, "video"))}&page=$p" }, Regex("^/?index\\.php\\?r=posts/view&id=\\d+$", IC)),
        )

        private val ANCHOR = Regex("<a\\b([^>]{0,2000})>([\\s\\S]{0,3000}?)</a>", IC)

        /** Video-page links on a site's search page, in page order, with the best title seen for each. */
        fun parseSiteLinks(key: String, html: String): List<Hit> {
            val cfg = SITES.getValue(key)
            val host = URI(cfg.base).host.removePrefix("www.")
            val byUrl = LinkedHashMap<String, String>()
            for (m in ANCHOR.findAll(html)) {
                val href = Regex("\\bhref\\s*=\\s*[\"']([^\"']+)[\"']", IC).find(m.groupValues[1])?.groupValues?.get(1) ?: continue
                val u = try { URI(cfg.base + "/").resolve(decodeEntities(href).replace(" ", "%20")) } catch (e: Exception) { continue }
                if ((u.host ?: "").removePrefix("www.").removePrefix("m.") != host) continue
                val rel = (u.rawPath ?: "") + (u.rawQuery?.let { "?$it" } ?: "")
                if (!cfg.video.matches(rel)) continue
                val url = cfg.base + rel
                val title = (Regex("\\btitle\\s*=\\s*[\"']([^\"']{3,300})[\"']", IC).find(m.groupValues[1])?.groupValues?.get(1)?.let(::decodeEntities))
                    ?: Regex("<img[^>]+alt\\s*=\\s*[\"']([^\"']{3,300})[\"']", IC).find(m.groupValues[2])?.groupValues?.get(1)?.let(::decodeEntities)
                    ?: stripTags(m.groupValues[2]).take(200)
                val usable = Regex("\\w{3}").containsMatchIn(title)
                val prev = byUrl[url]
                if (prev == null) byUrl[url] = if (usable) title else ""
                else if (usable && title.length > prev.length) byUrl[url] = title
            }
            return byUrl.map { (url, t) ->
                // No usable title on the search page: words from the address (".../big-red-dragon").
                val title = t.ifEmpty { decodeUrl(URI(url).rawPath.split('/').lastOrNull { it.isNotEmpty() } ?: "").replace(Regex("[-_]+"), " ").replace(Regex("\\.\\w+$"), "") }
                Hit(url, title)
            }.filter { !Adult.depictsMinors("${it.title} ${it.url}") }.take(40)
        }

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
