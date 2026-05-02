package com.yomidroid.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yomidroid.dictionary.DictionaryEntry
import com.yomidroid.dictionary.EntrySerializer
import com.yomidroid.tts.TtsManager

/**
 * Controller for [DictionaryEntryWebView]. Lets the caller push the result of an
 * Anki export back into the WebView so the in-popup star button reflects success,
 * already-exists, or error states. Statuses match popup.js' setAnkiResult contract:
 * "loading", "success", "exists", "error", "idle".
 */
class DictionaryWebViewController {
    internal var webView: WebView? = null
    internal var entries: List<DictionaryEntry> = emptyList()
    internal var onAnkiExport: ((DictionaryEntry, Int) -> Unit)? = null

    fun setAnkiResult(index: Int, status: String) {
        val wv = webView ?: return
        val safeStatus = status.replace("'", "")
        wv.post {
            wv.evaluateJavascript("setAnkiResult($index, '$safeStatus')", null)
        }
    }
}

@Composable
fun rememberDictionaryWebViewController(): DictionaryWebViewController =
    remember { DictionaryWebViewController() }

/**
 * Composable that renders full DictionaryEntry list using popup.js/popup.css.
 * Provides visual parity with the overlay popup for in-app screens.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DictionaryEntryWebView(
    entries: List<DictionaryEntry>,
    customCss: String? = null,
    dictionaryCssMap: Map<String, String> = emptyMap(),
    onAnkiExport: ((DictionaryEntry, Int) -> Unit)? = null,
    controller: DictionaryWebViewController = rememberDictionaryWebViewController(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val json = remember(entries, customCss, dictionaryCssMap) {
        EntrySerializer.serialize(context, entries, customCss, dictionaryCssMap)
    }

    var contentHeight by remember { mutableIntStateOf(200) }

    // Keep the controller's view of entries + callback fresh on every recomposition,
    // since the JS bridge dispatches through it.
    SideEffect {
        controller.entries = entries
        controller.onAnkiExport = onAnkiExport
    }

    AndroidView(
        modifier = modifier.heightIn(min = 50.dp, max = 600.dp),
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setBackgroundColor(Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = true
                @Suppress("DEPRECATION")
                settings.allowFileAccessFromFileURLs = true

                addJavascriptInterface(
                    DictionaryWebViewBridge(controller, ctx.applicationContext),
                    "YomidroidPopup"
                )

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        // Inject the entry data once popup.html is loaded
                        val escaped = json.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
                        view?.evaluateJavascript("setEntries('$escaped')", null)
                        // Measure content height
                        view?.evaluateJavascript(
                            "(function() { return document.body.scrollHeight; })()"
                        ) { result ->
                            try {
                                contentHeight = result.trim().replace("\"", "").toInt()
                            } catch (_: Exception) {}
                        }
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        return true
                    }
                }

                loadUrl("file:///android_asset/popup/popup.html")
                controller.webView = this
            }
        },
        update = { webView ->
            controller.webView = webView
            val escaped = json.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
            webView.evaluateJavascript("setEntries('$escaped')", null)
        }
    )
}

private class DictionaryWebViewBridge(
    private val controller: DictionaryWebViewController,
    appContext: Context
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tts = TtsManager.getInstance(appContext)

    @JavascriptInterface
    fun ankiExport(index: Int) {
        mainHandler.post {
            val entry = controller.entries.getOrNull(index) ?: return@post
            controller.onAnkiExport?.invoke(entry, index)
        }
    }

    @JavascriptInterface
    fun reportContentHeight(heightPx: Int) {
        // No-op: in-screen variant does not auto-resize.
    }

    @JavascriptInterface
    fun speak(text: String) {
        mainHandler.post { tts.speak(text) }
    }
}
