package app.meh

/** Turns playback failures into a plain-language reason, and decides whether a fresh link could help. */
object SkipReason {
    const val TIMED_OUT = "it never started (timed out)"

    /** [code] is a Media3 PlaybackException error code; [http] the server's status, if it answered with one. */
    fun describe(code: Int, http: Int? = null): String = when {
        http == 401 || http == 403 -> "the site refused to stream it (HTTP $http)"
        http == 404 || http == 410 -> "the video link is gone (HTTP $http)"
        http != null -> "the site returned an error (HTTP $http)"
        code in 2000..2999 -> "couldn't load it from the video server"
        code in 3000..3999 -> "the video file is damaged or not a video"
        code in 4000..4999 -> "this phone can't play that video format"
        code in 6000..6999 -> "it's copy-protected (DRM)"
        else -> "playback error"
    }

    /**
     * What to try after a failure in [mode]: the app's player → a Chrome player presenting itself
     * as the site's page (Chrome's decoders, network stack and cookies) → the site's own page.
     * Null = give up. Copy-protected (DRM) video can't be helped by any player.
     */
    fun nextMode(mode: String, kind: String, pageUrl: String, code: Int? = null): String? = when {
        kind == "embed" -> null
        code != null && code in 6000..6999 -> null
        mode == "exo" -> "web"
        mode == "web" && pageUrl.startsWith("http") -> "page"
        else -> null
    }

    const val WEB_FAILED = "Chrome's player couldn't play it either"
    const val PAGE_FAILED = "its own page wouldn't play it here"

    /**
     * Refused, expired or vanished links often work again with a fresh link from the page
     * (sites put short-lived tokens in video URLs), so those are worth one more try.
     */
    fun worthRefreshing(code: Int, http: Int? = null): Boolean =
        if (http != null) http in setOf(400, 401, 403, 404, 410, 498)
        else code == 2005 || code == 2006 // file not found / no permission
}
