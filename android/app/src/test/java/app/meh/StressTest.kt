package app.meh

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.random.Random

/**
 * Stress / robustness tests for the on-phone engine: real saved search pages (parity with
 * the desktop version), pathological and fuzzed input, and a hostile local web server.
 */
class StressTest {

    companion object {
        private val REAL = File("../../test/fixtures/real")
        private lateinit var base: String

        private lateinit var socket: ServerSocket
        private val routes = HashMap<String, (OutputStream) -> Unit>()

        private fun send(out: OutputStream, status: String, type: String, body: ByteArray, extra: String = "") {
            out.write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n${extra}Connection: close\r\n\r\n".toByteArray())
            out.write(body)
        }

        /** Write [chunk] forever (no Content-Length), until the client hangs up. */
        private fun pump(out: OutputStream, type: String, chunk: ByteArray) {
            out.write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nConnection: close\r\n\r\n".toByteArray())
            for (i in 0 until Int.MAX_VALUE) out.write(chunk)
        }

        @BeforeClass @JvmStatic fun startHostileServer() {
            fun html(path: String, body: () -> String) { routes[path] = { send(it, "200 OK", "text/html", body().toByteArray()) } }
            routes["/hang"] = { Thread.sleep(60_000) }
            routes["/endless"] = { pump(it, "text/html", "<p>volcano lava ".repeat(4096).toByteArray()) }
            routes["/redirect-loop"] = { send(it, "302 Found", "text/html", ByteArray(0), "Location: /redirect-loop\r\n") }
            routes["/challenge"] = { send(it, "202 Accepted", "text/html", "x".repeat(5000).toByteArray()) }
            routes["/binary"] = { send(it, "200 OK", "text/html", Random(1).nextBytes(500_000)) }
            routes["/direct.mp4"] = { pump(it, "video/mp4", ByteArray(65536)) }
            html("/huge-with-video") {
                "<html><head><title>Huge volcano page</title><meta property=\"og:video\" content=\"/v/eruption.mp4\"></head><body>" + "<div>lava</div>".repeat(1_500_000)
            }
            html("/deep-json") {
                "<html><head><script type=\"application/ld+json\">" + "[".repeat(200_000) + "]".repeat(200_000) + "</script>" +
                    "<script type=\"application/ld+json\">" + "{\"a\":".repeat(5000) + "{\"@type\":\"VideoObject\",\"name\":\"deep\",\"contentUrl\":\"/deep.mp4\"}" + "}".repeat(5000) + "</script>" +
                    "<meta property=\"og:video\" content=\"/v/fine.mp4\"></head></html>"
            }
            socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            base = "http://127.0.0.1:${socket.localPort}"
            val workers = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
            Thread {
                while (!socket.isClosed) {
                    val c = try { socket.accept() } catch (e: Exception) { break }
                    workers.execute {
                        c.use { sock ->
                            try {
                                val reader = sock.getInputStream().bufferedReader()
                                val path = reader.readLine()?.split(' ')?.getOrNull(1)?.substringBefore('?') ?: return@use
                                while (!reader.readLine().isNullOrEmpty()) { /* skip headers */ }
                                val out = sock.getOutputStream().buffered()
                                (routes[path] ?: { o -> send(o, "404 Not Found", "text/plain", ByteArray(0)) })(out)
                                out.flush()
                            } catch (e: Exception) { /* client hung up */ }
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
        }

        @AfterClass @JvmStatic fun stop() { socket.close() }

        private fun real(name: String) = GZIPInputStream(File(REAL, "$name.html.gz").inputStream()).bufferedReader().readText()
    }

    private fun timed(limitMs: Long, what: String, block: () -> Unit) {
        val t0 = System.currentTimeMillis()
        block()
        val ms = System.currentTimeMillis() - t0
        assertTrue("$what took $ms ms", ms < limitMs)
    }

    // ------------------------------------------------------------ real pages

    @Test fun duckDuckGoBotCheckFallsBackToBrowser() {
        var browserCalls = 0
        val s = Search({ Http.getHtml("$base/challenge") }, { browserCalls++; "" })
        assertEquals(0, s.duckduckgo("volcano", 0).size)
        assertEquals("202 challenge page must trigger the browser fallback", 1, browserCalls)
        assertEquals("", Http.getHtml("$base/challenge"))
    }

    // ------------------------------------------------------------ pathological input

    @Test fun pathologicalHtmlParsesQuickly() {
        val cases = mapOf(
            "unclosed <video> x20k" to "<video ".repeat(20_000) + "x".repeat(2_000_000),
            "unclosed <script> x20k" to "<script>".repeat(20_000) + "x".repeat(2_000_000),
            "unclosed <meta x50k" to "<meta property=\"og:video\" content=\"".repeat(50_000),
            "3 MB of attributes" to "<div " + "a=\"b\" ".repeat(500_000) + ">",
            "a million <" to "<".repeat(1_000_000),
        )
        for ((name, html) in cases) timed(5000, name) { Inspector.candidatesFromProbe(Inspector.parseStaticHtml(html, "https://x.test/")) }
    }

    @Test fun deeplyNestedJsonDoesNotCrash() {
        // Used to throw StackOverflowError (an Error, not an Exception) → app crash.
        val html = "<html><head><script type=\"application/ld+json\">" + "[".repeat(100_000) + "]".repeat(100_000) + "</script>" +
            "<script type=\"application/ld+json\">" + "{\"a\":".repeat(3000) + "{}" + "}".repeat(3000) + "</script></head></html>"
        val p = Inspector.parseStaticHtml(html, "https://x.test/")
        Inspector.finalize(Hit("https://x.test/"), p, Inspector.candidatesFromProbe(p), prober = null)
        Inspector.parseProbeJson("{\"ld\":[\"" + "[".repeat(50_000) + "\"]}")
        Inspector.parseProbeJson("[".repeat(100_000))
    }

    @Test fun fuzzEveryParser() {
        val pieces = listOf("<video", "<video src=\"/a.mp4\">", "</video>", "<source src=\"x.m3u8\" type=\"application/x-mpegURL\">",
            "<meta property=\"og:video\" content=\"", "\"", "'", ">", "<", "<script type=\"application/ld+json\">", "</script>",
            "{\"@type\":\"VideoObject\",\"contentUrl\":", "[", "]", "{", "}", "&amp;", "&#x1F30B;", "&#99999999;",
            "<iframe src=\"https://www.youtube.com/embed/", "dQw4w9WgXcQ", "\"></iframe>", "<title>", "</title>", "volcano",
            "\u0000", "￿", "é", "🌋", " ", "\n", "<body>", "PT1H", "https://", "blob:", "\"videoRenderer\":{\"videoId\":\"",
            "\"murl\":\"", "\\", "\\\"", "<a href=\"/view_video.php?viewkey=", "<a href=\"/video-ab12/", "result__a", "%", "%%", "http://[::1", "//")
        val rnd = Random(42)
        fun junk(n: Int) = buildString { while (length < n) append(pieces[rnd.nextInt(pieces.size)]) }
        repeat(3000) { i ->
            val page = junk(200 + rnd.nextInt(6000))
            val p = Inspector.parseStaticHtml(page, "https://fuzz.test/p")
            val v = Inspector.finalize(Hit("https://fuzz.test/p"), p, Inspector.candidatesFromProbe(p), prober = null)
            v.forEach { it.score = Ranker.score(junk(30), it) }
            Ranker.order(v)
            Inspector.parseProbeJson(page)
            Search.cleanHitUrl(junk(80)); embedFromUrl(junk(60)); parseDuration(junk(10)); absolute(junk(40), "https://x.test/")
            if (i % 20 == 0) {
                val s = Search({ page })
                s.duckduckgo("v", 0); Search.SITES.keys.forEach { Search.parseSiteLinks(it, page) }
            }
        }
    }

    // ------------------------------------------------------------ hostile server

    @Test fun hostileServerNeverHangsOrExplodes() {
        val pool = Executors.newFixedThreadPool(8)
        val jobs = listOf("/hang", "/endless", "/redirect-loop", "/binary", "/challenge", "/huge-with-video", "/deep-json", "/direct.mp4").associateWith { path ->
            pool.submit<Pair<List<Video>, Long>> {
                val t0 = System.currentTimeMillis()
                Inspector.staticStage(Hit("$base$path", title = "volcano")).videos to System.currentTimeMillis() - t0
            }
        }
        val out = jobs.mapValues { it.value.get(40, TimeUnit.SECONDS) }
        pool.shutdownNow()
        for ((path, r) in out) println("    %-18s %5d ms  %s".format(path, r.second, r.first.map { it.src.removePrefix(base) }))
        for ((path, r) in out) assertTrue("$path took ${r.second} ms", r.second < 15_000)
        assertEquals("/v/eruption.mp4", out.getValue("/huge-with-video").first.single().src.removePrefix(base))
        assertEquals("/v/fine.mp4", out.getValue("/deep-json").first.single().src.removePrefix(base))
        assertEquals("file", out.getValue("/direct.mp4").first.single().kind)
        for (p in listOf("/hang", "/endless", "/redirect-loop", "/binary", "/challenge")) assertTrue(p, out.getValue(p).first.isEmpty())
        // Endless page through the search fetcher: capped, returns promptly.
        timed(15_000, "endless getHtml") { assertTrue(Http.getHtml("$base/endless").length <= 4_000_000) }
    }
}
