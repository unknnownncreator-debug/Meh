package app.meh

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * One cookie store for everything the app does with a site: plain page requests (OkHttp), the
 * video player (ExoPlayer reads it per video) and the hidden / Chrome-player WebViews. It *is*
 * the WebView cookie store, so a session cookie a site sets while its page is read is sent when
 * its video is played. The "are you 18?" / consent cookies ([Http.cookieFor]) are added for any
 * site that hasn't set its own.
 *
 * Outside Android (unit tests) the WebView store doesn't exist, so an in-memory store stands in.
 */
object SharedCookies : CookieJar {
    private val memory = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>() // host -> name -> value
    @Volatile private var webViewStore = true

    private fun manager(): android.webkit.CookieManager? =
        if (!webViewStore) null else try { android.webkit.CookieManager.getInstance() } catch (e: Throwable) { webViewStore = false; null }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val cm = manager()
        for (c in cookies) {
            if (cm != null) {
                try { cm.setCookie(url.toString(), c.toString()) } catch (e: Throwable) { webViewStore = false }
            }
            if (cm == null || !webViewStore) {
                val jar = memory.getOrPut(c.domain) { ConcurrentHashMap() }
                if (c.expiresAt < System.currentTimeMillis()) jar.remove(c.name) else jar[c.name] = c.value
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val pairs = LinkedHashMap<String, String>()
        val cm = manager()
        val stored = if (cm != null) try { cm.getCookie(url.toString()) } catch (e: Throwable) { webViewStore = false; null } else null
        if (stored != null) parse(stored).forEach { (k, v) -> pairs[k] = v }
        else for ((domain, jar) in memory) if (url.host == domain || url.host.endsWith(".$domain")) pairs.putAll(jar)
        // Consent / age-confirmation defaults the site hasn't overridden.
        parse(Http.cookieFor(url.toString())).forEach { (k, v) -> pairs.putIfAbsent(k, v) }
        return pairs.mapNotNull { (k, v) -> try { Cookie.Builder().name(k).value(v).domain(url.host).build() } catch (e: Exception) { null } }
    }

    /** "Cookie:" header value for [url] — used by the video player. */
    fun header(url: String): String? =
        url.toHttpUrlOrNull()?.let { u -> loadForRequest(u).joinToString("; ") { "${it.name}=${it.value}" }.ifEmpty { null } }

    private fun parse(header: String): List<Pair<String, String>> =
        header.split(';').mapNotNull { part -> part.trim().split('=', limit = 2).takeIf { it.size == 2 && it[0].isNotEmpty() }?.let { it[0] to it[1] } }

    /** Tests only: forget the in-memory store. */
    fun clearMemory() = memory.clear()
}
