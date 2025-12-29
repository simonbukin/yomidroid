package com.vndict.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vndict.config.ColorConfig
import com.vndict.config.ColorConfigManager
import com.vndict.service.VNDictAccessibilityService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val configManager = remember { ColorConfigManager(context) }

    // Load saved config
    var config by remember { mutableStateOf(configManager.getConfig()) }

    // Color state
    var accentColor by remember { mutableIntStateOf(config.accentColor) }
    var highlightColor by remember { mutableIntStateOf(config.highlightColor) }
    var fabColor by remember { mutableIntStateOf(config.fabColor) }
    var cursorDotColor by remember { mutableIntStateOf(config.cursorDotColor) }

    // Which color picker is expanded
    var expandedPicker by remember { mutableStateOf<String?>(null) }

    // Helper to save and apply colors live
    fun saveAndApply() {
        val newConfig = ColorConfig(
            accentColor = accentColor,
            highlightColor = highlightColor,
            fabColor = fabColor,
            cursorDotColor = cursorDotColor
        )
        configManager.saveConfig(newConfig)
        VNDictAccessibilityService.instance?.loadColors()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Color Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Reset button
                    IconButton(onClick = {
                        configManager.resetToDefaults()
                        val defaults = ColorConfig()
                        accentColor = defaults.accentColor
                        highlightColor = defaults.highlightColor
                        fabColor = defaults.fabColor
                        cursorDotColor = defaults.cursorDotColor
                        VNDictAccessibilityService.instance?.loadColors()
                        Toast.makeText(context, "Reset to defaults", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset")
                    }
                    // Done button (colors are saved automatically)
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Check, contentDescription = "Done")
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
            // Preview card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Preview",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // FAB preview
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(Color(fabColor))
                                    .border(1.dp, Color.Gray.copy(alpha = 0.3f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("辞", color = Color(accentColor), style = MaterialTheme.typography.titleLarge)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            // Cursor dot preview
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(Color(cursorDotColor))
                                    .border(1.dp, Color.White, CircleShape)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("FAB", style = MaterialTheme.typography.bodySmall)
                        }
                        // Highlight preview
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(highlightColor))
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text("日本語", style = MaterialTheme.typography.titleMedium)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Highlight", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Color settings
            Text(
                text = "Colors",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Accent Color
            ColorSettingItem(
                title = "App Accent",
                description = "Buttons, links, and UI accents",
                color = accentColor,
                isExpanded = expandedPicker == "accent",
                onToggle = { expandedPicker = if (expandedPicker == "accent") null else "accent" },
                onColorChange = { accentColor = it; saveAndApply() }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Highlight Color
            ColorSettingItem(
                title = "Text Highlight",
                description = "OCR text highlighting overlay",
                color = highlightColor,
                isExpanded = expandedPicker == "highlight",
                onToggle = { expandedPicker = if (expandedPicker == "highlight") null else "highlight" },
                onColorChange = { highlightColor = it; saveAndApply() },
                showAlpha = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            // FAB Color
            ColorSettingItem(
                title = "FAB Color",
                description = "Main floating button circle",
                color = fabColor,
                isExpanded = expandedPicker == "fab",
                onToggle = { expandedPicker = if (expandedPicker == "fab") null else "fab" },
                onColorChange = { fabColor = it; saveAndApply() }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Cursor Dot Color
            ColorSettingItem(
                title = "Cursor Dot",
                description = "Selection indicator dot",
                color = cursorDotColor,
                isExpanded = expandedPicker == "cursor",
                onToggle = { expandedPicker = if (expandedPicker == "cursor") null else "cursor" },
                onColorChange = { cursorDotColor = it; saveAndApply() }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Info text
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Live Preview",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Color changes are applied instantly to the FAB and overlay. Changes are saved automatically.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorSettingItem(
    title: String,
    description: String,
    color: Int,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onColorChange: (Int) -> Unit,
    showAlpha: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(color))
                        .border(2.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                )
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(16.dp))
                Divider()
                Spacer(modifier = Modifier.height(16.dp))
                ColorPicker(
                    color = color,
                    onColorChange = onColorChange,
                    showAlpha = showAlpha
                )
            }
        }
    }
}

@Composable
private fun ColorPicker(
    color: Int,
    onColorChange: (Int) -> Unit,
    showAlpha: Boolean = false
) {
    val composeColor = Color(color)
    var red by remember(color) { mutableFloatStateOf(composeColor.red) }
    var green by remember(color) { mutableFloatStateOf(composeColor.green) }
    var blue by remember(color) { mutableFloatStateOf(composeColor.blue) }
    var alpha by remember(color) { mutableFloatStateOf(composeColor.alpha) }

    Column {
        // Preset colors - organized by hue in rows
        Text(
            text = "Presets",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Row 1: Warm colors + White
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            val warmPresets = listOf(
                0xFFFFFFFF.toInt(), // White
                0xFFFFEB3B.toInt(), // Yellow
                0xFFFF9800.toInt(), // Orange
                0xFFFF5722.toInt(), // Deep Orange
                0xFFF44336.toInt(), // Red
                0xFFE91E63.toInt(), // Pink
            )
            warmPresets.forEach { preset ->
                ColorPresetCircle(
                    preset = preset,
                    currentColor = color,
                    alpha = alpha,
                    showAlpha = showAlpha,
                    onSelect = { newColor ->
                        red = newColor.red
                        green = newColor.green
                        blue = newColor.blue
                        onColorChange(newColor.toArgb())
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Row 2: Cool colors + Gray/Black
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            val coolPresets = listOf(
                0xFF9C27B0.toInt(), // Purple
                0xFF673AB7.toInt(), // Deep Purple
                0xFF2196F3.toInt(), // Blue
                0xFF00BCD4.toInt(), // Cyan
                0xFF4CAF50.toInt(), // Green
                0xFF607D8B.toInt(), // Blue Gray
            )
            coolPresets.forEach { preset ->
                ColorPresetCircle(
                    preset = preset,
                    currentColor = color,
                    alpha = alpha,
                    showAlpha = showAlpha,
                    onSelect = { newColor ->
                        red = newColor.red
                        green = newColor.green
                        blue = newColor.blue
                        onColorChange(newColor.toArgb())
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // RGB Sliders
        Text(
            text = "Custom",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Red slider
        ColorSlider(
            label = "R",
            value = red,
            color = Color.Red,
            onValueChange = {
                red = it
                onColorChange(Color(red, green, blue, alpha).toArgb())
            }
        )

        // Green slider
        ColorSlider(
            label = "G",
            value = green,
            color = Color.Green,
            onValueChange = {
                green = it
                onColorChange(Color(red, green, blue, alpha).toArgb())
            }
        )

        // Blue slider
        ColorSlider(
            label = "B",
            value = blue,
            color = Color.Blue,
            onValueChange = {
                blue = it
                onColorChange(Color(red, green, blue, alpha).toArgb())
            }
        )

        // Alpha slider (optional)
        if (showAlpha) {
            ColorSlider(
                label = "A",
                value = alpha,
                color = Color.Gray,
                onValueChange = {
                    alpha = it
                    onColorChange(Color(red, green, blue, alpha).toArgb())
                }
            )
        }
    }
}

@Composable
private fun ColorSlider(
    label: String,
    value: Float,
    color: Color,
    onValueChange: (Float) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(24.dp)
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = color,
                activeTrackColor = color
            )
        )
        Text(
            text = "${(value * 255).toInt()}",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(32.dp)
        )
    }
}

@Composable
private fun ColorPresetCircle(
    preset: Int,
    currentColor: Int,
    alpha: Float,
    showAlpha: Boolean,
    onSelect: (Color) -> Unit,
    modifier: Modifier = Modifier
) {
    val isSelected = (currentColor and 0x00FFFFFF) == (preset and 0x00FFFFFF)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(Color(preset))
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                color = if (isSelected)
                    MaterialTheme.colorScheme.primary
                else
                    Color.White.copy(alpha = 0.3f),
                shape = CircleShape
            )
            .clickable {
                val newColor = if (showAlpha) {
                    Color(preset).copy(alpha = alpha)
                } else {
                    Color(preset)
                }
                onSelect(newColor)
            },
        contentAlignment = Alignment.Center
    ) {
        // Show checkmark for selected color
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.8f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Black
                )
            }
        }
    }
}
