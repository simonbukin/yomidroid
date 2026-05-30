package com.yomidroid.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yomidroid.config.HistoryConfig
import com.yomidroid.config.HistoryConfigManager
import com.yomidroid.config.HistoryRetentionMode
import com.yomidroid.data.HistoryPruner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val COUNT_PRESETS = listOf(100, 250, 500, 1000, 2500, 5000)
private val AGE_PRESETS = listOf(1, 3, 7, 14, 30, 90)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { HistoryConfigManager(context) }
    var config by remember { mutableStateOf(manager.getConfig()) }
    var maxEntriesText by remember(config.maxEntries) { mutableStateOf(config.maxEntries.toString()) }
    var maxAgeText by remember(config.maxAgeDays) { mutableStateOf(config.maxAgeDays.toString()) }
    var pruning by remember { mutableStateOf(false) }

    fun save(next: HistoryConfig) {
        config = next
        manager.saveConfig(next)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Older lookups and their screenshots are removed automatically when they no longer fit the retention policy.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text("Retention", style = MaterialTheme.typography.titleSmall)

            ModeRadio(
                label = "Unlimited",
                subtitle = "Keep all history forever.",
                selected = config.mode == HistoryRetentionMode.UNLIMITED,
                onSelect = { save(config.copy(mode = HistoryRetentionMode.UNLIMITED)) }
            )
            ModeRadio(
                label = "Keep last N entries",
                subtitle = "Drop the oldest entries once the count is exceeded.",
                selected = config.mode == HistoryRetentionMode.BY_COUNT,
                onSelect = { save(config.copy(mode = HistoryRetentionMode.BY_COUNT)) }
            )
            ModeRadio(
                label = "Keep last N days",
                subtitle = "Drop entries older than the cutoff.",
                selected = config.mode == HistoryRetentionMode.BY_AGE,
                onSelect = { save(config.copy(mode = HistoryRetentionMode.BY_AGE)) }
            )

            if (config.mode == HistoryRetentionMode.BY_COUNT) {
                HorizontalDivider()
                Text("Max entries", style = MaterialTheme.typography.titleSmall)
                PresetChips(COUNT_PRESETS, config.maxEntries) { n ->
                    save(config.copy(maxEntries = n))
                    maxEntriesText = n.toString()
                }
                OutlinedTextField(
                    value = maxEntriesText,
                    onValueChange = { raw ->
                        maxEntriesText = raw.filter { it.isDigit() }.take(7)
                        maxEntriesText.toIntOrNull()?.let { n ->
                            if (n >= 1) save(config.copy(maxEntries = n))
                        }
                    },
                    label = { Text("Custom") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (config.mode == HistoryRetentionMode.BY_AGE) {
                HorizontalDivider()
                Text("Max age (days)", style = MaterialTheme.typography.titleSmall)
                PresetChips(AGE_PRESETS, config.maxAgeDays) { n ->
                    save(config.copy(maxAgeDays = n))
                    maxAgeText = n.toString()
                }
                OutlinedTextField(
                    value = maxAgeText,
                    onValueChange = { raw ->
                        maxAgeText = raw.filter { it.isDigit() }.take(5)
                        maxAgeText.toIntOrNull()?.let { n ->
                            if (n >= 1) save(config.copy(maxAgeDays = n))
                        }
                    },
                    label = { Text("Custom (days)") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            HorizontalDivider()

            Button(
                onClick = {
                    if (pruning) return@Button
                    pruning = true
                    scope.launch {
                        val removed = withContext(Dispatchers.IO) {
                            HistoryPruner.pruneIfNeeded(context, config)
                        }
                        val orphans = withContext(Dispatchers.IO) {
                            HistoryPruner.deleteOrphanScreenshots(context)
                        }
                        pruning = false
                        val msg = when {
                            removed == 0 && orphans == 0 -> "Nothing to prune"
                            else -> "Pruned $removed entries, $orphans orphan screenshots"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = !pruning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (pruning) "Pruning…" else "Prune now")
            }
        }
    }
}

@Composable
private fun ModeRadio(
    label: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PresetChips(
    presets: List<Int>,
    current: Int,
    onSelect: (Int) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        presets.forEach { n ->
            FilterChip(
                selected = n == current,
                onClick = { onSelect(n) },
                label = { Text(n.toString()) }
            )
        }
    }
}
