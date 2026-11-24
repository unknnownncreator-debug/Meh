package app.meh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adult video sites: search pages read by video-address format (mirrors the desktop test). */
class SitesTest {
    private fun page(links: String) = "<html><body>$links<a href=\"/tags/dragon\">Dragon tag</a><a href=\"https://other.example/video-1/x\">elsewhere</a></body></html>"

    @Test fun searchPagesReadByVideoAddressFormat() {
        var hits = Search.parseSiteLinks("xvideos", page("<a href=\"/video.okbx123/big_red_dragon_fight\"><img alt=\"Big red dragon fight\"></a><a href=\"/video.okbx123/big_red_dragon_fight\" title=\"Big red dragon fight (HD)\">Big red…</a><a href=\"/video12345/old_style_dragon\">Old style dragon</a>"))
        assertEquals(listOf("https://www.xvideos.com/video.okbx123/big_red_dragon_fight" to "Big red dragon fight (HD)", "https://www.xvideos.com/video12345/old_style_dragon" to "Old style dragon"), hits.map { it.url to it.title })
        hits = Search.parseSiteLinks("xnxx", page("<a href=\"/video-1a2b3c/dragon_lair\">Dragon lair</a>"))
        assertEquals(listOf("https://www.xnxx.com/video-1a2b3c/dragon_lair"), hits.map { it.url })
        hits = Search.parseSiteLinks("pornhub", page("<a href=\"/view_video.php?viewkey=ph5f0abc123\"><img alt=\"Dragon night\"></a><a href=\"/view_video.php?viewkey=ph5f0abc123&amp;pkey=1\">playlist</a>"))
        assertEquals(listOf("https://www.pornhub.com/view_video.php?viewkey=ph5f0abc123" to "Dragon night"), hits.map { it.url to it.title })
        hits = Search.parseSiteLinks("xhamster", page("<a href=\"https://xhamster.com/videos/red-dragon-awakens-xh1A2b3\">Red dragon awakens</a><a href=\"https://xhamster.com/videos/teen-dragon-xh9\">x</a>"))
        assertEquals("minors-marked title dropped", listOf("https://xhamster.com/videos/red-dragon-awakens-xh1A2b3"), hits.map { it.url })
        hits = Search.parseSiteLinks("eporner", page("<a href=\"/video-AbC123dEf/dragon-fight/\"></a><a href=\"/hd-porn/XyZ987/another-dragon/\">Another dragon</a>"))
        assertEquals("no title → words from the address",
            listOf("https://www.eporner.com/video-AbC123dEf/dragon-fight/" to "dragon fight", "https://www.eporner.com/hd-porn/XyZ987/another-dragon/" to "Another dragon"), hits.map { it.url to it.title })
        hits = Search.parseSiteLinks("spankbang", page("<a href=\"/8xyz1/video/dragon+fight\">Dragon fight</a>"))
        assertEquals(listOf("https://spankbang.com/8xyz1/video/dragon+fight"), hits.map { it.url })
        assertEquals("redtube", listOf("https://www.redtube.com/40123456"), Search.parseSiteLinks("redtube", page("<a href=\"/40123456\" title=\"Red dragon\">x</a><a href=\"/redtube/dragon\">tag</a>")).map { it.url })
        assertEquals("youporn", listOf("https://www.youporn.com/watch/16012345/red-dragon-night/"), Search.parseSiteLinks("youporn", page("<a href=\"/watch/16012345/red-dragon-night/\">Red dragon night</a>")).map { it.url })
        assertEquals("porntrex", listOf("https://www.porntrex.com/video/2012345/red-dragon/"), Search.parseSiteLinks("porntrex", page("<a href=\"https://www.porntrex.com/video/2012345/red-dragon/\">Red dragon</a>")).map { it.url })
        assertEquals("hqporner", listOf("https://hqporner.com/hdporn/112233-red_dragon.html"), Search.parseSiteLinks("hqporner", page("<a href=\"/hdporn/112233-red_dragon.html\">Red dragon</a>")).map { it.url })
        assertEquals("youjizz", listOf("https://www.youjizz.com/videos/red-dragon-night-12345678.html"), Search.parseSiteLinks("youjizz", page("<a href=\"/videos/red-dragon-night-12345678.html\">Red dragon night</a>")).map { it.url })
        assertEquals("fourtube", listOf("https://www.4tube.com/videos/412345/red-dragon"), Search.parseSiteLinks("fourtube", page("<a href=\"/videos/412345/red-dragon\">Red dragon</a>")).map { it.url })
        assertEquals("tnaflix", listOf("https://www.tnaflix.com/amateur-porn/red-dragon-night/video1234567"), Search.parseSiteLinks("tnaflix", page("<a href=\"/amateur-porn/red-dragon-night/video1234567\">Red dragon night</a>")).map { it.url })
        assertEquals("gelbooru", listOf("https://gelbooru.com/index.php?page=post&s=view&id=9876543"), Search.parseSiteLinks("gelbooru", page("<a href=\"index.php?page=post&amp;s=view&amp;id=9876543\"><img alt=\"dragon animated video\"></a>")).map { it.url })
        hits = Search.parseSiteLinks("rule34us", page("<a href=\"index.php?r=posts/view&amp;id=123\"><img alt=\"dragon animated video\"></a>"))
        assertEquals(listOf("https://rule34.us/index.php?r=posts/view&id=123"), hits.map { it.url })
    }

    @Test fun searchAddresses() {
        assertEquals("https://www.pornhub.com/video/search?search=red+dragon&page=1", Search.SITES.getValue("pornhub").search("red dragon", 0))
        assertEquals("https://www.xvideos.com/?k=red+dragon&p=2", Search.SITES.getValue("xvideos").search("red dragon", 2))
        assertEquals("https://www.eporner.com/search/red-dragon/1/", Search.SITES.getValue("eporner").search("Red Dragon!", 0))
        val r34 = Search.SITES.getValue("rule34us").search("dragon", 0)
        assertTrue(r34, Regex("q=video\\+dragon\\+-loli\\+.*-pmv\\+-hmv\\+-male_focus\\+-yaoi&page=0$").containsMatchIn(r34))
    }

    @Test fun minorsQueriesNeverSentAndNewSitesAreAdult() {
        var asked = 0
        val s = Search({ asked++; "" }, null) { _, _ -> asked++; 404 to "" }
        for (key in Search.SITES.keys) assertTrue(key, s.site(key, "loli dragon", 0).isEmpty())
        assertEquals(0, asked)
        for (u in listOf("https://www.redtube.com/1", "https://www.youporn.com/watch/1/x/", "https://hqporner.com/hdporn/1-x.html", "https://gelbooru.com/index.php", "https://www.xvideos.com/video.x/y", "https://www.pornhub.com/view_video.php?viewkey=1", "https://ev.phncdn.com/hls/x.m3u8",
                "https://xhamster.com/videos/x-1", "https://spankbang.com/1/video/x", "https://rule34.us/index.php", "https://www.eporner.com/video-1/x/"))
            assertTrue(u, Adult.isAdultSite(u))
        assertEquals("accessAgeDisclaimerPH=1; accessAgeDisclaimerUK=1; age_verified=1; platform=pc", Http.cookieFor("https://www.pornhub.com/view_video.php?viewkey=1"))
    }

    @Test fun videoLinksSetUpByPageScripts() {
        val html = """<html><head><title>Big dragon fight</title></head><body><script>
          html5player.setVideoUrlHigh('https://cdn.example-cdn.com/videos/mp4/a/b/c.mp4?e=1&h=2');
          html5player.setVideoHLS('https://hls.example-cdn.com/videos/hls/a/b/hls.m3u8?e=1');
          var preview = "https:\/\/cdn.example.com\/videopreview\/x.mp4";
          var mediaDefinitions = [{"videoUrl":"https:\/\/ev.example.com\/hls\/master.m3u8?validfrom=1&ttl=2"}];
          </script><script src="https://cdn.example.com/player.js"></script></body></html>""".replace("\\u0026", "\\\\u0026")
        val p = Inspector.parseStaticHtml(html, "https://www.example.com/video.abc/x")
        assertEquals("escaped links decoded, preview skipped",
            listOf("https://cdn.example-cdn.com/videos/mp4/a/b/c.mp4?e=1&h=2", "https://hls.example-cdn.com/videos/hls/a/b/hls.m3u8?e=1", "https://ev.example.com/hls/master.m3u8?validfrom=1&ttl=2"), p.scriptMedia)
        assertEquals(listOf("script" to "file", "script" to "hls", "script" to "hls"), Inspector.candidatesFromProbe(p).map { it.origin to it.kind })
    }
}
