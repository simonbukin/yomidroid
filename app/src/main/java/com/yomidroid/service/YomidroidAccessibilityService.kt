package com.yomidroid.service

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.yomidroid.anki.AnkiDroidExporter
import com.yomidroid.config.OcrConfig
import com.yomidroid.config.OcrConfigManager
import com.yomidroid.ocr.OcrEngine
import com.yomidroid.ocr.OcrEngineFactory
import com.yomidroid.anki.ExportResult
import com.yomidroid.config.ColorConfig
import com.yomidroid.config.ColorConfigManager
import com.yomidroid.dictionary.DictionaryEngine
import com.yomidroid.ocr.OcrResult
import com.yomidroid.ocr.OcrResultRepository
import com.yomidroid.ocr.UnifiedOcrContext

class YomidroidAccessibilityService : AccessibilityService() {

    // Word snapping cache entry - stores lookup results for instant word highlighting
    private data class CachedLookup(
        val matchLength: Int,
        val entries: List<com.yomidroid.dictionary.DictionaryEntry>
    )

    companion object {
        private const val TAG = "Yomidroid"

        // Timing constants
        private const val DOUBLE_TAP_TIMEOUT_MS = 300L
        private const val FAB_HIDE_DELAY_MS = 100L

        // Cache limits
        private const val MAX_LOOKUP_CACHE_SIZE = 100

        @Volatile
        var instance: YomidroidAccessibilityService? = null
            private set

        val isRunning: Boolean
            get() = instance != null
    }

    // Coroutine scope for background operations (file I/O, database writes)
    // Uses SupervisorJob so one failed child doesn't cancel siblings
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val handler = Handler(Looper.getMainLooper())

    private var fabView: CursorFabView? = null
    private var overlayView: OverlayTextView? = null
    private var fabParams: WindowManager.LayoutParams? = null
    private var fabTouchListener: FabTouchListener? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var statusBarHeight = 0

    // For double-tap detection
    private var lastTapTime = 0L

    // Store current OCR results for cursor-based lookup
    private var currentOcrResults: List<OcrResult> = emptyList()

    // Unified context for cross-line text search and highlighting
    private var unifiedContext: UnifiedOcrContext? = null

    // Keep clean screenshot for Anki export
    private var currentScreenshot: Bitmap? = null

    // OCR engine system
    private var ocrEngine: OcrEngine? = null
    private var ocrConfigManager: OcrConfigManager? = null

    private var dictionaryEngine: DictionaryEngine? = null
    private var ankiExporter: AnkiDroidExporter? = null
    private var colorConfigManager: ColorConfigManager? = null
    private var colorConfig: ColorConfig = ColorConfig()

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected")
        instance = this

        // Get screen metrics
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        // Get status bar height for coordinate alignment
        statusBarHeight = getStatusBarHeight()
        Log.d(TAG, "Status bar height: $statusBarHeight")

        // Initialize dictionary engine
        dictionaryEngine = DictionaryEngine(this)

        // Initialize Anki exporter
        ankiExporter = AnkiDroidExporter(this)

        // Initialize color config and load saved colors
        colorConfigManager = ColorConfigManager(this)
        loadColors()

        // Initialize OCR engine based on config
        ocrConfigManager = OcrConfigManager(this)
        initializeOcrEngine()

        // Create FAB
        createFab()
    }

    /**
     * Initialize OCR engine based on current config.
     * Called on service start and when settings change.
     */
    private fun initializeOcrEngine() {
        val config = ocrConfigManager?.getConfig() ?: OcrConfig()

        // Close existing engine
        ocrEngine?.close()

        // Create engine based on config
        ocrEngine = OcrEngineFactory.createEngine(this, config.selectedEngine)

        Log.d(TAG, "OCR engine initialized: ${config.selectedEngine}")
    }

    /**
     * Reload OCR settings (call when settings change).
     */
    fun reloadOcrSettings() {
        initializeOcrEngine()
    }

    /**
     * Reload colors from saved config (call when settings change).
     */
    fun loadColors() {
        colorConfig = colorConfigManager?.getConfig() ?: ColorConfig()
        // Update FAB colors if it exists
        fabView?.updateColors(
            fabColor = colorConfig.fabColor,
            cursorDotColor = colorConfig.cursorDotColor,
            accentColor = colorConfig.accentColor
        )
        // Update overlay highlight color if it exists
        overlayView?.updateHighlightColor(colorConfig.highlightColor)
        // Update FAB visibility based on setting
        updateFabVisibility()
    }

    /**
     * Update FAB visibility based on the fabEnabled setting.
     * Called by TileService when the Quick Settings tile is toggled.
     */
    fun updateFabVisibility() {
        val config = colorConfigManager?.getConfig() ?: return
        fabView?.visibility = if (config.fabEnabled) View.VISIBLE else View.GONE
        Log.d(TAG, "FAB visibility updated: enabled=${config.fabEnabled}")
    }

    private fun getStatusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            resources.getDimensionPixelSize(resourceId)
        } else {
            // Fallback to a reasonable default (24dp)
            (24 * resources.displayMetrics.density).toInt()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to process accessibility events
        // We only use this service for takeScreenshot()
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "Configuration changed: orientation=${newConfig.orientation}")

        // Update screen dimensions
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        // Update FAB position to stay on screen
        updateFabPositionForNewDimensions()

        // Dismiss overlay if visible (coordinates won't match anymore)
        if (overlayView != null) {
            removeOverlay()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Accessibility service destroyed")
        instance = null

        // Cancel all pending coroutines
        serviceScope.cancel()

        removeFab()
        removeOverlay()

        // Close OCR engine
        ocrEngine?.close()
    }

    private fun createFab() {
        Log.d(TAG, "createFab called, screenWidth=$screenWidth, screenHeight=$screenHeight")

        fabView = CursorFabView(this)

        // Let the view measure itself
        fabView?.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val fabWidth = fabView?.measuredWidth ?: 200
        val fabHeight = fabView?.measuredHeight ?: 200

        fabParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,  // Same coord system as overlay
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - fabWidth - 16
            y = screenHeight / 2 - fabHeight / 2
        }

        try {
            // Apply configured colors
            fabView?.updateColors(
                fabColor = colorConfig.fabColor,
                cursorDotColor = colorConfig.cursorDotColor,
                accentColor = colorConfig.accentColor
            )

            fabTouchListener = FabTouchListener()
            fabView?.setOnTouchListener(fabTouchListener)
            windowManager.addView(fabView, fabParams)
            Log.d(TAG, "FAB added successfully, size: ${fabWidth}x${fabHeight}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add FAB: ${e.message}", e)
        }
    }

    private fun removeFab() {
        // Cancel any ongoing fling animation to prevent callbacks after removal
        fabTouchListener?.cancelFling()

        fabView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove FAB: ${e.message}")
            }
        }
        fabView = null
    }

    private fun updateFabPositionForNewDimensions() {
        val params = fabParams ?: return
        val fab = fabView ?: return
        val fabWidth = fab.width.takeIf { it > 0 } ?: fab.measuredWidth
        val fabHeight = fab.height.takeIf { it > 0 } ?: fab.measuredHeight

        // Clamp FAB position to stay fully on screen
        params.x = params.x.coerceIn(0, screenWidth - fabWidth)
        params.y = params.y.coerceIn(0, screenHeight - fabHeight)

        try {
            windowManager.updateViewLayout(fab, params)
            Log.d(TAG, "Updated FAB position for new dimensions: ${screenWidth}x${screenHeight}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update FAB position: ${e.message}")
        }
    }

    private fun captureScreen() {
        Log.d(TAG, "captureScreen called")

        // Check API level
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(this, "Screenshot requires Android 11+", Toast.LENGTH_SHORT).show()
            return
        }

        // Hide FAB before capture
        fabView?.visibility = View.INVISIBLE

        // Small delay to ensure FAB is hidden before screenshot capture
        handler.postDelayed({
            takeScreenshotAndProcess()
        }, FAB_HIDE_DELAY_MS)
    }

    private fun takeScreenshotAndProcess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        Log.d(TAG, "Screenshot success")
                        updateFabVisibility()

                        try {
                            val hardwareBuffer = result.hardwareBuffer
                            val colorSpace = result.colorSpace

                            val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                            if (bitmap != null) {
                                // Convert to software bitmap for ML Kit
                                val softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                                Log.d(TAG, "Processing OCR on bitmap ${softwareBitmap.width}x${softwareBitmap.height}")
                                processOcr(softwareBitmap)
                            } else {
                                Log.e(TAG, "Failed to create bitmap from hardware buffer")
                            }

                            hardwareBuffer.close()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing screenshot: ${e.message}", e)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "Screenshot failed with error code: $errorCode")
                        updateFabVisibility()

                        val errorMsg = when (errorCode) {
                            ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "Internal error"
                            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "No accessibility access"
                            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "Too fast, wait a moment"
                            ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "Invalid display"
                            ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "Invalid window"
                            else -> "Unknown error ($errorCode)"
                        }

                        handler.post {
                            Toast.makeText(
                                this@YomidroidAccessibilityService,
                                "Screenshot failed: $errorMsg",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            )
        }
    }

    private fun processOcr(bitmap: Bitmap) {
        val engine = ocrEngine ?: return

        serviceScope.launch {
            val result = engine.processImage(bitmap)

            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { ocrResults ->
                        Log.d(TAG, "OCR success, found ${ocrResults.size} results")
                        val merged = mergeAdjacentLines(ocrResults)
                        if (merged.isNotEmpty()) {
                            showTextOverlay(merged, bitmap)
                        } else {
                            Log.d(TAG, "No OCR results found")
                            Toast.makeText(
                                this@YomidroidAccessibilityService,
                                "No text found",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    onFailure = { e ->
                        Log.e(TAG, "OCR failed: ${e.message}", e)
                        Toast.makeText(
                            this@YomidroidAccessibilityService,
                            "OCR failed: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }
        }
    }

    /**
     * Merges adjacent OCR lines that are on the same horizontal row.
     * ML Kit can fragment continuous visual lines into multiple OcrResult objects,
     * which breaks dictionary lookup across word boundaries.
     */
    private fun mergeAdjacentLines(results: List<OcrResult>): List<OcrResult> {
        if (results.size <= 1) return results

        // Sort by vertical position (top of bounding box)
        val sorted = results.sortedBy { it.lineBounds.top }

        val merged = mutableListOf<OcrResult>()
        var current = sorted[0]

        for (i in 1 until sorted.size) {
            val next = sorted[i]

            // Check if lines are on the same horizontal row
            val yDiff = Math.abs(current.lineBounds.centerY() - next.lineBounds.centerY())
            val tolerance = current.lineBounds.height() * 0.5f

            if (yDiff <= tolerance) {
                // Merge: determine order by X position
                val (left, right) = if (current.lineBounds.left < next.lineBounds.left) {
                    current to next
                } else {
                    next to current
                }

                current = OcrResult(
                    text = left.text + right.text,
                    lineBounds = Rect(
                        minOf(left.lineBounds.left, right.lineBounds.left),
                        minOf(left.lineBounds.top, right.lineBounds.top),
                        maxOf(left.lineBounds.right, right.lineBounds.right),
                        maxOf(left.lineBounds.bottom, right.lineBounds.bottom)
                    ),
                    charBounds = left.charBounds + right.charBounds
                )
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)

        return merged
    }

    private fun showTextOverlay(results: List<OcrResult>, screenshot: Bitmap) {
        removeOverlay()

        // Clear lookup cache for new overlay
        fabTouchListener?.clearCache()

        // Update screen dimensions (in case orientation changed)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val oldWidth = screenWidth
        val oldHeight = screenHeight
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        // If dimensions changed, update FAB position to stay on screen
        if (oldWidth != screenWidth || oldHeight != screenHeight) {
            updateFabPositionForNewDimensions()
        }

        // Store results for cursor-based lookup
        currentOcrResults = results

        // Create unified context for cross-line search and highlighting
        unifiedContext = UnifiedOcrContext(results)

        // Share OCR results with repository for Grammar Analyzer
        OcrResultRepository.updateOcrResults(results)

        // Store clean screenshot for Anki export
        currentScreenshot = screenshot

        // Calculate scale factors for coordinate transformation
        // This maps ML Kit bounding boxes (in bitmap space) to screen coordinates
        val scaleX = screenWidth.toFloat() / screenshot.width
        val scaleY = screenHeight.toFloat() / screenshot.height
        Log.d(TAG, "Scale factors: scaleX=$scaleX, scaleY=$scaleY " +
                   "(screen: ${screenWidth}x${screenHeight}, " +
                   "bitmap: ${screenshot.width}x${screenshot.height})")

        overlayView = OverlayTextView(
            context = this,
            ocrResults = results,
            unifiedContext = unifiedContext,
            screenshot = screenshot,
            scaleX = scaleX,
            scaleY = scaleY,
            highlightColor = colorConfig.highlightColor,
            onTextTapped = { ocrResult, charIndex ->
                onTextTapped(ocrResult, charIndex)
            },
            onDismissRequested = {
                removeOverlay()
            },
            onAnkiExport = { entry, sentence ->
                exportToAnki(entry, sentence)
            },
            onSaveToHistory = { entry, sentence ->
                saveToHistory(entry, sentence)
            }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(overlayView, params)

        // Bring FAB to front so it's above the overlay
        fabView?.let {
            try {
                windowManager.removeView(it)
                windowManager.addView(it, fabParams)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bring FAB to front: ${e.message}")
            }
        }
    }

    private fun removeOverlay() {
        overlayView?.let {
            // Save to history if popup was open for 1+ second
            it.onDismiss()
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove overlay: ${e.message}")
            }
        }
        overlayView = null
        currentOcrResults = emptyList()
        unifiedContext = null
        currentScreenshot = null

        // Clear lookup cache when overlay is dismissed
        fabTouchListener?.clearCache()
    }

    private fun exportToAnki(entry: com.yomidroid.dictionary.DictionaryEntry, sentence: String) {
        val exporter = ankiExporter ?: return
        val overlay = overlayView ?: return

        // Set loading state immediately
        overlay.setAnkiButtonLoading()

        Thread {
            val result = exporter.exportCard(entry, sentence, currentScreenshot)

            handler.post {
                when (result) {
                    is ExportResult.Success -> {
                        overlay.setAnkiButtonSuccess()
                        Toast.makeText(this, "Added to Anki!", Toast.LENGTH_SHORT).show()
                    }
                    is ExportResult.AlreadyExists -> {
                        overlay.setAnkiButtonAlreadyExported()
                        Toast.makeText(this, "Already in Anki", Toast.LENGTH_SHORT).show()
                    }
                    is ExportResult.AnkiNotInstalled -> {
                        overlay.resetAnkiButtonState()
                        Toast.makeText(this, "AnkiDroid not installed", Toast.LENGTH_LONG).show()
                        // Open Play Store
                        try {
                            val intent = exporter.getPlayStoreIntent()
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(intent)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to open Play Store: ${e.message}")
                        }
                    }
                    is ExportResult.NotConfigured -> {
                        overlay.resetAnkiButtonState()
                        Toast.makeText(this, "Configure Anki in Yomidroid settings", Toast.LENGTH_LONG).show()
                    }
                    is ExportResult.PermissionDenied -> {
                        overlay.resetAnkiButtonState()
                        Toast.makeText(this, "AnkiDroid permission denied", Toast.LENGTH_LONG).show()
                    }
                    is ExportResult.ApiNotEnabled -> {
                        overlay.resetAnkiButtonState()
                        Toast.makeText(this, "Enable Yomidroid in AnkiDroid Settings → Advanced → AnkiDroid API", Toast.LENGTH_LONG).show()
                    }
                    is ExportResult.Error -> {
                        overlay.resetAnkiButtonState()
                        Toast.makeText(this, "Export failed: ${result.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    private fun saveToHistory(entry: com.yomidroid.dictionary.DictionaryEntry, sentence: String) {
        // Capture screenshot reference before it might be nulled
        val screenshot = currentScreenshot

        // Use proper coroutine scope instead of Thread+runBlocking
        serviceScope.launch {
            try {
                // Save screenshot to persistent storage
                var screenshotPath: String? = null
                if (screenshot != null) {
                    val timestamp = System.currentTimeMillis()
                    val screenshotDir = java.io.File(filesDir, "history_screenshots")
                    if (!screenshotDir.exists()) {
                        screenshotDir.mkdirs()
                    }
                    val screenshotFile = java.io.File(screenshotDir, "history_${timestamp}.jpg")
                    try {
                        java.io.FileOutputStream(screenshotFile).use { out ->
                            screenshot.compress(Bitmap.CompressFormat.JPEG, 80, out)
                        }
                        screenshotPath = screenshotFile.absolutePath
                        Log.d(TAG, "Saved screenshot: $screenshotPath")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save screenshot: ${e.message}")
                    }
                }

                val historyRecord = com.yomidroid.data.LookupHistoryEntity(
                    word = entry.expression,
                    reading = entry.reading,
                    definition = entry.glossary.take(3).joinToString("; "),
                    screenshotPath = screenshotPath,
                    sentence = sentence.ifEmpty { null }
                )
                com.yomidroid.data.AppDatabase.getInstance(this@YomidroidAccessibilityService).historyDao().insert(historyRecord)
                Log.d(TAG, "Saved to history: ${entry.expression}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save to history: ${e.message}")
            }
        }
    }

    private fun onTextTapped(ocrResult: OcrResult, charIndex: Int) {
        val context = unifiedContext ?: return

        // Get the unified index for this tap position
        val unifiedIndex = context.getUnifiedIndex(ocrResult, charIndex)
        if (unifiedIndex < 0) return

        Thread {
            // Search from this position in the unified text (all screen text)
            val searchText = context.unifiedText.substring(unifiedIndex)
            val entries = dictionaryEngine?.findTerms(searchText) ?: emptyList()

            handler.post {
                if (entries.isNotEmpty()) {
                    val firstEntry = entries.first()
                    // Show all definitions with unified match info for cross-line highlighting
                    overlayView?.showDefinitionsWithUnifiedMatch(
                        entries = entries,
                        unifiedStartIndex = unifiedIndex,
                        matchLength = firstEntry.matchedText.length,
                        context = context
                    )
                } else {
                    overlayView?.showNoResults()
                }
            }
        }.start()
    }

    private inner class FabTouchListener : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var initialTouchX = 0f
        private var initialTouchY = 0f
        private var isClick = true
        private var lastLookedUpText: String? = null
        private var lastLookedUpIndex: Int = -1

        // Physics-based movement
        private var velocityTracker: VelocityTracker? = null
        private var physicsAnimator: FabPhysicsAnimator? = null

        // Word snapping cache - stores lookup results for instant word highlighting
        private val lookupCache = mutableMapOf<String, CachedLookup>()

        private fun getCacheKey(text: String, charIndex: Int) = "$text:$charIndex"

        fun clearCache() {
            lookupCache.clear()
        }

        fun cancelFling() {
            physicsAnimator?.cancelAll()
        }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = fabParams ?: return false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Cancel any ongoing physics animations
                    physicsAnimator?.cancelAll()

                    // Start tracking velocity
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain()
                    velocityTracker?.addMovement(event)

                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)

                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isClick = false

                        // Calculate new position with rubber-banding at edges
                        var newX = initialX + dx
                        var newY = initialY + dy

                        val fabW = fabView?.width ?: 100
                        val fabH = fabView?.height ?: 100
                        val margin = 20

                        val minX = -fabW + margin
                        val maxX = screenWidth - margin
                        val minY = -fabH + margin
                        val maxY = screenHeight - margin

                        // Rubber-band factor (0.3 = 30% of over-drag distance)
                        val rubberBandFactor = 0.3f

                        if (newX < minX) {
                            newX = minX + ((newX - minX) * rubberBandFactor).toInt()
                        } else if (newX > maxX) {
                            newX = maxX + ((newX - maxX) * rubberBandFactor).toInt()
                        }

                        if (newY < minY) {
                            newY = minY + ((newY - minY) * rubberBandFactor).toInt()
                        } else if (newY > maxY) {
                            newY = maxY + ((newY - maxY) * rubberBandFactor).toInt()
                        }

                        params.x = newX
                        params.y = newY
                        windowManager.updateViewLayout(view, params)

                        // Perform cursor-based lookup if overlay is visible
                        if (overlayView != null) {
                            lookupAtCursor()
                        }
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    velocityTracker?.addMovement(event)
                    velocityTracker?.computeCurrentVelocity(1000)  // pixels per second

                    if (isClick) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < DOUBLE_TAP_TIMEOUT_MS) {
                            // Double tap - toggle cursor side (don't dismiss!)
                            fabView?.toggleCursorSide()
                            Log.d(TAG, "Double tap - toggled cursor side")
                        } else {
                            // Single tap - only scan if no overlay, don't dismiss
                            if (overlayView == null) {
                                captureScreen()
                            }
                            // If overlay is visible, do nothing - let user tap on overlay to dismiss
                        }
                        lastTapTime = now
                    } else {
                        // Drag ended - apply physics-based fling
                        val vx = velocityTracker?.xVelocity ?: 0f
                        val vy = velocityTracker?.yVelocity ?: 0f

                        // Initialize or update physics animator
                        if (physicsAnimator == null) {
                            physicsAnimator = FabPhysicsAnimator(
                                view = view,
                                params = params,
                                windowManager = windowManager,
                                onPositionUpdate = {
                                    if (overlayView != null) {
                                        lookupAtCursor()
                                    }
                                }
                            )
                        }

                        // Update dimensions
                        physicsAnimator?.apply {
                            screenWidth = this@YomidroidAccessibilityService.screenWidth
                            screenHeight = this@YomidroidAccessibilityService.screenHeight
                            fabWidth = fabView?.width ?: 100
                            fabHeight = fabView?.height ?: 100
                        }

                        // Apply fling or spring back from rubber-band
                        if (abs(vx) > FabPhysicsAnimator.MIN_FLING_VELOCITY ||
                            abs(vy) > FabPhysicsAnimator.MIN_FLING_VELOCITY) {
                            physicsAnimator?.fling(vx, vy)
                        } else {
                            // No fling velocity, but spring back if rubber-banded
                            physicsAnimator?.fling(0f, 0f)
                        }

                        // Perform final lookup
                        if (overlayView != null) {
                            lookupAtCursor()
                        }
                    }

                    velocityTracker?.recycle()
                    velocityTracker = null
                    return true
                }
            }
            return false
        }

        private fun lookupAtCursor() {
            val fab = fabView ?: return
            val overlay = overlayView ?: return
            val context = unifiedContext ?: return

            val cursorX = fab.getCursorScreenX()
            val cursorY = fab.getCursorScreenY()

            // Find text at cursor without modifying highlight yet
            val textInfo = overlay.findTextAtCursor(cursorX, cursorY)

            if (textInfo == null) {
                // Cursor not over any text - clear highlight
                if (lastLookedUpText != null) {
                    lastLookedUpText = null
                    lastLookedUpIndex = -1
                    overlay.clearHighlight()
                }
                return
            }

            val (ocrResult, charIndex, _) = textInfo

            // Convert to unified index for consistent caching across all OcrResults
            val unifiedIndex = context.getUnifiedIndex(ocrResult, charIndex)
            if (unifiedIndex < 0) return

            // Skip if same unified position
            if (context.unifiedText == lastLookedUpText && unifiedIndex == lastLookedUpIndex) {
                return
            }

            lastLookedUpText = context.unifiedText
            lastLookedUpIndex = unifiedIndex

            // Use unified index for cache key (unique across all OcrResults)
            val cacheKey = "unified:$unifiedIndex"

            // Check cache first for instant word snapping
            val cached = lookupCache[cacheKey]
            if (cached != null) {
                // Cache hit - immediately highlight full word (may span OcrResults)
                overlay.setHighlightFromUnified(context, unifiedIndex, cached.matchLength)
                if (cached.entries.isNotEmpty()) {
                    overlay.showDefinitionsAtCursor(cached.entries, cursorX, cursorY)
                } else {
                    overlay.hideDefinition()
                }
                return
            }

            // Cache miss - show single char highlight immediately as fallback
            overlay.setHighlight(ocrResult, charIndex, 1)

            // Perform dictionary lookup on low-priority background thread
            // Low priority to avoid competing with emulator audio threads (e.g., PPSSPP)
            Thread {
                val searchText = context.unifiedText.substring(unifiedIndex)
                val entries = dictionaryEngine?.findTerms(searchText) ?: emptyList()
                val matchLength = entries.firstOrNull()?.matchedText?.length ?: 1

                // Cache the result for instant lookup on subsequent cursor moves
                synchronized(lookupCache) {
                    if (lookupCache.size > MAX_LOOKUP_CACHE_SIZE) {
                        lookupCache.clear()
                    }
                    lookupCache[cacheKey] = CachedLookup(matchLength, entries)
                }

                handler.post {
                    // Verify cursor hasn't moved before updating
                    if (context.unifiedText == lastLookedUpText && unifiedIndex == lastLookedUpIndex) {
                        // Use unified highlighting for cross-line matches
                        overlay.setHighlightFromUnified(context, unifiedIndex, matchLength)
                        if (entries.isNotEmpty()) {
                            overlay.showDefinitionsAtCursor(entries, cursorX, cursorY)
                        } else {
                            overlay.hideDefinition()
                        }
                    }
                }
            }.start()
        }
    }
}
