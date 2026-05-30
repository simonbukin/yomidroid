package com.yomidroid.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yomidroid.dictionary.DictionaryEngine
import com.yomidroid.dictionary.DictionaryEntry
import com.yomidroid.dictionary.EntrySerializer
import com.yomidroid.dictionary.KanjiSimilarity
import com.yomidroid.tts.TtsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    internal var onOpenKanji: ((String) -> Unit)? = null
    internal var onCorrection: ((String) -> Unit)? = null
    internal var onLookupTerm: ((String) -> Unit)? = null
    internal var onOpenExternal: ((String) -> Unit)? = null
    internal var onRequestRanking: ((Char) -> Unit)? = null
    internal var onEditOcrInApp: (() -> Unit)? = null

    fun setAnkiResult(index: Int, status: String) {
        val wv = webView ?: return
        val safeStatus = status.replace("'", "")
        wv.post {
            wv.evaluateJavascript("setAnkiResult($index, '$safeStatus')", null)
        }
    }

    /** Push the result of an async correction-candidate ranking into the WebView. */
    fun setCorrectionRanking(originalChar: Char, ranked: List<Pair<Char, Int>>) {
        val wv = webView ?: return
        val arr = org.json.JSONArray()
        for ((c, n) in ranked) {
            arr.put(org.json.JSONObject().put("k", c.toString()).put("n", n))
        }
        val origQ = org.json.JSONObject.quote(originalChar.toString())
        val rankedQ = org.json.JSONObject.quote(arr.toString())
        wv.post {
            wv.evaluateJavascript("setCorrectionRanking($origQ, $rankedQ)", null)
        }
    }
}

@Composable
fun rememberDictionaryWebViewController(): DictionaryWebViewController =
    remember { DictionaryWebViewController() }

/**
 * Composable that renders full DictionaryEntry list using popup.js/popup.css.
 * Provides visual parity with the overlay popup for in-app screens.
 *
 * Redirect drill-down: when [dictionaryEngine] is supplied (and [onLookupTerm]
 * is not), clicking a Yomitan structured-content link pushes the current
 * entries onto an internal back-stack and re-renders with the redirect
 * target's entries. A "back" chip at the top pops the stack.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DictionaryEntryWebView(
    entries: List<DictionaryEntry>,
    customCss: String? = null,
    dictionaryCssMap: Map<String, String> = emptyMap(),
    onAnkiExport: ((DictionaryEntry, Int) -> Unit)? = null,
    onOpenKanji: ((String) -> Unit)? = null,
    onCorrection: ((String) -> Unit)? = null,
    onLookupTerm: ((String) -> Unit)? = null,
    onOpenExternal: ((String) -> Unit)? = null,
    onRequestRanking: ((Char) -> Unit)? = null,
    onEditOcrInApp: (() -> Unit)? = null,
    matchedText: String? = null,
    originalMatchedText: String? = null,
    dictionaryEngine: DictionaryEngine? = null,
    controller: DictionaryWebViewController = rememberDictionaryWebViewController(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Internal redirect chain. Each list is a frame to pop back to. Resets
    // whenever the caller swaps in a new top-level entry list.
    var redirectStack by remember(entries) {
        mutableStateOf<List<List<DictionaryEntry>>>(emptyList())
    }
    val effectiveEntries = redirectStack.lastOrNull() ?: entries

    val effectiveOnLookupTerm: ((String) -> Unit)? = when {
        onLookupTerm != null -> onLookupTerm
        dictionaryEngine != null -> { target ->
            scope.launch {
                val resolved = withContext(Dispatchers.IO) { dictionaryEngine.searchTerm(target) }
                if (resolved.isNotEmpty()) {
                    redirectStack = redirectStack + listOf(resolved)
                }
            }
            Unit
        }
        else -> null
    }

    val json = remember(effectiveEntries, customCss, dictionaryCssMap, matchedText, originalMatchedText, onCorrection != null) {
        val mt = if (onCorrection != null) matchedText else null
        val omt = if (onCorrection != null) (originalMatchedText ?: matchedText) else null
        EntrySerializer.serialize(context, effectiveEntries, customCss, dictionaryCssMap, mt, omt)
    }

    // Preload the kanji-similarity table off the JS thread when correction is wired.
    LaunchedEffect(onCorrection) {
        if (onCorrection != null) {
            try { KanjiSimilarity.ensureLoaded(context.applicationContext) } catch (_: Exception) {}
        }
    }

    // popup.html sets viewport=width=device-width, initial-scale=1, so the
    // scrollHeight value popup.js reports is already in CSS-px = dp. We use
    // it directly as the AndroidView's height.
    var contentHeightDp by remember { mutableIntStateOf(120) }

    // Keep the controller's view of entries + callback fresh on every recomposition,
    // since the JS bridge dispatches through it.
    SideEffect {
        controller.entries = effectiveEntries
        controller.onAnkiExport = onAnkiExport
        controller.onOpenKanji = onOpenKanji
        controller.onCorrection = onCorrection
        controller.onLookupTerm = effectiveOnLookupTerm
        controller.onOpenExternal = onOpenExternal
        controller.onRequestRanking = onRequestRanking
        controller.onEditOcrInApp = onEditOcrInApp
    }

    Column(modifier = modifier) {
        if (redirectStack.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(
                    onClick = { redirectStack = redirectStack.dropLast(1) },
                    label = {
                        Text(
                            if (redirectStack.size == 1) "Back to original entry"
                            else "Back (${redirectStack.size - 1} more)"
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            modifier = Modifier.size(AssistChipDefaults.IconSize)
                        )
                    }
                )
            }
        }

        // Re-query content height after every recomposition that swaps the
        // entry JSON. Measures the dictionary container directly (not
        // document.body.scrollHeight) because body.scrollHeight is clamped to
        // clientHeight — sizing the WebView to scrollHeight then makes
        // scrollHeight equal the new clientHeight, creating a feedback loop
        // that grows the WebView unboundedly. The container's offsetHeight is
        // viewport-independent.
        fun queryHeight(view: WebView) {
            view.evaluateJavascript(
                "(function(){var d=document.getElementById('dictionary-entries');" +
                    "if(!d)return 0;return Math.ceil(d.offsetTop+d.offsetHeight);})()"
            ) { result ->
                val trimmed = result?.trim()?.replace("\"", "") ?: return@evaluateJavascript
                val v = trimmed.toIntOrNull() ?: return@evaluateJavascript
                if (v > 0 && v != contentHeightDp) contentHeightDp = v
            }
        }

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height(contentHeightDp.dp.coerceAtLeast(80.dp)),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = false
                    settings.allowFileAccess = true
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = true
                    // The AndroidView's height is sized to scrollHeight, so
                    // there's no overflow to scroll inside the WebView. Let the
                    // outer composable scroll handle all vertical drags.
                    isVerticalScrollBarEnabled = false
                    overScrollMode = android.view.View.OVER_SCROLL_NEVER

                    addJavascriptInterface(
                        DictionaryWebViewBridge(controller, ctx.applicationContext) { dp ->
                            if (dp > 0 && dp != contentHeightDp) contentHeightDp = dp
                        },
                        "YomidroidPopup"
                    )

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            val v = view ?: return
                            val escaped = json.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
                            v.evaluateJavascript("setEntries('$escaped')", null)
                            // Re-query a few times because images/fonts/SVG
                            // pitch graphs settle after the initial render.
                            v.postDelayed({ queryHeight(v) }, 60)
                            v.postDelayed({ queryHeight(v) }, 250)
                            v.postDelayed({ queryHeight(v) }, 700)
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
                webView.postDelayed({ queryHeight(webView) }, 60)
                webView.postDelayed({ queryHeight(webView) }, 250)
                webView.postDelayed({ queryHeight(webView) }, 700)
            }
        )
    }
}

private class DictionaryWebViewBridge(
    private val controller: DictionaryWebViewController,
    private val bridgeContext: Context,
    private val onHeight: (Int) -> Unit = {}
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tts = TtsManager.getInstance(bridgeContext)

    @JavascriptInterface
    fun ankiExport(index: Int) {
        mainHandler.post {
            val entry = controller.entries.getOrNull(index) ?: return@post
            controller.onAnkiExport?.invoke(entry, index)
        }
    }

    @JavascriptInterface
    fun reportContentHeight(heightPx: Int) {
        if (heightPx > 0) mainHandler.post { onHeight(heightPx) }
    }

    @JavascriptInterface
    fun speak(text: String) {
        mainHandler.post { tts.speak(text) }
    }

    @JavascriptInterface
    fun openKanji(character: String) {
        mainHandler.post { controller.onOpenKanji?.invoke(character) }
    }

    @JavascriptInterface
    fun requestSimilarKanji(character: String): String {
        val ch = character.firstOrNull() ?: return ""
        return KanjiSimilarity.neighbors(ch).joinToString("")
    }

    @JavascriptInterface
    fun applyCorrection(correctedText: String) {
        if (correctedText.isEmpty()) return
        mainHandler.post { controller.onCorrection?.invoke(correctedText) }
    }

    @JavascriptInterface
    fun requestCorrectionRanking(originalChar: String) {
        val ch = originalChar.firstOrNull() ?: return
        mainHandler.post { controller.onRequestRanking?.invoke(ch) }
    }

    @JavascriptInterface
    fun editOcrInApp() {
        mainHandler.post { controller.onEditOcrInApp?.invoke() }
    }

    @JavascriptInterface
    fun lookupTerm(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        mainHandler.post { controller.onLookupTerm?.invoke(q) }
    }

    @JavascriptInterface
    fun lookupTermWithParams(paramsJson: String) {
        val query = try {
            org.json.JSONObject(paramsJson).optString("query", "").trim()
        } catch (e: Exception) { "" }
        if (query.isEmpty()) return
        mainHandler.post { controller.onLookupTerm?.invoke(query) }
    }

    @JavascriptInterface
    fun openExternal(url: String) {
        val u = url.trim()
        if (!(u.startsWith("https://") || u.startsWith("http://"))) return
        mainHandler.post {
            val override = controller.onOpenExternal
            if (override != null) {
                override(u)
            } else {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(u))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    bridgeContext.startActivity(intent)
                } catch (e: Exception) {
                    Log.w("DictionaryEntryWebView", "Failed to open external link: ${e.message}")
                    Toast.makeText(bridgeContext, "Couldn't open link", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
