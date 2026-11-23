package app.meh

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * Plays videos inside Chrome (the phone's WebView), reporting started / ended / failed back to
 * the app so autoplay can advance:
 *  - Vimeo / Dailymotion with the sites' own embedded players ([play]);
 *  - any video in a bare Chrome player that presents itself as the video's page ([playWeb]);
 *  - the video's own page, with its biggest video started and enlarged ([playPage]).
 * The last two are fallbacks for videos the app's own player can't play (format / refused).
 */
class EmbedPlayer(private val context: Context, private val host: ViewGroup, private val events: Events) {

    interface Events {
        fun onEmbedStarted(token: Int)
        fun onEmbedEnded(token: Int)
        fun onEmbedFailed(token: Int, why: String)
        fun onEmbedMuted(token: Int)
    }

    private val main = Handler(Looper.getMainLooper())
    private var token = 0
    /** Showing a site's own page: allow its navigations, inject the player script when loaded. */
    @Volatile private var pageMode = false

    private var web = makeWeb()

    @SuppressLint("SetJavaScriptEnabled")
    private fun makeWeb(): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        setBackgroundColor(0xFF000000.toInt())
        webChromeClient = WebChromeClient()
        webViewClient = object : WebViewClient() {
            // Ad blocker for the embedded players (Dailymotion in particular runs ads).
            // Background thread: never touch the WebView here, never let an error escape.
            override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? = try {
                if (request.url.path == PlayerPages.HLS_JS_PATH) {
                    // The Chrome player's HLS library, bundled in the app (on whatever origin it runs).
                    android.webkit.WebResourceResponse("application/javascript", "utf-8", context.assets.open("hls.min.js"))
                } else if (!request.isForMainFrame && isAdUrl(request.url.toString())) {
                    Blocklist.blocked.incrementAndGet()
                    android.webkit.WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                } else null
            } catch (e: Throwable) { null }

            // Keep the player page in place: links inside an embed must not navigate away.
            // A site's own page (fallback mode) may redirect within the web, never to apps.
            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean =
                if (pageMode) request.url.scheme !in setOf("http", "https") else request.isForMainFrame

            override fun onPageFinished(view: WebView, url: String?) {
                if (pageMode && token >= 0 && url != null && url.startsWith("http")) {
                    safely { view.evaluateJavascript(PlayerPages.pageScript(token), null) }
                }
            }

            // The renderer crashed or was killed (e.g. a frozen page elsewhere in the app):
            // swap in a fresh WebView and skip the current video instead of crashing the app.
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                main.post {
                    if (view !== web || destroyed) return@post
                    host.removeView(view)
                    try { view.destroy() } catch (e: Throwable) {}
                    web = makeWeb()
                    val t = token
                    if (t >= 0) events.onEmbedFailed(t, "player crashed")
                }
                return true
            }
        }
        addJavascriptInterface(Bridge(), "Meh")
        host.addView(this, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private inner class Bridge {
        private fun post(t: Int, fn: () -> Unit) = main.post { if (t == token) fn() }
        @JavascriptInterface fun started(t: Int) = post(t) { events.onEmbedStarted(t) }
        @JavascriptInterface fun ended(t: Int) = post(t) { events.onEmbedEnded(t) }
        @JavascriptInterface fun failed(t: Int, why: String) = post(t) { events.onEmbedFailed(t, why) }
        @JavascriptInterface fun muted(t: Int) = post(t) { events.onEmbedMuted(t) }
    }

    /** Fallback 1: the video in a bare Chrome player that presents itself as the video's page. */
    fun playWeb(v: Video, t: Int) {
        token = t
        pageMode = false
        val base = v.pageUrl.takeIf { it.startsWith("http") } ?: v.src
        safely { web.loadDataWithBaseURL(base, PlayerPages.webPlayer(v.src, v.kind == "hls", t), "text/html", "utf-8", null) }
    }

    /** Fallback 2: the video's own page, with its biggest video started and enlarged. */
    fun playPage(v: Video, t: Int) {
        token = t
        pageMode = true
        safely { web.loadUrl(v.pageUrl.ifEmpty { v.src }) }
    }

    fun play(v: Video, t: Int) {
        token = t
        pageMode = false
        val id = JSONObject.quote(v.id ?: "")
        val body = when (v.provider) {
            "vimeo" -> """
<div id="p"></div>
<script>
var T=$t, player;
var tag=document.createElement('script'); tag.src='https://player.vimeo.com/api/player.js'; tag.onerror=function(){Meh.failed(T,'api')};
tag.onload=function(){
  player=new Vimeo.Player('p',{id:Number($id),autoplay:true,width:640});
  player.ready().then(function(){ var f=document.querySelector('#p iframe'); if(f) f.style.cssText='position:absolute;inset:0;width:100%;height:100%;border:0'; });
  player.on('play',function(){ Meh.started(T); });
  player.on('ended',function(){ Meh.ended(T); });
  player.on('error',function(){ Meh.failed(T,'vimeo'); });
  player.play().catch(function(){ player.setMuted(true).then(function(){ Meh.muted(T); return player.play(); }).catch(function(){ Meh.failed(T,'vimeo blocked'); }); });
};
document.head.appendChild(tag);
function cmd(c){ if(!player) return;
  if(c=='toggle') player.getPaused().then(function(p){ p ? player.play() : player.pause(); });
  if(c=='pause') player.pause();
  if(c=='unmute') player.setMuted(false); }
</script>"""
            else -> """
<iframe id="p" allow="autoplay; fullscreen" allowfullscreen src="https://www.dailymotion.com/embed/video/${v.id}?autoplay=1" onload="Meh.started($t)"></iframe>
<script>function cmd(c){}</script>"""
        }
        val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p,#p iframe{position:absolute;inset:0;width:100%;height:100%;border:0}</style>
</head><body>$body</body></html>"""
        safely { web.loadDataWithBaseURL("https://meh.app/", html, "text/html", "utf-8", null) }
    }

    private var destroyed = false

    /** Every call into the WebView is guarded: after a renderer crash or destroy() it must not crash the app. */
    private inline fun safely(block: () -> Unit) { if (!destroyed) try { block() } catch (e: Throwable) { android.util.Log.w("meh", "embed", e) } }

    fun command(c: String) = safely { web.evaluateJavascript("try{cmd('$c')}catch(e){}", null) }

    fun stop() {
        token = -1
        pageMode = false
        safely { web.loadUrl("about:blank") }
    }

    fun pause() { command("pause"); safely { web.onPause() } }
    fun resume() = safely { web.onResume() }
    fun destroy() { safely { host.removeView(web); web.destroy() }; destroyed = true }
}
