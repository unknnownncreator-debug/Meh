package app.meh

import org.junit.Assert.assertTrue
import org.junit.Test

class SearchReportTest {
    @Test fun rendersPerSiteCountsHiddenAndFailures() {
        val r = SearchReport()
        r.reset("dragon fight")
        r.results("XVideos", 24); r.results("Eporner", 0)
        repeat(5) { r.inspected("XVideos", true) }; r.inspected("XVideos", false)
        repeat(4) { r.added("XVideos") }
        r.hidden("depicts minors (always excluded)"); r.hidden("music video"); r.hidden("music video")
        r.playFailed("xnxx.com", "the site refused to stream it (HTTP 403)")
        val t = r.render()
        assertTrue(t, t.contains("Search report for \"dragon fight\""))
        assertTrue(t, t.contains("XVideos: 24 results → 6 checked (1 without a video) → 4 added"))
        assertTrue(t, t.contains("Eporner: 0 results — blocked here, or the site changed"))
        assertTrue(t, t.contains("music video: 2"))
        assertTrue(t, t.contains("xnxx.com — the site refused to stream it (HTTP 403)"))
        r.reset("next")
        assertTrue("a new search starts a fresh report", !r.render().contains("XVideos"))
    }

    @Test fun strictMatchingOnlyForWebResults() {
        // A site's own search matched by tags: its result is kept even if the title lacks the words.
        assertTrue(Engine.GENERAL_ENGINE == "DuckDuckGo")
        assertTrue(!Ranker.matches("dragon fight", Video("file", "https://x/v.mp4", title = "Episode 12")))
    }
}
