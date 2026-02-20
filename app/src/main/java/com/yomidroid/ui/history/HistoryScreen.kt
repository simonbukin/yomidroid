package com.yomidroid.ui.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yomidroid.R
import com.yomidroid.anki.AnkiDroidExporter
import com.yomidroid.anki.ExportResult
import com.yomidroid.data.AppDatabase
import com.yomidroid.data.LookupHistoryEntity
import com.yomidroid.dictionary.DictionaryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

enum class AnkiButtonState {
    IDLE, LOADING, SUCCESS, ALREADY_EXISTS
}

enum class TimeFilter(val label: String, val durationMs: Long) {
    ALL("All", 0L),
    LAST_10_MIN("10 min", 10 * 60 * 1000L),
    LAST_HOUR("1 hour", 60 * 60 * 1000L),
    TODAY("Today", 24 * 60 * 60 * 1000L),
    THIS_WEEK("This week", 7 * 24 * 60 * 60 * 1000L)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onNavigateToDetail: (Long) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var historyItems by remember { mutableStateOf<List<LookupHistoryEntity>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showClearDialog by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf(TimeFilter.ALL) }
    var searchQuery by remember { mutableStateOf("") }

    // Statistics state
    var todayCount by remember { mutableIntStateOf(0) }
    var weekCount by remember { mutableIntStateOf(0) }
    var totalUnique by remember { mutableIntStateOf(0) }

    // Anki exporter
    val ankiExporter = remember { AnkiDroidExporter(context) }

    // Export to Anki callback
    fun exportToAnki(item: LookupHistoryEntity, onStateChange: (AnkiButtonState) -> Unit) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                // Load screenshot if available
                val screenshot = item.screenshotPath?.let { path ->
                    val file = File(path)
                    if (file.exists()) {
                        BitmapFactory.decodeFile(path)
                    } else null
                }

                // Create a DictionaryEntry from history item
                val entry = DictionaryEntry(
                    id = 0L,
                    expression = item.word,
                    reading = item.reading,
                    glossary = item.definition.split("; "),
                    partsOfSpeech = emptyList(),
                    score = 0,
                    matchedText = item.word
                )

                ankiExporter.exportCard(entry, item.sentence ?: "", screenshot)
            }

            when (result) {
                is ExportResult.Success -> {
                    onStateChange(AnkiButtonState.SUCCESS)
                    Toast.makeText(context, "Added to Anki!", Toast.LENGTH_SHORT).show()
                }
                is ExportResult.AlreadyExists -> {
                    onStateChange(AnkiButtonState.ALREADY_EXISTS)
                    Toast.makeText(context, "Already in Anki", Toast.LENGTH_SHORT).show()
                }
                is ExportResult.AnkiNotInstalled -> {
                    onStateChange(AnkiButtonState.IDLE)
                    Toast.makeText(context, "AnkiDroid not installed", Toast.LENGTH_LONG).show()
                }
                is ExportResult.NotConfigured -> {
                    onStateChange(AnkiButtonState.IDLE)
                    Toast.makeText(context, "Configure Anki in settings first", Toast.LENGTH_LONG).show()
                }
                is ExportResult.PermissionDenied -> {
                    onStateChange(AnkiButtonState.IDLE)
                    Toast.makeText(context, "AnkiDroid permission denied", Toast.LENGTH_LONG).show()
                }
                is ExportResult.ApiNotEnabled -> {
                    onStateChange(AnkiButtonState.IDLE)
                    Toast.makeText(context, "Enable Yomidroid in AnkiDroid Settings → Advanced → AnkiDroid API", Toast.LENGTH_LONG).show()
                }
                is ExportResult.Error -> {
                    onStateChange(AnkiButtonState.IDLE)
                    Toast.makeText(context, "Export failed: ${result.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Load history when filter or search changes
    LaunchedEffect(selectedFilter, searchQuery) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.getInstance(context).historyDao()
            historyItems = when {
                searchQuery.isNotEmpty() -> dao.search(searchQuery)
                selectedFilter == TimeFilter.ALL -> dao.getAll()
                else -> {
                    val since = System.currentTimeMillis() - selectedFilter.durationMs
                    dao.getSince(since)
                }
            }

            // Load statistics
            val now = System.currentTimeMillis()
            todayCount = dao.countSince(now - 24 * 60 * 60 * 1000L)
            weekCount = dao.countSince(now - 7 * 24 * 60 * 60 * 1000L)
            totalUnique = dao.countUniqueWords()
        }
        isLoading = false
    }

    // Clear all dialog
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear History") },
            text = { Text("Are you sure you want to delete all lookup history?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                AppDatabase.getInstance(context).historyDao().deleteAll()
                            }
                            historyItems = emptyList()
                        }
                        showClearDialog = false
                    }
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lookup History") },
                actions = {
                    if (historyItems.isNotEmpty()) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear all")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search TextField - always visible unless loading
            if (!isLoading) {
                // Statistics card (shown when not searching)
                if (searchQuery.isEmpty() && totalUnique > 0) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "$todayCount",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "Today",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "$weekCount",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "This Week",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "$totalUnique",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "Unique Words",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Search history...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true
                )

                // Filter chips - always visible unless loading
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TimeFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            label = { Text(filter.label) }
                        )
                    }
                }
            }

            // Content area
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                when {
                    isLoading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    historyItems.isEmpty() -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = if (searchQuery.isNotEmpty()) "No results found" else "No history yet",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (searchQuery.isNotEmpty())
                                    "Try a different search term."
                                else
                                    "Words you look up will appear here.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                items = historyItems,
                                key = { it.id }
                            ) { item ->
                                HistoryItem(
                                    item = item,
                                    onClick = { onNavigateToDetail(item.id) },
                                    onDelete = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                AppDatabase.getInstance(context).historyDao().delete(item.id)
                                            }
                                            historyItems = historyItems.filter { it.id != item.id }
                                        }
                                    },
                                    onExportToAnki = ::exportToAnki
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryItem(
    item: LookupHistoryEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onExportToAnki: (LookupHistoryEntity, (AnkiButtonState) -> Unit) -> Unit
) {
    val context = LocalContext.current
    var showDeleteDialog by remember { mutableStateOf(false) }
    var ankiButtonState by remember { mutableStateOf(AnkiButtonState.IDLE) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Entry") },
            text = { Text("Delete \"${item.word}\" from history?") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteDialog = false
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("word", item.word))
                    Toast.makeText(context, "Copied \"${item.word}\"", Toast.LENGTH_SHORT).show()
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.word,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (item.reading.isNotEmpty() && item.reading != item.word) {
                        Text(
                            text = item.reading,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    item.sourceAppLabel?.let { label ->
                        Text(
                            text = label + (item.sourceWindowTitle?.takeIf { it != label }?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatTimestamp(item.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Anki export button
                    IconButton(
                        onClick = {
                            if (ankiButtonState == AnkiButtonState.IDLE) {
                                ankiButtonState = AnkiButtonState.LOADING
                                onExportToAnki(item) { newState ->
                                    ankiButtonState = newState
                                }
                            }
                        },
                        modifier = Modifier.size(32.dp),
                        enabled = ankiButtonState == AnkiButtonState.IDLE
                    ) {
                        when (ankiButtonState) {
                            AnkiButtonState.LOADING -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                            AnkiButtonState.SUCCESS -> {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Exported",
                                    modifier = Modifier.size(18.dp),
                                    tint = Color(0xFF4CAF50)
                                )
                            }
                            AnkiButtonState.ALREADY_EXISTS -> {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Already in Anki",
                                    modifier = Modifier.size(18.dp),
                                    tint = Color(0xFFFFA726)
                                )
                            }
                            AnkiButtonState.IDLE -> {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_anki),
                                    contentDescription = "Export to Anki",
                                    modifier = Modifier.size(18.dp),
                                    tint = Color.Unspecified
                                )
                            }
                        }
                    }
                    // Delete button
                    IconButton(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = item.definition,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "Just now"
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 86400_000 -> "${diff / 3600_000}h ago"
        else -> {
            val format = SimpleDateFormat("MMM d", Locale.getDefault())
            format.format(Date(timestamp))
        }
    }
}
