package app.meh

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Page inspection, part 2: open the page in a hidden WebView, press play, and watch
 * every network request the page (and its iframes) makes for video files / HLS
 * playlists. Media requests are recorded and then blocked, so nothing plays out loud
 * and no data is wasted. Finally the live DOM is read with [PROBE_JS].
 */
class WebInspector(private val context: Context, private val host: ViewGroup, size: Int = 3) {

    private class Sniffed(val url: String, val kind: String?, val referer: String)

    private class Session {
        val sniffed = ConcurrentHashMap<String, Sniffed>()
    }

    private inner class Client : WebViewClient() {
        @Volatile var session: Session? = null
        /** The page's renderer crashed or was killed: this WebView must be thrown away. */
        @Volatile var dead = false

        // Without this, a crashing page (or the renderer being killed for memory) kills the whole app.
        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            dead = true
            return true
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme ?: return true
            return scheme != "http" && scheme != "https" // no app-store / intent:// jumps
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            if (isAdUrl(url)) return EMPTY()
            val s = session ?: return null
            val headers = request.requestHeaders.mapKeys { it.key.lowercase() }
            val kind = mediaKindFromUrl(url)
            val looksLikeMedia = kind != null || isSegment(url) ||
                (headers["range"] != null && !request.isForMainFrame) ||
                (headers["accept"] ?: "").startsWith("video/")
            if (!looksLikeMedia) return null
            if (!isSegment(url) && (kind == null || kind == "file" || kind == "hls")) {
                val key = url.substringBefore('?').substringBefore('#')
                s.sniffed.putIfAbsent(key, Sniffed(url, kind, headers["referer"] ?: view.url ?: url))
            }
            return EMPTY() // recorded; don't actually download/play it here
        }
    }

    private val pool = Channel<Pair<WebView, Client>>(Channel.UNLIMITED)
    private val all = mutableListOf<WebView>()

    init {
        CookieManager.getInstance().setAcceptCookie(true)
        repeat(size) { pool.trySend(create()) }
    }

    private fun create(): Pair<WebView, Client> {
        val wv = makeWebView(context)
        val client = Client()
        wv.webViewClient = client
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            // A page stuck in an endless script loop freezes the renderer (shared with the
            // video player). Kill it; onRenderProcessGone then replaces the WebViews.
            wv.setWebViewRenderProcessClient(object : android.webkit.WebViewRenderProcessClient() {
                override fun onRenderProcessUnresponsive(view: WebView, renderer: android.webkit.WebViewRenderProcess?) { renderer?.terminate() }
                override fun onRenderProcessResponsive(view: WebView, renderer: android.webkit.WebViewRenderProcess?) {}
            })
        }
        // Real on-screen size (behind the app UI) so layouts, lazy players and
        // size checks behave like a normal phone browser.
        host.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        all += wv
        return wv to client
    }

    /** Main thread: reset a WebView for the next page, or replace it if its renderer died. */
    private fun recycle(wv: WebView, client: Client): Pair<WebView, Client> {
        client.session = null
        if (!client.dead) {
            try {
                wv.stopLoading()
                wv.loadUrl("about:blank")
                return wv to client
            } catch (e: Throwable) { /* fall through and replace it */ }
        }
        all.remove(wv)
        host.removeView(wv)
        try { wv.destroy() } catch (e: Throwable) {}
        return create()
    }

    suspend fun inspect(hit: Hit, staticProbe: Probe?): List<Video> {
        val (wv, client) = pool.receive()
        val session = Session()
        val sniffed: List<Sniffed>
        var probes: List<Probe> = emptyList()
        try {
            sniffed = withContext(Dispatchers.Main) {
                client.session = session
                if (client.dead) return@withContext emptyList()
                wv.loadUrl(hit.url)
                delay(2200)
                eval(wv, PRESS_JS)
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < SNIFF_WINDOW) {
                    delay(400)
                    if (session.sniffed.isNotEmpty() && System.currentTimeMillis() - start > 1600) break
                    if (System.currentTimeMillis() - start in 1600..2000) eval(wv, PRESS_JS)
                }
                val live = eval(wv, PROBE_JS)?.let { raw ->
                    (try { JSONTokener(raw).nextValue() as? String } catch (e: Exception) { null })?.let(Inspector::parseProbeJson)
                }
                probes = listOfNotNull(live, staticProbe)
                session.sniffed.values.toList()
            }
        } finally {
            // Must run even when the search was cancelled, or the WebView never returns to the pool.
            pool.trySend(withContext(NonCancellable + Dispatchers.Main) { recycle(wv, client) })
        }
        if (probes.isEmpty()) probes = listOf(Probe(hit.url, hit.title))

        // Requests we couldn't classify by URL: ask the server what they are.
        val resolved = withContext(Dispatchers.IO) {
            sniffed.take(12).mapNotNull { s -> if (s.kind != null) s else contentKind(s.url, s.referer)?.let { Sniffed(s.url, it, s.referer) } }
        }
        val durations = HashMap<String, Double>()
        for (p in probes) for (v in p.videos) for (src in listOfNotNull(v.src) + v.sources.map { it.first }) {
            v.duration?.let { durations[src.substringBefore('?')] = it }
        }
        val cands = probes.flatMap(Inspector::candidatesFromProbe).toMutableList()
        for (s in resolved) {
            if (s.kind != "file" && s.kind != "hls") continue
            cands += Candidate(s.kind, s.url, "network", s.referer, duration = durations[s.url.substringBefore('?')])
        }
        return Inspector.finalize(hit, probes.first(), cands)
    }

    /** Load a page in a real browser and return its HTML (for search pages that block plain requests). */
    suspend fun fetchHtml(url: String): String {
        val (wv, client) = pool.receive()
        try {
            return withContext(Dispatchers.Main) {
                client.session = null
                if (client.dead) return@withContext ""
                wv.loadUrl(url)
                delay(3500)
                val raw = eval(wv, "document.documentElement.outerHTML", 5000)
                (try { JSONTokener(raw ?: "").nextValue() as? String } catch (e: Exception) { null }) ?: ""
            }
        } finally {
            pool.trySend(withContext(NonCancellable + Dispatchers.Main) { recycle(wv, client) })
        }
    }

    fun destroy() {
        all.forEach { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        all.clear()
    }

    private suspend fun eval(wv: WebView, js: String, timeout: Long = 3000): String? =
        withTimeoutOrNull(timeout) {
            suspendCancellableCoroutine { cont -> wv.evaluateJavascript(js) { if (cont.isActive) cont.resume(it) } }
        }

    private fun contentKind(url: String, referer: String): String? = try {
        val req = Request.Builder().url(url).header("User-Agent", DESKTOP_UA).header("Referer", referer).header("Range", "bytes=0-1").build()
        Http.client.newCall(req).execute().use { mediaKindFromUrl(it.request.url.toString(), it.header("Content-Type") ?: "") }
    } catch (e: Exception) { null }

    companion object {
        private const val SNIFF_WINDOW = 4500L

        private fun EMPTY() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

        @SuppressLint("SetJavaScriptEnabled")
        fun makeWebView(context: Context): WebView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.blockNetworkImage = true
            settings.loadsImagesAutomatically = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // Look like regular mobile Chrome rather than an embedded WebView.
            settings.userAgentString = WebSettings.getDefaultUserAgent(context).replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
            isFocusable = false
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }

        /** Mute everything, start every <video>, and click the obvious play buttons. */
        const val PRESS_JS = """(function(){
  try {
    document.querySelectorAll('video,audio').forEach(function(v){ v.muted = true; v.volume = 0; var p = v.play(); if (p && p.catch) p.catch(function(){}); });
    window.scrollBy(0, 500);
    var sels = ['.vjs-big-play-button','.jw-icon-display','.ytp-large-play-button','.plyr__control--overlaid',
      'button[aria-label*="play" i]','[class*="play-button" i]','[class*="PlayButton"]','[data-testid*="play" i]'];
    for (var i = 0; i < sels.length; i++) { var el = document.querySelector(sels[i]); if (el && el.offsetWidth) { el.click(); break; } }
  } catch (e) {}
  return 1;
})()"""

        /** Read the live DOM (same shape as [Inspector.parseStaticHtml]). */
        const val PROBE_JS = """(function(){
  var out = { url: location.href, title: document.title, metas: {}, ld: [], videos: [], iframes: [], text: '' };
  document.querySelectorAll('meta[property],meta[name],meta[itemprop]').forEach(function(m){
    var k = (m.getAttribute('property') || m.getAttribute('name') || m.getAttribute('itemprop') || '').toLowerCase();
    var v = m.getAttribute('content');
    if (k && v && !(k in out.metas)) out.metas[k] = v;
  });
  document.querySelectorAll('script[type="application/ld+json"]').forEach(function(s){ out.ld.push(s.textContent); });
  document.querySelectorAll('video').forEach(function(v){
    var r = v.getBoundingClientRect();
    var src = v.currentSrc || v.src || '';
    out.videos.push({ src: /^blob:/.test(src) ? '' : src,
      sources: Array.prototype.map.call(v.querySelectorAll('source'), function(s){ return { src: s.src, type: s.type || '' }; }),
      poster: v.poster || '', duration: isFinite(v.duration) ? v.duration : 0, w: r.width, h: r.height });
  });
  document.querySelectorAll('iframe').forEach(function(f){ if (f.src) out.iframes.push(f.src); });
  out.text = (document.body ? document.body.innerText : '').slice(0, 3000);
  return JSON.stringify(out);
})()"""
    }
}
