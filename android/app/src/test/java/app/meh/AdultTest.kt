package app.meh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/** Adult sites: rule34 parsers, the opt-in switch, and content depicting minors ALWAYS excluded. */
class AdultTest {
    @Test fun minorsTermsIncludingCompounds() {
        for (t in listOf("loli", "young_link", "Shota boy", "aged-down", "middle school", "toddlercon", "little_sister", "teen"))
            assertTrue(t, Adult.depictsMinors(t))
        for (t in listOf("overwatch", "mercy", "volcano eruption", "kidnapped", "skid", "babylon", "minority report", "cuba"))
            assertFalse(t, Adult.depictsMinors(t))
    }

    @Test fun parsers() {
        val rv = """<div class="item thumb video_1"><a class="th js-open-popup" href="https://rule34video.com/video/101/dragon-fight/"><img alt="Dragon fight"><div class="time">13:14</div></a><div class="thumb_title">Dragon fight</div></div>""" +
            """<div class="item thumb video_2"><a class="th" href="https://rule34video.com/video/102/young-link-x/"><div class="time">2:00</div><div class="thumb_title">Young Link x</div></a></div>"""
        assertEquals(listOf(Triple("https://rule34video.com/video/101/dragon-fight/", "Dragon fight", 794.0), Triple("https://rule34video.com/video/102/young-link-x/", "Young Link x", 120.0)),
            Search.parseRule34video(rv).map { Triple(it.url, it.title, it.duration) })
        val ph = "<div class='shm-thumb thumb' data-mime='video/mp4' data-tags='dragon animated sound' data-post-id='1'><a class='shm-thumb-link' href='/post/view/1'></a></div>" +
            "<div class='shm-thumb thumb' data-mime='image/png' data-tags='dragon' data-post-id='2'><a href='/post/view/2'></a></div>" +
            "<div class='shm-thumb thumb' data-mime='video/webm' data-tags='dragon loli animated' data-post-id='3'><a href='/post/view/3'></a></div>"
        assertEquals("videos only, minors-tagged dropped", listOf("https://rule34.paheal.net/post/view/1"), Search.parsePaheal(ph).map { it.url })
        val xx = """<span id="s7" class="thumb"><a id="p7" href="index.php?page=post&amp;s=view&amp;id=7"><img alt="dragon animated video" title=" dragon animated video score:9 rating:explicit"></a></span>""" +
            """<span id="s8" class="thumb"><a id="p8" href="index.php?page=post&amp;s=view&amp;id=8"><img title="dragon shota video"></a></span>"""
        assertEquals(listOf("https://rule34.xxx/index.php?page=post&s=view&id=7" to "dragon animated video"), Search.parseRule34xxx(xx).map { it.url to it.title })
    }

    @Test fun queriesCarryMinusTagsAndMinorsQueriesAreNeverSent() {
        val asked = mutableListOf<String>()
        val s = Search({ url -> asked += url; "" }, null) { url, _ -> asked += url; 404 to "" }
        s.paheal("dragon fight", 0); s.rule34xxx("dragon", 1); s.rule34video("dragon fight", 0)
        assertTrue(asked[0], asked[0].startsWith("https://rule34.paheal.net/post/list/animated%20dragon%20fight%20-loli%20-lolicon") && asked[0].endsWith("/1"))
        assertTrue(asked[1], asked[1].startsWith("https://rule34.xxx/index.php?page=post&s=list&tags=video+dragon+-loli+") && asked[1].endsWith("&pid=42"))
        assertEquals("https://rule34video.com/search/dragon-fight/", asked[2])
        asked.clear()
        for (e in listOf(s::redgifs, s::rule34video, s::paheal, s::rule34xxx)) assertTrue(e("loli dragon", 0).isEmpty())
        assertEquals("nothing sent to adult sites for such a query", 0, asked.size)
    }

    @Test fun switchAndFinalGate() {
        val pad = "<!--" + "x".repeat(2500) + "-->"
        val page = pad + "<div class=\"result\"><a class=\"result__a\" href=\"https://rule34video.com/video/5/dragon/\">Dragon</a></div>" +
            "<div class=\"result\"><a class=\"result__a\" href=\"https://rule34video.com/video/6/loli-x/\">x</a></div><div class=\"result\"><a class=\"result__a\" href=\"https://news.example.org/v\">n</a></div>"
        val s = Search({ url -> if ("html.duckduckgo.com" in url) page else "" }, null) { _, _ -> 404 to "" }
        assertEquals(listOf("https://news.example.org/v"), s.searchWeb("dragon", 0).map { it.url })
        assertEquals(listOf("https://rule34video.com/video/5/dragon/", "https://news.example.org/v"),
            s.searchWeb("dragon", 0, includeRedgifs = true).map { it.url })

        val v = Video("file", "https://r34i.paheal-cdn.net/a.mp4", pageUrl = "https://rule34.paheal.net/post/view/1", title = "Dragon", pageText = "Tags: dragon animated")
        assertFalse(Adult.drop(v, null, true))
        assertTrue("switched off", Adult.drop(v, null, false))
        assertTrue("minors tag on the page", Adult.drop(v.copy(pageText = "Tags: dragon shotacon animated"), null, true))
        assertFalse("not an adult site", Adult.drop(v.copy(src = "https://cdn.example.org/a.mp4", pageUrl = "https://news.example.org/a"), null, false))
    }

    @Test fun kvsFileLinksWithTrailingSlash() {
        assertEquals("file", mediaKindFromUrl("https://rule34video.com/get_file/47/abc/1/2/3_720p.mp4/?v-acctoken=x"))
        assertEquals(null, mediaKindFromUrl("https://x/a.mp4x"))
    }

    /** Real pages saved during development (not in the repo): MEH_REAL_R34_DIR=… to run. */
    @Test fun realPagesWhenAvailable() {
        val dir = System.getenv("MEH_REAL_R34_DIR")?.let(::File)
        Assume.assumeTrue(dir != null && dir.isDirectory)
        val rv = Search.parseRule34video(File(dir, "rv-search.html").readText())
        assertTrue("${rv.size}", rv.size >= 20 && rv.all { it.title.isNotEmpty() && it.duration != null })
        assertTrue(Search.parsePaheal(File(dir, "ph.html").readText()).size >= 20)
        val p = Inspector.parseStaticHtml(File(dir, "rv-video.html").readText(), "https://rule34video.com/video/1/x/")
        val v = Inspector.finalize(Hit("https://rule34video.com/video/1/x/"), p, Inspector.candidatesFromProbe(p), prober = null)
        assertTrue(v.toString(), v.single().src.contains("/get_file/") && v.single().duration == 72.0)
    }
}
