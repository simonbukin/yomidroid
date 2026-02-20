package com.yomidroid.service

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.yomidroid.R
import com.yomidroid.dictionary.DictionaryEntry
import com.yomidroid.ocr.CharMapping
import com.yomidroid.ocr.OcrResult
import com.yomidroid.ocr.UnifiedOcrContext

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
    private val unifiedContext: UnifiedOcrContext? = null,  // Unified context for cross-line search
    private val screenshot: Bitmap,
    private val scaleX: Float = 1f,  // Scale factor: screenWidth / bitmapWidth
    private val scaleY: Float = 1f,  // Scale factor: screenHeight / bitmapHeight
    private val highlightColor: Int = Color.argb(60, 255, 200, 0),  // Configurable highlight color
    private val onTextTapped: (OcrResult, Int) -> Unit,  // Changed: now passes OcrResult instead of String
    private val onDismissRequested: () -> Unit = {},
    private val onCursorLookup: ((String, Int, Float, Float) -> Unit)? = null,
    private val onAnkiExport: ((DictionaryEntry, String) -> Unit)? = null,  // Export to Anki callback
    private val onSaveToHistory: ((DictionaryEntry, String) -> Unit)? = null,  // Save to history callback
    private val onDismissRichPopup: (() -> Unit)? = null  // Dismiss WebView popup callback
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

    // Badge paints for frequency and name type
    private val frequencyBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 76, 175, 80)  // Material green
        textSize = 11f * density
        typeface = Typeface.DEFAULT_BOLD
    }

    private val frequencyBadgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 76, 175, 80)  // Light green background
    }

    private val nameBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 152, 0)  // Material orange
        textSize = 11f * density
        typeface = Typeface.DEFAULT_BOLD
    }

    private val nameBadgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 255, 152, 0)  // Light orange background
    }

    private val sourceLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 255, 255, 255)  // Subtle white
        textSize = 10f * density
    }

    private val entryCounterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 200, 200, 200)
        textSize = 12f * density
        textAlign = Paint.Align.CENTER
    }

    // Anki icon drawable
    private val ankiIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_anki)

    // Track Anki button bounds for touch detection (one per entry in vertical list)
    private var ankiButtonBoundsList: MutableList<RectF> = mutableListOf()

    // Track Anki export state per entry (index -> state)
    private var ankiButtonStates: MutableMap<Int, AnkiButtonState> = mutableMapOf()
    private var loadingAnimator: ValueAnimator? = null
    private var loadingAngle: Float = 0f
    private var loadingEntryIndex: Int = -1  // Which entry is currently loading

    private var selectedResult: OcrResult? = null
    private var selectedCharIndex: Int = -1
    private var selectedMatchLength: Int = 1  // How many characters are matched

    // Multi-definition support
    private var currentDefinitions: List<DictionaryEntry> = emptyList()
    private var selectedDefinitionIndex: Int = 0
    private val currentDefinition: DictionaryEntry?
        get() = currentDefinitions.getOrNull(selectedDefinitionIndex)

    private var popupX: Float = 0f
    private var popupY: Float = 0f
    private var showingNoResults: Boolean = false

    // Cross-line highlight support: stores all CharMappings when match spans multiple OcrResults
    private var crossLineHighlight: List<CharMapping>? = null

    // Popup scrolling state
    private var popupScrollY: Float = 0f
    private var popupContentHeight: Float = 0f
    private var popupVisibleHeight: Float = 0f
    private var popupBounds: RectF? = null
    private var lastTouchY: Float = 0f
    private var isDraggingPopup: Boolean = false

    // When true, a WebView popup is active — skip Canvas popup drawing
    var richPopupActive: Boolean = false

    // Cursor position for smart popup placement (avoids overlapping cursor FAB)
    private var lastCursorY: Float = 0f

    // Track popup open time for history feature
    private var popupOpenTime: Long = 0L
    private var lastShownEntry: DictionaryEntry? = null
    private var lastShownSentence: String = ""
    private var lastSavedExpression: String? = null

    /**
     * Pre-computed tight bounding boxes for hit testing, in view-local coordinates.
     * ML Kit's lineBounds includes extra padding which causes cursor misalignment,
     * so we compute tight bounds from actual character bounding boxes instead.
     * Applies overlay offset so bounds match onTouchEvent coordinates.
     * Lazy to avoid computation until first touch event.
     */
    private val adjustedResults: List<Pair<OcrResult, RectF>> by lazy {
        // Get overlay offset to convert from screen space to view-local space
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val ofsX = -loc[0].toFloat()
        val ofsY = -loc[1].toFloat()

        ocrResults.map { result ->
            val actualBounds = if (result.charBounds.isNotEmpty()) {
                // Calculate tight bounds from actual characters (in screen space)
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
                // Apply overlay offset to convert to view-local space
                RectF(left + ofsX, top + ofsY, right + ofsX, bottom + ofsY)
            } else {
                val screenBounds = result.lineBounds.toScreenCoords()
                screenBounds.offset(ofsX, ofsY)
                screenBounds
            }
            result to actualBounds
        }
    }

    init {
        // Skip animation to reduce CPU load (helps with emulator audio)
        highlightAlpha = 1f
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
            // Draw unified line highlight (covers full merged line)
            val adjustedLine = result.lineBounds.toScreenCoords()
            adjustedLine.offset(offsetX, offsetY)
            canvas.drawRect(adjustedLine, animatedHighlightPaint)
            canvas.drawRect(adjustedLine, animatedBorderPaint)
        }

        // Draw selected character(s) highlight - highlight full matched word
        // Supports cross-line matches via crossLineHighlight
        val crossLine = crossLineHighlight
        if (crossLine != null && crossLine.isNotEmpty()) {
            // Cross-line match: highlight characters across multiple OcrResults
            selectedHighlightPaint.color = Color.argb((140 * highlightAlpha).toInt(), 80, 200, 120)
            for (mapping in crossLine) {
                val bounds = mapping.ocrResult.charBounds.getOrNull(mapping.charIndex)
                bounds?.let {
                    val adjusted = it.toScreenCoords()
                    adjusted.offset(offsetX, offsetY)
                    canvas.drawRect(adjusted, selectedHighlightPaint)
                }
            }
        } else if (selectedResult != null && selectedCharIndex >= 0) {
            // Single-line match: original highlighting logic
            selectedHighlightPaint.color = Color.argb((140 * highlightAlpha).toInt(), 80, 200, 120)
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

        // Draw definitions list if present (with alpha)
        if (currentDefinitions.isNotEmpty()) {
            drawDefinitionsList(canvas, currentDefinitions)
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

    /**
     * Draw all definitions in a vertical scrollable list.
     * Each entry shows full details: headword, reading, deinflection, all glosses, badges, source.
     * Entries are separated by dividers.
     */
    private fun drawDefinitionsList(canvas: Canvas, entries: List<DictionaryEntry>) {
        if (popupAlpha <= 0f || entries.isEmpty()) return
        if (richPopupActive) return  // WebView popup handles rendering

        val paddingH = 20f * density
        val paddingV = 16f * density
        val maxWidth = width * 0.9f
        val maxHeight = height * 0.65f  // Constrain height
        val cornerRadius = 12f * density
        val dividerHeight = 16f * density
        val ankiButtonWidth = 36f * density
        val ankiButtonHeight = 28f * density
        val ankiButtonMargin = 8f * density
        val showAnkiButton = onAnkiExport != null

        val alpha = popupAlpha

        // Pre-calculate each entry's height and create gloss layouts
        data class EntryLayout(
            val entry: DictionaryEntry,
            val glossLayouts: List<StaticLayout>,
            val height: Float
        )

        // Calculate popup width first (need it for gloss wrapping)
        var contentWidth = 0f
        entries.forEach { entry ->
            contentWidth = maxOf(contentWidth, headwordPaint.measureText(entry.expression))
            if (entry.reading.isNotEmpty() && entry.reading != entry.expression) {
                contentWidth = maxOf(contentWidth, headwordPaint.measureText(entry.expression) + readingPaint.measureText(entry.reading) + 16f * density)
            }
            entry.glossary.forEach { gloss ->
                val displayGloss = if (gloss.length > 50) gloss.take(50) + "…" else gloss
                contentWidth = maxOf(contentWidth, glossPaint.measureText(displayGloss) + 24f * density)
            }
        }
        if (showAnkiButton) {
            contentWidth += ankiButtonWidth + ankiButtonMargin
        }
        val popupWidth = minOf(contentWidth + paddingH * 2, maxWidth)

        // Calculate gloss text width for wrapping
        val glossNumberWidth = 20f * density
        val glossTextWidth = (popupWidth - paddingH * 2 - glossNumberWidth).toInt().coerceAtLeast(100)
        val glossTextPaintForLayout = TextPaint(glossPaint)

        // Calculate layouts and heights for each entry
        val entryLayouts = entries.map { entry ->
            val glossLayouts = entry.glossary.map { gloss ->
                StaticLayout.Builder.obtain(gloss, 0, gloss.length, glossTextPaintForLayout, glossTextWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1f)
                    .setIncludePad(false)
                    .build()
            }

            var entryHeight = headwordPaint.textSize  // Headword
            entryHeight += headwordPaint.textSize * 0.3f  // Space after headword
            if (entry.reading.isNotEmpty() && entry.reading != entry.expression) {
                entryHeight += readingPaint.textSize + 2f * density  // Reading
            }
            if (entry.pitchDownstep != null) {
                entryHeight += 24f * density  // Pitch contour
            }
            if (entry.posDisplayLabel != null) {
                entryHeight += posTagPaint.textSize + 4f * density  // POS label
            }
            if (entry.deinflectionPath.isNotEmpty()) {
                entryHeight += deinflectPaint.textSize + 8f * density  // Deinflection
            }
            if (glossLayouts.isNotEmpty()) {
                entryHeight += 10f * density  // Gap before glosses
                glossLayouts.forEach { layout ->
                    entryHeight += layout.height + 4f * density
                }
            }
            entryHeight += 8f * density  // Bottom padding

            EntryLayout(entry, glossLayouts, entryHeight)
        }

        // Calculate total content height
        var totalContentHeight = 0f
        entryLayouts.forEachIndexed { index, layout ->
            totalContentHeight += layout.height
            if (index < entryLayouts.size - 1) {
                totalContentHeight += dividerHeight  // Divider between entries
            }
        }
        popupContentHeight = totalContentHeight

        // Calculate popup height (constrained), accounting for counter
        val counterReserve = if (entries.size > 1) 18f * density else 0f
        val fullPopupHeight = totalContentHeight + paddingV * 2 + counterReserve
        val popupHeight = minOf(fullPopupHeight, maxHeight)
        popupVisibleHeight = popupHeight - paddingV * 2 - counterReserve

        // Compute the bounding rect of highlighted text in view coordinates
        val (offsetX, offsetY) = getOverlayOffset()
        var hlTop = Float.MAX_VALUE
        var hlBottom = Float.MIN_VALUE
        val crossLine = crossLineHighlight
        if (crossLine != null && crossLine.isNotEmpty()) {
            for (mapping in crossLine) {
                val bounds = mapping.ocrResult.charBounds.getOrNull(mapping.charIndex)
                if (bounds != null) {
                    val top = bounds.top * scaleY + offsetY
                    val bottom = bounds.bottom * scaleY + offsetY
                    if (top < hlTop) hlTop = top
                    if (bottom > hlBottom) hlBottom = bottom
                }
            }
        } else if (selectedResult != null && selectedCharIndex >= 0) {
            for (i in 0 until selectedMatchLength) {
                val bounds = selectedResult!!.charBounds.getOrNull(selectedCharIndex + i)
                if (bounds != null) {
                    val top = bounds.top * scaleY + offsetY
                    val bottom = bounds.bottom * scaleY + offsetY
                    if (top < hlTop) hlTop = top
                    if (bottom > hlBottom) hlBottom = bottom
                }
            }
        }

        // Smart popup positioning: avoid covering the highlighted text
        var finalX = popupX - popupWidth / 2
        finalX = finalX.coerceIn(8f, width - popupWidth - 8f)
        val margin = 12f * density
        var finalY: Float
        if (hlTop < Float.MAX_VALUE && hlBottom > Float.MIN_VALUE) {
            // We have valid highlight bounds - position to avoid covering them
            val spaceAbove = hlTop
            val spaceBelow = height - hlBottom
            finalY = if (spaceBelow >= popupHeight + margin) {
                // Enough space below highlighted text
                hlBottom + margin
            } else if (spaceAbove >= popupHeight + margin) {
                // Enough space above highlighted text
                hlTop - popupHeight - margin
            } else if (spaceBelow > spaceAbove) {
                // More space below, even if tight
                hlBottom + margin
            } else {
                // More space above, even if tight
                hlTop - popupHeight - margin
            }
        } else {
            // Fallback to cursor-based positioning
            val spaceAbove = lastCursorY
            val spaceBelow = height - lastCursorY
            val fabMargin = 60f * density
            finalY = if (lastCursorY > 0f && (spaceBelow > spaceAbove)) {
                lastCursorY + fabMargin
            } else if (lastCursorY > 0f) {
                lastCursorY - popupHeight - 40f * density
            } else {
                popupY - popupHeight - 20f * density
            }
        }
        finalY = finalY.coerceIn(8f, height - popupHeight - 8f)

        popupBounds = RectF(finalX, finalY, finalX + popupWidth, finalY + popupHeight)

        // Draw background
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

        // Draw entry counter ("1 of 3") when multiple entries
        val counterHeight = if (entries.size > 1) 18f * density else 0f
        if (entries.size > 1) {
            // Calculate which entry is currently visible based on scroll position
            var visibleIndex = 0
            var accHeight = 0f
            for (i in entryLayouts.indices) {
                accHeight += entryLayouts[i].height
                if (i < entryLayouts.size - 1) accHeight += dividerHeight
                if (accHeight > popupScrollY + 10f * density) {
                    visibleIndex = i
                    break
                }
                if (i == entryLayouts.lastIndex) visibleIndex = i
            }
            val counterText = "${visibleIndex + 1} of ${entries.size}"
            val counterPaint = Paint(entryCounterPaint).apply { this.alpha = (alpha * 255).toInt() }
            canvas.drawText(counterText, finalX + popupWidth / 2, finalY + paddingV / 2 + counterPaint.textSize / 3, counterPaint)
        }

        // Clip content area for scrolling (shifted down by counter height)
        canvas.save()
        canvas.clipRect(finalX, finalY + paddingV + counterHeight, finalX + popupWidth, finalY + popupHeight - paddingV)

        // Clear Anki button bounds list
        ankiButtonBoundsList.clear()

        // Draw each entry
        val headPaint = Paint(headwordPaint).apply { this.alpha = (255 * alpha).toInt() }
        val readPaint = Paint(readingPaint).apply { this.alpha = (180 * alpha).toInt() }
        val deinPaint = Paint(deinflectPaint).apply { this.alpha = (150 * alpha).toInt() }
        val glossNumPaint = Paint(glossNumberPaint).apply { this.alpha = (120 * alpha).toInt() }
        val dividerPaint = Paint().apply {
            color = Color.argb((30 * alpha).toInt(), 255, 255, 255)
            strokeWidth = 1f * density
        }

        var currentY = finalY + paddingV + counterHeight - popupScrollY

        entryLayouts.forEachIndexed { index, entryLayout ->
            val entry = entryLayout.entry
            val entryStartY = currentY

            // Skip if completely outside visible area
            if (currentY + entryLayout.height < finalY + paddingV - 50f * density ||
                currentY > finalY + popupHeight + 50f * density) {
                ankiButtonBoundsList.add(RectF())
                currentY += entryLayout.height
                if (index < entryLayouts.size - 1) currentY += dividerHeight
                return@forEachIndexed
            }

            // Draw Anki button (top-right of this entry)
            if (showAnkiButton) {
                val buttonX = finalX + popupWidth - ankiButtonWidth - ankiButtonMargin
                val buttonY = currentY + ankiButtonMargin
                val buttonRect = RectF(buttonX, buttonY, buttonX + ankiButtonWidth, buttonY + ankiButtonHeight)
                ankiButtonBoundsList.add(buttonRect)

                val buttonRadius = 6f * density
                val state = ankiButtonStates[index] ?: AnkiButtonState.IDLE

                when (state) {
                    AnkiButtonState.IDLE -> {
                        val buttonPaint = Paint(ankiButtonPaint).apply { this.alpha = (200 * alpha).toInt() }
                        canvas.drawRoundRect(buttonRect, buttonRadius, buttonRadius, buttonPaint)
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
                        val buttonPaint = Paint(ankiButtonPaint).apply { this.alpha = (180 * alpha).toInt() }
                        canvas.drawRoundRect(buttonRect, buttonRadius, buttonRadius, buttonPaint)
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
                        val buttonPaint = Paint(ankiButtonSuccessPaint).apply { this.alpha = (200 * alpha).toInt() }
                        canvas.drawRoundRect(buttonRect, buttonRadius, buttonRadius, buttonPaint)
                        val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.WHITE
                            style = Paint.Style.STROKE
                            strokeWidth = 2.5f * density
                            strokeCap = Paint.Cap.ROUND
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
                ankiButtonBoundsList.add(RectF())
            }

            // Calculate max width for content (leave room for Anki button)
            val maxContentX = if (showAnkiButton) {
                finalX + popupWidth - ankiButtonWidth - ankiButtonMargin - 8f * density
            } else {
                finalX + popupWidth - paddingH
            }

            var y = currentY + headwordPaint.textSize * 0.85f

            // Draw headword
            canvas.drawText(entry.expression, finalX + paddingH, y, headPaint)
            var badgeX = finalX + paddingH + headwordPaint.measureText(entry.expression) + 8f * density

            // Draw frequency badge (dynamic color per entry)
            entry.frequencyBadge?.let { badge ->
                val badgeColor = entry.frequencyBadgeColor
                val badgePadH = 6f * density
                val badgePadV = 2f * density
                val badgeWidth = frequencyBadgePaint.measureText(badge) + badgePadH * 2
                val badgeHeight = frequencyBadgePaint.textSize + badgePadV * 2

                if (badgeX + badgeWidth <= maxContentX) {
                    val badgeY = y - headwordPaint.textSize + (headwordPaint.textSize - badgeHeight) / 2
                    val freqBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.argb((40 * alpha).toInt(), Color.red(badgeColor), Color.green(badgeColor), Color.blue(badgeColor))
                    }
                    val badgeRect = RectF(badgeX, badgeY, badgeX + badgeWidth, badgeY + badgeHeight)
                    canvas.drawRoundRect(badgeRect, badgeHeight / 2, badgeHeight / 2, freqBgPaint)
                    val freqTextPaint = Paint(frequencyBadgePaint).apply {
                        color = Color.argb((220 * alpha).toInt(), Color.red(badgeColor), Color.green(badgeColor), Color.blue(badgeColor))
                    }
                    canvas.drawText(badge, badgeX + badgePadH, badgeY + badgeHeight - badgePadV - 1f * density, freqTextPaint)
                    badgeX += badgeWidth + 6f * density
                }
            }

            // Draw JPDB badge
            entry.jpdbBadge?.let { badge ->
                val jpdbColor = entry.jpdbBadgeColor
                val badgePadH = 6f * density
                val badgePadV = 2f * density
                val badgeWidth = frequencyBadgePaint.measureText(badge) + badgePadH * 2
                val badgeHeight = frequencyBadgePaint.textSize + badgePadV * 2

                if (badgeX + badgeWidth <= maxContentX) {
                    val badgeY = y - headwordPaint.textSize + (headwordPaint.textSize - badgeHeight) / 2
                    val jpdbBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.argb((40 * alpha).toInt(), Color.red(jpdbColor), Color.green(jpdbColor), Color.blue(jpdbColor))
                    }
                    val badgeRect = RectF(badgeX, badgeY, badgeX + badgeWidth, badgeY + badgeHeight)
                    canvas.drawRoundRect(badgeRect, badgeHeight / 2, badgeHeight / 2, jpdbBgPaint)
                    val jpdbTextPaint = Paint(frequencyBadgePaint).apply {
                        color = Color.argb((220 * alpha).toInt(), Color.red(jpdbColor), Color.green(jpdbColor), Color.blue(jpdbColor))
                    }
                    canvas.drawText(badge, badgeX + badgePadH, badgeY + badgeHeight - badgePadV - 1f * density, jpdbTextPaint)
                    badgeX += badgeWidth + 6f * density
                }
            }

            // Draw name type badge
            entry.nameTypeLabel?.let { label ->
                val badgePadH = 6f * density
                val badgePadV = 2f * density
                val badgeWidth = nameBadgePaint.measureText(label) + badgePadH * 2
                val badgeHeight = nameBadgePaint.textSize + badgePadV * 2

                if (badgeX + badgeWidth <= maxContentX) {
                    val badgeY = y - headwordPaint.textSize + (headwordPaint.textSize - badgeHeight) / 2
                    val nameBgPaint = Paint(nameBadgeBgPaint).apply { this.alpha = (40 * alpha).toInt() }
                    val badgeRect = RectF(badgeX, badgeY, badgeX + badgeWidth, badgeY + badgeHeight)
                    canvas.drawRoundRect(badgeRect, badgeHeight / 2, badgeHeight / 2, nameBgPaint)
                    val nameTextPaint = Paint(nameBadgePaint).apply { this.alpha = (220 * alpha).toInt() }
                    canvas.drawText(label, badgeX + badgePadH, badgeY + badgeHeight - badgePadV - 1f * density, nameTextPaint)
                }
            }

            y += headwordPaint.textSize * 0.3f

            // Draw reading (if different from expression)
            if (entry.reading.isNotEmpty() && entry.reading != entry.expression) {
                y += readingPaint.textSize + 2f * density
                canvas.drawText(entry.reading, finalX + paddingH, y, readPaint)
            }

            // Draw pitch accent contour
            entry.pitchDownstep?.let { downstep ->
                val reading = if (entry.reading.isNotEmpty()) entry.reading else entry.expression
                val morae = splitMorae(reading)
                if (morae.isNotEmpty()) {
                    y += 4f * density
                    drawPitchContour(canvas, morae, downstep, finalX + paddingH, y, alpha)
                    y += 20f * density
                }
            }

            // Draw POS label
            entry.posDisplayLabel?.let { posLabel ->
                y += posTagPaint.textSize + 4f * density
                val posText = if (posLabel.length > 40) posLabel.take(40) + "…" else posLabel
                val posAlpha = alpha
                val posPaint = Paint(posTagPaint).apply { this.alpha = (200 * posAlpha).toInt() }
                canvas.drawText(posText, finalX + paddingH, y, posPaint)
            }

            // Draw deinflection path
            if (entry.deinflectionPath.isNotEmpty()) {
                y += deinflectPaint.textSize + 8f * density
                canvas.drawText("→ ${entry.deinflectionPath}", finalX + paddingH, y, deinPaint)
            }

            // Draw glossary
            if (entryLayout.glossLayouts.isNotEmpty()) {
                y += 10f * density
                entryLayout.glossLayouts.forEachIndexed { glossIndex, layout ->
                    y += 4f * density
                    val numText = "${glossIndex + 1}."
                    canvas.drawText(numText, finalX + paddingH, y + glossPaint.textSize * 0.85f, glossNumPaint)
                    canvas.save()
                    canvas.translate(finalX + paddingH + glossNumberWidth, y)
                    layout.draw(canvas)
                    canvas.restore()
                    y += layout.height
                }
            }

            // Draw source label
            val srcLabelPaint = Paint(sourceLabelPaint).apply { this.alpha = (alpha * 255).toInt() }
            val sourceLabel = entry.sourceLabel
            val labelWidth = sourceLabelPaint.measureText(sourceLabel)
            canvas.drawText(sourceLabel, maxContentX - labelWidth, currentY + entryLayout.height - 4f * density, srcLabelPaint)

            currentY += entryLayout.height

            // Draw divider (if not last entry)
            if (index < entryLayouts.size - 1) {
                val dividerY = currentY + dividerHeight / 2
                canvas.drawLine(finalX + paddingH, dividerY, finalX + popupWidth - paddingH, dividerY, dividerPaint)
                currentY += dividerHeight
            }
        }

        canvas.restore()

        // Draw scroll indicator if content overflows
        if (popupContentHeight > popupVisibleHeight) {
            val scrollBarWidth = 3f * density
            val scrollBarMargin = 4f * density
            val scrollableRange = popupContentHeight - popupVisibleHeight
            val visibleRatio = popupVisibleHeight / popupContentHeight
            val scrollBarHeight = visibleRatio * (popupHeight - paddingV * 2)
            val scrollBarY = finalY + paddingV + (popupScrollY / scrollableRange) * (popupHeight - paddingV * 2 - scrollBarHeight)

            val scrollBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb((80 * alpha).toInt(), 255, 255, 255)
            }
            val scrollBarRect = RectF(
                finalX + popupWidth - scrollBarWidth - scrollBarMargin,
                scrollBarY,
                finalX + popupWidth - scrollBarMargin,
                scrollBarY + scrollBarHeight
            )
            canvas.drawRoundRect(scrollBarRect, scrollBarWidth / 2, scrollBarWidth / 2, scrollBarPaint)
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val source = event.source
        if ((source and InputDevice.SOURCE_JOYSTICK) != 0 ||
            (source and InputDevice.SOURCE_GAMEPAD) != 0) {
            val service = YomidroidAccessibilityService.instance
            if (service != null) {
                return service.handleJoystickMotionFromView(event)
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchY = y
                // Check if touch is within popup bounds for potential scrolling
                popupBounds?.let { bounds ->
                    if (bounds.contains(x, y) && popupContentHeight > popupVisibleHeight) {
                        isDraggingPopup = true
                    }
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDraggingPopup && popupContentHeight > popupVisibleHeight) {
                    val deltaY = lastTouchY - y
                    val maxScroll = popupContentHeight - popupVisibleHeight
                    popupScrollY = (popupScrollY + deltaY).coerceIn(0f, maxScroll)
                    lastTouchY = y
                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = isDraggingPopup
                isDraggingPopup = false

                // If we were dragging, don't process as a tap
                if (wasDragging && kotlin.math.abs(y - lastTouchY) > 10f) {
                    return true
                }

                // Check if tap is on any per-row Anki export button
                for ((index, bounds) in ankiButtonBoundsList.withIndex()) {
                    if (bounds.contains(x, y) && index < currentDefinitions.size) {
                        val state = ankiButtonStates[index] ?: AnkiButtonState.IDLE
                        if (state == AnkiButtonState.IDLE) {
                            val entry = currentDefinitions[index]
                            val sentence = selectedResult?.text ?: ""
                            selectedDefinitionIndex = index  // Track which one is exporting
                            onAnkiExport?.invoke(entry, sentence)
                        }
                        return true
                    }
                }

                // Check if tap is within popup (but not on button) - ignore to allow scrolling
                popupBounds?.let { bounds ->
                    if (bounds.contains(x, y)) {
                        return true
                    }
                }

                // First check if tap is on any OCR text region
                for ((result, bounds) in adjustedResults) {
                    if (bounds.contains(x, y)) {
                        val charIndex = getCharIndexAt(result, x, y)
                        if (charIndex >= 0) {
                            // Clear any existing popup before new lookup
                            currentDefinitions = emptyList()
                            selectedDefinitionIndex = 0
                            showingNoResults = false
                            popupScrollY = 0f  // Reset scroll for new popup

                            selectedResult = result
                            selectedCharIndex = charIndex

                            // Position popup near tap
                            popupX = x
                            popupY = y

                            invalidate()
                            onTextTapped(result, charIndex)  // Pass OcrResult for unified context lookup
                            return true
                        }
                    }
                }

                // Tap is outside text regions
                // If showing popup/no-results, close it first
                if (currentDefinitions.isNotEmpty() || showingNoResults || richPopupActive) {
                    currentDefinitions = emptyList()
                    selectedDefinitionIndex = 0
                    showingNoResults = false
                    if (richPopupActive) {
                        richPopupActive = false
                        onDismissRichPopup?.invoke()
                    }
                    selectedResult = null
                    selectedCharIndex = -1
                    popupScrollY = 0f
                    popupBounds = null  // Clear bounds so subsequent taps aren't blocked
                    ankiButtonBoundsList.clear()
                    ankiButtonStates.clear()
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
     * Get the overlay window's offset from the screen origin.
     * Non-zero when the overlay doesn't cover the full screen (e.g., status bar offset).
     * Used to convert between screen coordinates and overlay-local coordinates.
     */
    private fun getOverlayOffset(): Pair<Float, Float> {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        return loc[0].toFloat() to loc[1].toFloat()
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
     * @param result The OCR result to search within
     * @param screenX X coordinate in screen space
     * @param screenY Y coordinate in screen space
     * @param overlayOffsetX Overlay window X offset from screen origin
     * @param overlayOffsetY Overlay window Y offset from screen origin
     * @return Character index, or -1 if cursor is not over any character
     */
    /**
     * Find which character index a coordinate maps to.
     *
     * Coordinate handling:
     * - When called from onTouchEvent: coords are view-local, overlayOffsetX/Y = 0 (default)
     *   → screen = viewLocal + overlayLocation → bitmap = screen / scale
     * - When called from findTextAtCursor: coords are screen space, overlayOffsetX/Y are provided
     *   → bitmap = coords / scale (offset cancels out)
     */
    private fun getCharIndexAt(
        result: OcrResult,
        inputX: Float,
        inputY: Float,
        overlayOffsetX: Float = 0f,
        overlayOffsetY: Float = 0f
    ): Int {
        // Convert to screen coordinates first
        val loc = IntArray(2)
        getLocationOnScreen(loc)

        // If overlayOffset is provided (> 0), input is screen coords: screen = input
        // If overlayOffset is 0 (from onTouchEvent), input is view-local: screen = input + loc
        val screenCoordX: Float
        val screenCoordY: Float
        if (overlayOffsetX != 0f || overlayOffsetY != 0f) {
            // Caller provided screen coords with explicit offset
            screenCoordX = inputX
            screenCoordY = inputY
        } else {
            // Touch event: view-local → screen
            screenCoordX = inputX + loc[0].toFloat()
            screenCoordY = inputY + loc[1].toFloat()
        }

        // Screen → bitmap
        val bitmapX = screenCoordX / scaleX
        val bitmapY = screenCoordY / scaleY

        // Nearest-center matching: find the character whose center is closest to the tap point.
        // This avoids the right-leaning bias of bounds-containment when character bounding boxes
        // are wider than the visual glyphs.
        var bestIndex = -1
        var bestDist = Float.MAX_VALUE

        for ((index, bounds) in result.charBounds.withIndex()) {
            val centerX = (bounds.left + bounds.right) / 2f
            val centerY = (bounds.top + bounds.bottom) / 2f
            val dx = bitmapX - centerX
            val dy = bitmapY - centerY
            val dist = dx * dx + dy * dy
            if (dist < bestDist) {
                bestDist = dist
                bestIndex = index
            }
        }

        // Only accept if within reasonable distance (1.5x the average char width)
        if (bestIndex >= 0 && result.charBounds.isNotEmpty()) {
            val avgWidth = result.charBounds.sumOf { (it.right - it.left).toDouble() }.toFloat() / result.charBounds.size
            val maxAcceptDist = avgWidth * 1.5f
            if (kotlin.math.sqrt(bestDist) <= maxAcceptDist) {
                return bestIndex
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
        showDefinitions(listOf(entry), charIndex)
    }

    /**
     * Show multiple dictionary definitions with navigation support.
     */
    fun showDefinitions(entries: List<DictionaryEntry>, charIndex: Int) {
        val newExpression = entries.firstOrNull()?.expression
        if (newExpression != lastSavedExpression || lastShownEntry == null) {
            // Save previous word if viewed for 500ms+
            maybeSaveToHistory()
        }

        showingNoResults = false
        currentDefinitions = entries
        selectedDefinitionIndex = 0
        selectedMatchLength = entries.firstOrNull()?.matchedText?.length ?: 1
        resetAnkiButtonState()
        popupScrollY = 0f  // Reset scroll for new popup

        // Track popup open time and entry for history
        popupOpenTime = System.currentTimeMillis()
        lastShownEntry = currentDefinition
        lastShownSentence = selectedResult?.text ?: ""

        animatePopupIn()
    }

    /**
     * Show definition at a specific cursor position (for cursor-based lookup).
     * Positions popup above the selected text, not the cursor.
     */
    fun showDefinitionAtCursor(entry: DictionaryEntry, cursorX: Float, cursorY: Float) {
        showDefinitionsAtCursor(listOf(entry), cursorX, cursorY)
    }

    fun showDefinitionsAtCursor(entries: List<DictionaryEntry>, cursorX: Float, cursorY: Float) {
        if (entries.isEmpty()) {
            hideDefinition()
            return
        }

        // Skip if already showing these exact entries (reduces redraws during cursor movement)
        if (currentDefinitions == entries && popupAlpha >= 1f) {
            return
        }

        val newExpression = entries.firstOrNull()?.expression
        if (newExpression != lastSavedExpression || lastShownEntry == null) {
            // Save previous word if viewed for 500ms+
            maybeSaveToHistory()
        }

        // Store cursor position for smart popup placement
        val (_, offsetY) = getOverlayOffset()
        lastCursorY = cursorY - offsetY

        showingNoResults = false
        currentDefinitions = entries
        selectedDefinitionIndex = 0
        selectedMatchLength = entries.first().matchedText.length
        resetAnkiButtonState()

        // Track popup open time and entry for history
        popupOpenTime = System.currentTimeMillis()
        lastShownEntry = currentDefinition
        lastShownSentence = selectedResult?.text ?: ""

        // Position popup centered over matched text horizontally
        val firstBounds = selectedResult?.charBounds?.getOrNull(selectedCharIndex)
        val lastBounds = selectedResult?.charBounds?.getOrNull(selectedCharIndex + selectedMatchLength - 1)

        if (firstBounds != null && lastBounds != null) {
            val centerX = (firstBounds.left + lastBounds.right) / 2f * scaleX
            popupX = centerX
        } else if (firstBounds != null) {
            popupX = firstBounds.centerX() * scaleX
        } else {
            popupX = cursorX
        }
        // popupY is set dynamically in drawDefinitionsList based on lastCursorY
        popupY = lastCursorY
        animatePopupIn()
    }

    fun showNoResults() {
        currentDefinitions = emptyList()
        selectedDefinitionIndex = 0
        showingNoResults = true
        selectedMatchLength = 1
        animatePopupIn()
    }

    fun hideDefinition() {
        // Save to history if popup was open for 500ms+
        maybeSaveToHistory()

        animatePopupOut {
            currentDefinitions = emptyList()
            selectedDefinitionIndex = 0
            showingNoResults = false
            selectedResult = null
            selectedCharIndex = -1
            selectedMatchLength = 1
        }
    }

    /**
     * Check if the popup was open for 500ms+ and save to history if so.
     * Uses lastSavedExpression to avoid re-saving the same word on cursor micro-movements.
     */
    private fun maybeSaveToHistory() {
        val entry = lastShownEntry ?: return
        if (popupOpenTime <= 0) return

        val elapsed = System.currentTimeMillis() - popupOpenTime
        if (elapsed >= 500 && entry.expression != lastSavedExpression) {
            onSaveToHistory?.invoke(entry, lastShownSentence)
            lastSavedExpression = entry.expression
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
        crossLineHighlight = null  // Clear cross-line highlight when using single-line
        selectedResult = result
        selectedCharIndex = charIndex
        selectedMatchLength = matchLength
        invalidate()
    }

    /**
     * Set highlight for a match that may span multiple OcrResults.
     * Uses unified context to find all character positions to highlight.
     *
     * @param context The unified OCR context
     * @param unifiedStartIndex Starting index in the unified text string
     * @param matchLength Number of characters in the match
     */
    fun setHighlightFromUnified(
        context: UnifiedOcrContext,
        unifiedStartIndex: Int,
        matchLength: Int
    ) {
        // Get all character mappings in the match range
        val mappings = context.getCharMappingsInRange(unifiedStartIndex, matchLength)
        if (mappings.isEmpty()) return

        // Skip if highlight hasn't changed (reduces redraws during cursor movement)
        if (crossLineHighlight == mappings) return

        // Store cross-line highlight info
        crossLineHighlight = mappings

        // Set selectedResult to first character's OcrResult for popup positioning
        val first = mappings.first()
        selectedResult = first.ocrResult
        selectedCharIndex = first.charIndex
        // Count how many characters are in the first OcrResult for sentence extraction
        selectedMatchLength = mappings.count { it.ocrResult == first.ocrResult }

        invalidate()
    }

    /**
     * Show definitions with unified match info for cross-line highlighting.
     * Positions popup and sets up highlighting across OcrResult boundaries.
     *
     * @param entries List of dictionary entries to display (with navigation)
     * @param unifiedStartIndex Starting index in the unified text string
     * @param matchLength Number of characters in the match
     * @param context The unified OCR context
     */
    fun showDefinitionsWithUnifiedMatch(
        entries: List<DictionaryEntry>,
        unifiedStartIndex: Int,
        matchLength: Int,
        context: UnifiedOcrContext
    ) {
        val newExpression = entries.firstOrNull()?.expression
        if (newExpression != lastSavedExpression || lastShownEntry == null) {
            // Save previous word if viewed for 500ms+
            maybeSaveToHistory()
        }

        // Set up cross-line highlighting
        setHighlightFromUnified(context, unifiedStartIndex, matchLength)

        showingNoResults = false
        currentDefinitions = entries
        selectedDefinitionIndex = 0
        resetAnkiButtonState()
        popupScrollY = 0f

        // Track popup open time and entry for history
        popupOpenTime = System.currentTimeMillis()
        lastShownEntry = currentDefinition
        lastShownSentence = selectedResult?.text ?: ""

        // Position popup above the first character of the match
        val firstMapping = context.getLocalPosition(unifiedStartIndex)
        if (firstMapping != null) {
            val firstBounds = firstMapping.ocrResult.charBounds.getOrNull(firstMapping.charIndex)
            if (firstBounds != null) {
                // Center horizontally over the match start
                popupX = firstBounds.centerX() * scaleX
                popupY = firstBounds.top * scaleY
            }
        }

        animatePopupIn()
    }

    // Keep old method for compatibility
    fun showDefinitionWithUnifiedMatch(
        entry: DictionaryEntry,
        unifiedStartIndex: Int,
        matchLength: Int,
        context: UnifiedOcrContext
    ) {
        showDefinitionsWithUnifiedMatch(listOf(entry), unifiedStartIndex, matchLength, context)
    }

    /**
     * Clear the current highlight.
     */
    fun clearHighlight() {
        selectedResult = null
        selectedCharIndex = -1
        selectedMatchLength = 1
        crossLineHighlight = null  // Clear cross-line highlight
        currentDefinitions = emptyList()
        selectedDefinitionIndex = 0
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
     * Programmatic popup scroll for hardware button input.
     * Positive deltaY scrolls down, negative scrolls up.
     */
    fun scrollBy(deltaY: Float) {
        if (popupContentHeight <= popupVisibleHeight) return
        val maxScroll = popupContentHeight - popupVisibleHeight
        popupScrollY = (popupScrollY + deltaY).coerceIn(0f, maxScroll)
        invalidate()
    }

    /**
     * Whether the popup is currently visible (has definitions or no-results).
     */
    fun isPopupVisible(): Boolean = currentDefinitions.isNotEmpty() || showingNoResults

    /**
     * Whether the popup has scrollable content.
     */
    fun isPopupScrollable(): Boolean = popupContentHeight > popupVisibleHeight

    /**
     * Dismiss the popup (definitions or no-results) without dismissing the overlay.
     */
    fun dismissPopup() {
        if (currentDefinitions.isNotEmpty() || showingNoResults) {
            maybeSaveToHistory()
            currentDefinitions = emptyList()
            selectedDefinitionIndex = 0
            showingNoResults = false
            selectedResult = null
            selectedCharIndex = -1
            popupScrollY = 0f
            popupBounds = null
            ankiButtonBoundsList.clear()
            ankiButtonStates.clear()
            invalidate()
        }
    }

    /**
     * Get the screen-space center points of all OCR text blocks.
     * Used for block-based cursor navigation (jump between text regions).
     * Returns list of (centerX, centerY) in screen coordinates.
     */
    fun getOcrBlockCenters(): List<Pair<Float, Float>> {
        return adjustedResults.map { (_, bounds) ->
            bounds.centerX() to bounds.centerY()
        }
    }

    /**
     * Get all character center positions grouped by OCR line (row).
     * Each row is sorted left-to-right; rows are sorted top-to-bottom.
     * Returns screen coordinates (including overlay offset).
     */
    fun getCharacterGrid(): List<List<Pair<Float, Float>>> {
        val (offsetX, offsetY) = getOverlayOffset()
        val sorted = adjustedResults.sortedBy { it.first.lineBounds.top }
        return sorted.mapNotNull { (result, _) ->
            val chars = result.charBounds.map { b ->
                (b.centerX() * scaleX + offsetX) to (b.centerY() * scaleY + offsetY)
            }
            chars.ifEmpty { null }
        }
    }

    /**
     * Find OCR result and char index at cursor position (for cursor-based lookup).
     * Cursor coordinates are in screen space. Accounts for overlay window offset
     * (e.g., status bar) when converting to overlay-local coordinates for hit testing.
     * Returns null if cursor is not over any text.
     */
    fun findTextAtCursor(cursorX: Float, cursorY: Float): Triple<OcrResult, Int, RectF>? {
        val (offsetX, offsetY) = getOverlayOffset()

        // Convert cursor screen coords to overlay-local coords for bounds checking
        val localX = cursorX - offsetX
        val localY = cursorY - offsetY

        for ((result, bounds) in adjustedResults) {
            if (bounds.contains(localX, localY)) {
                val charIndex = getCharIndexAt(result, cursorX, cursorY, offsetX, offsetY)
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
     * Cursor coordinates are in screen space. Accounts for overlay window offset.
     * Used for visual feedback during cursor-based navigation.
     */
    fun highlightAtCursor(cursorX: Float, cursorY: Float): Pair<String, Int>? {
        val (offsetX, offsetY) = getOverlayOffset()
        val localX = cursorX - offsetX
        val localY = cursorY - offsetY

        for ((result, bounds) in adjustedResults) {
            if (bounds.contains(localX, localY)) {
                val charIndex = getCharIndexAt(result, cursorX, cursorY, offsetX, offsetY)
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
     * Uses selectedDefinitionIndex to know which entry's button to update.
     */
    fun setAnkiButtonLoading() {
        loadingEntryIndex = selectedDefinitionIndex
        ankiButtonStates[selectedDefinitionIndex] = AnkiButtonState.LOADING
        startLoadingAnimation()
        invalidate()
    }

    /**
     * Set the Anki button to success state (card was added).
     */
    fun setAnkiButtonSuccess() {
        stopLoadingAnimation()
        if (loadingEntryIndex >= 0) {
            ankiButtonStates[loadingEntryIndex] = AnkiButtonState.SUCCESS
        }
        invalidate()
    }

    /**
     * Set the Anki button to already exported state (card exists in Anki).
     */
    fun setAnkiButtonAlreadyExported() {
        stopLoadingAnimation()
        if (loadingEntryIndex >= 0) {
            ankiButtonStates[loadingEntryIndex] = AnkiButtonState.ALREADY
        }
        invalidate()
    }

    /**
     * Reset all Anki button states to idle.
     */
    fun resetAnkiButtonState() {
        stopLoadingAnimation()
        ankiButtonStates.clear()
        loadingEntryIndex = -1
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
     * Split a Japanese reading into morae (syllable units).
     * Handles small kana (ゃゅょぁぃぅぇぉ) as part of the previous mora.
     */
    private fun splitMorae(reading: String): List<String> {
        val smallKana = setOf(
            'ゃ', 'ゅ', 'ょ', 'ぁ', 'ぃ', 'ぅ', 'ぇ', 'ぉ',
            'ャ', 'ュ', 'ョ', 'ァ', 'ィ', 'ゥ', 'ェ', 'ォ'
        )
        val morae = mutableListOf<String>()
        var i = 0
        while (i < reading.length) {
            val sb = StringBuilder()
            sb.append(reading[i])
            i++
            // Absorb following small kana
            while (i < reading.length && reading[i] in smallKana) {
                sb.append(reading[i])
                i++
            }
            morae.add(sb.toString())
        }
        return morae
    }

    /**
     * Draw pitch accent contour visualization.
     * Shows morae with a line indicating pitch pattern (high/low).
     *
     * @param downstep 0 = heiban (flat), 1 = atamadaka, n = odaka/nakadaka
     */
    private fun drawPitchContour(
        canvas: Canvas,
        morae: List<String>,
        downstep: Int,
        startX: Float,
        startY: Float,
        alpha: Float
    ) {
        val moraWidth = 14f * density
        val highY = startY
        val lowY = startY + 14f * density
        val dotRadius = 2.5f * density

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((180 * alpha).toInt(), 130, 180, 255)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
            strokeCap = Paint.Cap.ROUND
        }
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((220 * alpha).toInt(), 130, 180, 255)
            style = Paint.Style.FILL
        }
        val moraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((160 * alpha).toInt(), 200, 200, 200)
            textSize = 10f * density
            textAlign = Paint.Align.CENTER
        }

        // Determine pitch for each mora
        val isHigh = BooleanArray(morae.size) { i ->
            when {
                downstep == 0 -> i > 0  // Heiban: low then all high
                downstep == 1 -> i == 0  // Atamadaka: high then all low
                else -> i > 0 && i < downstep  // Nakadaka/Odaka: low, high until drop
            }
        }

        // Draw connecting lines and dots
        for (i in morae.indices) {
            val cx = startX + i * moraWidth + moraWidth / 2
            val cy = if (isHigh[i]) highY else lowY

            // Draw dot
            canvas.drawCircle(cx, cy, dotRadius, dotPaint)

            // Draw line to next mora
            if (i < morae.size - 1) {
                val nextCy = if (isHigh[i + 1]) highY else lowY
                val nextCx = startX + (i + 1) * moraWidth + moraWidth / 2
                canvas.drawLine(cx, cy, nextCx, nextCy, linePaint)
            }

            // Draw mora text below
            canvas.drawText(morae[i], cx, lowY + moraPaint.textSize + 2f * density, moraPaint)
        }

        // Draw downstep marker (red circle) at the drop point
        if (downstep > 0 && downstep <= morae.size) {
            val dropX = startX + (downstep - 1) * moraWidth + moraWidth / 2
            val dropY = highY
            val dropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb((200 * alpha).toInt(), 244, 67, 54)
                style = Paint.Style.STROKE
                strokeWidth = 1.5f * density
            }
            canvas.drawCircle(dropX, dropY, dotRadius + 2f * density, dropPaint)
        }
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
