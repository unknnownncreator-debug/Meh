package app.meh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * One search = web search → inspect every result page (fast static pass first, then
 * the hidden browser when needed) → stream each found video to the UI as it appears.
 */
class Engine(private val scope: CoroutineScope, private val web: WebInspector) {

    interface Listener {
        fun onStatus(message: String)
        fun onVideo(video: Video)
        fun onDone(found: Int, hasMore: Boolean)
    }

    private var job: Job? = null
    val busy get() = job?.isActive == true

    private val search = Search(Http::getHtml, browser = { url -> runBlocking { web.fetchHtml(url) } })

    fun cancel() { job?.cancel(); job = null }

    fun run(query: String, page: Int, includeRedgifs: Boolean, listener: Listener) {
        cancel()
        job = scope.launch {
            var found = 0
            val emit: suspend (Video, Hit) -> Unit = emit@{ v, hit ->
                if (v.blocked) return@emit // excluded sites (YouTube, TikTok)
                // Adult sites switched off, or content depicting minors (always excluded).
                if (Adult.drop(v, hit, includeRedgifs)) return@emit
                v.score = Ranker.score(query, v, hit.rank)
                found++
                withContext(Dispatchers.Main) { listener.onVideo(v) }
            }
            listener.onStatus(if (page == 0) "Searching the web…" else "Finding more videos…")
            val hits = withContext(Dispatchers.IO) {
                search.searchWeb(query, page, includeRedgifs) { name, n -> scope.launch(Dispatchers.Main) { if (busy) listener.onStatus("$name: $n results") } }
            }
            val queue = hits.take(MAX_PAGES)
            if (queue.isEmpty()) {
                listener.onStatus("No search results — the search engines may be blocking requests. Try again in a moment.")
                listener.onDone(0, false)
                return@launch
            }
            val toInspect = queue
            var done = 0
            listener.onStatus("Inspecting ${toInspect.size} pages for video…")
            val staticGate = Semaphore(6)
            toInspect.map { hit ->
                async {
                    val videos = try {
                        val st = staticGate.withPermit { withContext(Dispatchers.IO) { Inspector.staticStage(hit) } }
                        coroutineContext.ensureActive()
                        st.videos.ifEmpty { web.inspect(hit, st.probe) }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        emptyList()
                    }
                    videos.forEach { emit(it, hit) }
                    done++
                    listener.onStatus("Inspected $done/${toInspect.size} pages · $found videos found")
                }
            }.awaitAll()
            listener.onDone(found, page < 10)
        }
    }

    companion object {
        const val MAX_PAGES = 40
    }
}
