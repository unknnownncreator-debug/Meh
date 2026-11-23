package app.meh

import android.util.Base64
import java.net.URI
import java.net.URLEncoder

/**
 * Web search by reading public search-result pages (no API keys).
 * [fetch] gets a page's HTML; the app passes one that falls back to a real WebView
 * when a plain request is blocked.
 */
class Search(private val plain: (String) -> String, private val browser: ((String) -> String)? = null) {

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
    fun searchWeb(query: String, page: Int, onEngine: (String, Int) -> Unit = { _, _ -> }): List<Hit> {
        val engines: List<Pair<String, (String, Int) -> List<Hit>>> = listOf(
            "Google" to ::google, "Bing" to ::bing, "Bing Videos" to ::bingVideos, "DuckDuckGo" to ::duckduckgo,
        )
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
                if (isYouTube(h.url)) continue // YouTube is excluded
                if (seen.add(normalizeKey(h.url))) merged += h
            }
        }
        return merged
    }

    companion object {
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
