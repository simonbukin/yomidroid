package com.yomidroid.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import com.yomidroid.service.YomidroidAccessibilityService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val configManager = remember { OcrConfigManager(context) }
    var config by remember { mutableStateOf(configManager.getConfig()) }

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
                EngineSelectionCard(
                    engineType = engineType,
                    isSelected = config.selectedEngine == engineType,
                    onSelect = {
                        saveAndApply(config.copy(selectedEngine = engineType))
                    }
                )
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
        }
    }
}

@Composable
private fun EngineSelectionCard(
    engineType: OcrEngineType,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
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
                onClick = onSelect
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = engineType.displayName,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = engineType.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
