package app.meh

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WebView's network hook (shouldInterceptRequest) runs on a background thread, where calling
 * ANY WebView method crashes the app ("A WebView method was called on thread ..."). This
 * guards the code that runs there against touching the WebView again.
 */
class WebViewThreadTest {
    @Test fun networkHookNeverTouchesTheWebView() {
        val src = File("src/main/java/app/meh/WebInspector.kt").readText()
        val start = src.indexOf("private fun intercept(request: WebResourceRequest)")
        assertTrue("intercept() not found", start > 0)
        val end = src.indexOf("\n        }\n", start)
        val body = src.substring(start, end)
        assertTrue("intercept() must not use the WebView:\n$body", !Regex("\\bview\\b|\\bwv\\b|WebView").containsMatchIn(body))
        val hook = src.substring(src.indexOf("override fun shouldInterceptRequest"), start)
        assertTrue("shouldInterceptRequest must catch everything", "catch (e: Throwable)" in hook)
    }
}
