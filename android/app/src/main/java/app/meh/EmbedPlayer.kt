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
 * Plays YouTube / Vimeo / Dailymotion videos with the sites' own embedded players,
 * reporting started / ended / failed back to the app so autoplay can advance.
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

    private var web = makeWeb()

    @SuppressLint("SetJavaScriptEnabled")
    private fun makeWeb(): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        setBackgroundColor(0xFF000000.toInt())
        webChromeClient = WebChromeClient()
        webViewClient = object : WebViewClient() {
            // Keep the player page in place: links inside the embed must not navigate away.
            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest) = request.isForMainFrame

            // The renderer crashed or was killed (e.g. a frozen page elsewhere in the app):
            // swap in a fresh WebView and skip the current video instead of crashing the app.
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                main.post {
                    if (view !== web) return@post
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

    fun play(v: Video, t: Int) {
        token = t
        val id = JSONObject.quote(v.id ?: "")
        val body = when (v.provider) {
            "youtube" -> """
<div id="p"></div>
<script>
var T=$t, started=false, player;
var tag=document.createElement('script'); tag.src='https://www.youtube.com/iframe_api'; tag.onerror=function(){Meh.failed(T,'api')}; document.head.appendChild(tag);
function onYouTubeIframeAPIReady(){
  player=new YT.Player('p',{videoId:$id,width:'100%',height:'100%',
    playerVars:{autoplay:1,playsinline:1,rel:0,modestbranding:1,origin:'https://meh.app'},
    events:{
      onReady:function(e){ e.target.playVideo(); setTimeout(function(){ if(!started){ e.target.mute(); e.target.playVideo(); Meh.muted(T); } },2500); },
      onStateChange:function(e){ if(e.data==1&&!started){ started=true; Meh.started(T); } if(e.data==0) Meh.ended(T); },
      onError:function(e){ Meh.failed(T,'youtube '+e.data); }
    }});
}
function cmd(c){ if(!player||!player.getPlayerState) return;
  if(c=='toggle') { player.getPlayerState()==1 ? player.pauseVideo() : player.playVideo(); }
  if(c=='pause') player.pauseVideo();
  if(c=='unmute'){ player.unMute(); player.setVolume(100); } }
</script>"""
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
        web.loadDataWithBaseURL("https://meh.app/", html, "text/html", "utf-8", null)
    }

    fun command(c: String) = web.evaluateJavascript("try{cmd('$c')}catch(e){}", null)

    fun stop() {
        token = -1
        web.loadUrl("about:blank")
    }

    fun pause() { command("pause"); web.onPause() }
    fun resume() = web.onResume()
    fun destroy() { host.removeView(web); web.destroy() }
}
