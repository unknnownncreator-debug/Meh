package app.meh

import org.json.JSONObject

/**
 * The HTML/JS for the two fallback players that run inside Chrome (the phone's WebView), used
 * when the app's own player can't play a video:
 *
 *  - [webPlayer]: a bare Chrome video player. Loaded with the video's *page* as its address, so
 *    its requests look like the site's own page (same origin, Referer and cookies) and use
 *    Chrome's network stack and decoders (VP9/AV1/... in software where the hardware can't).
 *  - [PAGE_SCRIPT]: injected into the site's own page as a last resort: finds its biggest
 *    video, starts it and blows it up to fill the screen.
 *
 * Both report back through the `Meh` bridge with a token, like the other embedded players.
 */
object PlayerPages {
    /** Served by the player's request hook from the app's assets (any origin, this path). */
    const val HLS_JS_PATH = "/__meh__/hls.min.js"

    fun webPlayer(src: String, hls: Boolean, token: Int): String = """<!doctype html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}video{position:absolute;inset:0;width:100%;height:100%;object-fit:contain;background:#000}</style>
</head><body><video id="v" controls autoplay playsinline></video>
<script>
var T=$token, SRC=${jsString(src)}, HLS=$hls, v=document.getElementById('v'), started=false;
v.addEventListener('playing', function(){ if(!started){ started=true; Meh.started(T); } });
v.addEventListener('ended', function(){ Meh.ended(T); });
v.addEventListener('error', function(){ Meh.failed(T, 'web player error ' + (v.error && v.error.code)); });
function go(){ var p=v.play(); if(p && p.catch) p.catch(function(){ v.muted=true; Meh.muted(T); v.play().catch(function(){}); }); }
if (HLS && !v.canPlayType('application/vnd.apple.mpegurl')) {
  var s=document.createElement('script'); s.src='$HLS_JS_PATH';
  s.onload=function(){
    if (!(window.Hls && Hls.isSupported())) { Meh.failed(T, 'no hls support'); return; }
    var h=new Hls(); h.on(Hls.Events.ERROR, function(e, d){ if (d.fatal) Meh.failed(T, 'hls ' + d.details); });
    h.on(Hls.Events.MANIFEST_PARSED, go); h.loadSource(SRC); h.attachMedia(v);
  };
  s.onerror=function(){ Meh.failed(T, 'hls.js missing'); };
  document.head.appendChild(s);
} else { v.src=SRC; go(); }
function cmd(c){ if(c=='toggle'){ v.paused ? v.play() : v.pause(); } if(c=='pause') v.pause(); if(c=='unmute') v.muted=false; }
</script></body></html>"""

    /**
     * A JS string literal that is also safe inside an HTML <script> block: the address comes from
     * an arbitrary website, so every "<" is escaped and it can never open or close a tag.
     */
    fun jsString(s: String): String = JSONObject.quote(s).replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    /** Injected (with the token) into the site's own page once it has loaded. */
    fun pageScript(token: Int): String = """(function(T){
  if (window.__mehPage) return; window.__mehPage = true;
  var started = false, best = null;
  function hook(v){
    if (v.__meh) return; v.__meh = 1;
    v.addEventListener('playing', function(){ if(!started){ started = true; Meh.started(T); } });
    v.addEventListener('ended', function(){ if (v === best) Meh.ended(T); });
  }
  function scan(){
    var vs = document.querySelectorAll('video');
    for (var i = 0; i < vs.length; i++) hook(vs[i]);
    // Pick the biggest video once; keep it while it's still on the page.
    if (!best || !document.contains(best)) {
      var area = 0; best = null;
      for (var j = 0; j < vs.length; j++) { var r = vs[j].getBoundingClientRect(), a = r.width * r.height; if (a > area) { area = a; best = vs[j]; } }
    }
    if (!best) return;
    if (!best.__mehBig) {
      best.__mehBig = 1;
      best.style.cssText += ';position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;' +
        'max-width:none!important;max-height:none!important;z-index:2147483647!important;background:#000!important;object-fit:contain!important';
      best.controls = true;
    }
    if (best.paused && !best.ended) { var p = best.play(); if (p && p.catch) p.catch(function(){ best.muted = true; Meh.muted(T); best.play().catch(function(){}); }); }
  }
  var sels = ['.vjs-big-play-button','.jw-icon-display','.plyr__control--overlaid','button[aria-label*="play" i]','[class*="play-button" i]','[class*="PlayButton"]','[data-testid*="play" i]'];
  for (var i = 0; i < sels.length; i++) { var el = document.querySelector(sels[i]); if (el && el.offsetWidth) { try { el.click(); } catch(e) {} break; } }
  scan(); setInterval(scan, 1500);
  // Players inside other sites' frames can't be seen from here: if nothing is detected,
  // assume the page is showing its video and let the duration timer / Next take over.
  setTimeout(function(){ if (!started) { started = true; Meh.started(T); } }, 8000);
  window.cmd = function(c){ if (!best) return; if(c=='toggle'){ best.paused ? best.play() : best.pause(); } if(c=='pause') best.pause(); if(c=='unmute') best.muted = false; };
})($token);"""
}
