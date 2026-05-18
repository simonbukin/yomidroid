package com.yomidroid.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.yomidroid.MainActivity
import com.yomidroid.dictionary.DictionaryEntry
import com.yomidroid.dictionary.KanjiSimilarity
import com.yomidroid.tts.TtsManager

/**
 * WebView popup for dictionary results, following Yomitan's architecture:
 * - Loads popup.html from assets (like Yomitan's iframe loading popup.html)
 * - Entry data passed as JSON via JS interface (like Yomitan's cross-frame messaging)
 * - popup.js builds DOM from JSON (like Yomitan's DisplayGenerator)
 * - popup.css provides the design system (like Yomitan's material.css + display.css)
 * - Dictionary-scoped CSS via [data-dictionary="Name"] selectors
 * - TTS via system TextToSpeech
 */
@SuppressLint("SetJavaScriptEnabled")
class OverlayPopupWebView(private val context: Context) {

    companion object {
        private const val TAG = "OverlayPopupWebView"
        private const val POPUP_URL = "file:///android_asset/popup/popup.html"
    }

    private var webView: WebView? = null
    private var currentContainer: FrameLayout? = null
    private var currentEntries: List<DictionaryEntry> = emptyList()
    private var currentMatchedText: String? = null
    private var onAnkiExport: ((DictionaryEntry, Int) -> Unit)? = null
    private var onCorrection: ((String) -> Unit)? = null
    private var onRequestRanking: ((Char) -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ttsManager: TtsManager by lazy { TtsManager.getInstance(context) }
    private var isPageLoaded = false

    // Pending data to inject once the page loads
    private var pendingJson: String? = null

    // Dictionary-scoped CSS (dictTitle → CSS string)
    var dictionaryCssMap: Map<String, String> = emptyMap()

    // Sizing constraints
    private var maxPopupWidth: Int = 0
    private var maxPopupHeight: Int = 0
    private var textBounds: Rect = Rect()

    // The matched text from the very first lookup in this session; threaded
    // into the JSON payload so the popup can expose a "← original" reset.
    private var pendingOriginalMatchedText: String? = null

    val isVisible: Boolean get() = webView?.visibility == View.VISIBLE

    /**
     * Show the popup with dictionary entries rendered via the asset-based popup system.
     * Positions the popup above or below [textBounds] depending on available space,
     * never overlapping the highlighted text region.
     */
    fun show(
        container: FrameLayout,
        entries: List<DictionaryEntry>,
        textBounds: Rect,
        maxWidth: Int,
        maxHeight: Int,
        customCss: String?,
        onAnkiExport: (DictionaryEntry, Int) -> Unit,
        onCorrection: ((String) -> Unit)? = null,
        originalMatchedText: String? = null,
        onRequestRanking: ((Char) -> Unit)? = null
    ) {
        if (entries.isEmpty()) return

        this.currentContainer = container
        this.currentEntries = entries
        this.currentMatchedText = entries.firstOrNull()?.matchedText
        this.onAnkiExport = onAnkiExport
        this.onCorrection = onCorrection
        this.onRequestRanking = onRequestRanking
        this.maxPopupWidth = maxWidth
        this.maxPopupHeight = maxHeight
        this.textBounds = Rect(textBounds)
        this.pendingOriginalMatchedText = originalMatchedText ?: currentMatchedText

        // Preload similarity table off the JS thread so requestSimilarKanji is cheap.
        if (onCorrection != null) {
            try { KanjiSimilarity.ensureLoaded(context) } catch (e: Exception) {
                Log.w(TAG, "Failed to preload kanji similarity table: ${e.message}")
            }
        }

        val wv = getOrCreateWebView(container)

        // Position and size — smart above/below placement
        val lp = computeLayoutParams(container, maxWidth, maxHeight, textBounds)
        wv.layoutParams = lp

        // Serialize entries to JSON
        val json = serializeEntries(entries, customCss)

        if (isPageLoaded) {
            // Page already loaded — inject data directly
            injectData(json)
        } else {
            // Page still loading — queue for onPageFinished
            pendingJson = json
        }

        wv.visibility = View.VISIBLE
    }

    /**
     * Compute layout params using 4-way smart positioning.
     * Evaluates all four directions (below, above, right, left) relative to
     * [bounds] and picks the direction with the largest usable area.
     */
    private fun computeLayoutParams(
        container: FrameLayout,
        maxWidth: Int,
        maxHeight: Int,
        bounds: Rect
    ): FrameLayout.LayoutParams {
        val density = context.resources.displayMetrics.density
        val margin = (12 * density).toInt()
        val edgePad = 8

        val containerH = container.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
        val containerW = container.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels

        // Compute usable dimensions for each direction, clamped to max
        data class Candidate(val w: Int, val h: Int, val x: Int, val y: Int)

        fun makeCandidate(rawW: Int, rawH: Int, anchorX: Int, anchorY: Int, centerAxis: Char): Candidate {
            val w = rawW.coerceAtMost(maxWidth).coerceAtLeast(0)
            val h = rawH.coerceAtMost(maxHeight).coerceAtLeast(0)
            val x: Int
            val y: Int
            if (centerAxis == 'x') {
                // Horizontal placement: center on the perpendicular (vertical) axis
                x = anchorX.coerceIn(edgePad, containerW - w - edgePad)
                y = (bounds.centerY() - h / 2).coerceIn(edgePad, containerH - h - edgePad)
            } else {
                // Vertical placement: center on the perpendicular (horizontal) axis
                x = (bounds.centerX() - w / 2).coerceIn(edgePad, containerW - w - edgePad)
                y = anchorY.coerceIn(edgePad, containerH - h - edgePad)
            }
            return Candidate(w, h, x, y)
        }

        val below = makeCandidate(
            rawW = containerW - 2 * edgePad,
            rawH = containerH - bounds.bottom - margin,
            anchorX = 0, anchorY = bounds.bottom + margin,
            centerAxis = 'y'
        )
        val above = makeCandidate(
            rawW = containerW - 2 * edgePad,
            rawH = bounds.top - margin,
            anchorX = 0, anchorY = 0,  // anchorY recalculated: top of popup = bounds.top - margin - h
            centerAxis = 'y'
        ).let { it.copy(y = (bounds.top - margin - it.h).coerceAtLeast(edgePad)) }

        val right = makeCandidate(
            rawW = containerW - bounds.right - margin,
            rawH = containerH - 2 * edgePad,
            anchorX = bounds.right + margin, anchorY = 0,
            centerAxis = 'x'
        )
        val left = makeCandidate(
            rawW = bounds.left - margin,
            rawH = containerH - 2 * edgePad,
            anchorX = 0, anchorY = 0,
            centerAxis = 'x'
        ).let { it.copy(x = (bounds.left - margin - it.w).coerceAtLeast(edgePad)) }

        val best = listOf(below, above, right, left).maxByOrNull { it.w.toLong() * it.h.toLong() }
            ?: below

        return FrameLayout.LayoutParams(best.w, best.h).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = best.x
            topMargin = best.y
        }
    }

    /**
     * Scroll the popup content by [deltaPx] pixels vertically.
     */
    fun scrollContent(deltaPx: Int) {
        webView?.evaluateJavascript("scrollContent($deltaPx)", null)
    }

    /**
     * Navigate to the next (+1) or previous (-1) entry in the popup.
     */
    fun navigateEntry(delta: Int) {
        webView?.evaluateJavascript("jumpEntry($delta)", null)
    }

    /**
     * Push the result of an Anki export back into the popup so the matching star
     * button reflects the actual outcome. Statuses must match popup.js'
     * setAnkiResult contract: "loading", "success", "exists", "error", "idle".
     */
    fun setAnkiResult(index: Int, status: String) {
        val safe = status.replace("'", "")
        webView?.evaluateJavascript("setAnkiResult($index, '$safe')", null)
    }

    fun hide() {
        webView?.visibility = View.GONE
    }

    fun destroy() {
        webView?.let { wv ->
            (wv.parent as? FrameLayout)?.removeView(wv)
            wv.destroy()
        }
        webView = null
        currentContainer = null
        currentEntries = emptyList()
        isPageLoaded = false
        pendingJson = null
    }

    private fun getOrCreateWebView(container: FrameLayout): WebView {
        webView?.let { existing ->
            if (existing.parent == container) return existing
            (existing.parent as? FrameLayout)?.removeView(existing)
            container.addView(existing)
            return existing
        }

        val wv = WebView(context).apply {
            setBackgroundColor(Color.argb(250, 20, 20, 25))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = true  // Required for file:///android_asset/
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = true  // Required for images from dictionaries/

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    isPageLoaded = true
                    // Inject any pending data
                    pendingJson?.let { json ->
                        pendingJson = null
                        injectData(json)
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    return true // Block navigation
                }
            }

            addJavascriptInterface(PopupJsInterface(), "YomidroidPopup")

            // Rounded corners
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    val radius = 12f * context.resources.displayMetrics.density
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }

            // Load the popup HTML from assets
            loadUrl(POPUP_URL)
        }

        container.addView(wv)
        webView = wv
        return wv
    }

    /**
     * Call setEntries(json) in the WebView to render entries.
     */
    private fun injectData(json: String) {
        val escaped = org.json.JSONObject.quote(json)
        webView?.evaluateJavascript("setEntries($escaped)", null)
    }

    /**
     * Serialize dictionary entries to JSON for the popup JS renderer.
     */
    private fun serializeEntries(entries: List<DictionaryEntry>, customCss: String?): String {
        val matched = entries.firstOrNull()?.matchedText
        val correctionOn = onCorrection != null
        return com.yomidroid.dictionary.EntrySerializer.serialize(
            context, entries, customCss, dictionaryCssMap,
            matchedText = if (correctionOn) matched else null,
            originalMatchedText = if (correctionOn) pendingOriginalMatchedText else null
        )
    }

    /**
     * Push the result of an async correction-candidate ranking back into the
     * open popup. [ranked] is the list of `(neighbor, hitCount)` pairs sorted
     * by descending hit count. JS reorders the sheet chips in place and adds
     * count badges.
     */
    fun setCorrectionRanking(originalChar: Char, ranked: List<Pair<Char, Int>>) {
        val arr = org.json.JSONArray()
        for ((c, n) in ranked) {
            arr.put(org.json.JSONObject().put("k", c.toString()).put("n", n))
        }
        val origQ = org.json.JSONObject.quote(originalChar.toString())
        val rankedQ = org.json.JSONObject.quote(arr.toString())
        webView?.evaluateJavascript("setCorrectionRanking($origQ, $rankedQ)", null)
    }

    private fun resizeToContent(contentHeightPx: Int) {
        val wv = webView ?: return
        val container = currentContainer ?: return

        val density = context.resources.displayMetrics.density
        val minHeight = (80 * density).toInt()
        val targetHeight = (contentHeightPx + (8 * density).toInt())
            .coerceIn(minHeight, maxPopupHeight)

        // Re-run smart positioning with the new height
        val lp = computeLayoutParams(container, maxPopupWidth, targetHeight, textBounds)
        wv.layoutParams = lp
    }

    inner class PopupJsInterface {
        @JavascriptInterface
        fun ankiExport(index: Int) {
            val entry = currentEntries.getOrNull(index) ?: return
            handler.post {
                onAnkiExport?.invoke(entry, index)
            }
        }

        @JavascriptInterface
        fun reportContentHeight(heightPx: Int) {
            handler.post {
                resizeToContent(heightPx)
            }
        }

        @JavascriptInterface
        fun speak(text: String) {
            handler.post {
                ttsManager.speak(text)
            }
        }

        @JavascriptInterface
        fun openKanji(character: String) {
            handler.post {
                val intent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(MainActivity.EXTRA_OPEN_KANJI, character)
                }
                context.startActivity(intent)
            }
        }

        /**
         * Synchronously return the visually similar neighbors of [character]
         * as a single concatenated string (one char per neighbor, up to ~8).
         * Called on the WebView JS thread; the table is preloaded in show().
         */
        @JavascriptInterface
        fun requestSimilarKanji(character: String): String {
            val ch = character.firstOrNull() ?: return ""
            return KanjiSimilarity.neighbors(ch).joinToString("")
        }

        @JavascriptInterface
        fun applyCorrection(correctedText: String) {
            val cb = onCorrection ?: return
            if (correctedText.isEmpty()) return
            handler.post { cb(correctedText) }
        }

        @JavascriptInterface
        fun requestCorrectionRanking(originalChar: String) {
            val cb = onRequestRanking ?: return
            val ch = originalChar.firstOrNull() ?: return
            handler.post { cb(ch) }
        }
    }
}
