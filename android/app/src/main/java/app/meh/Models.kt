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
    /** Set when the search page itself already tells us how to play it (YouTube results). */
    val media: Video? = null,
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
    val via: String = "",
    var score: Double = 0.0,
) {
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

    /** GET a page as text, or "" if blocked / failed / tiny. */
    fun getHtml(url: String): String = try {
        client.newCall(pageRequest(url)).execute().use { res ->
            if (!res.isSuccessful) "" else (res.body?.string() ?: "").takeIf { it.length > 2000 } ?: ""
        }
    } catch (e: Exception) { "" }
}
