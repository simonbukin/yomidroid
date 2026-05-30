package com.yomidroid.ui.settings

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.yomidroid.config.CropPreset
import com.yomidroid.config.OcrConfigManager
import java.io.File
import kotlin.math.abs

private data class CropEdges(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun toRectSize(canvasW: Float, canvasH: Float) =
        floatArrayOf(left * canvasW, top * canvasH, right * canvasW, bottom * canvasH)
}

private enum class CropHandle { LEFT, TOP, RIGHT, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, CENTER }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrCropPickerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configManager = remember { OcrConfigManager(context) }
    val initialConfig = remember { configManager.getConfig() }

    val sampleFile = remember { File(context.cacheDir, "crop_picker_sample.jpg") }
    val sampleBitmap: ImageBitmap? = remember(sampleFile.lastModified()) {
        if (sampleFile.exists()) {
            runCatching { BitmapFactory.decodeFile(sampleFile.absolutePath)?.asImageBitmap() }
                .getOrNull()
        } else null
    }

    var edges by remember {
        mutableStateOf(
            CropEdges(
                left = initialConfig.customCropLeft,
                top = initialConfig.customCropTop,
                right = initialConfig.customCropRight,
                bottom = initialConfig.customCropBottom
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Adjust crop") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        configManager.saveConfig(
                            initialConfig.copy(
                                cropPreset = CropPreset.CUSTOM,
                                customCropLeft = edges.left,
                                customCropTop = edges.top,
                                customCropRight = edges.right,
                                customCropBottom = edges.bottom
                            )
                        )
                        Toast.makeText(context, "Crop saved", Toast.LENGTH_SHORT).show()
                        onBack()
                    }) {
                        Text("Save")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Drag a corner or edge to resize. Drag inside the box to move it. Saved as Custom crop.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (sampleBitmap == null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "No sample screenshot yet",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Open the app you want to OCR, tap the floating button to capture once, then come back here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            } else {
                val bmp = sampleBitmap
                val handleRadiusPx = with(density) { 14.dp.toPx() }
                val hitSlopPx = with(density) { 28.dp.toPx() }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.Black)
                ) {
                    val imgRatio = bmp.width.toFloat() / bmp.height.toFloat()
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .aspectRatio(imgRatio)
                            .fillMaxWidth()
                    ) {
                        androidx.compose.foundation.Image(
                            bitmap = bmp,
                            contentDescription = "Sample capture",
                            modifier = Modifier.fillMaxSize()
                        )

                        var canvasW by remember { mutableFloatStateOf(0f) }
                        var canvasH by remember { mutableFloatStateOf(0f) }
                        var grabbed by remember { mutableStateOf<CropHandle?>(null) }
                        var dragOriginEdges by remember { mutableStateOf<CropEdges?>(null) }
                        var dragOriginPoint by remember { mutableStateOf(Offset.Zero) }

                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = { pos ->
                                            grabbed = pickHandle(pos, edges, canvasW, canvasH, hitSlopPx)
                                            dragOriginEdges = edges
                                            dragOriginPoint = pos
                                        },
                                        onDragEnd = {
                                            grabbed = null
                                            dragOriginEdges = null
                                        },
                                        onDragCancel = {
                                            grabbed = null
                                            dragOriginEdges = null
                                        },
                                        onDrag = { change, _ ->
                                            change.consume()
                                            val origin = dragOriginEdges ?: return@detectDragGestures
                                            val cw = canvasW
                                            val ch = canvasH
                                            if (cw <= 0f || ch <= 0f) return@detectDragGestures
                                            val dxN = (change.position.x - dragOriginPoint.x) / cw
                                            val dyN = (change.position.y - dragOriginPoint.y) / ch
                                            edges = applyDrag(grabbed, origin, dxN, dyN)
                                        }
                                    )
                                }
                        ) {
                            canvasW = size.width
                            canvasH = size.height
                            val (l, t, r, b) = edges.toRectSize(size.width, size.height)

                            // Dim outside selection
                            val dim = Color.Black.copy(alpha = 0.55f)
                            drawRect(color = dim, topLeft = Offset(0f, 0f), size = Size(size.width, t))
                            drawRect(color = dim, topLeft = Offset(0f, b), size = Size(size.width, size.height - b))
                            drawRect(color = dim, topLeft = Offset(0f, t), size = Size(l, b - t))
                            drawRect(color = dim, topLeft = Offset(r, t), size = Size(size.width - r, b - t))

                            // Selection border
                            drawRect(
                                color = Color(0xFF82B4FF),
                                topLeft = Offset(l, t),
                                size = Size(r - l, b - t),
                                style = Stroke(width = 4f)
                            )

                            // Corner handles
                            val cornerColor = Color(0xFF82B4FF)
                            for (corner in listOf(Offset(l, t), Offset(r, t), Offset(l, b), Offset(r, b))) {
                                drawCircle(color = Color.White, radius = handleRadiusPx + 2f, center = corner)
                                drawCircle(color = cornerColor, radius = handleRadiusPx, center = corner)
                            }
                        }
                    }
                }

                // Aspect-ratio quick-fit row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { edges = CropEdges(0f, 0f, 1f, 1f) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Reset") }
                    OutlinedButton(
                        onClick = {
                            edges = fitAspectRatio(edges, 16f / 9f, bmp.width.toFloat() / bmp.height)
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("16:9") }
                    OutlinedButton(
                        onClick = {
                            edges = fitAspectRatio(edges, 4f / 3f, bmp.width.toFloat() / bmp.height)
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("4:3") }
                }

                Text(
                    "Crop: ${(edges.left * 100).toInt()}%, ${(edges.top * 100).toInt()}% → " +
                        "${(edges.right * 100).toInt()}%, ${(edges.bottom * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private const val MIN_EDGE = 0.05f

private fun pickHandle(
    pos: Offset,
    edges: CropEdges,
    canvasW: Float,
    canvasH: Float,
    hitSlopPx: Float
): CropHandle {
    if (canvasW <= 0f || canvasH <= 0f) return CropHandle.CENTER
    val l = edges.left * canvasW
    val t = edges.top * canvasH
    val r = edges.right * canvasW
    val b = edges.bottom * canvasH

    val tl = abs(pos.x - l) <= hitSlopPx && abs(pos.y - t) <= hitSlopPx
    val tr = abs(pos.x - r) <= hitSlopPx && abs(pos.y - t) <= hitSlopPx
    val bl = abs(pos.x - l) <= hitSlopPx && abs(pos.y - b) <= hitSlopPx
    val br = abs(pos.x - r) <= hitSlopPx && abs(pos.y - b) <= hitSlopPx
    if (tl) return CropHandle.TOP_LEFT
    if (tr) return CropHandle.TOP_RIGHT
    if (bl) return CropHandle.BOTTOM_LEFT
    if (br) return CropHandle.BOTTOM_RIGHT

    val nearLeft = abs(pos.x - l) <= hitSlopPx && pos.y in t..b
    val nearRight = abs(pos.x - r) <= hitSlopPx && pos.y in t..b
    val nearTop = abs(pos.y - t) <= hitSlopPx && pos.x in l..r
    val nearBottom = abs(pos.y - b) <= hitSlopPx && pos.x in l..r
    if (nearLeft) return CropHandle.LEFT
    if (nearRight) return CropHandle.RIGHT
    if (nearTop) return CropHandle.TOP
    if (nearBottom) return CropHandle.BOTTOM

    return CropHandle.CENTER
}

private fun applyDrag(
    handle: CropHandle?,
    origin: CropEdges,
    dxN: Float,
    dyN: Float
): CropEdges {
    if (handle == null) return origin
    var l = origin.left
    var t = origin.top
    var r = origin.right
    var b = origin.bottom
    when (handle) {
        CropHandle.LEFT -> l = (origin.left + dxN).coerceIn(0f, origin.right - MIN_EDGE)
        CropHandle.RIGHT -> r = (origin.right + dxN).coerceIn(origin.left + MIN_EDGE, 1f)
        CropHandle.TOP -> t = (origin.top + dyN).coerceIn(0f, origin.bottom - MIN_EDGE)
        CropHandle.BOTTOM -> b = (origin.bottom + dyN).coerceIn(origin.top + MIN_EDGE, 1f)
        CropHandle.TOP_LEFT -> {
            l = (origin.left + dxN).coerceIn(0f, origin.right - MIN_EDGE)
            t = (origin.top + dyN).coerceIn(0f, origin.bottom - MIN_EDGE)
        }
        CropHandle.TOP_RIGHT -> {
            r = (origin.right + dxN).coerceIn(origin.left + MIN_EDGE, 1f)
            t = (origin.top + dyN).coerceIn(0f, origin.bottom - MIN_EDGE)
        }
        CropHandle.BOTTOM_LEFT -> {
            l = (origin.left + dxN).coerceIn(0f, origin.right - MIN_EDGE)
            b = (origin.bottom + dyN).coerceIn(origin.top + MIN_EDGE, 1f)
        }
        CropHandle.BOTTOM_RIGHT -> {
            r = (origin.right + dxN).coerceIn(origin.left + MIN_EDGE, 1f)
            b = (origin.bottom + dyN).coerceIn(origin.top + MIN_EDGE, 1f)
        }
        CropHandle.CENTER -> {
            val w = origin.right - origin.left
            val h = origin.bottom - origin.top
            l = (origin.left + dxN).coerceIn(0f, 1f - w)
            t = (origin.top + dyN).coerceIn(0f, 1f - h)
            r = l + w
            b = t + h
        }
    }
    return CropEdges(l, t, r, b)
}

private fun fitAspectRatio(
    current: CropEdges,
    targetRatio: Float,
    bitmapRatio: Float
): CropEdges {
    // Express the target rect in image-pixel space (1.0 = full bitmap width or height).
    // Selection is in normalized 0..1 of the bitmap. Convert to physical aspect via the
    // bitmap's actual aspect; then center inside the current bbox or center on its center.
    val cx = (current.left + current.right) / 2f
    val cy = (current.top + current.bottom) / 2f

    // Pick the largest centered rect of the target aspect that fits in the bitmap.
    val ratioInNormalized = targetRatio / bitmapRatio
    val nh: Float
    val nw: Float
    if (ratioInNormalized >= 1f) {
        // Target is wider relative to bitmap → fit width = 1, height = 1/ratioInNormalized
        nw = 1f
        nh = (1f / ratioInNormalized).coerceAtMost(1f)
    } else {
        nh = 1f
        nw = ratioInNormalized
    }
    val left = (cx - nw / 2f).coerceIn(0f, 1f - nw)
    val top = (cy - nh / 2f).coerceIn(0f, 1f - nh)
    return CropEdges(left, top, left + nw, top + nh)
}
