package app.meh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackTest {
    @Test fun appPlayerThenChromePlayerThenSitePage() {
        val page = "https://news.example.org/story"
        assertEquals("web", SkipReason.nextMode("exo", "file", page, 4003))   // can't decode → Chrome's decoders
        assertEquals("web", SkipReason.nextMode("exo", "hls", page, 2004))    // refused → Chrome's network/cookies
        assertEquals("web", SkipReason.nextMode("exo", "file", page))         // never started
        assertEquals("page", SkipReason.nextMode("web", "file", page))
        assertNull("site page was the last resort", SkipReason.nextMode("page", "file", page))
        assertNull("no page to open", SkipReason.nextMode("web", "file", ""))
        assertNull("copy-protected: no player helps", SkipReason.nextMode("exo", "file", page, 6001))
        assertNull("Vimeo/Dailymotion have their own players", SkipReason.nextMode("exo", "embed", page))
        assertEquals("exo", Video("file", "https://x/v.mp4").mode)
    }

    @Test fun chromePlayerPageIsSafeAndComplete() {
        val tricky = "https://cdn.example.org/v.mp4?a=1&b=\"</script><script>alert(1)</script>"
        val html = PlayerPages.webPlayer(tricky, hls = false, token = 42)
        assertTrue("source is a quoted JS string with every < escaped", html.contains("SRC=\"https://cdn.example.org/v.mp4?a=1&b=\\\"\\u003c\\/script>\\u003cscript>alert(1)\\u003c\\/script>\""))
        assertEquals("only our own script tags", 2, Regex("<script>|</script>").findAll(html).count())
        assertTrue(html.contains("var T=42") && html.contains("Meh.started(T)") && html.contains("Meh.ended(T)") && html.contains("Meh.failed(T"))
        assertTrue("HLS only loads the bundled library when needed", PlayerPages.webPlayer("https://x/a.m3u8", true, 1).contains("HLS=true"))
        assertTrue(PlayerPages.webPlayer("https://x/a.m3u8", true, 1).contains("'${PlayerPages.HLS_JS_PATH}'"))
    }

    /** Writes the exact pages the app generates, for test/player-pages.js to run in real Chromium. */
    @Test fun writePlayerPagesForBrowserCheck() {
        val dir = java.io.File("build/player-pages").apply { mkdirs() }
        java.io.File(dir, "web-file.html").writeText(PlayerPages.webPlayer("/media/eruption.webm", hls = false, token = 11))
        java.io.File(dir, "web-hls.html").writeText(PlayerPages.webPlayer("/media/stream/index.m3u8", hls = true, token = 12))
        java.io.File(dir, "web-broken.html").writeText(PlayerPages.webPlayer("/media/does-not-exist.mp4", hls = false, token = 13))
        java.io.File(dir, "page-script.js").writeText(PlayerPages.pageScript(14))
    }

    @Test fun sitePageScriptReportsAndEnlarges() {
        val js = PlayerPages.pageScript(7)
        assertTrue(js.trimEnd().endsWith("})(7);"))
        assertTrue(js.contains("Meh.started(T)") && js.contains("Meh.ended(T)") && js.contains("position:fixed"))
        assertTrue("runs once per page", js.contains("window.__mehPage"))
    }
}
