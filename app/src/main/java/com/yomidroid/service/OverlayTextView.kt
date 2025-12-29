package com.yomidroid.service

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.yomidroid.R
import com.yomidroid.dictionary.DictionaryEntry
import com.yomidroid.ocr.OcrResult

/**
 * State of the Anki export button.
 */
enum class AnkiButtonState {
    IDLE,       // Ready to export (show Anki icon)
    LOADING,    // Export in progress (show spinner)
    SUCCESS,    // Successfully exported (show checkmark)
    ALREADY     // Already in Anki (show checkmark)
}

class OverlayTextView(
    context: Context,
    private val ocrResults: List<OcrResult>,
    private val screenshot: Bitmap,
    private val scaleX: Float = 1f,  // Scale factor: screenWidth / bitmapWidth
    private val scaleY: Float = 1f,  // Scale factor: screenHeight / bitmapHeight
    private val highlightColor: Int = Color.argb(60, 255, 200, 0),  // Configurable highlight color
    private val onTextTapped: (String, Int) -> Unit,
    private val onDismissRequested: () -> Unit = {},
    private val onCursorLookup: ((String, Int, Float, Float) -> Unit)? = null,
    private val onAnkiExport: ((DictionaryEntry, String) -> Unit)? = null,  // Export to Anki callback
    private val onSaveToHistory: ((DictionaryEntry, String) -> Unit)? = null  // Save to history callback
) : View(context) {

    private val density = context.resources.displayMetrics.density

    /**
     * Transform OCR bounding box from bitmap coordinates to screen coordinates.
     * ML Kit returns bounds in bitmap space; we need screen space for rendering.
     * scaleX = screenWidth / bitmapWidth, scaleY = screenHeight / bitmapHeight
     */
    private fun Rect.toScreenCoords(): RectF {
        return RectF(
            left * scaleX,
            top * scaleY,
            right * scaleX,
            bottom * scaleY
        )
    }

    // Animation state
    private var popupAlpha = 0f
    private var highlightAlpha = 0f
    private var popupAnimator: ValueAnimator? = null
    private var highlightAnimator: ValueAnimator? = null

    // Reusable Paint objects for onDraw (avoid allocations during animation frames)
    // These are updated with alpha values each frame instead of creating new Paint objects
    private val animatedHighlightPaint = Paint()
    private val animatedBorderPaint = Paint()
    private val selectedHighlightPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    // Highlight colors
    private val highlightPaint = Paint().apply {
        color = highlightColor
        style = Paint.Style.FILL
    }

    private val textBorderPaint = Paint().apply {
        color = Color.argb(120, 100, 180, 255) // Subtle blue border
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }

    // Popup styling - clean dark theme
    private val popupBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(250, 20, 20, 25) // Near-black
        style = Paint.Style.FILL
    }

    private val popupBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255) // Subtle white border
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }

    // Typography
    private val headwordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f * density
        typeface = Typeface.DEFAULT_BOLD
    }

    private val readingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 200, 200, 200) // Muted gray
        textSize = 16f * density
    }

    private val posTagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 130, 180, 255) // Soft blue
        textSize = 13f * density
    }

    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 240, 240, 240)
        textSize = 16f * density
    }

    private val glossNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 200, 200, 200)
        textSize = 14f * density
    }

    private val deinflectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 180, 140, 255) // Soft purple
        textSize = 13f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
    }

    // Anki export button styling - white background for blue star icon
    private val ankiButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 255, 255) // White button background
        style = Paint.Style.FILL
    }

    private val ankiButtonSuccessPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 60, 180, 100) // Green for success
        style = Paint.Style.FILL
    }

    private val ankiButtonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * density
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    // Anki icon drawable
    private val ankiIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_anki)

    // Track Anki button bounds for touch detection
    private var ankiButtonBounds: RectF? = null

    // Track Anki export state for current definition
    private var ankiButtonState: AnkiButtonState = AnkiButtonState.IDLE
    private var loadingAnimator: ValueAnimator? = null
    private var loadingAngle: Float = 0f

    private var selectedResult: OcrResult? = null
    private var selectedCharIndex: Int = -1
    private var selectedMatchLength: Int = 1  // How many characters are matched
    private var currentDefinition: DictionaryEntry? = null
    private var popupX: Float = 0f
    private var popupY: Float = 0f
    private var showingNoResults: Boolean = false

    // Track popup open time for history feature
    private var popupOpenTime: Long = 0L
    private var lastShownEntry: DictionaryEntry? = null
    private var lastShownSentence: String = ""

    /**
     * Pre-computed tight bounding boxes for hit testing.
     * ML Kit's lineBounds includes extra padding which causes cursor misalignment,
     * so we compute tight bounds from actual character bounding boxes instead.
     * Lazy to avoid computation until first touch event.
     */
    private val adjustedResults: List<Pair<OcrResult, RectF>> by lazy {
        ocrResults.map { result ->
            val actualBounds = if (result.charBounds.isNotEmpty()) {
                // Calculate tight bounds from actual characters
                var left = Float.MAX_VALUE
                var top = Float.MAX_VALUE
                var right = Float.MIN_VALUE
                var bottom = Float.MIN_VALUE
                for (bounds in result.charBounds) {
                    left = minOf(left, bounds.left * scaleX)
                    top = minOf(top, bounds.top * scaleY)
                    right = maxOf(right, bounds.right * scaleX)
                    bottom = maxOf(bottom, bounds.bottom * scaleY)
                }
                RectF(left, top, right, bottom)
            } else {
                result.lineBounds.toScreenCoords()
            }
            result to actualBounds
        }
    }

    init {
        // Fade in highlights on creation
        highlightAlpha = 0f
        highlightAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                highlightAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Get overlay position offset (needed for devices with display cutouts)
        // OCR bounds are in screen coordinates, but overlay may be offset from screen origin
        val overlayLocation = IntArray(2)
        getLocationOnScreen(overlayLocation)
        val offsetX = -overlayLocation[0].toFloat()
        val offsetY = -overlayLocation[1].toFloat()

        // Apply highlight alpha for fade-in animation (update reusable paints instead of allocating)
        animatedHighlightPaint.set(highlightPaint)
        animatedHighlightPaint.alpha = (Color.alpha(highlightPaint.color) * highlightAlpha).toInt()
        animatedBorderPaint.set(textBorderPaint)
        animatedBorderPaint.alpha = (Color.alpha(textBorderPaint.color) * highlightAlpha).toInt()

        // Draw highlight boxes around detected text (with scale transformation and offset)
        for (result in ocrResults) {
            // Draw individual character highlights
            for (charBounds in result.charBounds) {
                val adjusted = charBounds.toScreenCoords()
                adjusted.offset(offsetX, offsetY)
                canvas.drawRect(adjusted, animatedHighlightPaint)
            }
            // Draw line border
            val adjustedLine = result.lineBounds.toScreenCoords()
            adjustedLine.offset(offsetX, offsetY)
            canvas.drawRect(adjustedLine, animatedBorderPaint)
        }

        // Draw selected character(s) highlight - highlight full matched word
        if (selectedResult != null && selectedCharIndex >= 0) {
            // Update reusable paint instead of allocating new one
            selectedHighlightPaint.color = Color.argb((140 * highlightAlpha).toInt(), 80, 200, 120)
            // Highlight all characters in the match
            for (i in 0 until selectedMatchLength) {
                val charIdx = selectedCharIndex + i
                val selectedBounds = selectedResult!!.charBounds.getOrNull(charIdx)
                selectedBounds?.let {
                    val adjusted = it.toScreenCoords()
                    adjusted.offset(offsetX, offsetY)
                    canvas.drawRect(adjusted, selectedHighlightPaint)
                }
            }
        }

        // Draw definition popup if present (with alpha)
        currentDefinition?.let { entry ->
            drawDefinitionPopup(canvas, entry)
        }

        // Draw "no results" message if needed
        if (showingNoResults) {
            drawNoResultsPopup(canvas)
        }
    }

    private fun drawNoResultsPopup(canvas: Canvas) {
        if (popupAlpha <= 0f) return

        val message = "No match"
        val padding = 16f * density
        val textWidth = readingPaint.measureText(message)
        val popupWidth = textWidth + padding * 2
        val popupHeight = 44f * density
        val cornerRadius = 8f * density

        val px = popupX.coerceIn(16f, width - popupWidth - 16f)
        val py = popupY.coerceIn(16f, height - popupHeight - 16f)

        // Apply popup alpha
        val bgPaint = Paint(popupBgPaint).apply { alpha = (250 * popupAlpha).toInt() }
        val borderPaint = Paint(popupBorderPaint).apply { alpha = (60 * popupAlpha).toInt() }
        val textPaint = Paint(readingPaint).apply { alpha = (180 * popupAlpha).toInt() }

        val rect = RectF(px, py, px + popupWidth, py + popupHeight)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)
        canvas.drawText(message, px + padding, py + popupHeight / 2 + readingPaint.textSize / 3, textPaint)
    }

    private fun drawDefinitionPopup(canvas: Canvas, entry: DictionaryEntry) {
        if (popupAlpha <= 0f) return

        val paddingH = 20f * density
        val paddingV = 16f * density
        val maxWidth = width * 0.9f
        val cornerRadius = 12f * density

        // Anki button dimensions
        val ankiButtonWidth = 36f * density
        val ankiButtonHeight = 28f * density
        val ankiButtonMargin = 8f * density
        val showAnkiButton = onAnkiExport != null

        // Calculate content
        val headword = entry.expression
        val reading = if (entry.reading.isNotEmpty() && entry.reading != entry.expression) {
            entry.reading
        } else null

        val deinflection = if (entry.deinflectionPath.isNotEmpty()) {
            "→ ${entry.deinflectionPath}"
        } else null

        // Limit glossary entries and truncate long ones
        val maxGlossLength = 50
        val glosses = entry.glossary.take(3).map { gloss ->
            if (gloss.length > maxGlossLength) gloss.take(maxGlossLength) + "…" else gloss
        }

        // Calculate popup width based on content (add space for Anki button)
        var contentWidth = headwordPaint.measureText(headword)
        if (showAnkiButton) {
            contentWidth += ankiButtonWidth + ankiButtonMargin
        }
        reading?.let { contentWidth = maxOf(contentWidth, readingPaint.measureText(it)) }
        glosses.forEach { contentWidth = maxOf(contentWidth, glossPaint.measureText(it) + 24f * density) }
        val popupWidth = minOf(contentWidth + paddingH * 2, maxWidth)

        // Calculate popup height
        var contentHeight = headwordPaint.textSize  // Headword
        reading?.let { contentHeight += readingPaint.textSize + 4f * density }  // Reading
        deinflection?.let { contentHeight += deinflectPaint.textSize + 8f * density }  // Deinflection
        if (glosses.isNotEmpty()) {
            contentHeight += 12f * density  // Gap before glosses
            contentHeight += glosses.size * (glossPaint.textSize + 6f * density)
        }
        val popupHeight = contentHeight + paddingV * 2

        // Position popup - prefer above cursor, fallback to below
        var finalX = popupX - popupWidth / 2
        var finalY = popupY - popupHeight - 20f * density

        // Keep on screen
        finalX = finalX.coerceIn(8f, width - popupWidth - 8f)
        if (finalY < 8f) {
            finalY = popupY + 40f * density  // Below cursor if no room above
        }
        finalY = finalY.coerceIn(8f, height - popupHeight - 8f)

        // Apply popup alpha for fade animation
        val alpha = popupAlpha

        // Draw background with subtle shadow effect
        val shadowRect = RectF(finalX + 2f * density, finalY + 2f * density,
                               finalX + popupWidth + 2f * density, finalY + popupHeight + 2f * density)
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((40 * alpha).toInt(), 0, 0, 0)
        }
        canvas.drawRoundRect(shadowRect, cornerRadius, cornerRadius, shadowPaint)

        val bgPaint = Paint(popupBgPaint).apply { this.alpha = (250 * alpha).toInt() }
        val borderPaint = Paint(popupBorderPaint).apply { this.alpha = (60 * alpha).toInt() }

        val rect = RectF(finalX, finalY, finalX + popupWidth, finalY + popupHeight)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)

        // Draw Anki export button (top-right corner)
        if (showAnkiButton) {
            val buttonX = finalX + popupWidth - ankiButtonWidth - ankiButtonMargin
            val buttonY = finalY + ankiButtonMargin
            val buttonRect = RectF(buttonX, buttonY, buttonX + ankiButtonWidth, buttonY + ankiButtonHeight)
            ankiButtonBounds = buttonRect

            val buttonRadius = 6f * density

            when (ankiButtonState) {
                AnkiButtonState.IDLE -> {
                    // Blue button with Anki icon
                    val buttonPaint = Paint(ankiButtonPaint).apply { this.alpha = (200 * alpha).toInt() }
                    canvas.drawRoundRect(buttonRect, buttonRadius, buttonRadius, buttonPaint)

                    // Draw Anki icon
                    ankiIcon?.let { icon ->
                        val iconSize = (18 * density).toInt()
                        val iconLeft = (buttonX + (ankiButtonWidth - iconSize) / 2).toInt()
                        val iconTop = (buttonY + (ankiButtonHeight - iconSize) / 2).toInt()
                        icon.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
                        icon.alpha = (255 * alpha).toInt()
                        icon.draw(canvas)
                    }
                }

                AnkiButtonState.LOADING -> {
                    // Blue button with spinning indicator
                    val buttonPaint = Paint(ankiButtonPaint).apply { this.alpha = (180 * alpha).toInt() }
                    canvas.drawRoundRect(buttonRect, buttonRadius, buttonRadius, buttonPaint)

                    // Draw spinning arc
                    val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                        style = Paint.Style.STROKE
                        strokeWidth = 2f * density
                        this.alpha = (255 * alpha).toInt()
                    }
                    val arcSize = 14f * density
                    val arcRect = RectF(
                        buttonX + (ankiButtonWidth - arcSize) / 2,
                        buttonY + (ankiButtonHeight - arcSize) / 2,
                        buttonX + (ankiButtonWidth + arcSize) / 2,
                        buttonY + (ankiButtonHeight + arcSize) / 2
                    )
                    canvas.drawArc(arcRect, loadingAngle, 270f, false, arcPaint)
                }

                AnkiButtonState.SUCCESS, AnkiButtonState.ALREADY -> {
                    // Green button with checkmark
                    val buttonPaint = Paint(ankiButtonSuccessPaint).apply { this.alpha = (200 * alpha).toInt() }
                    canvas.drawRoundRect(buttonRect, buttonRadius, buttonRadius, buttonPaint)

                    // Draw checkmark
                    val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                        style = Paint.Style.STROKE
                        strokeWidth = 2.5f * density
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                        this.alpha = (255 * alpha).toInt()
                    }
                    val cx = buttonX + ankiButtonWidth / 2
                    val cy = buttonY + ankiButtonHeight / 2
                    val checkSize = 6f * density
                    val path = Path().apply {
                        moveTo(cx - checkSize, cy)
                        lineTo(cx - checkSize / 3, cy + checkSize * 0.7f)
                        lineTo(cx + checkSize, cy - checkSize * 0.6f)
                    }
                    canvas.drawPath(path, checkPaint)
                }
            }
        } else {
            ankiButtonBounds = null
        }

        // Draw content with alpha
        val headPaint = Paint(headwordPaint).apply { this.alpha = (255 * alpha).toInt() }
        val readPaint = Paint(readingPaint).apply { this.alpha = (180 * alpha).toInt() }
        val deinPaint = Paint(deinflectPaint).apply { this.alpha = (150 * alpha).toInt() }
        val glossTextPaint = Paint(glossPaint).apply { this.alpha = (230 * alpha).toInt() }
        val glossNumPaint = Paint(glossNumberPaint).apply { this.alpha = (120 * alpha).toInt() }

        var y = finalY + paddingV + headwordPaint.textSize * 0.85f

        // Headword
        canvas.drawText(headword, finalX + paddingH, y, headPaint)
        y += headwordPaint.textSize * 0.3f

        // Reading (if different from headword)
        reading?.let {
            y += readingPaint.textSize + 2f * density
            canvas.drawText(it, finalX + paddingH, y, readPaint)
        }

        // Deinflection path
        deinflection?.let {
            y += deinflectPaint.textSize + 8f * density
            canvas.drawText(it, finalX + paddingH, y, deinPaint)
        }

        // Glossary
        if (glosses.isNotEmpty()) {
            y += 14f * density
            glosses.forEachIndexed { index, gloss ->
                y += glossPaint.textSize + 4f * density
                // Draw number
                val numText = "${index + 1}."
                canvas.drawText(numText, finalX + paddingH, y, glossNumPaint)
                // Draw gloss
                canvas.drawText(gloss, finalX + paddingH + 20f * density, y, glossTextPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                return true
            }

            MotionEvent.ACTION_UP -> {
                val x = event.x
                val y = event.y

                // Check if tap is on Anki export button
                ankiButtonBounds?.let { bounds ->
                    if (bounds.contains(x, y) && currentDefinition != null) {
                        // Only allow tap if in IDLE state
                        if (ankiButtonState == AnkiButtonState.IDLE) {
                            val sentence = selectedResult?.text ?: ""
                            onAnkiExport?.invoke(currentDefinition!!, sentence)
                        }
                        return true
                    }
                }

                // First check if tap is on any OCR text region
                for ((result, bounds) in adjustedResults) {
                    if (bounds.contains(x, y)) {
                        val charIndex = getCharIndexAt(result, x, y)
                        if (charIndex >= 0) {
                            // Clear any existing popup before new lookup
                            currentDefinition = null
                            showingNoResults = false

                            selectedResult = result
                            selectedCharIndex = charIndex

                            // Position popup near tap
                            popupX = x
                            popupY = y

                            invalidate()
                            onTextTapped(result.text, charIndex)
                            return true
                        }
                    }
                }

                // Tap is outside text regions
                // If showing popup/no-results, close it first
                if (currentDefinition != null || showingNoResults) {
                    currentDefinition = null
                    showingNoResults = false
                    selectedResult = null
                    selectedCharIndex = -1
                    invalidate()
                    return true
                }

                // Tap outside text with no popup showing - dismiss entire overlay
                onDismissRequested()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Find which character index a screen coordinate maps to.
     * Converts screen coordinates back to bitmap space (reverse of toScreenCoords),
     * then checks which character bounding box contains that point.
     *
     * Two-pass matching:
     * 1. Exact match: point is inside character bounds
     * 2. X-range fallback: for slight Y misalignment (common with vertical text)
     *
     * @return Character index, or -1 if cursor is not over any character
     */
    private fun getCharIndexAt(result: OcrResult, screenX: Float, screenY: Float): Int {
        // Convert screen coordinates to bitmap/OCR coordinate space (reverse the scale)
        val bitmapX = screenX / scaleX
        val bitmapY = screenY / scaleY

        // Pass 1: Direct coordinate matching
        for ((index, bounds) in result.charBounds.withIndex()) {
            if (bounds.contains(bitmapX.toInt(), bitmapY.toInt())) {
                return index
            }
        }
        // Pass 2: X-range fallback for slight Y misalignment
        for ((index, bounds) in result.charBounds.withIndex()) {
            if (bitmapX >= bounds.left && bitmapX <= bounds.right) {
                return index
            }
        }
        return -1
    }

    private fun animatePopupIn() {
        popupAnimator?.cancel()
        popupAnimator = ValueAnimator.ofFloat(popupAlpha, 1f).apply {
            duration = 150
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                popupAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun animatePopupOut(onEnd: (() -> Unit)? = null) {
        popupAnimator?.cancel()
        popupAnimator = ValueAnimator.ofFloat(popupAlpha, 0f).apply {
            duration = 100
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                popupAlpha = it.animatedValue as Float
                invalidate()
            }
            if (onEnd != null) {
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        onEnd()
                    }
                })
            }
            start()
        }
    }

    fun showDefinition(entry: DictionaryEntry, charIndex: Int) {
        // Save previous entry to history if it was open for 1+ second
        maybeSaveToHistory()

        showingNoResults = false
        currentDefinition = entry
        selectedMatchLength = entry.matchedText.length
        resetAnkiButtonState()

        // Track popup open time and entry for history
        popupOpenTime = System.currentTimeMillis()
        lastShownEntry = entry
        lastShownSentence = selectedResult?.text ?: ""

        animatePopupIn()
    }

    /**
     * Show definition at a specific cursor position (for cursor-based lookup).
     * Positions popup above the selected text, not the cursor.
     */
    fun showDefinitionAtCursor(entry: DictionaryEntry, cursorX: Float, cursorY: Float) {
        // Save previous entry to history if it was open for 1+ second
        maybeSaveToHistory()

        showingNoResults = false
        currentDefinition = entry
        selectedMatchLength = entry.matchedText.length
        resetAnkiButtonState()

        // Track popup open time and entry for history
        popupOpenTime = System.currentTimeMillis()
        lastShownEntry = entry
        lastShownSentence = selectedResult?.text ?: ""

        // Position popup above the selected text, centered over all matched characters
        val firstBounds = selectedResult?.charBounds?.getOrNull(selectedCharIndex)
        val lastBounds = selectedResult?.charBounds?.getOrNull(selectedCharIndex + selectedMatchLength - 1)

        if (firstBounds != null && lastBounds != null) {
            // Center horizontally over all matched chars (with scale applied)
            val centerX = (firstBounds.left + lastBounds.right) / 2f * scaleX
            popupX = centerX
            popupY = firstBounds.top * scaleY  // Above the text
        } else if (firstBounds != null) {
            popupX = firstBounds.centerX() * scaleX
            popupY = firstBounds.top * scaleY
        } else {
            // Fallback to cursor position
            popupX = cursorX
            popupY = cursorY
        }
        animatePopupIn()
    }

    fun showNoResults() {
        currentDefinition = null
        showingNoResults = true
        selectedMatchLength = 1
        animatePopupIn()
    }

    fun hideDefinition() {
        // Save to history if popup was open for 1+ second
        maybeSaveToHistory()

        animatePopupOut {
            currentDefinition = null
            showingNoResults = false
            selectedResult = null
            selectedCharIndex = -1
            selectedMatchLength = 1
        }
    }

    /**
     * Check if the popup was open for 1+ second and save to history if so.
     */
    private fun maybeSaveToHistory() {
        val entry = lastShownEntry ?: return
        if (popupOpenTime <= 0) return

        val elapsed = System.currentTimeMillis() - popupOpenTime
        if (elapsed >= 1000) {
            // Popup was open for 1+ second, save to history
            onSaveToHistory?.invoke(entry, lastShownSentence)
        }

        // Reset tracking
        popupOpenTime = 0L
        lastShownEntry = null
        lastShownSentence = ""
    }

    /**
     * Called when overlay is being dismissed to save any pending history.
     */
    fun onDismiss() {
        maybeSaveToHistory()
    }

    /**
     * Set highlight for a specific character range (used when match length is known).
     */
    fun setHighlight(result: OcrResult, charIndex: Int, matchLength: Int) {
        selectedResult = result
        selectedCharIndex = charIndex
        selectedMatchLength = matchLength
        invalidate()
    }

    /**
     * Clear the current highlight.
     */
    fun clearHighlight() {
        selectedResult = null
        selectedCharIndex = -1
        selectedMatchLength = 1
        currentDefinition = null
        showingNoResults = false
        invalidate()
    }

    /**
     * Update the highlight color and redraw.
     */
    fun updateHighlightColor(newColor: Int) {
        highlightPaint.color = newColor
        invalidate()
    }

    /**
     * Find OCR result and char index at cursor position (for cursor-based lookup).
     * Cursor coordinates are in screen space, OCR bounds are also in screen space.
     * Returns null if cursor is not over any text.
     */
    fun findTextAtCursor(cursorX: Float, cursorY: Float): Triple<OcrResult, Int, RectF>? {
        for ((result, bounds) in adjustedResults) {
            if (bounds.contains(cursorX, cursorY)) {
                val charIndex = getCharIndexAt(result, cursorX, cursorY)
                if (charIndex >= 0) {
                    selectedResult = result
                    selectedCharIndex = charIndex
                    return Triple(result, charIndex, bounds)
                }
            }
        }
        return null
    }

    /**
     * Highlight the character at cursor position without triggering lookup callback.
     * Cursor coordinates are in screen space, OCR bounds are also in screen space.
     * Used for visual feedback during cursor-based navigation.
     */
    fun highlightAtCursor(cursorX: Float, cursorY: Float): Pair<String, Int>? {
        for ((result, bounds) in adjustedResults) {
            if (bounds.contains(cursorX, cursorY)) {
                val charIndex = getCharIndexAt(result, cursorX, cursorY)
                if (charIndex >= 0) {
                    selectedResult = result
                    selectedCharIndex = charIndex
                    // Start with single char highlight, will update when lookup completes
                    selectedMatchLength = 1
                    invalidate()
                    return result.text to charIndex
                }
            }
        }
        // Cursor not over any text
        if (selectedResult != null) {
            selectedResult = null
            selectedCharIndex = -1
            selectedMatchLength = 1
            invalidate()
        }
        return null
    }

    // ==================== Anki Button State Methods ====================

    /**
     * Set the Anki button to loading state with spinning animation.
     */
    fun setAnkiButtonLoading() {
        ankiButtonState = AnkiButtonState.LOADING
        startLoadingAnimation()
        invalidate()
    }

    /**
     * Set the Anki button to success state (card was added).
     */
    fun setAnkiButtonSuccess() {
        stopLoadingAnimation()
        ankiButtonState = AnkiButtonState.SUCCESS
        invalidate()
    }

    /**
     * Set the Anki button to already exported state (card exists in Anki).
     */
    fun setAnkiButtonAlreadyExported() {
        stopLoadingAnimation()
        ankiButtonState = AnkiButtonState.ALREADY
        invalidate()
    }

    /**
     * Reset the Anki button to idle state.
     */
    fun resetAnkiButtonState() {
        stopLoadingAnimation()
        ankiButtonState = AnkiButtonState.IDLE
        invalidate()
    }

    private fun startLoadingAnimation() {
        loadingAnimator?.cancel()
        loadingAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                loadingAngle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopLoadingAnimation() {
        loadingAnimator?.cancel()
        loadingAnimator = null
        loadingAngle = 0f
    }

    /**
     * Clean up animators when view is detached to prevent memory leaks.
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        popupAnimator?.cancel()
        popupAnimator = null
        highlightAnimator?.cancel()
        highlightAnimator = null
        loadingAnimator?.cancel()
        loadingAnimator = null
    }
}
