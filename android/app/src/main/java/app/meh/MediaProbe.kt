package app.meh

import okhttp3.Request
import java.net.URI

/**
 * Read a video's real length without downloading it: the first 64 KB of an MP4 (its
 * "mvhd" header) or WebM (its "Duration" element), or the segment list of an HLS
 * playlist. Used to tell a 15–30 s pre-roll ad from the real video behind it.
 */
object MediaProbe {
    private const val HEAD_BYTES = 64 * 1024L

    private fun u32(b: ByteArray, i: Int) =
        ((b[i].toLong() and 0xff) shl 24) or ((b[i + 1].toLong() and 0xff) shl 16) or ((b[i + 2].toLong() and 0xff) shl 8) or (b[i + 3].toLong() and 0xff)

    private fun indexOf(b: ByteArray, pat: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..b.size - pat.size) {
            for (j in pat.indices) if (b[i + j] != pat[j]) continue@outer
            return i
        }
        return -1
    }

    /** MP4/MOV: duration from the movie header box (only if it sits near the start — "faststart" files). */
    fun mp4Duration(b: ByteArray): Double? {
        val tag = "mvhd".toByteArray()
        var i = indexOf(b, tag)
        while (i >= 4 && i + 32 <= b.size) {
            val version = b[i + 4].toInt()
            var timescale = 0L
            var duration = 0L
            if (version == 0) { timescale = u32(b, i + 16); duration = u32(b, i + 20) }
            else if (version == 1 && i + 36 <= b.size) { timescale = u32(b, i + 24); duration = (u32(b, i + 28) shl 32) or u32(b, i + 32) }
            if (timescale > 0 && duration > 0 && duration != 0xffffffffL) return duration.toDouble() / timescale
            i = indexOf(b, tag, i + 4)
        }
        return null
    }

    /** WebM/Matroska: Segment Info → Duration (0x4489) × TimecodeScale (0x2AD7B1, default 1 ms). */
    fun webmDuration(b: ByteArray): Double? {
        if (b.size < 4 || b[0] != 0x1a.toByte() || b[1] != 0x45.toByte() || b[2] != 0xdf.toByte() || b[3] != 0xa3.toByte()) return null
        var scale = 1e6
        val ts = indexOf(b, byteArrayOf(0x2a, 0xd7.toByte(), 0xb1.toByte()))
        if (ts >= 0 && ts + 4 < b.size) {
            val sizeByte = b[ts + 3].toInt() and 0xff
            val len = sizeByte and 0x0f
            if (sizeByte and 0xf0 == 0x80 && len in 1..6 && ts + 4 + len <= b.size) {
                var v = 0L
                for (k in 0 until len) v = (v shl 8) or (b[ts + 4 + k].toLong() and 0xff)
                scale = v.toDouble()
            }
        }
        val d = indexOf(b, byteArrayOf(0x44, 0x89.toByte()))
        if (d < 0 || d + 3 > b.size) return null
        val units = when (b[d + 2].toInt() and 0xff) {
            0x84 -> if (d + 7 <= b.size) java.lang.Float.intBitsToFloat(u32(b, d + 3).toInt()).toDouble() else null
            0x88 -> if (d + 11 <= b.size) java.lang.Double.longBitsToDouble((u32(b, d + 3) shl 32) or u32(b, d + 7)) else null
            else -> null
        }
        if (units == null || !units.isFinite() || units <= 0) return null
        return units * scale / 1e9
    }

    /** HLS: sum of #EXTINF segment lengths. Live streams (no ENDLIST) count as long. */
    fun hlsDuration(text: String): Double? {
        if ("#EXTM3U" !in text) return null
        val total = Regex("#EXTINF:\\s*([\\d.]+)").findAll(text).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }
        if ("#EXT-X-ENDLIST" !in text && total > 0) return Double.POSITIVE_INFINITY
        return total.takeIf { it > 0 }
    }

    private fun head(url: String, referer: String, range: Boolean): Pair<ByteArray, String>? {
        val req = Request.Builder().url(url).header("User-Agent", DESKTOP_UA).header("Accept", "*/*")
            .apply { if (referer.isNotEmpty()) header("Referer", referer); if (range) header("Range", "bytes=0-${HEAD_BYTES - 1}") }
            .build()
        return Http.client.newCall(req).execute().use { res ->
            val src = res.body?.source()
            if (!res.isSuccessful || src == null) return null
            src.request(HEAD_BYTES)
            src.buffer.readByteArray(minOf(src.buffer.size, HEAD_BYTES)) to res.request.url.toString()
        }
    }

    /** Length in seconds of a media candidate, or null if it can't be told. Blocking: call off the main thread. */
    fun probeDuration(c: Candidate): Double? = try { probe(c) } catch (e: Exception) { null }

    private fun probe(c: Candidate): Double? {
        return when (c.kind) {
            "hls" -> {
                val (first, finalUrl) = head(c.src, c.referer, false) ?: return null
                var text = String(first)
                if ("#EXT-X-STREAM-INF" in text) { // master playlist → read the first variant
                    val variant = text.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return null
                    text = String(head(URI(finalUrl).resolve(variant.trim()).toString(), c.referer, false)?.first ?: return null)
                }
                hlsDuration(text)
            }
            "file" -> head(c.src, c.referer, true)?.first?.let { mp4Duration(it) ?: webmDuration(it) }
            else -> null
        }
    }
}
