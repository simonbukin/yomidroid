package com.yomidroid.ui.components

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.ViewGroup
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val json = remember(entries, customCss, dictionaryCssMap) {
        EntrySerializer.serialize(context, entries, customCss, dictionaryCssMap)
    }

    var contentHeight by remember { mutableIntStateOf(200) }

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
            }
        },
        update = { webView ->
            val escaped = json.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
            webView.evaluateJavascript("setEntries('$escaped')", null)
        }
    )
}
