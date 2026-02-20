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
            Text(
                text = "OCR Engine",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))

            OcrEngineType.entries.forEach { engineType ->
                val isMangaOcr = engineType == OcrEngineType.MANGA_OCR
                val enabled = !isMangaOcr || mangaOcrReady

                EngineSelectionCard(
                    engineType = engineType,
                    isSelected = config.selectedEngine == engineType,
                    enabled = enabled,
                    onSelect = {
                        if (enabled) {
                            saveAndApply(config.copy(selectedEngine = engineType))
                        }
                    }
                )

                // Manga OCR download/delete controls
                if (isMangaOcr) {
                    MangaOcrDownloadControls(
                        isReady = mangaOcrReady,
                        isDownloading = isDownloading,
                        downloadProgress = downloadProgress,
                        onDownload = {
                            isDownloading = true
                            downloadProgress = 0f
                            scope.launch {
                                val success = modelManager.downloadModels { progress ->
                                    downloadProgress = progress
                                }
                                isDownloading = false
                                mangaOcrReady = modelManager.isModelReady()
                                if (success) {
                                    Toast.makeText(context, "Manga OCR models downloaded", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
                                }
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

                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Info cards
            InfoCard(
                title = "ML Kit",
                points = listOf(
                    "Google's fast on-device OCR",
                    "Good for modern, clear text",
                    "Handles horizontal text well",
                    "Smaller memory footprint"
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            InfoCard(
                title = "RapidOCR",
                points = listOf(
                    "PaddleOCR-based ONNX engine",
                    "Excellent for Japanese text",
                    "Handles manga and vertical text well",
                    "Runs entirely on-device"
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            InfoCard(
                title = "Manga OCR",
                points = listOf(
                    "Specialized for manga/comic text",
                    "Uses PaddleOCR detection + manga-ocr recognition",
                    "Requires ~141 MB model download",
                    "Best accuracy for stylized fonts"
                )
            )
        }
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
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Downloading... ${(downloadProgress * 100).toInt()}%",
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
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Models downloaded",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDelete) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
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
                    Spacer(modifier = Modifier.width(8.dp))
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
        border = if (isSelected)
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = { if (enabled) onSelect() },
                enabled = enabled
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = engineType.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
                Text(
                    text = engineType.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                )
            }
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    points: List<String>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            points.forEach { point ->
                Text(
                    text = "\u2022 $point",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
