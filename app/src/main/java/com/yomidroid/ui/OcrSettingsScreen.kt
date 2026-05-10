package com.yomidroid.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yomidroid.config.MangaOcrCropScaling
import com.yomidroid.config.MangaOcrDetector
import com.yomidroid.config.OcrConfig
import com.yomidroid.config.OcrConfigManager
import com.yomidroid.config.OcrEngineType
import com.yomidroid.ocr.MangaOcrModelManager
import com.yomidroid.service.YomidroidAccessibilityService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val configManager = remember { OcrConfigManager(context) }
    var config by remember { mutableStateOf(configManager.getConfig()) }
    val modelManager = remember { MangaOcrModelManager.getInstance(context) }
    var mangaOcrReady by remember { mutableStateOf(modelManager.isModelReady()) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var isDownloading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun saveAndApply(newConfig: OcrConfig) {
        config = newConfig
        configManager.saveConfig(newConfig)
        YomidroidAccessibilityService.instance?.reloadOcrSettings()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OCR Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        configManager.resetToDefaults()
                        config = configManager.getConfig()
                        YomidroidAccessibilityService.instance?.reloadOcrSettings()
                        Toast.makeText(context, "Reset to defaults", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reset")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text("OCR Engine", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            val translationPrefs = remember {
                context.getSharedPreferences("translation_settings", android.content.Context.MODE_PRIVATE)
            }
            val geminiKeyConfigured = remember(config) {
                !translationPrefs.getString("remote_api_key", "").isNullOrBlank() &&
                    !translationPrefs.getString("remote_api_endpoint", "").isNullOrBlank()
            }

            OcrEngineType.entries.forEach { engineType ->
                val isMangaOcr = engineType == OcrEngineType.MANGA_OCR
                val isGemini = engineType == OcrEngineType.GEMINI_FLASH
                val enabled = when {
                    isMangaOcr -> mangaOcrReady
                    isGemini -> geminiKeyConfigured
                    else -> true
                }

                EngineSelectionCard(
                    engineType = engineType,
                    isSelected = config.selectedEngine == engineType,
                    enabled = enabled,
                    onSelect = {
                        if (enabled) saveAndApply(config.copy(selectedEngine = engineType))
                    }
                )

                if (isGemini && !geminiKeyConfigured) {
                    Text(
                        "Set the endpoint + API key under Settings → Translation → Remote API.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 4.dp)
                    )
                }

                if (isMangaOcr) {
                    MangaOcrDownloadControls(
                        isReady = mangaOcrReady,
                        isDownloading = isDownloading,
                        downloadProgress = downloadProgress,
                        onDownload = {
                            isDownloading = true
                            downloadProgress = 0f
                            scope.launch {
                                val success = modelManager.downloadModels { p -> downloadProgress = p }
                                isDownloading = false
                                mangaOcrReady = modelManager.isModelReady()
                                Toast.makeText(
                                    context,
                                    if (success) "Manga OCR models downloaded" else "Download failed",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onDelete = {
                            modelManager.deleteModels()
                            mangaOcrReady = false
                            if (config.selectedEngine == OcrEngineType.MANGA_OCR) {
                                saveAndApply(config.copy(selectedEngine = OcrEngineType.ML_KIT))
                            }
                            Toast.makeText(context, "Manga OCR models deleted", Toast.LENGTH_SHORT).show()
                        }
                    )
                }

                Spacer(Modifier.height(8.dp))
            }

            if (mangaOcrReady && config.selectedEngine == OcrEngineType.MANGA_OCR) {
                Spacer(Modifier.height(16.dp))
                MangaOcrTuningCard(
                    config = config,
                    onChange = ::saveAndApply
                )
            }

            if (config.selectedEngine == OcrEngineType.GEMINI_FLASH) {
                Spacer(Modifier.height(16.dp))
                GeminiFlashTuningCard(
                    config = config,
                    onChange = ::saveAndApply
                )
            }

            Spacer(Modifier.height(24.dp))

            InfoCard(
                title = "ML Kit",
                points = listOf(
                    "Google's fast on-device OCR",
                    "Good for modern, clear text",
                    "Handles horizontal text well",
                    "Smaller memory footprint"
                )
            )
            Spacer(Modifier.height(8.dp))
            InfoCard(
                title = "Manga OCR",
                points = listOf(
                    "Specialized for manga/comic text",
                    "Tuning section above lets you A/B detector + scaling",
                    "Requires ~141 MB model download"
                )
            )
            Spacer(Modifier.height(8.dp))
            InfoCard(
                title = "Gemini Flash",
                points = listOf(
                    "Online vision LLM via Google's API",
                    "Best accuracy on stylized fonts (retro games, manga, low-res text)",
                    "Reuses translation endpoint + API key",
                    "Slower (~1–2s per OCR) and counts against your API quota"
                )
            )
        }
    }
}

@Composable
private fun MangaOcrTuningCard(
    config: OcrConfig,
    onChange: (OcrConfig) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Manga OCR tuning", style = MaterialTheme.typography.titleSmall)
            Text(
                "Toggle settings and re-OCR to A/B. Watch logcat (`MangaOcrEngine`) for timings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))
            SettingLabel("Detector")
            SegmentedRow(
                options = MangaOcrDetector.entries,
                selected = config.mangaOcrDetector,
                label = { it.displayName },
                onSelect = { onChange(config.copy(mangaOcrDetector = it)) }
            )
            HelperText(config.mangaOcrDetector.description)

            Spacer(Modifier.height(16.dp))
            SettingLabel("Crop scaling")
            SegmentedRow(
                options = MangaOcrCropScaling.entries,
                selected = config.mangaOcrCropScaling,
                label = { it.displayName },
                onSelect = { onChange(config.copy(mangaOcrCropScaling = it)) }
            )
            HelperText(
                "NEAREST keeps pixel art crisp; bilinear is the model's default."
            )

            Spacer(Modifier.height(16.dp))
            ToggleRow(
                label = "Whole-bubble recognition",
                description = "Cluster lines into bubbles. Faster + matches Manga109 training. " +
                    "Loses per-line tap precision in coupled mode.",
                checked = config.mangaOcrWholeBubbleMode,
                onChange = { onChange(config.copy(mangaOcrWholeBubbleMode = it)) }
            )

            Spacer(Modifier.height(8.dp))
            ToggleRow(
                label = "Preprocessing (sigmoid + auto-invert)",
                description = "Upscale, contrast, invert dark backgrounds. Disable to feed " +
                    "manga-ocr the raw screenshot.",
                checked = config.mangaOcrPreprocess,
                onChange = { onChange(config.copy(mangaOcrPreprocess = it)) }
            )

            Spacer(Modifier.height(16.dp))
            SettingLabel("Detection input scale: ${config.mangaOcrMaxSideLen}px")
            HelperText(
                "PaddleOCR's longest-side cap. Higher = sees small text better, slower."
            )
            Slider(
                value = config.mangaOcrMaxSideLen.toFloat(),
                onValueChange = { v ->
                    val snapped = (v / 256f).toInt() * 256
                    onChange(config.copy(mangaOcrMaxSideLen = snapped.coerceIn(1024, 3200)))
                },
                valueRange = 1024f..3200f,
                steps = 8
            )

        }
    }
}

@Composable
private fun GeminiFlashTuningCard(
    config: OcrConfig,
    onChange: (OcrConfig) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Gemini Flash tuning", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(12.dp))
            ToggleRow(
                label = "Hybrid bounds (ML Kit + Gemini)",
                description = "Use ML Kit's per-line bounding boxes with Gemini's text. " +
                    "Required for accurate cursor lookup. Adds ~100ms (parallel with " +
                    "the network call so usually free).",
                checked = config.geminiUseMlKitBounds,
                onChange = { onChange(config.copy(geminiUseMlKitBounds = it)) }
            )
        }
    }
}

@Composable
private fun SettingLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun HelperText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun <T> SegmentedRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            val isSel = option == selected
            FilterChip(
                modifier = Modifier.weight(1f),
                selected = isSel,
                onClick = { onSelect(option) },
                label = { Text(label(option), maxLines = 2) }
            )
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun MangaOcrDownloadControls(
    isReady: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 56.dp, end = 16.dp, bottom = 8.dp)
    ) {
        when {
            isDownloading -> {
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Downloading... ${(downloadProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            isReady -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "Downloaded",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Models downloaded",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDelete) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Delete")
                    }
                }
            }
            else -> {
                Button(onClick = onDownload) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = "Download",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Download (~141 MB)")
                }
            }
        }
    }
}

@Composable
private fun EngineSelectionCard(
    engineType: OcrEngineType,
    isSelected: Boolean,
    enabled: Boolean = true,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        ),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = { if (enabled) onSelect() },
                enabled = enabled
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    engineType.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
                Text(
                    engineType.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                )
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, points: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            points.forEach { point ->
                Text(
                    "• $point",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
