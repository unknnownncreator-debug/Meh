package app.meh

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

/** Tiny thumbnail loader: download, downscale, cache in memory. */
class ImageLoader(private val scope: CoroutineScope) {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = HashSet<String>()

    fun load(url: String?, into: ImageView) {
        into.tag = url
        into.setImageDrawable(null)
        if (url.isNullOrEmpty() || url in failed) return
        cache.get(url)?.let { into.setImageBitmap(it); return }
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { fetch(url) }
            if (bmp == null) failed += url else cache.put(url, bmp)
            if (into.tag == url && bmp != null) into.setImageBitmap(bmp)
        }
    }

    private fun fetch(url: String): Bitmap? = try {
        val req = Request.Builder().url(url).header("User-Agent", DESKTOP_UA).build()
        Http.client.newCall(req).execute().use { res ->
            val bytes = res.body?.bytes() ?: return null
            if (!res.isSuccessful || bytes.size > 8_000_000) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 320) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (e: Exception) { null }
}
