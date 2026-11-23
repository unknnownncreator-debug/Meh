package app.meh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RedgifsTest {
    @Test fun playsTheCopyWithSound() {
        assertEquals("https://media.redgifs.com/AbleGiddyCanary.mp4", withSound("https://media.redgifs.com/AbleGiddyCanary-silent.mp4"))
        assertEquals("https://media.redgifs.com/AbleGiddyCanary-mobile.mp4", withSound("https://media.redgifs.com/AbleGiddyCanary-mobile.mp4"))
        assertEquals("only RedGIFs files", "https://cdn.example.org/clip-silent.mp4", withSound("https://cdn.example.org/clip-silent.mp4"))

        // A watch page shaped like RedGIFs' real ones: it declares the silent copy, plus its player page.
        val watch = """<html><head><title>Clip</title><meta property="og:type" content="video/other">
          <meta property="og:video" content="https://media.redgifs.com/AbleGiddyCanary-silent.mp4"><meta property="og:video:type" content="video/mp4">
          <meta property="og:video:iframe" content="https://www.redgifs.com/ifr/ablegiddycanary"><meta name="twitter:player" content="https://www.redgifs.com/ifr/ablegiddycanary">
          <script type="application/ld+json">{"@type":"SocialMediaPosting","video":{"@type":"VideoObject","contentUrl":"https://media.redgifs.com/AbleGiddyCanary-silent.mp4"}}</script></head></html>"""
        val cands = Inspector.candidatesFromProbe(Inspector.parseStaticHtml(watch, "https://www.redgifs.com/watch/ablegiddycanary"))
        assertTrue(cands.map { it.src }.toString(), cands.isNotEmpty() && cands.all { it.src == "https://media.redgifs.com/AbleGiddyCanary.mp4" })
    }

    private val data = """{"gifs":[
        {"id":"AbleGiddyCanary","type":1,"duration":33.6,"description":"Volcano clip","tags":["Nature"],"urls":{"hd":"https://media.redgifs.com/AbleGiddyCanary.mp4"}},
        {"id":"StillPicture","type":2,"duration":0,"urls":{"hd":"https://media.redgifs.com/StillPicture.jpg"}},
        {"id":"bad id!","type":1,"urls":{"hd":"x"}},
        {"id":"NoTitle","type":1,"duration":12,"tags":["Lava","Night"],"urls":{"hd":"https://media.redgifs.com/NoTitle.mp4"}}]}"""

    @Test fun parsesSearchResults() {
        assertEquals(
            listOf(Triple("https://www.redgifs.com/watch/ablegiddycanary", "Volcano clip", 33.6), Triple("https://www.redgifs.com/watch/notitle", "Lava Night", 12.0)),
            Search.parseRedgifs(data).map { Triple(it.url, it.title, it.duration) },
        )
        assertTrue(Search.parseRedgifs("not json").isEmpty())
    }

    @Test fun guestTokenReusedAndRenewedOnce() {
        val calls = mutableListOf<String>()
        var tokenNo = 0
        var searches = 0
        val s = Search({ "" }, null) { url, headers ->
            calls += url.substringBefore('?')
            when {
                url.endsWith("/v2/auth/temporary") -> 200 to """{"token":"t${++tokenNo}"}"""
                "/v2/gifs/search" in url -> { searches++; if (headers["Authorization"] == "Bearer t1" && searches == 2) 401 to "" else 200 to data }
                else -> 404 to ""
            }
        }
        assertEquals(2, s.redgifs("volcano", 0).size)
        assertEquals("refused token renewed", 2, s.redgifs("volcano", 0).size)
        assertEquals("token fetched once, renewed once", 2, calls.count { it.endsWith("/temporary") })
    }

    @Test fun switchedOffMeansNotSearchedAndFiltered() {
        val pad = "<!--" + "x".repeat(2500) + "-->"
        val page = pad + "<li class=\"b_algo\"><h2><a href=\"https://www.redgifs.com/watch/x\">r</a></h2></li><li class=\"b_algo\"><h2><a href=\"https://news.example.org/v\">n</a></h2></li>"
        var apiCalls = 0
        val s = Search({ url -> if ("bing.com/search" in url) page else "" }, null) { _, _ -> apiCalls++; 200 to """{"token":"t"}""" }
        assertEquals(listOf("https://news.example.org/v"), s.searchWeb("volcano", 0).map { it.url })
        assertEquals("RedGIFs not contacted while off", 0, apiCalls)
        assertTrue(s.searchWeb("volcano", 0, includeRedgifs = true).any { it.url == "https://www.redgifs.com/watch/x" })
    }
}
