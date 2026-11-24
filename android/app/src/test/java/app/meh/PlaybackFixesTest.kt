package app.meh

import okhttp3.Request
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors

/** The three playback fixes: shared cookies, per-site memory, shorter start limits. */
class PlaybackFixesTest {
    companion object {
        private lateinit var socket: ServerSocket
        private lateinit var base: String

        /** A site whose video only plays for the visitor that loaded its page (session cookie). */
        @BeforeClass @JvmStatic fun start() {
            socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            base = "http://127.0.0.1:${socket.localPort}"
            val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
            Thread {
                while (!socket.isClosed) {
                    val c = try { socket.accept() } catch (e: Exception) { break }
                    pool.execute {
                        c.use { sock ->
                            val r = sock.getInputStream().bufferedReader()
                            val path = r.readLine()?.split(' ')?.getOrNull(1) ?: return@use
                            var cookie = ""
                            while (true) { val h = r.readLine() ?: break; if (h.isEmpty()) break; if (h.lowercase().startsWith("cookie:")) cookie = h.substring(7).trim() }
                            val out = sock.getOutputStream()
                            val (status, extra, body) = when {
                                path.startsWith("/page") -> Triple("200 OK", "Set-Cookie: sess=abc123; Path=/\r\n", "<html>page</html>")
                                path.startsWith("/video") && "sess=abc123" in cookie -> Triple("200 OK", "", "VIDEO")
                                else -> Triple("403 Forbidden", "", "no session")
                            }
                            out.write("HTTP/1.1 $status\r\nContent-Type: text/html\r\nContent-Length: ${body.length}\r\n${extra}Connection: close\r\n\r\n$body".toByteArray())
                            out.flush()
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
        }

        @AfterClass @JvmStatic fun stop() = socket.close()
    }

    private fun get(url: String) = Http.client.newCall(Request.Builder().url(url).build()).execute().use { it.code to it.body.string() }

    @Test fun sessionCookieFromThePageIsSentWithTheVideo() {
        SharedCookies.clearMemory()
        assertEquals("without the page's cookie the site refuses", 403, get("$base/video").first)
        Http.getHtml("$base/page")                                   // the app reads the page…
        assertEquals(200 to "VIDEO", get("$base/video"))             // …and the video request carries its cookie
        assertTrue("the video player gets the same cookie", SharedCookies.header("$base/video")!!.contains("sess=abc123"))
    }

    @Test fun ageConfirmationCookiesAreIncluded() {
        val ph = SharedCookies.header("https://www.pornhub.com/view_video.php?viewkey=1")!!
        assertTrue(ph, ph.contains("accessAgeDisclaimerPH=1") && ph.contains("age_verified=1"))
        assertTrue(SharedCookies.header("https://spankbang.com/1/video/x")!!.contains("age_pass=1"))
    }

    @Test fun perSiteMemory() {
        val store = HashMap<String, String>()
        var clock = 1_000_000L
        val m = SiteModes({ store[it] }, { k, v -> if (v == null) store.remove(k) else store[k] = v }, { clock })
        assertNull(m.get("www.example.com"))
        m.started("www.example.com", "page")
        assertEquals("remembered (www. ignored)", "page", m.get("example.com"))
        m.failed("example.com", "web")
        assertEquals("a different mode failing doesn't forget it", "page", m.get("example.com"))
        m.failed("example.com", "page")
        assertNull("forgotten once it stops working", m.get("example.com"))
        m.started("example.com", "web")
        m.started("example.com", "exo")
        assertNull("the app's own player works again → nothing to remember", m.get("example.com"))
        m.started("example.com", "web")
        clock += SiteModes.MAX_AGE + 1
        assertNull("expires after a week", m.get("example.com"))
    }

    @Test fun shorterStartLimits() {
        assertEquals(12_000L, SkipReason.startTimeout("file", "exo"))
        assertEquals(12_000L, SkipReason.startTimeout("hls", "web"))
        assertEquals(25_000L, SkipReason.startTimeout("file", "page"))
        assertEquals(20_000L, SkipReason.startTimeout("embed", "exo"))
    }
}
