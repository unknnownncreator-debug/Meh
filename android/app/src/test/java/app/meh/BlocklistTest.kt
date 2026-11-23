package app.meh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class BlocklistTest {
    companion object {
        @BeforeClass @JvmStatic fun load() {
            // Same file the app bundles as an asset.
            File("../../data/adblock-domains.txt").useLines { Blocklist.load(it) }
        }
    }

    @Test fun listIsLoaded() = assertTrue("${Blocklist.size}", Blocklist.size > 40_000)

    @Test fun blocksAdServersAndTheirSubdomains() {
        for (h in listOf("securepubads.g.doubleclick.net", "imasdk.googleapis.com", "pagead2.googlesyndication.com",
                "ib.adnxs.com", "cdn.teads.tv", "mssl.fwmrm.net", "c.amazon-adsystem.com", "ads.pubmatic.com"))
            assertTrue(h, Blocklist.isListedHost(h))
        assertTrue(isAdUrl("https://imasdk.googleapis.com/js/sdkloader/ima3.js"))
    }

    @Test fun neverBlocksVideoHostsTheAppNeeds() {
        for (h in listOf("www.cnn.com", "player.vimeo.com", "www.dailymotion.com", "s1.dmcdn.net", "cdn.jwplayer.com",
                "d1.cloudfront.net", "vod-progressive.akamaized.net", "www.googleapis.com", "www.google.com",
                "en.wikipedia.org", "archive.org", "www.nasa.gov", "www.bbc.co.uk", "media.giphy.com"))
            assertFalse(h, Blocklist.isListedHost(h))
        assertFalse("a parent of a listed domain isn't listed", Blocklist.isListedHost("googleapis.com"))
        assertFalse(isAdUrl("https://www.cnn.com/videos/world/volcano"))
    }
}
