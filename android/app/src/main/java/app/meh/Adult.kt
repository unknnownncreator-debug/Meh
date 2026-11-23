package app.meh

import java.net.URI

/**
 * Adult sites (opt-in, 18+): RedGIFs and the rule34 sites. Searched only while the
 * "Adult sites (18+)" switch is on; their videos are hidden while it's off.
 *
 * Content depicting minors is ALWAYS excluded from them — not a setting: queries asking for
 * it aren't sent, booru searches carry minus-tags for it, and results / pages whose tags,
 * titles or text mark it are dropped. Mirrors lib/util.js on the desktop.
 */
object Adult {
    private val HOST = Regex("(^|\\.)(redgifs\\.com|rule34video\\.com|rule34\\.xxx|paheal\\.net|paheal-cdn\\.net)$", RegexOption.IGNORE_CASE)

    fun isAdultSite(url: String?): Boolean = try {
        url != null && HOST.containsMatchIn(URI(url.trim()).host ?: "")
    } catch (e: Exception) { false }

    private val AGE_TERMS = setOf("loli", "lolis", "lolicon", "shota", "shotas", "shotacon", "toddlercon", "child", "children",
        "childs", "kid", "kids", "toddler", "toddlers", "infant", "infants", "baby", "babies", "underage", "preteen", "preteens",
        "teen", "teens", "teenage", "teenager", "teenagers", "young", "younger", "youngest", "cub", "cubs", "minor", "minors",
        "kindergarten", "elementary", "schoolgirl", "schoolboy", "agedown", "ageplay", "pedo", "pedophile", "cp")
    private val AGE_PHRASES = Regex("\\b(middle[\\s_-]?school|grade[\\s_-]?school|primary[\\s_-]?school|aged?[\\s_-]?down|age[\\s_-]?regression|little[\\s_-]?(girl|boy|sister|brother))\\b", RegexOption.IGNORE_CASE)

    /** Same terms as "minus" tags for booru-style searches (rule34.xxx, paheal). */
    val NEGATIVE_TAGS = listOf("loli", "lolicon", "shota", "shotacon", "toddlercon", "child", "young", "younger", "underage", "cub", "teen")

    /** True if the text (tags, title, query…) marks content as depicting minors. */
    fun depictsMinors(text: String?): Boolean {
        val t = (text ?: "").lowercase()
        if (AGE_PHRASES.containsMatchIn(t)) return true
        return t.split(Regex("[^a-z0-9]+")).any { it in AGE_TERMS }
    }

    /** Final gate for a found video: drop adult-site videos while switched off, and any marked as depicting minors. */
    fun drop(v: Video, hit: Hit?, includeAdult: Boolean): Boolean {
        if (!isAdultSite(v.pageUrl) && !isAdultSite(v.src)) return false
        if (!includeAdult) return true
        return depictsMinors(listOf(v.title, v.description, v.pageTitle, v.pageText, hit?.title, hit?.snippet, v.pageUrl).joinToString(" "))
    }
}
