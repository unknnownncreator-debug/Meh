package app.meh

import java.text.Normalizer

/** Relevance scoring: how well a found video matches what was typed (0..100). */
object Ranker {
    private val STOP = ("a an and are as at be by for from how i in is it of on or that the this to was what " +
        "when where who why will with video videos watch").split(' ').toSet()

    fun tokens(s: String?): List<String> =
        Normalizer.normalize(s ?: "", Normalizer.Form.NFKD).replace(Regex("[\\u0300-\\u036f]"), "").lowercase()
            .split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in STOP }

    private fun stem(t: String) = if (t.length > 4) t.replace(Regex("(ing|ed|es|s|ly)$"), "") else t

    private fun fieldScore(q: List<String>, text: String?): Double {
        val toks = tokens(text).map(::stem)
        if (toks.isEmpty()) return 0.0
        val set = toks.toHashSet()
        val hit = q.count { w -> w in set || toks.any { t -> t.length > 3 && w.length > 3 && (t.startsWith(w) || w.startsWith(t)) } }
        return hit.toDouble() / q.size
    }

    fun score(query: String, v: Video, rank: Int? = null): Double {
        val q = tokens(query).map(::stem).distinct()
        if (q.isEmpty()) return 50.0
        val phrase = query.lowercase().trim()
        var s = 0.0
        s += 40 * fieldScore(q, v.title)
        s += 15 * fieldScore(q, v.pageTitle)
        s += 15 * fieldScore(q, v.description)
        s += 10 * fieldScore(q, v.pageText)
        s += 5 * fieldScore(q, decodeUrl(v.pageUrl).replace(Regex("[-_/]"), " "))
        if (phrase.length > 3 && phrase in v.title.lowercase()) s += 8
        if (rank != null) s += maxOf(0.0, 7 - rank * 0.35)
        v.duration?.let { if (it < 45) s -= 6; if (it > 3 * 3600) s -= 4 }
        return (Math.round(s.coerceIn(0.0, 100.0) * 10) / 10.0)
    }

    /**
     * Strict matching: does this video really match what was typed?
     *  - every query word must appear in the video's title, its page's title, its description, its
     *    keywords/tags, or its page address — NOT the rest of the page's text (that's where unrelated
     *    matches come from). [extra] adds text that counts (an adult site's tag list);
     *  - searches of 4+ words may miss one word;
     *  - "quoted phrases" must appear exactly.
     * Word forms count ("volcano" ~ "volcanoes"), different words don't ("dragon" ≠ "dragonball").
     */
    fun matches(query: String, v: Video, hit: Hit? = null, extra: String = ""): Boolean {
        val phrases = Regex("\"([^\"]+)\"").findAll(query).map { norm(it.groupValues[1]) }.filter { it.isNotBlank() }.toList()
        val words = tokens(query.replace("\"", " ")).map(::stem).distinct()
        if (words.isEmpty() && phrases.isEmpty()) return true
        val slug = try { decodeUrl(java.net.URI(v.pageUrl).rawPath ?: "") } catch (e: Exception) { "" }
        val text = listOf(v.title, v.pageTitle, v.description, v.keywords, hit?.title, hit?.snippet, slug, extra).filterNotNull().joinToString(" \n ")
        val toks = tokens(text).map(::stem)
        val set = toks.toHashSet()
        fun close(a: String, b: String) = a.length > 3 && b.length > 3 && kotlin.math.abs(a.length - b.length) <= 3 && (a.startsWith(b) || b.startsWith(a))
        val missing = words.count { q -> q !in set && toks.none { close(it, q) } }
        if (missing > (if (words.size >= 4) 1 else 0)) return false
        val n = norm(text)
        return phrases.all { n.contains(it) }
    }

    private fun norm(s: String) =
        " " + Normalizer.normalize(s, Normalizer.Form.NFKD).replace(Regex("[\\u0300-\\u036f]"), "").lowercase().replace(Regex("[^a-z0-9]+"), " ").trim() + " "

    /** Highest score first, spreading sites out so one domain can't take over the queue. */
    fun order(list: List<Video>): List<Video> {
        val remaining = list.sortedByDescending { it.score }.toMutableList()
        val out = ArrayList<Video>(remaining.size)
        val perSite = HashMap<String, Int>()
        while (remaining.isNotEmpty()) {
            var best = 0
            var bestV = Double.NEGATIVE_INFINITY
            for ((i, v) in remaining.withIndex()) {
                val adj = v.score - 7 * (perSite[v.site] ?: 0)
                if (adj > bestV) { bestV = adj; best = i }
            }
            val pick = remaining.removeAt(best)
            perSite[pick.site] = (perSite[pick.site] ?: 0) + 1
            out += pick
        }
        return out
    }
}
