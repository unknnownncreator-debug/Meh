package app.meh

/**
 * What happened during the current search, per engine: how many results it returned, how many
 * of those pages were inspected, how many videos it contributed, what was hidden and why, and
 * which videos failed to play. Shown when the status line is tapped (with a Copy button).
 * Thread-safe: engines report from background threads.
 */
class SearchReport {
    private class Counts { var results = -1; var inspected = 0; var noVideo = 0; var videos = 0 }

    private var query = ""
    private val engines = LinkedHashMap<String, Counts>()
    private val hidden = LinkedHashMap<String, Int>()
    private val failures = ArrayList<String>()

    @Synchronized fun reset(q: String) { query = q; engines.clear(); hidden.clear(); failures.clear() }
    private fun of(engine: String) = engines.getOrPut(engine.ifEmpty { "?" }) { Counts() }

    @Synchronized fun results(engine: String, n: Int) { of(engine).results = maxOf(0, of(engine).results) + n }
    @Synchronized fun inspected(engine: String, foundVideo: Boolean) { of(engine).inspected++; if (!foundVideo) of(engine).noVideo++ }
    @Synchronized fun added(engine: String) { of(engine).videos++ }
    @Synchronized fun hidden(reason: String) { hidden[reason] = (hidden[reason] ?: 0) + 1 }
    @Synchronized fun playFailed(site: String, why: String) { if (failures.size < 30) failures += "$site — $why" }

    @Synchronized fun render(): String = buildString {
        append("Search report for \"").append(query).append("\"\n\n")
        append("Sites (results → pages checked → videos added):\n")
        if (engines.isEmpty()) append("  (no search yet)\n")
        for ((name, c) in engines) {
            append("  ").append(name).append(": ")
            when {
                c.results < 0 -> append("still searching…")
                c.results == 0 -> append("0 results — blocked here, or the site changed")
                else -> append("${c.results} results → ${c.inspected} checked (${c.noVideo} without a video) → ${c.videos} added")
            }
            append('\n')
        }
        if (hidden.isNotEmpty()) {
            append("\nHidden:\n")
            for ((reason, n) in hidden) append("  ").append(reason).append(": ").append(n).append('\n')
        }
        if (failures.isNotEmpty()) {
            append("\nCouldn't play:\n")
            for (f in failures) append("  ").append(f).append('\n')
        }
    }
}
