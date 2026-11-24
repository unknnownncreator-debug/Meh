package app.meh

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** One web search result. */
data class Hit(
    val url: String,
    val title: String = "",
    val snippet: String = "",
    val duration: Double? = null,
    val engine: String = "",
    val rank: Int = 0,
)

/** A playable video found on a page. */
data class Video(
    val kind: String,              // "file", "hls" or "embed"
    val src: String,
    val provider: String? = null,  // youtube / vimeo / dailymotion for embeds
    val id: String? = null,
    val referer: String = "",
    val title: String = "",
    val description: String = "",
    val thumbnail: String? = null,
    val duration: Double? = null,
    val siteName: String = "",
    val pageUrl: String = "",
    val pageTitle: String = "",
    val pageText: String = "",
    /** The page's keywords / tag metadata (counts for strict matching). */
    val keywords: String = "",
    val via: String = "",
    var score: Double = 0.0,
    /** Browser identity the video was found with; some sites tie their video links to it. */
    val userAgent: String = DESKTOP_UA,
    /** Already re-fetched once after a failed play. */
    val refreshed: Boolean = false,
    /** How it's played: "exo" (the app's player), "web" (Chrome player) or "page" (the site's own page). */
    val mode: String = "exo",
) {
    /** A found video we refuse to play (YouTube, TikTok). */
    val blocked: Boolean get() = provider == "youtube" || isBlockedSite(src) || isBlockedSite(pageUrl)

    val key: String get() = if (kind == "embed") "$provider:$id" else src.substringBefore('?').substringBefore('#')
    val site: String get() = domainOf(pageUrl.ifEmpty { src })
    val kindLabel: String get() = when (kind) {
        "embed" -> provider ?: "embed"
        "hls" -> "HLS stream"
        else -> Regex("\\.(\\w{2,4})$").find(src.substringBefore('?'))?.groupValues?.get(1)?.uppercase() ?: "video file"
    }
}

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun pageRequest(url: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", DESKTOP_UA)
        .header("Accept", "text/html,application/xhtml+xml,*/*")
        .header("Accept-Language", "en-US,en;q=0.9")
        // Skip cookie-consent interstitials (YouTube / Google properties).
        .header("Cookie", "CONSENT=YES+1; SOCS=CAI")
        .build()

    /**
     * GET a page as text (at most [limit] bytes), or "" if blocked / failed.
     * Only 200 counts: DuckDuckGo answers bots with "202 Accepted" and a challenge page.
     */
    fun getHtml(url: String, limit: Long = 4_000_000): String = try {
        client.newCall(pageRequest(url)).execute().use { res ->
            val src = res.body?.source()
            if (res.code != 200 || src == null) "" else {
                src.request(limit)
                src.buffer.readUtf8(minOf(src.buffer.size, limit))
            }
        }
    } catch (e: Exception) { "" }

    /** GET with extra headers; returns (status, body) or null if unreachable. Blocking. */
    fun get(url: String, headers: Map<String, String>): Pair<Int, String>? = try {
        val req = Request.Builder().url(url).header("User-Agent", DESKTOP_UA).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(req).execute().use { res ->
            val src = res.body.source()
            src.request(4_000_000)
            res.code to src.buffer.readUtf8(minOf(src.buffer.size, 4_000_000))
        }
    } catch (e: Exception) { null }
}
