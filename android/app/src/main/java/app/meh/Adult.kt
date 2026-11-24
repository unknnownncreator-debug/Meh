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
    // Includes the sites' own video servers (CDNs), so their streams count as adult too.
    private val HOST = Regex("(^|\\.)(" + listOf(
        "redgifs\\.com", "rule34video\\.com", "rule34\\.xxx", "rule34\\.us", "paheal\\.net", "paheal-cdn\\.net",
        "xvideos\\.com", "xvideos-cdn\\.com", "xnxx\\.com", "xnxx-cdn\\.com", "pornhub\\.com", "phncdn\\.com",
        "xhamster\\.com", "xhcdn\\.com", "eporner\\.com", "spankbang\\.com", "sb-cd\\.com",
    ).joinToString("|") + ")$", RegexOption.IGNORE_CASE)

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

    // ---- unwanted content (not a setting): music videos — incl. HMV/PMV/AMV — everywhere,
    // male-focus content on adult sites. Matched against titles, descriptions and tags.
    private val MUSIC_WORDS = setOf("hmv", "pmv", "amv", "hmvs", "pmvs", "amvs", "musicvideo", "musicvideos")
    private val MUSIC_PHRASES = Regex("\\b(music[\\s_-]?videos?|official[\\s_-]?(music[\\s_-]?)?(video|mv|audio|clip)|lyrics?[\\s_-]?videos?|visuali[sz]er|official[\\s_-]?lyric)\\b", RegexOption.IGNORE_CASE)
    private val MALE_WORDS = setOf("yaoi", "gay", "bara", "malefocus", "maleonly", "solomale", "malesolo", "allmale")
    private val MALE_PHRASES = Regex("\\b(male[\\s_-]?focus|males?[\\s_-]?only|solo[\\s_-]?male|male[\\s_-]?solo|all[\\s_-]?males?|gay[\\s_-]?(sex|porn|couple)|male[\\s_-]?masturbation|man[\\s_-]?on[\\s_-]?man)\\b", RegexOption.IGNORE_CASE)

    /** Extra booru minus-tags for unwanted content; the rest is filtered after. */
    val UNWANTED_NEGATIVE_TAGS = listOf("pmv", "hmv", "male_focus", "yaoi")

    /** Why this text is unwanted ("music video" / "male focus"), or null. Male focus only counts for adult sites. */
    fun unwanted(text: String?, adult: Boolean): String? {
        val t = (text ?: "").lowercase()
        val words = t.split(Regex("[^a-z0-9]+"))
        if (MUSIC_PHRASES.containsMatchIn(t) || words.any { it in MUSIC_WORDS }) return "music video"
        if (adult && (MALE_PHRASES.containsMatchIn(t) || words.any { it in MALE_WORDS })) return "male focus"
        return null
    }

    /** Final gate for a found video: drop adult-site videos while switched off, and any marked as depicting minors. */
    fun drop(v: Video, hit: Hit?, includeAdult: Boolean): Boolean {
        if (!isAdultSite(v.pageUrl) && !isAdultSite(v.src)) return false
        if (!includeAdult) return true
        return depictsMinors(listOf(v.title, v.description, v.pageTitle, v.pageText, hit?.title, hit?.snippet, v.pageUrl).joinToString(" "))
    }
}
