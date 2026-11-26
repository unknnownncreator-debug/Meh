package app.meh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same cases as the desktop checks, so both versions behave identically. */
class StrictMatchTest {
    private fun m(q: String, title: String) = Ranker.matches(q, Video("file", "https://x/v.mp4", title = title))

    @Test fun everyWordMustBeThere() {
        val cases = listOf(
            Triple("volcano eruption", "Volcano eruption caught on camera", true),
            Triple("volcano eruption", "Top 10 volcano facts", false),
            Triple("volcano eruption", "Volcanoes erupting in Iceland", true),
            Triple("dragon", "Dragonball Z episode", false),
            Triple("dragon", "Red dragons fight", true),
            Triple("how volcanoes form", "How volcanoes form", true),
            Triple("big red dragon fight", "Big dragon fight", true),   // 4+ words: one may be missing
            Triple("big red dragon fight", "Big fight", false),
            Triple("\"red dragon\"", "A dragon that is red", false),     // quoted phrase must be exact
            Triple("\"red dragon\"", "The Red Dragon awakens", true),
            Triple("cat", "Concatenate tips", false),
        )
        for ((q, title, want) in cases) assertEquals("$q / $title", want, m(q, title))
    }

    @Test fun siteResultsNeedHalfTheWords() {
        fun l(q: String, title: String, tags: String = "") = Ranker.matchesLoosely(q, Video("file", "https://x/v.mp4", title = title), null, tags)
        assertEquals(true, l("red dragon fight", "Dragon fight in the cave"))
        assertEquals(false, l("red dragon fight", "Hot summer compilation"))
        assertEquals("the site's tag list counts", true, l("red dragon fight", "Episode 12", "Tags: dragon animated fight"))
        assertEquals(false, l("dragon", "Episode 12", "Tags: knight castle"))
        assertEquals(true, l("dragon", "Big dragons"))
        assertEquals(true, l("\"red dragon\"", "Red dragon awakens"))
        assertEquals("quoted phrases still exact", false, l("\"red dragon\"", "Dragon red"))
    }

    @Test fun unrelatedSearchPageLinksSkippedBeforeOpening() {
        assertEquals(false, Ranker.hitLooksRelated("red dragon", Hit("https://x.com/video-1/summer", "Trending: summer party")))
        assertEquals("no title to judge by → keep", true, Ranker.hitLooksRelated("red dragon", Hit("https://x.com/video-1/x", "")))
        assertEquals("the address counts", true, Ranker.hitLooksRelated("red dragon", Hit("https://x.com/video-1/red-dragon-ep3", "Episode 3")))
    }

    @Test fun onlyTitleDescriptionTagsAndAddressCount() {
        val body = Video("file", "https://x/v.mp4", title = "Cooking pasta at home", pageUrl = "https://blog.example/pasta",
            pageText = "Unrelated aside: a volcano eruption once delayed my flight.")
        assertTrue("words only in the page's body text don't count", !Ranker.matches("volcano eruption", body))
        assertTrue("tags count", Ranker.matches("volcano eruption", body.copy(keywords = "volcano, eruption, lava")))
        assertTrue("the page address counts", Ranker.matches("dragon fight", Video("file", "https://x/v.mp4", pageUrl = "https://rule34video.com/video/1/dragon-fight/")))
        assertTrue("an adult site's tag list counts when passed", Ranker.matches("dragon", body, null, "Tags: dragon animated"))
    }

    @Test fun musicVideosEverywhereMaleFocusOnAdultSites() {
        val cases = listOf(
            Triple("Tracer PMV compilation", false, "music video"), Triple("hmv dragon", true, "music video"),
            Triple("Volcano (Official Music Video)", false, "music video"), Triple("Song - Lyric Video", false, "music video"),
            Triple("Artist - Official Audio", false, "music video"), Triple("music_video animated", true, "music video"),
            Triple("Naruto AMV", false, "music video"), Triple("male_focus animated", true, "male focus"),
            Triple("solo male", true, "male focus"), Triple("yaoi dragon", true, "male focus"),
            Triple("gay rights march news", false, null), Triple("male female animated", true, null), Triple("female solo", true, null),
            Triple("volcano eruption", false, null), Triple("musical theatre show", false, null), Triple("video game music review", false, null),
            Triple("dragon fight animated", true, null), Triple("official trailer", false, null),
        )
        for ((text, adult, want) in cases) assertEquals("$text (adult=$adult)", want, Adult.unwanted(text, adult))
        assertTrue(Video("file", "https://www.vevo.com/watch/x").blocked)
    }

    @Test fun booruSearchesCarryTheUnwantedMinusTags() {
        val tags = Search.booruTags("dragon", "video")
        for (t in listOf("-pmv", "-hmv", "-male_focus", "-yaoi", "-loli", "-shota")) assertTrue(tags, tags.split(' ').contains(t))
    }
}
