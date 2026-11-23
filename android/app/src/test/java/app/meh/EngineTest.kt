package app.meh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {

    @Test fun recognisesEmbeddablePlayers() {
        assertEquals(Embed("youtube", "dQw4w9WgXcQ"), embedFromUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=3"))
        assertEquals(Embed("youtube", "dQw4w9WgXcQ"), embedFromUrl("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(Embed("youtube", "dQw4w9WgXcQ"), embedFromUrl("https://m.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals(Embed("vimeo", "76979871"), embedFromUrl("https://vimeo.com/76979871"))
        assertEquals(Embed("dailymotion", "x8abc12"), embedFromUrl("https://www.dailymotion.com/video/x8abc12"))
        assertNull(embedFromUrl("https://example.com/watch?v=x"))
    }

    @Test fun durationsAndAds() {
        assertEquals(3723.0, parseDuration("PT1H2M3S")!!, 0.0)
        assertEquals(253.0, parseDuration("4:13")!!, 0.0)
        assertEquals(95.0, parseDuration("95")!!, 0.0)
        assertTrue(isAdUrl("https://pubads.g.doubleclick.net/x.mp4"))
        assertTrue(isAdUrl("https://cdn.site.com/ads/preroll.mp4"))
        assertTrue(!isAdUrl("https://cdn.site.com/videos/volcano.mp4"))
        assertEquals("hls", mediaKindFromUrl("https://x.com/a/master.m3u8?t=1"))
        assertEquals("file", mediaKindFromUrl("https://x.com/a/clip", "video/mp4"))
    }

    @Test fun decodesSearchRedirects() {
        assertEquals("https://example.com/v?a=1", Search.cleanHitUrl("//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fv%3Fa%3D1&amp;rut=abc"))
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("https://news.example.org/story".toByteArray())
        assertEquals("https://news.example.org/story", Search.cleanHitUrl("https://www.bing.com/ck/a?!&&p=xyz&u=a1$b64&ntb=1"))
        assertNull(Search.cleanHitUrl("https://www.bing.com/images/search?q=x"))
    }

    @Test fun parsesSearchPages() {
        val pad = "<!--" + "x".repeat(2500) + "-->"
        val pages = mapOf(
            "html.duckduckgo.com" to """$pad<div class="result results_links"><h2><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fa.com%2Fvolcano">Volcano <b>video</b></a></h2><a class="result__snippet" href="#">Lava!</a></div>""",
            "www.bing.com/search" to """$pad<li class="b_algo"><h2><a href="https://b.org/eruption" h="ID=1">Eruption</a></h2><p>Big one</p></li>""",
            "www.bing.com/videos" to """$pad<div class="vrhdata" vrhm='{&quot;du&quot;:&quot;4:13&quot;,&quot;murl&quot;:&quot;https://c.net/clip/9&quot;,&quot;vt&quot;:&quot;Volcano clip&quot;}'></div>""",
        )
        val s = Search({ url -> pages.entries.firstOrNull { url.contains(it.key) }?.value ?: "" })
        s.duckduckgo("volcano", 0)[0].let { assertEquals(listOf("https://a.com/volcano", "Volcano video", "Lava!"), listOf(it.url, it.title, it.snippet)) }
        assertEquals("https://b.org/eruption", s.bing("volcano", 0)[0].url)
        s.bingVideos("volcano", 0)[0].let { assertEquals("https://c.net/clip/9", it.url); assertEquals("Volcano clip", it.title); assertEquals(253.0, it.duration!!, 0.0) }

        val merged = s.searchWeb("volcano", 0)
        assertEquals(3, merged.size)
        assertEquals(setOf("DuckDuckGo", "Bing", "Bing Videos"), merged.map { it.engine }.toSet())
    }

    private fun inspect(html: String, url: String = "https://site.com/page"): List<Video> {
        val p = Inspector.parseStaticHtml(html, url)
        return Inspector.finalize(Hit(url), p, Inspector.candidatesFromProbe(p), prober = null)
    }

    @Test fun findsPlainVideoTag() {
        val v = inspect("""<html><head><title>Volcano eruption caught on camera</title><meta property="og:title" content="Volcano eruption caught on camera"></head>
            <body><video controls src="/media/eruption.mp4"></video></body></html>""")
        assertEquals(1, v.size)
        assertEquals("file", v[0].kind)
        assertEquals("https://site.com/media/eruption.mp4", v[0].src)
        assertEquals("Volcano eruption caught on camera", v[0].title)
    }

    @Test fun findsJsonLdVideoObject() {
        val v = inspect("""<html><head><script type="application/ld+json">{"@context":"https://schema.org","@graph":[{"@type":"VideoObject","name":"How volcanoes form",
            "description":"Plate tectonics","duration":"PT30S","contentUrl":"https://cdn.site.com/v/form.m3u8","thumbnailUrl":["https://cdn.site.com/t.jpg"]}]}</script></head><body></body></html>""")
        assertEquals("hls", v[0].kind)
        assertEquals("How volcanoes form", v[0].title)
        assertEquals(30.0, v[0].duration!!, 0.0)
        assertEquals("https://cdn.site.com/t.jpg", v[0].thumbnail)
    }

    @Test fun findsEmbeddedYouTubeAndOgVideo() {
        assertEquals("vimeo", inspect("""<html><body><iframe src="https://player.vimeo.com/video/76979871"></iframe></body></html>""")[0].provider)
        val og = inspect("""<html><head><meta property="og:video:secure_url" content="https://v.site.com/a.mp4"><meta property="og:video:type" content="video/mp4"></head></html>""")
        assertEquals("https://v.site.com/a.mp4", og[0].src)
    }

    @Test fun excludesYouTubeAndTikTokEverywhere() {
        // A page whose only video is YouTube yields nothing…
        assertTrue(inspect("""<html><head><meta property="og:video:url" content="https://www.youtube.com/embed/dQw4w9WgXcQ"></head>
            <body><iframe src="https://www.youtube.com/embed/dQw4w9WgXcQ"></iframe></body></html>""").isEmpty())
        // …an article with a YouTube embed and its own video plays its own video…
        val mixed = inspect("""<html><body><iframe src="https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ"></iframe>
            <video controls width="640" height="360" src="/media/report.webm"></video></body></html>""")
        assertEquals("https://site.com/media/report.webm", mixed.single().src)
        // …and YouTube links are dropped from search results.
        val page = "<!--" + "x".repeat(2500) + "-->" + listOf("https://www.youtube.com/watch?v=abcdefghijk", "https://youtu.be/abcdefghijk",
            "https://m.youtube.com/watch?v=zzzzzzzzzzz", "https://www.tiktok.com/@nasa/video/7234567890123456789",
            "https://vm.tiktok.com/ZMabc123/", "https://news.example.org/volcano-video").joinToString("") { "<li class=\"b_algo\"><h2><a href=\"$it\">r</a></h2></li>" }
        val s = Search({ url -> if ("bing.com/search" in url) page else "" })
        assertEquals(listOf("https://news.example.org/volcano-video"), s.searchWeb("volcano", 0).map { it.url })
        assertTrue(Video("embed", "https://www.youtube.com/watch?v=abcdefghijk", provider = "youtube", id = "abcdefghijk").blocked)
        assertTrue(Video("file", "https://rr1---sn-x.googlevideo.com/videoplayback?id=1").blocked)
        assertTrue(!Video("file", "https://cdn.example.org/v.mp4", pageUrl = "https://example.org/").blocked)
        // TikTok: pages, embeds and its video servers
        assertTrue(inspect("""<html><head><meta property="og:video" content="https://v16-webapp.tiktokcdn.com/video/volcano.mp4"></head>
            <body><iframe src="https://www.tiktok.com/embed/v2/7234567890123456789"></iframe>
            <video width="640" height="360" src="https://v16-webapp.tiktokcdn.com/video/volcano.mp4"></video></body></html>""").isEmpty())
        assertTrue(Video("file", "https://v19.tiktokcdn-us.com/a/b.mp4").blocked)
        assertTrue(Video("file", "https://cdn.example.org/v.mp4", pageUrl = "https://www.tiktok.com/@x/video/1").blocked)
        assertTrue(!Video("file", "https://notiktok.com.example.org/v.mp4").blocked)
    }

    @Test fun ignoresAdsAndBlankClips() {
        assertTrue(inspect("""<html><body><video src="/ads/preroll.mp4"></video></body></html>""").isEmpty())
        assertTrue(inspect("""<html><body><p>just text</p></body></html>""").isEmpty())
        // Only near-empty clips are rejected by length now (no 20/45 s rules).
        val ld = """<script type="application/ld+json">{"@type":"VideoObject","name":"blank","duration":"PT3S","contentUrl":"/t.mp4"}</script>"""
        assertTrue(inspect("<html><head>$ld</head></html>").isEmpty())
        assertEquals(1, inspect("<html><head>${ld.replace("PT3S", "PT8S")}</head></html>").size)
        // A <video> inside an ad slot in the plain HTML is an ad; one after the slot closes isn't.
        assertTrue(inspect("""<div class="ima-ad-container"><div><video width="640" height="360" src="/s3.mp4"></video></div></div>""").isEmpty())
        assertEquals("https://site.com/real.mp4", inspect("""<div class="ad-slot"><img src="x"></div><div class="player"><video src="/real.mp4"></video></div>""").single().src)
    }

    @Test fun parsesLiveDomProbe() {
        val json = """{"url":"https://s.com/p","title":"Cam","metas":{"og:title":"Live cam"},"ld":[],"videos":[{"src":"https://s.com/v.webm","sources":[],"poster":"","duration":120,"w":640,"h":360}],"iframes":[],"text":"hello"}"""
        val p = Inspector.parseProbeJson(json)!!
        val v = Inspector.finalize(Hit("https://s.com/p"), p, Inspector.candidatesFromProbe(p), prober = null)
        assertEquals("https://s.com/v.webm", v[0].src)
        assertEquals("Live cam", v[0].title)
        assertEquals(120.0, v[0].duration!!, 0.0)
        // tiny on-page player = ad/teaser
        val small = Inspector.parseProbeJson(json.replace("\"w\":640", "\"w\":120"))!!
        assertTrue(Inspector.finalize(Hit("https://s.com/p"), small, Inspector.candidatesFromProbe(small), prober = null).isEmpty())
    }

    @Test fun ranksRelevantVideosHigherAndSpreadsSites() {
        val a = Video("file", "https://a.com/1.mp4", title = "Huge volcano eruption filmed", pageUrl = "https://a.com/x")
        val b = Video("file", "https://b.com/1.mp4", title = "Cooking pasta at home", pageUrl = "https://b.com/x")
        a.score = Ranker.score("volcano eruption", a); b.score = Ranker.score("volcano eruption", b)
        assertTrue("${a.score} vs ${b.score}", a.score > b.score + 30)

        val same = (1..3).map { Video("file", "https://same.com/$it.mp4", pageUrl = "https://same.com/$it", score = 80.0 - it) }
        val other = Video("file", "https://other.com/1.mp4", pageUrl = "https://other.com/1", score = 75.0)
        val order = Ranker.order(same + other)
        assertEquals("https://same.com/1.mp4", order[0].src)
        assertEquals("https://other.com/1.mp4", order[1].src) // spread out, even though same.com/2 scored higher
    }
}
