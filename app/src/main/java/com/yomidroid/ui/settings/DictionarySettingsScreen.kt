package com.yomidroid.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yomidroid.config.*
import com.yomidroid.data.DictionaryDb
import com.yomidroid.data.DictionaryDownloader
import com.yomidroid.data.DictionaryImporter
import com.yomidroid.data.ImportResult
import com.yomidroid.service.YomidroidAccessibilityService
import kotlinx.coroutines.launch

data class ImportQueueItem(
    val uri: Uri,
    val displayName: String
)

data class ImportProgress(
    val currentIndex: Int,
    val totalCount: Int,
    val currentName: String,
    val phase: String,
    val progress: Float,
    val results: List<ImportResult> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionarySettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configManager = remember { DictionaryConfigManager(context) }
    val importer = remember { DictionaryImporter(context) }

    var installed by remember { mutableStateOf(configManager.getInstalledDictionaries()) }
    val downloader = remember { DictionaryDownloader(context) }
    var importProgress by remember { mutableStateOf<ImportProgress?>(null) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf<String?>(null) }
    var customCss by remember { mutableStateOf(configManager.getCustomCss() ?: "") }
    var backfillTargetDictId by remember { mutableStateOf<String?>(null) }
    var customCssFileName by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        installed = configManager.getInstalledDictionaries()
    }

    fun reloadService() {
        DictionaryDb.getInstance(context).reloadFromConfig(configManager)
        YomidroidAccessibilityService.instance?.reloadDictionaries()
    }

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return

        scope.launch {
            val total = uris.size
            val results = mutableListOf<ImportResult>()

            for ((index, uri) in uris.withIndex()) {
                // Try to get a display name from the URI
                val displayName = try {
                    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                } catch (e: Exception) { null } ?: "Dictionary ${index + 1}"

                importProgress = ImportProgress(
                    currentIndex = index,
                    totalCount = total,
                    currentName = displayName,
                    phase = "Starting...",
                    progress = 0f,
                    results = results.toList()
                )

                val result = importer.importFromUri(uri) { phase, progress ->
                    importProgress = ImportProgress(
                        currentIndex = index,
                        totalCount = total,
                        currentName = displayName,
                        phase = phase,
                        progress = progress,
                        results = results.toList()
                    )
                }

                results.add(result)
            }

            importProgress = null
            refresh()
            reloadService()

            // Show summary
            val succeeded = results.count { it.success }
            val failed = results.count { !it.success }
            val message = when {
                failed == 0 && succeeded == 1 -> "${results.first().dictTitle} installed"
                failed == 0 -> "$succeeded dictionaries installed"
                succeeded == 0 && failed == 1 -> results.first().error ?: "Import failed"
                succeeded == 0 -> "$failed imports failed"
                else -> "$succeeded installed, $failed failed"
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    // Backfill file picker — user selects the original ZIP for a dictionary
    val backfillPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val dictId = backfillTargetDictId ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val dict = installed.find { it.id == dictId }

        scope.launch {
            importProgress = ImportProgress(
                currentIndex = 0,
                totalCount = 1,
                currentName = dict?.title ?: dictId,
                phase = "Starting backfill...",
                progress = 0f
            )

            val result = importer.backfillRichContent(dictId, uri) { phase, progress ->
                importProgress = ImportProgress(
                    currentIndex = 0,
                    totalCount = 1,
                    currentName = dict?.title ?: dictId,
                    phase = phase,
                    progress = progress
                )
            }

            importProgress = null
            backfillTargetDictId = null
            refresh()
            reloadService()

            val message = if (result.success) {
                "Backfilled ${result.entryCount} entries with rich content"
            } else {
                result.error ?: "Backfill failed"
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    // CSS file picker
    val cssFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val cssContent = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
            if (cssContent != null) {
                customCss = cssContent
                configManager.setCustomCss(cssContent)
                // Get filename for display
                val fileName = try {
                    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                } catch (e: Exception) { null }
                customCssFileName = fileName
                Toast.makeText(context, "Loaded ${fileName ?: "CSS file"}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to read CSS file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Multi-file picker
    val multiFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        importUris(uris)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dictionaries") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
        ) {
            // Installed Dictionaries Section
            Text(
                text = "Installed",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            if (installed.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "No dictionaries installed",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Import Yomitan dictionary ZIPs to get started",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            installed.forEachIndexed { index, dict ->
                InstalledDictionaryRow(
                    dict = dict,
                    isFirst = index == 0,
                    isLast = index == installed.size - 1,
                    onToggleEnabled = { enabled ->
                        configManager.setEnabled(dict.id, enabled)
                        refresh()
                        reloadService()
                    },
                    onMoveUp = {
                        configManager.moveDictionaryUp(dict.id)
                        refresh()
                        reloadService()
                    },
                    onMoveDown = {
                        configManager.moveDictionaryDown(dict.id)
                        refresh()
                        reloadService()
                    },
                    onDelete = {
                        showDeleteDialog = dict.id
                    },
                    onBackfill = if (dict.type == DictSourceType.DICTIONARY) {
                        {
                            backfillTargetDictId = dict.id
                            backfillPickerLauncher.launch(arrayOf(
                                "application/zip",
                                "application/octet-stream",
                                "application/x-zip-compressed"
                            ))
                        }
                    } else null
                )

                if (index < installed.size - 1) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Import button
            FilledTonalButton(
                onClick = {
                    multiFilePickerLauncher.launch(arrayOf(
                        "application/zip",
                        "application/octet-stream",
                        "application/x-zip-compressed"
                    ))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                enabled = importProgress == null
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Import Yomitan Dictionaries")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Select one or more Yomitan-format .zip files",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Recommended Dictionaries Section
            Text(
                text = "Recommended",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            val groupedRecommended = RecommendedDictionaryCatalog.dictionaries.groupBy { it.category }
            for ((category, dicts) in groupedRecommended) {
                val categoryLabel = when (category) {
                    DictCategory.TERMS -> "Term Dictionaries"
                    DictCategory.KANJI -> "Kanji"
                    DictCategory.FREQUENCY -> "Frequency"
                    DictCategory.PRONUNCIATION -> "Pronunciation"
                }
                Text(
                    text = categoryLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )

                for (dict in dicts) {
                    val isInstalled = installed.any { it.title.contains(dict.name, ignoreCase = true) }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = dict.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "${dict.description} (${dict.sizeEstimate})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        if (isInstalled) {
                            Text(
                                text = "Installed",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            FilledTonalButton(
                                onClick = {
                                    downloadingId = dict.id
                                    scope.launch {
                                        importProgress = ImportProgress(
                                            currentIndex = 0,
                                            totalCount = 1,
                                            currentName = dict.name,
                                            phase = "Starting...",
                                            progress = 0f
                                        )

                                        val result = downloader.downloadAndImport(dict.downloadUrl) { phase, progress ->
                                            importProgress = ImportProgress(
                                                currentIndex = 0,
                                                totalCount = 1,
                                                currentName = dict.name,
                                                phase = phase,
                                                progress = progress
                                            )
                                        }

                                        importProgress = null
                                        downloadingId = null
                                        refresh()
                                        reloadService()

                                        val message = if (result.success) {
                                            "${result.dictTitle} installed"
                                        } else {
                                            result.error ?: "Download failed"
                                        }
                                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                    }
                                },
                                enabled = importProgress == null,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                            ) {
                                Icon(
                                    Icons.Default.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Download", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Custom CSS Section
            Text(
                text = "Custom CSS",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            Text(
                text = "Custom CSS applied to rich dictionary definitions. Yomitan-compatible CSS variables supported.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Load CSS file button
            FilledTonalButton(
                onClick = {
                    cssFilePickerLauncher.launch(arrayOf(
                        "text/css",
                        "text/plain",
                        "application/octet-stream"
                    ))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Icon(
                    Icons.Default.FileOpen,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Load CSS File")
            }

            if (customCss.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))

                // Show loaded CSS info
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = customCssFileName ?: "Custom CSS",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            IconButton(
                                onClick = {
                                    customCss = ""
                                    customCssFileName = null
                                    configManager.setCustomCss(null)
                                    Toast.makeText(context, "CSS cleared", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Clear CSS",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Text(
                            text = "${customCss.lines().size} lines",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Manual CSS editor (collapsible)
            var showCssEditor by remember { mutableStateOf(false) }
            TextButton(
                onClick = { showCssEditor = !showCssEditor },
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                Text(if (showCssEditor) "Hide CSS Editor" else "Edit CSS Manually")
            }

            if (showCssEditor) {
                OutlinedTextField(
                    value = customCss,
                    onValueChange = { customCss = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    label = { Text("CSS") },
                    placeholder = { Text(":root { --text-color: #fff; }") },
                    minLines = 3,
                    maxLines = 8,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                FilledTonalButton(
                    onClick = {
                        configManager.setCustomCss(customCss.ifBlank { null })
                        Toast.makeText(context, "CSS saved", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Text("Save CSS")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Import progress dialog
    importProgress?.let { progress ->
        AlertDialog(
            onDismissRequest = { /* non-dismissable while importing */ },
            title = {
                Text(
                    if (progress.totalCount > 1)
                        "Importing ${progress.currentIndex + 1} of ${progress.totalCount}"
                    else
                        "Importing Dictionary"
                )
            },
            text = {
                Column {
                    Text(
                        text = progress.currentName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = progress.phase,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { progress.progress },
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Show completed results
                    if (progress.results.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        progress.results.forEach { result ->
                            Text(
                                text = if (result.success) {
                                    "${result.dictTitle} - OK"
                                } else {
                                    "${result.dictTitle ?: "?"} - ${result.error}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (result.success)
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                else
                                    MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    // Delete confirmation dialog
    showDeleteDialog?.let { dictId ->
        val dict = installed.find { it.id == dictId }
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Delete Dictionary") },
            text = { Text("Delete ${dict?.title ?: dictId}? The dictionary data will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        importer.deleteDictionary(dictId)
                        refresh()
                        reloadService()
                    }
                    showDeleteDialog = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun InstalledDictionaryRow(
    dict: InstalledDictionary,
    isFirst: Boolean,
    isLast: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onBackfill: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Reorder buttons
            Column {
                IconButton(
                    onClick = onMoveUp,
                    enabled = !isFirst,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = "Move up",
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = !isLast,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "Move down",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Name and info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = dict.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append(dict.type.name.lowercase().replaceFirstChar { it.uppercase() })
                        if (dict.entryCount > 0) {
                            append(" \u2022 ${formatCount(dict.entryCount)} entries")
                        }
                        if (dict.revision.isNotEmpty()) {
                            append(" \u2022 v${dict.revision}")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Enable/disable switch
            Switch(
                checked = dict.enabled,
                onCheckedChange = onToggleEnabled,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            // Delete button
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Backfill button for term dictionaries
        if (onBackfill != null) {
            TextButton(
                onClick = onBackfill,
                modifier = Modifier.padding(start = 40.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Backfill rich content from ZIP",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

private fun formatCount(count: Int): String {
    return when {
        count >= 1_000_000 -> "%.1fM".format(count / 1_000_000.0)
        count >= 1_000 -> "%.0fK".format(count / 1_000.0)
        else -> count.toString()
    }
}
