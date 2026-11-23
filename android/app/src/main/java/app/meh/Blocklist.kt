package app.meh

import android.content.Context
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ad blocker: ~43,000 ad-server domains (EasyList + core video-ad networks), bundled from
 * data/adblock-domains.txt. A host is blocked if it or any parent domain is listed
 * (ads.example.com ← example.com). Used by the hidden browsers and the embedded players.
 */
object Blocklist {
    @Volatile private var domains: Set<String> = emptySet()
    /** Ad requests blocked since the app started. */
    val blocked = AtomicInteger()

    val size get() = domains.size

    fun load(lines: Sequence<String>) {
        domains = lines.map { it.trim().lowercase() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toHashSet()
    }

    /** Blocking file read: call off the main thread. Missing list = pattern filter only. */
    fun loadFromAssets(ctx: Context) {
        try { ctx.assets.open("adblock-domains.txt").bufferedReader().useLines { load(it) } } catch (e: Exception) { }
    }

    fun isListedHost(host: String): Boolean {
        val set = domains
        if (set.isEmpty()) return false
        val parts = host.lowercase().trimEnd('.').split('.')
        for (i in 0 until parts.size - 1) if (parts.subList(i, parts.size).joinToString(".") in set) return true
        return false
    }

    fun isListedUrl(url: String): Boolean = try {
        isListedHost(java.net.URI(url).host ?: "")
    } catch (e: Exception) { false }
}
