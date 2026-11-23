package app.meh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipReasonTest {
    @Test fun plainLanguageReasons() {
        assertEquals("the site refused to stream it (HTTP 403)", SkipReason.describe(2004, 403))
        assertEquals("the video link is gone (HTTP 404)", SkipReason.describe(2004, 404))
        assertEquals("the site returned an error (HTTP 500)", SkipReason.describe(2004, 500))
        assertEquals("couldn't load it from the video server", SkipReason.describe(2001))
        assertEquals("the video file is damaged or not a video", SkipReason.describe(3001))
        assertEquals("this phone can't play that video format", SkipReason.describe(4003))
        assertEquals("it's copy-protected (DRM)", SkipReason.describe(6001))
    }

    @Test fun refusedOrExpiredLinksGetOneMoreTry() {
        assertTrue(SkipReason.worthRefreshing(2004, 403))
        assertTrue(SkipReason.worthRefreshing(2004, 410))
        assertTrue(SkipReason.worthRefreshing(2004, 401))
        assertFalse("server error: a new link won't help", SkipReason.worthRefreshing(2004, 500))
        assertFalse("unsupported format: a new link won't help", SkipReason.worthRefreshing(4003))
        assertFalse(SkipReason.worthRefreshing(6001))
    }

    @Test fun videosCarryTheIdentityTheyWereFoundWith() {
        assertEquals(DESKTOP_UA, Video("file", "https://x/v.mp4").userAgent)
        assertEquals("phone", Video("file", "https://x/v.mp4").copy(userAgent = "phone").userAgent)
    }
}
