package app.meh

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity(), Engine.Listener, EmbedPlayer.Events {

    private val scope = MainScope()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var engine: Engine
    private lateinit var web: WebInspector
    private lateinit var embed: EmbedPlayer
    private lateinit var exo: ExoPlayer
    private lateinit var images: ImageLoader

    // views
    private lateinit var home: View
    private lateinit var results: LinearLayout
    private lateinit var topBar: View
    private lateinit var scroll: ScrollView
    private lateinit var homeQuery: EditText
    private lateinit var topQuery: EditText
    private lateinit var playerBox: AspectFrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var embedBox: FrameLayout
    private lateinit var overlay: View
    private lateinit var overlaySpinner: ProgressBar
    private lateinit var overlayText: TextView
    private lateinit var nowTitle: TextView
    private lateinit var nowSource: TextView
    private lateinit var nowKind: TextView
    private lateinit var nowScore: TextView
    private lateinit var nowDesc: TextView
    private lateinit var unmute: Button
    private lateinit var autoplay: CheckBox
    private lateinit var skipWatched: CheckBox
    private lateinit var status: TextView
    private lateinit var statusDot: ImageView
    private lateinit var queue: LinearLayout
    private lateinit var queueCount: TextView

    // search session state
    private var query = ""
    private val items = LinkedHashMap<String, Video>()
    private val failed = HashSet<String>()
    private val played = HashSet<String>()
    private val history = ArrayDeque<String>()
    private var current: String? = null
    private var page = 0
    private var loading = false
    private var hasMore = false
    private var startPending = false

    // playback state
    private var token = 0
    private var fullscreen = false
    private val watchdog = Runnable { failCurrent(SkipReason.TIMED_OUT) }
    private var skippedCount = 0
    private var adsAtSearchStart = 0
    private val embedEnd = Runnable { onEmbedEnded(token) }

    private var webUnavailable = false
    private val prefs by lazy { getSharedPreferences("meh", Context.MODE_PRIVATE) }
    private val watched = LinkedHashSet<String>()

    // ------------------------------------------------------------------ setup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        setContentView(R.layout.activity_main)
        onBackPressedDispatcher.addCallback(this, backCallback)
        scope.launch(Dispatchers.IO) { Blocklist.loadFromAssets(applicationContext) }
        // Claim the same Chrome version the phone actually has (WebView version = Chrome version).
        try {
            android.webkit.WebView.getCurrentWebViewPackage()?.versionName?.substringBefore('.')?.toIntOrNull()
                ?.takeIf { it in 100..999 }?.let { chromeMajor = it }
        } catch (e: Throwable) { /* keep the built-in default */ }
        bindViews()

        watched += (prefs.getString("watched", "") ?: "").split('\n').filter { it.isNotEmpty() }
        autoplay.isChecked = prefs.getBoolean("autoplay", true)
        skipWatched.isChecked = prefs.getBoolean("skipWatched", true)
        autoplay.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("autoplay", v).apply() }
        skipWatched.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("skipWatched", v).apply(); renderQueue() }

        try {
            web = WebInspector(this, findViewById(R.id.inspectorHost))
            embed = EmbedPlayer(this, embedBox, this)
        } catch (e: Throwable) {
            // Android System WebView missing, disabled or mid-update: say so instead of crashing.
            android.util.Log.e("meh", "WebView unavailable", e)
            CrashLog.show(this, "Can't start meh",
                "Android System WebView isn't available on this phone right now (it may be updating or disabled).\n\n" +
                    "Open the Play Store, update “Android System WebView” and “Chrome”, then open meh again.\n\n(${e.javaClass.simpleName}: ${e.message})")
            webUnavailable = true
            return
        }
        engine = Engine(scope, web)
        images = ImageLoader(scope)

        exo = ExoPlayer.Builder(this).build()
        playerView.player = exo
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) started()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) advanceAfterEnd()
                // Last line of defence against pre-roll ads: a video found inside a page (not
                // declared by the site) that turns out to be under 45 s is skipped, not played.
                if (state == Player.STATE_READY) {
                    val v = current?.let { items[it] }
                    val ms = exo.duration
                    if (v != null && v.via in setOf("network", "live", "dom") && ms != C.TIME_UNSET && ms in 1 until 45_000) failCurrent(SkipReason.TOO_SHORT)
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                val http = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                android.util.Log.w("meh", "player error ${error.errorCodeName} http=$http")
                failCurrent(SkipReason.describe(error.errorCode, http), refresh = SkipReason.worthRefreshing(error.errorCode, http))
            }
        })

        listOf(homeQuery, topQuery).forEach { field ->
            field.setOnEditorActionListener { v, id, ev ->
                if (id == EditorInfo.IME_ACTION_SEARCH || (ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)) {
                    search(v.text.toString()); true
                } else false
            }
        }
        findViewById<Button>(R.id.homeGo).setOnClickListener { search(homeQuery.text.toString()) }
        findViewById<Button>(R.id.topGo).setOnClickListener { search(topQuery.text.toString()) }
        findViewById<View>(R.id.topLogo).setOnClickListener { goHome() }
        findViewById<Button>(R.id.next).setOnClickListener { playNext() }
        findViewById<Button>(R.id.prev).setOnClickListener { playPrev() }
        findViewById<View>(R.id.fullscreen).setOnClickListener { toggleFullscreen() }
        nowSource.setOnClickListener {
            items[current]?.let { v -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(v.pageUrl.ifEmpty { v.src }))) }
        }
        unmute.setOnClickListener {
            unmute.visibility = View.GONE
            if (playerView.visibility == View.VISIBLE) exo.volume = 1f else embed.command("unmute")
        }

        applyInsets()
        handleIntent(intent)
        CrashLog.showIfAny(this)
    }

    private fun bindViews() {
        home = findViewById(R.id.home); results = findViewById(R.id.results); topBar = findViewById(R.id.topBar)
        scroll = findViewById(R.id.scroll); homeQuery = findViewById(R.id.homeQuery); topQuery = findViewById(R.id.topQuery)
        playerBox = findViewById(R.id.playerBox); playerView = findViewById(R.id.playerView); embedBox = findViewById(R.id.embedBox)
        overlay = findViewById(R.id.overlay); overlaySpinner = findViewById(R.id.overlaySpinner); overlayText = findViewById(R.id.overlayText)
        nowTitle = findViewById(R.id.nowTitle); nowSource = findViewById(R.id.nowSource); nowKind = findViewById(R.id.nowKind)
        nowScore = findViewById(R.id.nowScore); nowDesc = findViewById(R.id.nowDesc); unmute = findViewById(R.id.unmute)
        autoplay = findViewById(R.id.autoplay); skipWatched = findViewById(R.id.skipWatched)
        status = findViewById(R.id.status); statusDot = findViewById(R.id.statusDot)
        queue = findViewById(R.id.queue); queueCount = findViewById(R.id.queueCount)
    }

    /** Android 15 draws edge-to-edge: pad the UI clear of the status bar, nav bar and keyboard. */
    private fun applyInsets() {
        // Draw edge-to-edge on every Android version (15+ forces it anyway), so the
        // padding below is always the only thing keeping content clear of the bars.
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else @Suppress("DEPRECATION") { window.decorView.systemUiVisibility = LAYOUT_FLAGS }
        val app = findViewById<View>(R.id.app)
        app.setOnApplyWindowInsetsListener { v, insets ->
            if (fullscreen) v.setPadding(0, 0, 0, 0)
            else if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Text shared from another app becomes a search. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT)?.let { search(it.take(200)) }
    }

    // ------------------------------------------------------------------ search

    private fun search(raw: String) {
        val q = raw.trim()
        if (q.isEmpty() || webUnavailable) return
        hideKeyboard()
        query = q
        topQuery.setText(q)
        homeQuery.setText(q)
        home.visibility = View.GONE
        results.visibility = View.VISIBLE
        updateBack()
        stopPlayback()
        engine.cancel()
        items.clear(); failed.clear(); played.clear(); history.clear()
        current = null; page = 0; hasMore = false; startPending = false; skippedCount = 0
        adsAtSearchStart = Blocklist.blocked.get()
        clearNow()
        setOverlay("Searching the web for “$q”…", busy = true)
        loadPage(0)
        renderQueue()
    }

    private fun loadPage(p: Int) {
        page = p
        loading = true
        setStatus(if (p == 0) "Searching the web…" else "Finding more videos…", done = false)
        engine.run(query, p, this)
    }

    override fun onStatus(message: String) {
        setStatus(message, done = false)
        if (current == null && items.isEmpty()) setOverlay(message, busy = true)
    }

    override fun onVideo(video: Video) {
        if (items.containsKey(video.key)) return
        items[video.key] = video
        scheduleRender()
        // Start as soon as there's something, after a short beat so a better match
        // arriving in the same burst can take the first slot.
        if (current == null && !startPending) {
            startPending = true
            ui.postDelayed({ startPending = false; if (current == null) playNext() }, 900)
        }
    }

    override fun onDone(found: Int, hasMore: Boolean) {
        loading = false
        this.hasMore = hasMore
        val n = items.size
        val sites = items.values.map { it.site }.toSet().size
        val ads = Blocklist.blocked.get() - adsAtSearchStart
        val adText = if (ads > 0) " · $ads ad${if (ads == 1) "" else "s"} blocked" else ""
        setStatus((if (n > 0) "Found $n video${if (n == 1) "" else "s"} across $sites site${if (sites == 1) "" else "s"}" else "No playable videos found") + adText, done = true)
        if (current == null && !startPending) {
            when {
                upcoming().isNotEmpty() -> playNext()
                hasMore && page < 3 -> loadPage(page + 1)
                else -> setOverlay(if (n > 0) "You've watched everything found for this search. Untick “Skip watched” or try another search." else "No playable videos found. Try different words.", busy = false)
            }
        }
        maybeLoadMore()
        renderQueue()
    }

    private fun maybeLoadMore() {
        if (!loading && hasMore && page < 10 && upcoming().size < 4 && query.isNotEmpty()) loadPage(page + 1)
    }

    /** Ranked queue: best matches first, sites spread out, watched videos last or hidden. */
    private fun upcoming(): List<Video> {
        val pool = items.values.filter { it.key != current && it.key !in played && it.key !in failed }
        val fresh = pool.filter { it.key !in watched }
        val old = if (skipWatched.isChecked) emptyList() else pool.filter { it.key in watched }
        return Ranker.order(fresh) + Ranker.order(old)
    }

    // ------------------------------------------------------------------ playback

    private fun playNext() {
        val next = upcoming().firstOrNull()
        if (next == null) {
            current?.let { played += it }
            current = null
            if (loading || hasMore) { setOverlay("Looking for more videos…", busy = true); maybeLoadMore() }
            else if (items.isNotEmpty()) setOverlay("That's everything found for this search. Try another search.", busy = false)
            renderQueue()
            return
        }
        current?.let { history.addLast(it) }
        play(next)
        maybeLoadMore()
    }

    private fun playPrev() {
        val key = history.removeLastOrNull() ?: return
        val v = items[key] ?: return
        played.remove(key)
        current?.let { played.remove(it) } // current goes back into the queue
        play(v)
    }

    private fun play(v: Video) {
        stopPlayback()
        val t = token
        current = v.key
        played += v.key
        showNow(v)
        renderQueue()
        scroll.smoothScrollTo(0, 0)
        setOverlay("Loading video…", busy = true)
        ui.postDelayed(watchdog, if (v.kind == "embed") 20_000 else 20_000)

        if (v.kind == "embed") {
            playerView.visibility = View.GONE
            embedBox.visibility = View.VISIBLE
            embed.resume()
            embed.play(v, t)
            if (v.provider == "dailymotion" && v.duration != null) ui.postDelayed(embedEnd, ((v.duration + 3) * 1000).toLong())
            return
        }
        playerView.visibility = View.VISIBLE
        embedBox.visibility = View.GONE
        // Ask for the video the way the page did: same browser identity, same cookies,
        // same Referer. Many sites refuse video requests that don't match.
        val cookies = try { CookieManager.getInstance().getCookie(v.src) } catch (e: Exception) { null }
        val ds = DefaultHttpDataSource.Factory()
            .setUserAgent(v.userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000).setReadTimeoutMs(15_000)
            .setDefaultRequestProperties(buildMap {
                if (v.referer.isNotEmpty()) {
                    put("Referer", v.referer)
                    Uri.parse(v.referer).let { u -> if (u.scheme != null && u.host != null) put("Origin", "${u.scheme}://${u.host}") }
                }
                if (!cookies.isNullOrEmpty()) put("Cookie", cookies)
            })
        val item = MediaItem.fromUri(v.src)
        val source = if (v.kind == "hls") HlsMediaSource.Factory(ds).createMediaSource(item) else ProgressiveMediaSource.Factory(ds).createMediaSource(item)
        exo.volume = 1f
        exo.setMediaSource(source)
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun stopPlayback() {
        token++
        ui.removeCallbacks(watchdog)
        ui.removeCallbacks(embedEnd)
        exo.stop()
        exo.clearMediaItems()
        embed.stop()
        unmute.visibility = View.GONE
    }

    private fun started() {
        ui.removeCallbacks(watchdog)
        hideOverlay()
        current?.let { markWatched(it) }
    }

    private fun advanceAfterEnd() {
        if (autoplay.isChecked) playNext()
        else setOverlay("Finished. Tap Next for the next video.", busy = false)
    }

    /**
     * The current video can't be played. If a fresh link might help (refused / expired
     * link), reopen its page once to get a new one; otherwise skip it, saying why.
     */
    private fun failCurrent(why: String, refresh: Boolean = false) {
        val key = current ?: return
        val v = items[key]
        android.util.Log.w("meh", "can't play ${v?.src}: $why")
        val t = token
        ui.removeCallbacks(watchdog)
        if (refresh && v != null && v.kind != "embed" && !v.refreshed && v.pageUrl.isNotEmpty()) {
            setOverlay("That link was refused — getting a fresh one from ${v.site}…", busy = true)
            scope.launch {
                val hit = Hit(v.pageUrl, v.pageTitle)
                val fresh = try {
                    val st = withContext(Dispatchers.IO) { Inspector.staticStage(hit) }
                    st.videos.ifEmpty { web.inspect(hit, st.probe) }.firstOrNull { !it.blocked }
                } catch (e: Exception) { null }
                if (t != token) return@launch // user moved on meanwhile
                if (fresh == null) { skip(key, v, why); return@launch }
                val retry = fresh.copy(refreshed = true, title = v.title.ifEmpty { fresh.title }).also { it.score = v.score }
                items.remove(key)
                items[retry.key] = retry
                current = null
                play(retry)
            }
            return
        }
        skip(key, v, why)
    }

    private fun skip(key: String, v: Video?, why: String) {
        val t = token
        failed += key
        current = null
        skippedCount++
        val name = v?.title?.take(60)?.ifEmpty { null } ?: "that video"
        setOverlay("Couldn't play “$name”: $why. Skipping…", busy = true)
        setStatus("Skipped “$name” (${v?.site ?: "?"}): $why · $skippedCount skipped so far", done = !loading)
        ui.postDelayed({ if (t == token) playNext() }, 1800)
    }

    override fun onEmbedStarted(token: Int) { if (token == this.token) started() }
    override fun onEmbedEnded(token: Int) { if (token == this.token) advanceAfterEnd() }
    override fun onEmbedFailed(token: Int, why: String) {
        if (token == this.token) failCurrent(if (why == "player crashed") "the player crashed" else "the owner doesn't allow it to be played here, or it was removed")
    }
    override fun onEmbedMuted(token: Int) { if (token == this.token) unmute.visibility = View.VISIBLE }

    private fun markWatched(key: String) {
        watched.remove(key); watched.add(key)
        while (watched.size > 3000) watched.remove(watched.first())
        prefs.edit().putString("watched", watched.joinToString("\n")).apply()
    }

    // ------------------------------------------------------------------ full screen

    private fun toggleFullscreen() {
        requestedOrientation = if (!fullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        setFullscreen(!fullscreen)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Turning the phone sideways while watching = full screen.
        if (results.visibility == View.VISIBLE) setFullscreen(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        topBar.visibility = if (on) View.GONE else View.VISIBLE
        scroll.visibility = if (on) View.GONE else View.VISIBLE
        playerBox.fill = on
        (playerBox.layoutParams as LinearLayout.LayoutParams).apply {
            height = if (on) LinearLayout.LayoutParams.MATCH_PARENT else LinearLayout.LayoutParams.WRAP_CONTENT
            weight = if (on) 1f else 0f
        }
        playerBox.requestLayout()
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                if (on) {
                    it.hide(WindowInsets.Type.systemBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else it.show(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (on) (LAYOUT_FLAGS or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY) else LAYOUT_FLAGS
        }
        findViewById<View>(R.id.app).requestApplyInsets()
        updateBack()
    }

    // ------------------------------------------------------------------ navigation / lifecycle

    private fun goHome() {
        if (fullscreen) toggleFullscreen()
        engine.cancel()
        stopPlayback()
        loading = false
        current = null
        results.visibility = View.GONE
        home.visibility = View.VISIBLE
        updateBack()
    }

    /**
     * Back: leave full screen, then go from results to home. The callback is only enabled
     * while there's something to go back from, so on the home screen the system's own
     * (predictive) back gesture applies. Works the same on every Android version.
     */
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                fullscreen -> toggleFullscreen()
                results.visibility == View.VISIBLE -> goHome()
            }
        }
    }

    private fun updateBack() {
        backCallback.isEnabled = !webUnavailable && (fullscreen || results.visibility == View.VISIBLE)
    }

    override fun onPause() {
        super.onPause()
        if (webUnavailable) return
        exo.pause()
        embed.pause()
    }

    override fun onResume() {
        super.onResume()
        if (webUnavailable) return
        embed.resume()
    }

    override fun onDestroy() {
        scope.cancel()
        if (!webUnavailable) {
            engine.cancel()
            exo.release()
            web.destroy()
            embed.destroy()
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ rendering

    private fun setOverlay(text: String, busy: Boolean) {
        overlay.visibility = View.VISIBLE
        overlaySpinner.visibility = if (busy) View.VISIBLE else View.GONE
        overlayText.text = text
    }

    private fun hideOverlay() { overlay.visibility = View.GONE }

    private fun setStatus(text: String, done: Boolean) {
        status.text = text
        statusDot.setColorFilter(getColor(if (done) R.color.ok else R.color.accent))
    }

    private fun clearNow() {
        nowTitle.text = ""; nowSource.text = ""; nowKind.text = ""; nowKind.visibility = View.GONE; nowScore.text = ""; nowDesc.text = ""
    }

    private fun showNow(v: Video) {
        nowTitle.text = v.title.ifEmpty { v.pageTitle.ifEmpty { "Untitled video" } }
        nowSource.text = "${v.siteName.ifEmpty { v.site }} ↗"
        nowKind.text = v.kindLabel
        nowKind.visibility = View.VISIBLE
        nowScore.text = "relevance ${v.score.roundToInt()}"
        nowDesc.text = v.description
    }

    private val renderRunnable = Runnable { renderQueue() }
    private fun scheduleRender() { ui.removeCallbacks(renderRunnable); ui.postDelayed(renderRunnable, 150) }

    private fun renderQueue() {
        val list = upcoming()
        val cur = current?.let { items[it] }
        val rows = listOfNotNull(cur) + list
        queue.removeAllViews()
        val inflater = layoutInflater
        for (v in rows.take(60)) {
            val row = inflater.inflate(R.layout.item_queue, queue, false)
            val isCur = v === cur
            if (isCur) row.setBackgroundResource(R.drawable.bg_row_current)
            else if (v.key in watched) row.alpha = 0.55f
            row.findViewById<TextView>(R.id.title).text = (if (isCur) "▶ " else "") + v.title.ifEmpty { v.pageTitle.ifEmpty { "Untitled" } }
            row.findViewById<TextView>(R.id.site).text = "${v.siteName.ifEmpty { v.site }} · ${v.kindLabel}"
            row.findViewById<TextView>(R.id.score).text = "relevance ${v.score.roundToInt()}"
            row.findViewById<TextView>(R.id.dur).apply {
                text = fmtDuration(v.duration)
                visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            }
            images.load(v.thumbnail, row.findViewById(R.id.thumb))
            row.setOnClickListener { if (!isCur) { current?.let { history.addLast(it) }; play(v) } }
            queue.addView(row)
        }
        if (rows.isEmpty()) {
            queue.addView(TextView(this).apply {
                text = if (loading) "Inspecting pages — videos will appear here as they're found." else "Nothing queued."
                setTextColor(getColor(R.color.muted))
                setPadding(12, 24, 12, 24)
            })
        }
        queueCount.text = if (list.isNotEmpty()) "${list.size} queued" else ""
    }

    private fun fmtDuration(d: Double?): String {
        if (d == null || d <= 0) return ""
        val s = d.roundToInt()
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    companion object {
        @Suppress("DEPRECATION")
        private const val LAYOUT_FLAGS = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        currentFocus?.clearFocus()
    }
}
