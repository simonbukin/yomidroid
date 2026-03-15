package com.yomidroid.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
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
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.yomidroid.MainActivity
import com.yomidroid.anki.AnkiDroidExporter
import com.yomidroid.config.BindableAction
import com.yomidroid.config.InputConfig
import com.yomidroid.config.InputConfigManager
import com.yomidroid.config.OcrConfig
import com.yomidroid.config.OcrConfigManager
import com.yomidroid.ocr.OcrEngine
import com.yomidroid.ocr.OcrEngineFactory
import com.yomidroid.anki.ExportResult
import com.yomidroid.config.ColorConfig
import com.yomidroid.config.ColorConfigManager
import com.yomidroid.dictionary.DictionaryEngine
import com.yomidroid.ocr.OcrResult
import java.util.concurrent.atomic.AtomicReference
import com.yomidroid.dictionary.LookupResultRepository
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

        // D-pad movement constants
        private const val DPAD_FRAME_MS = 16L  // ~60fps
        private const val CHAR_NAV_REPEAT_MS = 80L  // repeat rate for held D-pad char navigation
        private const val CHAR_NAV_INITIAL_DELAY_MS = 250L  // delay before repeat starts

        // Joystick/hat-switch constants
        private const val HAT_THRESHOLD = 0.5f
        private const val STICK_DEAD_ZONE = 0.18f
        private const val STICK_FRAME_MS = 16L  // ~60fps

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
    private var overlayContainer: android.widget.FrameLayout? = null
    private var overlayPopupWebView: OverlayPopupWebView? = null
    private var fabParams: WindowManager.LayoutParams? = null
    private var fabTouchListener: FabTouchListener? = null

    // When true, FAB was hidden by TOGGLE_CURSOR and should stay hidden
    // until toggled back on or overlay is dismissed
    private var fabHiddenByToggle = false

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
    // Original screenshot dimensions (before scaling) for coordinate mapping
    private var originalScreenshotWidth = 0
    private var originalScreenshotHeight = 0

    // OCR engine system
    private var ocrEngine: OcrEngine? = null
    private var ocrConfigManager: OcrConfigManager? = null

    private val dictionaryEngineRef = AtomicReference<DictionaryEngine?>(null)
    private var ankiExporter: AnkiDroidExporter? = null
    private var colorConfigManager: ColorConfigManager? = null
    private var colorConfig: ColorConfig = ColorConfig()
    private var isDecoupledMode: Boolean = false

    // Dictionary config for CSS etc.
    private var dictConfigManager: com.yomidroid.config.DictionaryConfigManager? = null

    // Input config for hardware button bindings
    private var inputConfigManager: InputConfigManager? = null
    private var inputConfig: InputConfig = InputConfig()

    // Service-level lookup cache (shared between touch and hardware input) — LRU eviction
    private val lookupCache = object : LinkedHashMap<String, CachedLookup>(
        MAX_LOOKUP_CACHE_SIZE + 1, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedLookup>?): Boolean {
            return size > MAX_LOOKUP_CACHE_SIZE
        }
    }
    private var lastLookedUpText: String? = null
    private var lastLookedUpIndex: Int = -1

    // D-pad discrete character navigation grid
    private var charGrid: List<List<Pair<Float, Float>>> = emptyList()

    // D-pad continuous movement/scroll state
    private val activeDirections = mutableSetOf<BindableAction>()
    private var dpadMoveRunnable: Runnable? = null
    private var dpadHoldStartTime = 0L

    // Hat switch (D-pad on gamepad) state — edge-triggered
    private var lastHatX = 0f
    private var lastHatY = 0f

    // Analog stick continuous movement state
    private var stickX = 0f
    private var stickY = 0f
    private var stickMoveRunnable: Runnable? = null

    // Layer key state tracking
    private var isLayerKeyHeld = false

    // Popup lock mode: when true, D-pad scrolls popup content instead of moving cursor
    private var popupLocked = false

    // Track which actions have shown a first-fire toast (only show once per service lifecycle)
    private val toastedActions = mutableSetOf<BindableAction>()

    // Foreground app tracking for history context
    private var currentForegroundPackage: String? = null
    private var currentForegroundAppLabel: String? = null
    private var currentWindowTitle: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected")
        instance = this

        // Programmatically ensure key event filtering is enabled.
        // Some devices/ROMs don't honor the XML canRequestFilterKeyEvents attribute.
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        Log.d(TAG, "ServiceInfo flags: 0x${Integer.toHexString(serviceInfo.flags)}, " +
                "canRequestFilterKeyEvents=${(serviceInfo.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0}")

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
        dictionaryEngineRef.set(DictionaryEngine(this))

        // Initialize Anki exporter
        ankiExporter = AnkiDroidExporter(this)

        // Initialize color config and load saved colors
        colorConfigManager = ColorConfigManager(this)
        loadColors()

        // Initialize OCR engine based on config
        ocrConfigManager = OcrConfigManager(this)
        initializeOcrEngine()

        // Initialize dictionary config for CSS
        dictConfigManager = com.yomidroid.config.DictionaryConfigManager(this)

        // Initialize input config for hardware bindings
        inputConfigManager = InputConfigManager(this)
        inputConfig = inputConfigManager?.getConfig() ?: InputConfig()
        Log.d(TAG, "Input config: enabled=${inputConfig.enabled}, " +
                "layerKey=${inputConfig.layerKeyCode}, " +
                "API=${Build.VERSION.SDK_INT}, " +
                "joystickMode=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) "onMotionEvent" else "focusable-overlay"}")

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
     * Reload dictionary databases from config (call when dictionary settings change).
     */
    fun reloadDictionaries() {
        val configManager = com.yomidroid.config.DictionaryConfigManager(this)
        com.yomidroid.data.DictionaryDb.getInstance(this).reloadFromConfig(configManager)
        Log.d(TAG, "Dictionaries reloaded from config")
    }

    /**
     * Reload colors from saved config (call when settings change).
     */
    fun loadColors() {
        colorConfig = colorConfigManager?.getConfig() ?: ColorConfig()
        isDecoupledMode = colorConfigManager?.isDecoupledMode() ?: false
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
        val visible = config.fabEnabled && !fabHiddenByToggle
        fabView?.visibility = if (visible) View.VISIBLE else View.GONE
        Log.d(TAG, "FAB visibility updated: enabled=${config.fabEnabled}, hiddenByToggle=$fabHiddenByToggle")
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
        if (event == null) return

        // Track foreground app for history context tagging
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            // Filter out our own package
            if (pkg == packageName) return

            currentForegroundPackage = pkg
            currentWindowTitle = event.text.firstOrNull()?.toString()

            // Resolve human-readable app label
            currentForegroundAppLabel = try {
                val appInfo = packageManager.getApplicationInfo(pkg, 0)
                packageManager.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                null
            }

            Log.d(TAG, "Foreground app: $currentForegroundAppLabel ($currentForegroundPackage), window: $currentWindowTitle")
        }
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

        // Stop D-pad and stick movement
        stopDpadMovement()
        stopStickMovement()

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
        popupLocked = false

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
                                Log.d(TAG, "Screenshot: ${softwareBitmap.width}x${softwareBitmap.height}, " +
                                    "Screen: ${screenWidth}x${screenHeight}, " +
                                    "Scale: ${screenWidth.toFloat()/softwareBitmap.width}x${screenHeight.toFloat()/softwareBitmap.height}")
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
     * Merges adjacent OCR lines that are on the same row (horizontal) or column (vertical).
     * ML Kit can fragment continuous visual lines into multiple OcrResult objects,
     * which breaks dictionary lookup across word boundaries.
     * Detects vertical text (manga/VN) by aspect ratio and merges columns right-to-left.
     */
    private fun mergeAdjacentLines(results: List<OcrResult>): List<OcrResult> {
        if (results.size <= 1) return results

        // Detect orientation: vertical text lines are taller than wide
        val isVertical = results.map {
            it.lineBounds.height().toFloat() / it.lineBounds.width().coerceAtLeast(1).toFloat()
        }.average() > 2.0

        return if (isVertical) {
            // Sort right-to-left (Japanese vertical reading order), merge columns
            val sorted = results.sortedByDescending { it.lineBounds.centerX() }
            mergeByAxis(sorted) { current, next ->
                val xDiff = Math.abs(current.lineBounds.centerX() - next.lineBounds.centerX())
                val tolerance = current.lineBounds.width() * 0.5f
                xDiff <= tolerance
            }
        } else {
            // Horizontal: sort top-to-bottom, merge rows
            val sorted = results.sortedBy { it.lineBounds.top }
            mergeByAxis(sorted) { current, next ->
                val yDiff = Math.abs(current.lineBounds.centerY() - next.lineBounds.centerY())
                val tolerance = current.lineBounds.height() * 0.5f
                yDiff <= tolerance
            }
        }
    }

    /**
     * Merge adjacent OCR results using a predicate to determine if two results
     * belong to the same group (row or column). Within a group, segments are
     * ordered by position (left-to-right for horizontal, top-to-bottom for vertical).
     */
    private fun mergeByAxis(
        sorted: List<OcrResult>,
        shouldMerge: (OcrResult, OcrResult) -> Boolean
    ): List<OcrResult> {
        val merged = mutableListOf<OcrResult>()
        var current = sorted[0]

        for (i in 1 until sorted.size) {
            val next = sorted[i]

            if (shouldMerge(current, next)) {
                // Determine reading order: by top for vertical columns, by left for horizontal rows
                val (first, second) = if (current.lineBounds.top < next.lineBounds.top ||
                    (current.lineBounds.top == next.lineBounds.top && current.lineBounds.left < next.lineBounds.left)) {
                    current to next
                } else {
                    next to current
                }

                current = OcrResult(
                    text = first.text + second.text,
                    lineBounds = Rect(
                        minOf(first.lineBounds.left, second.lineBounds.left),
                        minOf(first.lineBounds.top, second.lineBounds.top),
                        maxOf(first.lineBounds.right, second.lineBounds.right),
                        maxOf(first.lineBounds.bottom, second.lineBounds.bottom)
                    ),
                    charBounds = first.charBounds + second.charBounds
                )
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)

        return merged
    }

    private fun storeScreenshot(fullBitmap: Bitmap) {
        val maxDim = 1080f
        val scale = maxDim / maxOf(fullBitmap.width, fullBitmap.height)
        if (scale < 1f) {
            currentScreenshot = Bitmap.createScaledBitmap(
                fullBitmap,
                (fullBitmap.width * scale).toInt(),
                (fullBitmap.height * scale).toInt(),
                true
            )
            fullBitmap.recycle()
        } else {
            currentScreenshot = fullBitmap
        }
    }

    private fun showTextOverlay(results: List<OcrResult>, screenshot: Bitmap) {
        removeOverlay()

        // Clear lookup cache for new overlay
        clearLookupCache()

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

        // Store original dimensions for coordinate mapping before any scaling
        originalScreenshotWidth = screenshot.width
        originalScreenshotHeight = screenshot.height

        // Store clean screenshot for Anki export (scaled down to save memory)
        storeScreenshot(screenshot)

        // Calculate scale factors for coordinate transformation
        // This maps ML Kit bounding boxes (in original bitmap space) to screen coordinates
        val scaleX = screenWidth.toFloat() / originalScreenshotWidth
        val scaleY = screenHeight.toFloat() / originalScreenshotHeight
        Log.d(TAG, "Scale factors: scaleX=$scaleX, scaleY=$scaleY " +
                   "(screen: ${screenWidth}x${screenHeight}, " +
                   "original bitmap: ${originalScreenshotWidth}x${originalScreenshotHeight})")

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
            },
            onDismissRichPopup = {
                overlayPopupWebView?.hide()
            }
        )

        // Pre-API 34: overlay must be focusable to receive joystick MotionEvents
        // via onGenericMotionEvent(). On API 34+ the service's onMotionEvent() handles this.
        val overlayFlags = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE && inputConfig.enabled) {
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            overlayFlags,
            PixelFormat.TRANSLUCENT
        )

        // Wrap overlay in FrameLayout for WebView popup support
        val container = android.widget.FrameLayout(this)
        container.addView(overlayView, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ))
        overlayContainer = container
        windowManager.addView(container, params)

        // On pre-API 34, request focus so the overlay receives joystick MotionEvents
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE && inputConfig.enabled) {
            overlayView?.isFocusable = true
            overlayView?.isFocusableInTouchMode = true
            overlayView?.requestFocus()
        }

        // Bring FAB to front so it's above the overlay
        fabView?.let {
            try {
                windowManager.removeView(it)
                windowManager.addView(it, fabParams)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bring FAB to front: ${e.message}")
            }
        }

        // Register joystick/gamepad MotionEvent interception while overlay is visible
        updateMotionEventSources()

        // Build character grid for discrete D-pad navigation
        handler.post {
            charGrid = overlayView?.getCharacterGrid() ?: emptyList()
        }
    }

    private fun removeOverlay() {
        // Destroy WebView popup first
        overlayPopupWebView?.destroy()
        overlayPopupWebView = null

        overlayView?.let {
            // Save to history if popup was open for 500ms+
            it.onDismiss()
        }

        // Remove the container (which holds both overlayView and any WebView popup)
        overlayContainer?.let { container ->
            try {
                windowManager.removeView(container)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove overlay container: ${e.message}")
            }
        }
        overlayContainer = null
        overlayView = null
        currentOcrResults = emptyList()
        unifiedContext = null
        currentScreenshot = null
        originalScreenshotWidth = 0
        originalScreenshotHeight = 0
        charGrid = emptyList()

        // Reset cursor toggle, popup lock, and joystick/hat state
        popupLocked = false
        fabHiddenByToggle = false
        lastHatX = 0f
        lastHatY = 0f
        stopStickMovement()

        // Unregister joystick/gamepad MotionEvent interception
        updateMotionEventSources()

        // Clear lookup cache when overlay is dismissed
        clearLookupCache()
    }

    private fun exportToAnki(entry: com.yomidroid.dictionary.DictionaryEntry, sentence: String) {
        val exporter = ankiExporter ?: return
        val overlay = overlayView ?: return

        // Set loading state immediately
        overlay.setAnkiButtonLoading()

        serviceScope.launch {
            val result = exporter.exportCard(entry, sentence, currentScreenshot)

            withContext(Dispatchers.Main) {
                when (result) {
                    is ExportResult.Success -> {
                        overlay.setAnkiButtonSuccess()
                        Toast.makeText(this@YomidroidAccessibilityService, "Added to Anki!", Toast.LENGTH_SHORT).show()
                    }
                    is ExportResult.AlreadyExists -> {
                        overlay.setAnkiButtonAlreadyExported()
                        Toast.makeText(this@YomidroidAccessibilityService, "Already in Anki", Toast.LENGTH_SHORT).show()
                    }
                    is ExportResult.AnkiNotInstalled -> {
                        overlay.resetAnkiButtonState()
                        Toast.makeText(this@YomidroidAccessibilityService, "AnkiDroid not installed", Toast.LENGTH_LONG).show()
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
                        Toast.makeText(this@YomidroidAccessibilityService, "Configure Anki in Yomidroid settings", Toast.LENGTH_LONG).show()
                    }
                    is ExportResult.PermissionDenied -> {
                        overlay.resetAnkiButtonState()
                        Toast.makeText(this@YomidroidAccessibilityService, "AnkiDroid permission denied", Toast.LENGTH_LONG).show()
                    }
                    is ExportResult.ApiNotEnabled -> {
                        overlay.resetAnkiButtonState()
                        Toast.makeText(this@YomidroidAccessibilityService, "Enable Yomidroid in AnkiDroid Settings → Advanced → AnkiDroid API", Toast.LENGTH_LONG).show()
                    }
                    is ExportResult.Error -> {
                        overlay.resetAnkiButtonState()
                        Toast.makeText(this@YomidroidAccessibilityService, "Export failed: ${result.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun saveToHistory(entry: com.yomidroid.dictionary.DictionaryEntry, sentence: String) {
        // Capture screenshot reference before it might be nulled
        val screenshot = currentScreenshot
        // Capture source app info at call time (may change by the time coroutine runs)
        val srcPackage = currentForegroundPackage
        val srcAppLabel = currentForegroundAppLabel
        val srcWindowTitle = currentWindowTitle

        // Use proper coroutine scope instead of Thread+runBlocking
        serviceScope.launch {
            try {
                val dao = com.yomidroid.data.AppDatabase.getInstance(this@YomidroidAccessibilityService).historyDao()

                // 60-second deduplication: skip if same word was saved recently
                val now = System.currentTimeMillis()
                val recent = dao.findRecentByWord(entry.expression, now - 60_000)
                if (recent != null) {
                    Log.d(TAG, "Skipping duplicate history save for: ${entry.expression} (saved ${(now - recent.timestamp) / 1000}s ago)")
                    return@launch
                }

                // Save screenshot to persistent storage
                var screenshotPath: String? = null
                if (screenshot != null) {
                    val timestamp = now
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
                    sentence = sentence.ifEmpty { null },
                    sourcePackage = srcPackage,
                    sourceAppLabel = srcAppLabel,
                    sourceWindowTitle = srcWindowTitle
                )
                dao.insert(historyRecord)
                Log.d(TAG, "Saved to history: ${entry.expression} from ${srcAppLabel ?: "unknown"}")
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

        serviceScope.launch {
            // Search from this position in the unified text (all screen text)
            val searchText = context.unifiedText.substring(unifiedIndex)
            val entries = dictionaryEngineRef.get()?.findTerms(searchText) ?: emptyList()

            withContext(Dispatchers.Main) {
                if (entries.isNotEmpty()) {
                    val firstEntry = entries.first()

                    // Set up highlighting regardless of popup type
                    overlayView?.setHighlightFromUnified(context, unifiedIndex, firstEntry.matchedText.length)

                    // Always use WebView popup for consistent styling
                    val container = overlayContainer

                    if (container != null) {
                        overlayView?.richPopupActive = true

                        if (overlayPopupWebView == null) {
                            overlayPopupWebView = OverlayPopupWebView(this@YomidroidAccessibilityService).also {
                                it.dictionaryCssMap = dictConfigManager?.getAllDictionaryCss() ?: emptyMap()
                            }
                        }

                        // Build screen-space textBounds for smart popup positioning
                        val sx = screenWidth.toFloat() / (originalScreenshotWidth.takeIf { it > 0 } ?: screenWidth)
                        val sy = screenHeight.toFloat() / (originalScreenshotHeight.takeIf { it > 0 } ?: screenHeight)
                        val matchLen = firstEntry.matchedText.length

                        val textBounds = buildTextBounds(context, unifiedIndex, matchLen, sx, sy)

                        if (isDecoupledMode) {
                            LookupResultRepository.updateEntries(entries, ocrResult.text)
                        } else {
                            overlayPopupWebView?.show(
                                container = container,
                                entries = entries,
                                textBounds = textBounds,
                                maxWidth = (screenWidth * 0.9f).toInt(),
                                maxHeight = (screenHeight * 0.65f).toInt(),
                                customCss = null,
                                onAnkiExport = { entry ->
                                    val sentence = ocrResult.text
                                    exportToAnki(entry, sentence)
                                }
                            )
                        }

                        // Save to history (WebView popup path bypasses OverlayTextView's
                        // showDefinitions which normally tracks entries for history saving)
                        saveToHistory(firstEntry, ocrResult.text)
                    } else {
                        // Fallback: no container available
                        overlayView?.richPopupActive = false
                        overlayPopupWebView?.hide()
                        overlayView?.showDefinitionsWithUnifiedMatch(
                            entries = entries,
                            unifiedStartIndex = unifiedIndex,
                            matchLength = firstEntry.matchedText.length,
                            context = context
                        )
                    }
                } else {
                    overlayView?.richPopupActive = false
                    overlayPopupWebView?.hide()
                    overlayView?.showNoResults()
                }
            }
        }
    }

    // ==================== Service-Level Methods (shared by touch + hardware input) ====================

    fun clearLookupCache() {
        lookupCache.clear()
        lastLookedUpText = null
        lastLookedUpIndex = -1
    }

    fun openMainApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    /**
     * Reload input config (call when settings change).
     */
    fun reloadInputConfig() {
        inputConfig = inputConfigManager?.getConfig() ?: InputConfig()
        updateMotionEventSources()
        Log.d(TAG, "Input config reloaded: enabled=${inputConfig.enabled}")
    }

    /**
     * Perform dictionary lookup at the current cursor position.
     * Called by both touch drag and hardware D-pad movement.
     */
    fun lookupAtCursor() {
        val fab = fabView ?: return
        val overlay = overlayView ?: return
        val context = unifiedContext ?: return

        val cursorX = fab.getCursorScreenX()
        val cursorY = fab.getCursorScreenY()

        val textInfo = overlay.findTextAtCursor(cursorX, cursorY)

        if (textInfo == null) {
            if (lastLookedUpText != null) {
                lastLookedUpText = null
                lastLookedUpIndex = -1
                overlay.clearHighlight()
            }
            return
        }

        val (ocrResult, charIndex, _) = textInfo

        val unifiedIndex = context.getUnifiedIndex(ocrResult, charIndex)
        if (unifiedIndex < 0) return

        Log.d(TAG, "lookupAtCursor: cursor=($cursorX,$cursorY), " +
            "ocrResult='${ocrResult.text}', charIndex=$charIndex, " +
            "char='${ocrResult.text.getOrNull(charIndex)}', " +
            "unifiedIndex=$unifiedIndex, " +
            "searchText='${context.unifiedText.substring(unifiedIndex).take(10)}'")

        if (context.unifiedText == lastLookedUpText && unifiedIndex == lastLookedUpIndex) {
            return
        }

        lastLookedUpText = context.unifiedText
        lastLookedUpIndex = unifiedIndex

        val cacheKey = "unified:$unifiedIndex"

        val cached = lookupCache[cacheKey]
        if (cached != null) {
            overlay.setHighlightFromUnified(context, unifiedIndex, cached.matchLength)
            if (cached.entries.isNotEmpty()) {
                showEntriesAtCursor(cached.entries, cursorX, cursorY, overlay, ocrResult)
            } else {
                overlay.richPopupActive = false
                overlayPopupWebView?.hide()
                overlay.hideDefinition()
            }
            return
        }

        overlay.setHighlight(ocrResult, charIndex, 1)

        serviceScope.launch {
            val searchText = context.unifiedText.substring(unifiedIndex)
            val entries = dictionaryEngineRef.get()?.findTerms(searchText) ?: emptyList()
            val matchLength = entries.firstOrNull()?.matchedText?.length ?: 1

            synchronized(lookupCache) {
                lookupCache[cacheKey] = CachedLookup(matchLength, entries)
            }

            withContext(Dispatchers.Main) {
                if (context.unifiedText == lastLookedUpText && unifiedIndex == lastLookedUpIndex) {
                    overlay.setHighlightFromUnified(context, unifiedIndex, matchLength)
                    if (entries.isNotEmpty()) {
                        showEntriesAtCursor(entries, cursorX, cursorY, overlay, ocrResult)
                    } else {
                        overlay.richPopupActive = false
                        overlayPopupWebView?.hide()
                        overlay.hideDefinition()
                    }
                }
            }
        }
    }

    /**
     * Show entries at cursor position, always using WebView popup for consistent styling.
     */
    private fun showEntriesAtCursor(
        entries: List<com.yomidroid.dictionary.DictionaryEntry>,
        cursorX: Float,
        cursorY: Float,
        overlay: OverlayTextView,
        ocrResult: OcrResult
    ) {
        val container = overlayContainer
        val ctx = unifiedContext

        if (container != null) {
            overlay.richPopupActive = true

            if (overlayPopupWebView == null) {
                overlayPopupWebView = OverlayPopupWebView(this).also {
                    it.dictionaryCssMap = dictConfigManager?.getAllDictionaryCss() ?: emptyMap()
                }
            }

            // Build textBounds from current highlight or cursor position
            val sx = screenWidth.toFloat() / (originalScreenshotWidth.takeIf { it > 0 } ?: screenWidth)
            val sy = screenHeight.toFloat() / (originalScreenshotHeight.takeIf { it > 0 } ?: screenHeight)
            val matchLen = entries.firstOrNull()?.matchedText?.length ?: 1

            val textBounds = if (ctx != null && lastLookedUpIndex >= 0) {
                buildTextBounds(ctx, lastLookedUpIndex, matchLen, sx, sy)
            } else {
                // Fallback: small rect around cursor
                val density = resources.displayMetrics.density
                Rect(
                    (cursorX - 20 * density).toInt(),
                    (cursorY - 10 * density).toInt(),
                    (cursorX + 20 * density).toInt(),
                    (cursorY + 10 * density).toInt()
                )
            }

            if (isDecoupledMode) {
                LookupResultRepository.updateEntries(entries, ocrResult.text)
            } else {
                overlayPopupWebView?.show(
                    container = container,
                    entries = entries,
                    textBounds = textBounds,
                    maxWidth = (screenWidth * 0.9f).toInt(),
                    maxHeight = (screenHeight * 0.65f).toInt(),
                    customCss = null,
                    onAnkiExport = { entry ->
                        exportToAnki(entry, ocrResult.text)
                    }
                )
            }

            // Save to history (WebView popup path bypasses OverlayTextView's history tracking)
            entries.firstOrNull()?.let { firstEntry ->
                saveToHistory(firstEntry, ocrResult.text)
            }
        } else {
            // Fallback: no container available
            overlay.richPopupActive = false
            overlayPopupWebView?.hide()
            overlay.showDefinitionsAtCursor(entries, cursorX, cursorY)
        }
    }

    /**
     * Build a screen-space Rect covering the matched text characters.
     */
    private fun buildTextBounds(
        context: UnifiedOcrContext,
        unifiedIndex: Int,
        matchLen: Int,
        scaleX: Float,
        scaleY: Float
    ): Rect {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE

        for (offset in 0 until matchLen) {
            val mapping = context.getLocalPosition(unifiedIndex + offset) ?: continue
            val charRect = mapping.ocrResult.charBounds.getOrNull(mapping.charIndex) ?: continue
            left = minOf(left, (charRect.left * scaleX).toInt())
            top = minOf(top, (charRect.top * scaleY).toInt())
            right = maxOf(right, (charRect.right * scaleX).toInt())
            bottom = maxOf(bottom, (charRect.bottom * scaleY).toInt())
        }

        // Fallback if no char bounds found
        if (left == Int.MAX_VALUE) {
            val density = resources.displayMetrics.density
            return Rect(
                (screenWidth / 2 - 40 * density).toInt(),
                (screenHeight / 3 - 20 * density).toInt(),
                (screenWidth / 2 + 40 * density).toInt(),
                (screenHeight / 3 + 20 * density).toInt()
            )
        }

        return Rect(left, top, right, bottom)
    }

    /**
     * Move the FAB by a delta in pixels. Clamps to screen bounds.
     * Triggers lookupAtCursor() if overlay is visible.
     */
    fun moveFabBy(dx: Int, dy: Int) {
        val params = fabParams ?: return
        val fab = fabView ?: return
        val fabW = fab.width.takeIf { it > 0 } ?: fab.measuredWidth
        val fabH = fab.height.takeIf { it > 0 } ?: fab.measuredHeight

        params.x = (params.x + dx).coerceIn(0, screenWidth - fabW)
        params.y = (params.y + dy).coerceIn(0, screenHeight - fabH)

        try {
            windowManager.updateViewLayout(fab, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to move FAB: ${e.message}")
            return
        }

        if (overlayView != null) {
            lookupAtCursor()
        }
    }

    /**
     * Move the FAB to an absolute screen position (cursor dot lands at targetX, targetY).
     * Smoothly animates from current position.
     */
    fun moveFabTo(targetX: Float, targetY: Float) {
        val params = fabParams ?: return
        val fab = fabView ?: return
        val fabW = fab.width.takeIf { it > 0 } ?: fab.measuredWidth
        val fabH = fab.height.takeIf { it > 0 } ?: fab.measuredHeight

        // Calculate where the FAB top-left needs to be so the cursor dot lands at target
        // Cursor X is at FAB center horizontally
        val newX = (targetX - fabW / 2f).toInt().coerceIn(0, screenWidth - fabW)
        // Cursor Y is offset from FAB depending on cursor side
        val cursorOffset = if (fab.cursorOnBottom) {
            fabH.toFloat()  // cursor is below FAB
        } else {
            0f  // cursor is above FAB, FAB top-left is below cursor
        }
        val newY = (targetY - cursorOffset).toInt().coerceIn(0, screenHeight - fabH)

        params.x = newX
        params.y = newY

        try {
            windowManager.updateViewLayout(fab, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to move FAB: ${e.message}")
            return
        }

        if (overlayView != null) {
            lookupAtCursor()
        }
    }

    /**
     * Scroll the popup by the configured scroll speed.
     */
    fun scrollPopup(direction: BindableAction) {
        val overlay = overlayView ?: return
        val delta = when (direction) {
            BindableAction.SCROLL_UP -> -inputConfig.scrollSpeed
            BindableAction.SCROLL_DOWN -> inputConfig.scrollSpeed
            else -> return
        }
        overlay.scrollBy(delta)
    }

    // ==================== Hardware Key Event Handling ====================

    override fun onKeyEvent(event: KeyEvent): Boolean {
        Log.d(TAG, "onKeyEvent: keyCode=${event.keyCode}, action=${event.action}, source=0x${Integer.toHexString(event.source)}")

        if (!inputConfig.enabled) return false

        val keyCode = event.keyCode

        // Never consume system keys
        if (keyCode in InputConfig.NEVER_CONSUME) return false

        // Track layer key state
        val layerKey = inputConfig.layerKeyCode
        if (layerKey != 0 && keyCode == layerKey) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    isLayerKeyHeld = true
                    return true  // Consume the layer key itself
                }
                KeyEvent.ACTION_UP -> {
                    isLayerKeyHeld = false
                    // Stop all movement when layer key is released
                    stopDpadMovement()
                    stopStickMovement()
                    return true
                }
            }
        }

        // If layer key is configured, only require it when no overlay is visible.
        // Once the overlay is showing, bound keys work directly without the layer key.
        if (layerKey != 0 && !isLayerKeyHeld && overlayView == null) return false

        // Find which action this key is bound to (context-aware)
        val action = resolveAction(keyCode) ?: return false

        // Show a one-time toast confirming the binding works
        if (event.action == KeyEvent.ACTION_DOWN && toastedActions.add(action)) {
            handler.post {
                Toast.makeText(this, "${action.name} triggered", Toast.LENGTH_SHORT).show()
            }
        }

        // --- Popup-locked intercept ---
        // When popup is locked: D-pad up/down scrolls popup, L1/R1 cycles entries, B dismisses
        if (popupLocked && overlayPopupWebView?.isVisible == true) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    when (action) {
                        BindableAction.MOVE_UP, BindableAction.MOVE_DOWN -> {
                            startDpadPopupScroll(action)
                            return true
                        }
                        BindableAction.MOVE_LEFT, BindableAction.MOVE_RIGHT -> {
                            // D-pad left/right: cycle entries when locked
                            val delta = if (action == BindableAction.MOVE_RIGHT) 1 else -1
                            overlayPopupWebView?.navigateEntry(delta)
                            return true
                        }
                        BindableAction.SCROLL_UP -> {
                            overlayPopupWebView?.navigateEntry(-1)
                            return true
                        }
                        BindableAction.SCROLL_DOWN -> {
                            overlayPopupWebView?.navigateEntry(1)
                            return true
                        }
                        BindableAction.DISMISS -> {
                            // B always dismisses popup and unlocks
                            popupLocked = false
                            overlayPopupWebView?.hide()
                            overlayView?.richPopupActive = false
                            return true
                        }
                        else -> return true  // consume all other bound keys while locked
                    }
                }
                KeyEvent.ACTION_UP -> {
                    // Stop popup scroll on D-pad release
                    if (action in setOf(BindableAction.MOVE_UP, BindableAction.MOVE_DOWN)) {
                        stopDpadDirection(action)
                    }
                    return true
                }
            }
            return true
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // D-pad directions: discrete character navigation
                if (action in setOf(
                    BindableAction.MOVE_UP, BindableAction.MOVE_DOWN,
                    BindableAction.MOVE_LEFT, BindableAction.MOVE_RIGHT
                )) {
                    if (overlayView != null) {
                        startDpadCharNav(action)
                    }
                    return true
                }

                // Single-fire actions on DOWN
                when (action) {
                    BindableAction.SCAN -> {
                        if (overlayView == null) captureScreen()
                        return true
                    }
                    BindableAction.DISMISS -> {
                        val overlay = overlayView
                        if (overlay != null) {
                            if (overlayPopupWebView?.isVisible == true) {
                                overlayPopupWebView?.hide()
                                overlay.richPopupActive = false
                            } else if (overlay.isPopupVisible()) {
                                overlay.dismissPopup()
                            } else {
                                removeOverlay()
                            }
                        }
                        return true
                    }
                    BindableAction.TOGGLE_CURSOR -> {
                        fabHiddenByToggle = !fabHiddenByToggle
                        updateFabVisibility()
                        return true
                    }
                    BindableAction.OPEN_APP -> {
                        // When popup is visible, dismiss it instead of opening the app
                        val popupVis = overlayView?.isPopupVisible() == true || overlayPopupWebView?.isVisible == true
                        if (popupVis) {
                            overlayPopupWebView?.hide()
                            overlayView?.richPopupActive = false
                            overlayView?.dismissPopup()
                        } else {
                            openMainApp()
                        }
                        return true
                    }
                    BindableAction.SCROLL_UP, BindableAction.SCROLL_DOWN -> {
                        val popupVis = overlayView?.isPopupVisible() == true || overlayPopupWebView?.isVisible == true
                        if (popupVis) {
                            // Jump between entries in the popup
                            val delta = if (action == BindableAction.SCROLL_UP) -1 else 1
                            overlayPopupWebView?.navigateEntry(delta)
                        }
                        return true
                    }
                    BindableAction.LOCK_POPUP -> {
                        if (overlayPopupWebView?.isVisible == true) {
                            popupLocked = true
                            stopDpadMovement()
                        }
                        return true
                    }
                    else -> {}
                }
            }

            KeyEvent.ACTION_UP -> {
                // Stop D-pad movement on key release
                if (action in setOf(
                    BindableAction.MOVE_UP, BindableAction.MOVE_DOWN,
                    BindableAction.MOVE_LEFT, BindableAction.MOVE_RIGHT
                )) {
                    stopDpadDirection(action)
                    return true
                }
                // Consume UP for all bound keys to prevent pass-through
                return true
            }
        }

        return false
    }

    /**
     * Resolve which BindableAction a keyCode maps to, considering context.
     * Some keys are shared between actions (e.g., D-pad is MOVE when no popup,
     * could be BLOCK when overlay is visible; R1 is SCAN vs SCROLL_DOWN).
     *
     * Priority:
     * 1. If overlay has popup visible, check scroll/dismiss bindings first
     * 2. If overlay is visible (no popup), check block navigation and move bindings
     * 3. Otherwise check all bindings
     */
    private fun resolveAction(keyCode: Int): BindableAction? {
        val bindings = inputConfig.bindings
        val overlayVisible = overlayView != null
        val popupVisible = overlayView?.isPopupVisible() == true || overlayPopupWebView?.isVisible == true

        // Build priority-ordered action list based on context
        val contextActions = mutableListOf<BindableAction>()

        if (popupVisible) {
            // Popup is showing: lock, scroll and dismiss take priority
            contextActions.addAll(listOf(
                BindableAction.LOCK_POPUP,
                BindableAction.SCROLL_UP, BindableAction.SCROLL_DOWN,
                BindableAction.DISMISS
            ))
        }

        if (overlayVisible) {
            // Overlay showing: move, dismiss, toggle
            contextActions.addAll(listOf(
                BindableAction.MOVE_UP, BindableAction.MOVE_DOWN,
                BindableAction.MOVE_LEFT, BindableAction.MOVE_RIGHT,
                BindableAction.DISMISS, BindableAction.TOGGLE_CURSOR
            ))
        }

        // Always available
        contextActions.addAll(listOf(
            BindableAction.SCAN, BindableAction.TOGGLE_CURSOR,
            BindableAction.OPEN_APP
        ))

        // Find first matching action in priority order
        for (action in contextActions) {
            if (bindings[action] == keyCode) return action
        }

        return null
    }

    /**
     * Navigate to the closest character in a direction using the character grid.
     * Snaps to the nearest char from current cursor position, then steps one in [direction].
     */
    private fun navigateChar(direction: BindableAction) {
        val grid = charGrid
        if (grid.isEmpty()) return
        val fab = fabView ?: return

        val cx = fab.getCursorScreenX()
        val cy = fab.getCursorScreenY()

        // Find closest character to current cursor position
        var bestRow = 0
        var bestCol = 0
        var bestDist = Float.MAX_VALUE
        for (r in grid.indices) {
            for (c in grid[r].indices) {
                val (x, y) = grid[r][c]
                val dist = (x - cx) * (x - cx) + (y - cy) * (y - cy)
                if (dist < bestDist) {
                    bestDist = dist
                    bestRow = r
                    bestCol = c
                }
            }
        }

        var row = bestRow
        var col = bestCol

        when (direction) {
            BindableAction.MOVE_RIGHT -> {
                col++
                if (col >= grid[row].size) {
                    if (row + 1 < grid.size) { row++; col = 0 }
                    else col = grid[row].size - 1
                }
            }
            BindableAction.MOVE_LEFT -> {
                col--
                if (col < 0) {
                    if (row > 0) { row--; col = grid[row].size - 1 }
                    else col = 0
                }
            }
            BindableAction.MOVE_DOWN -> {
                if (row + 1 < grid.size) {
                    val curX = grid[row][col].first
                    row++
                    col = grid[row].indices.minByOrNull { abs(grid[row][it].first - curX) } ?: 0
                }
            }
            BindableAction.MOVE_UP -> {
                if (row > 0) {
                    val curX = grid[row][col].first
                    row--
                    col = grid[row].indices.minByOrNull { abs(grid[row][it].first - curX) } ?: 0
                }
            }
            else -> return
        }

        val (x, y) = grid[row][col]
        moveFabTo(x, y)
    }

    /**
     * Start repeating D-pad character navigation (held key).
     */
    private fun startDpadCharNav(direction: BindableAction) {
        // Immediate first step
        navigateChar(direction)

        activeDirections.add(direction)
        if (dpadMoveRunnable == null) {
            dpadMoveRunnable = object : Runnable {
                override fun run() {
                    if (activeDirections.isEmpty()) {
                        dpadMoveRunnable = null
                        return
                    }
                    for (dir in activeDirections) {
                        navigateChar(dir)
                    }
                    handler.postDelayed(this, CHAR_NAV_REPEAT_MS)
                }
            }
            handler.postDelayed(dpadMoveRunnable!!, CHAR_NAV_INITIAL_DELAY_MS)
        }
    }

    /**
     * Start continuous D-pad popup scroll in a direction (used in popup-locked mode).
     */
    private fun startDpadPopupScroll(direction: BindableAction) {
        activeDirections.add(direction)

        if (dpadMoveRunnable == null) {
            dpadMoveRunnable = object : Runnable {
                override fun run() {
                    if (activeDirections.isEmpty()) {
                        dpadMoveRunnable = null
                        return
                    }

                    val speed = inputConfig.scrollSpeed
                    var dy = 0f
                    if (BindableAction.MOVE_UP in activeDirections) dy -= speed
                    if (BindableAction.MOVE_DOWN in activeDirections) dy += speed

                    if (dy != 0f) {
                        overlayPopupWebView?.scrollContent(dy.toInt())
                    }

                    handler.postDelayed(this, DPAD_FRAME_MS)
                }
            }
            handler.post(dpadMoveRunnable!!)
        }
    }

    private fun stopDpadDirection(direction: BindableAction) {
        activeDirections.remove(direction)
        if (activeDirections.isEmpty()) {
            stopDpadMovement()
        }
    }

    private fun stopDpadMovement() {
        activeDirections.clear()
        dpadMoveRunnable?.let { handler.removeCallbacks(it) }
        dpadMoveRunnable = null
    }

    // ==================== Joystick / Hat-Switch MotionEvent Handling ====================

    /**
     * Handle joystick/gamepad MotionEvents forwarded from the overlay view.
     * Processes hat switch (AXIS_HAT_X/Y) for block navigation and
     * analog stick (AXIS_X/Y) for continuous cursor movement.
     */
    private fun handleJoystickMotion(event: MotionEvent): Boolean {
        if (overlayView == null) return false

        val source = event.source
        if ((source and android.view.InputDevice.SOURCE_JOYSTICK) == 0 &&
            (source and android.view.InputDevice.SOURCE_GAMEPAD) == 0) {
            return false
        }

        var handled = false

        // Hat switch → edge-triggered block navigation
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        handled = handleHatSwitch(hatX, hatY) || handled

        // Analog stick → continuous cursor movement
        val axisX = event.getAxisValue(MotionEvent.AXIS_X)
        val axisY = event.getAxisValue(MotionEvent.AXIS_Y)
        handled = handleAnalogStick(axisX, axisY) || handled

        return handled
    }

    /**
     * Edge-triggered hat switch: navigates between characters on transition
     * from neutral to pressed. When popup is locked, scrolls popup content instead.
     */
    private fun handleHatSwitch(hatX: Float, hatY: Float): Boolean {
        var handled = false

        if (popupLocked && overlayPopupWebView?.isVisible == true) {
            // When locked: hat up/down scrolls popup, left/right cycles entries
            if (abs(lastHatY) < HAT_THRESHOLD && hatY >= HAT_THRESHOLD) {
                overlayPopupWebView?.scrollContent(inputConfig.scrollSpeed.toInt())
                handled = true
            } else if (abs(lastHatY) < HAT_THRESHOLD && hatY <= -HAT_THRESHOLD) {
                overlayPopupWebView?.scrollContent(-inputConfig.scrollSpeed.toInt())
                handled = true
            }
            if (abs(lastHatX) < HAT_THRESHOLD && hatX >= HAT_THRESHOLD) {
                overlayPopupWebView?.navigateEntry(1)
                handled = true
            } else if (abs(lastHatX) < HAT_THRESHOLD && hatX <= -HAT_THRESHOLD) {
                overlayPopupWebView?.navigateEntry(-1)
                handled = true
            }
        } else {
            // Normal mode: hat navigates between characters
            if (abs(lastHatX) < HAT_THRESHOLD && hatX >= HAT_THRESHOLD) {
                navigateChar(BindableAction.MOVE_RIGHT)
                handled = true
            } else if (abs(lastHatX) < HAT_THRESHOLD && hatX <= -HAT_THRESHOLD) {
                navigateChar(BindableAction.MOVE_LEFT)
                handled = true
            }
            if (abs(lastHatY) < HAT_THRESHOLD && hatY >= HAT_THRESHOLD) {
                navigateChar(BindableAction.MOVE_DOWN)
                handled = true
            } else if (abs(lastHatY) < HAT_THRESHOLD && hatY <= -HAT_THRESHOLD) {
                navigateChar(BindableAction.MOVE_UP)
                handled = true
            }
        }

        lastHatX = hatX
        lastHatY = hatY
        return handled
    }

    /**
     * Analog stick: continuous cursor movement proportional to stick deflection.
     * Dead zone filtering prevents drift. Starts/stops a 16ms runnable.
     */
    private fun handleAnalogStick(axisX: Float, axisY: Float): Boolean {
        val x = if (abs(axisX) > STICK_DEAD_ZONE) axisX else 0f
        val y = if (abs(axisY) > STICK_DEAD_ZONE) axisY else 0f

        stickX = x
        stickY = y

        if (x != 0f || y != 0f) {
            startStickMovement()
            return true
        } else {
            stopStickMovement()
            return false
        }
    }

    private fun startStickMovement() {
        if (stickMoveRunnable != null) return  // Already running

        stickMoveRunnable = object : Runnable {
            override fun run() {
                if (stickX == 0f && stickY == 0f) {
                    stickMoveRunnable = null
                    return
                }

                if (popupLocked && overlayPopupWebView?.isVisible == true) {
                    // When locked: joystick scrolls popup content
                    val scrollDelta = (stickY * inputConfig.scrollSpeed * 2f).toInt()
                    if (scrollDelta != 0) {
                        overlayPopupWebView?.scrollContent(scrollDelta)
                    }
                } else {
                    // Normal: joystick moves cursor
                    val speed = inputConfig.cursorSpeed * 3f
                    val dx = (stickX * speed).toInt()
                    val dy = (stickY * speed).toInt()

                    if (dx != 0 || dy != 0) {
                        moveFabBy(dx, dy)
                    }
                }

                handler.postDelayed(this, STICK_FRAME_MS)
            }
        }
        handler.post(stickMoveRunnable!!)
    }

    private fun stopStickMovement() {
        stickMoveRunnable?.let { handler.removeCallbacks(it) }
        stickMoveRunnable = null
        stickX = 0f
        stickY = 0f
    }

    /**
     * Public wrapper for handleJoystickMotion(), called by OverlayTextView.onGenericMotionEvent()
     * on pre-API 34 devices where the service's onMotionEvent() is unavailable.
     */
    fun handleJoystickMotionFromView(event: MotionEvent): Boolean {
        return handleJoystickMotion(event)
    }

    // ==================== Joystick MotionEvent Interception (API 34+) ====================

    /**
     * Dynamically register/unregister joystick+gamepad MotionEvent sources.
     * Requires API 34+ (onMotionEvent callback). On API 33 and below this is a no-op;
     * TYPE_ACCESSIBILITY_OVERLAY windows cannot win window focus, so MotionEvents
     * cannot be intercepted.
     */
    private fun updateMotionEventSources() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Log.d(TAG, "updateMotionEventSources: API ${Build.VERSION.SDK_INT} < 34, " +
                    "using focusable overlay fallback for joystick input")
            return
        }
        val wantSources = overlayView != null && inputConfig.enabled
        serviceInfo = serviceInfo.apply {
            motionEventSources = if (wantSources) {
                InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_GAMEPAD
            } else {
                0
            }
        }
        Log.d(TAG, "updateMotionEventSources: API 34+, motionEventSources=${if (wantSources) "JOYSTICK|GAMEPAD" else "none"}")
    }

    override fun onMotionEvent(event: MotionEvent) {
        handleJoystickMotion(event)
    }

    // ==================== FabTouchListener ====================

    private inner class FabTouchListener : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var initialTouchX = 0f
        private var initialTouchY = 0f
        private var isClick = true

        // Long press handling
        private var isLongPressTriggered = false
        private val longPressRunnable = Runnable {
            isLongPressTriggered = true
            openMainApp()
        }

        // Physics-based movement
        private var velocityTracker: VelocityTracker? = null
        private var physicsAnimator: FabPhysicsAnimator? = null

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
                    isLongPressTriggered = false

                    // Schedule long press (500ms)
                    handler.postDelayed(longPressRunnable, 500L)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)

                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isClick = false
                        // Cancel long press when dragging
                        handler.removeCallbacks(longPressRunnable)

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
                    // Cancel long press if not yet triggered
                    handler.removeCallbacks(longPressRunnable)

                    velocityTracker?.addMovement(event)
                    velocityTracker?.computeCurrentVelocity(1000)  // pixels per second

                    // Skip tap handling if long press was triggered
                    if (isLongPressTriggered) {
                        velocityTracker?.recycle()
                        velocityTracker = null
                        return true
                    }

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
    }
}
