package app.meh

/**
 * Remembers, per site, which way of playing worked ("web" = Chrome's player, "page" = the site's
 * own page) when the app's own player didn't — so the next video from that site starts there
 * instead of waiting through attempts that fail. Entries expire after a week, are forgotten as
 * soon as they stop working, and are dropped when the app's own player works for the site again.
 *
 * [store] is a plain key → value map (SharedPreferences in the app, a HashMap in tests).
 */
class SiteModes(
    private val read: (String) -> String?,
    private val write: (String, String?) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private fun key(site: String) = "mode:" + site.lowercase().removePrefix("www.")

    /** The remembered fallback mode for [site], if any and still fresh. */
    fun get(site: String): String? {
        if (site.isEmpty()) return null
        val (mode, at) = read(key(site))?.split('|')?.takeIf { it.size == 2 } ?: return null
        val t = at.toLongOrNull() ?: return null
        if (mode !in setOf("web", "page") || now() - t > MAX_AGE) { write(key(site), null); return null }
        return mode
    }

    /** A video from [site] started playing in [mode]. */
    fun started(site: String, mode: String) {
        if (site.isEmpty()) return
        if (mode == "exo") write(key(site), null)              // direct play works again
        else write(key(site), "$mode|${now()}")
    }

    /** A video from [site] failed in [mode]: if that was the remembered way, forget it. */
    fun failed(site: String, mode: String) {
        if (site.isNotEmpty() && get(site) == mode) write(key(site), null)
    }

    companion object {
        const val MAX_AGE = 7L * 24 * 3600 * 1000
    }
}
