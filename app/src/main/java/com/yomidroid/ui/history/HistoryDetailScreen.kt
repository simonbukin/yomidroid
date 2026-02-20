package com.yomidroid.ui.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.yomidroid.R
import com.yomidroid.anki.AnkiDroidExporter
import com.yomidroid.anki.ExportResult
import com.yomidroid.data.AppDatabase
import com.yomidroid.data.LookupHistoryEntity
import com.yomidroid.dictionary.DictionaryEngine
import com.yomidroid.dictionary.DictionaryEntry
import com.yomidroid.ui.components.DictionaryEntryWebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private fun copyToClipboard(context: android.content.Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "Copied $label", Toast.LENGTH_SHORT).show()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDetailScreen(
    historyId: Long,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var entry by remember { mutableStateOf<LookupHistoryEntity?>(null) }
    var dictEntries by remember { mutableStateOf<List<DictionaryEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var ankiButtonState by remember { mutableStateOf(AnkiButtonState.IDLE) }

    val ankiExporter = remember { AnkiDroidExporter(context) }
    val dictionaryEngine = remember { DictionaryEngine(context) }

    // Load entry + re-lookup from dictionary for rich rendering
    LaunchedEffect(historyId) {
        val historyEntry = withContext(Dispatchers.IO) {
            AppDatabase.getInstance(context).historyDao().getById(historyId)
        }
        entry = historyEntry
        if (historyEntry != null) {
            dictEntries = dictionaryEngine.searchTerm(historyEntry.word)
        }
        isLoading = false
    }

    // Export to Anki
    fun exportToAnki() {
        val item = entry ?: return
        ankiButtonState = AnkiButtonState.LOADING

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val screenshot = item.screenshotPath?.let { path ->
                    val file = File(path)
                    if (file.exists()) BitmapFactory.decodeFile(path) else null
                }

                val dictEntry = DictionaryEntry(
                    id = 0L,
                    expression = item.word,
                    reading = item.reading,
                    glossary = item.definition.split("; "),
                    partsOfSpeech = emptyList(),
                    score = 0,
                    matchedText = item.word
                )

                ankiExporter.exportCard(dictEntry, item.sentence ?: "", screenshot)
            }

            ankiButtonState = when (result) {
                is ExportResult.Success -> {
                    Toast.makeText(context, "Added to Anki!", Toast.LENGTH_SHORT).show()
                    AnkiButtonState.SUCCESS
                }
                is ExportResult.AlreadyExists -> {
                    Toast.makeText(context, "Already in Anki", Toast.LENGTH_SHORT).show()
                    AnkiButtonState.ALREADY_EXISTS
                }
                else -> {
                    val msg = when (result) {
                        is ExportResult.AnkiNotInstalled -> "AnkiDroid not installed"
                        is ExportResult.NotConfigured -> "Configure Anki in settings first"
                        is ExportResult.PermissionDenied -> "AnkiDroid permission denied"
                        is ExportResult.ApiNotEnabled -> "Enable Yomidroid in AnkiDroid API settings"
                        is ExportResult.Error -> "Export failed: ${result.message}"
                        else -> "Export failed"
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    AnkiButtonState.IDLE
                }
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Entry") },
            text = { Text("Delete \"${entry?.word}\" from history?") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            AppDatabase.getInstance(context).historyDao().delete(historyId)
                            // Delete screenshot file if exists
                            entry?.screenshotPath?.let { path ->
                                File(path).delete()
                            }
                        }
                        onBack()
                    }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(entry?.word ?: "Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Anki export button
                    IconButton(
                        onClick = { if (ankiButtonState == AnkiButtonState.IDLE) exportToAnki() },
                        enabled = ankiButtonState == AnkiButtonState.IDLE && entry != null
                    ) {
                        when (ankiButtonState) {
                            AnkiButtonState.LOADING -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                            AnkiButtonState.SUCCESS -> {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Exported",
                                    tint = Color(0xFF4CAF50)
                                )
                            }
                            AnkiButtonState.ALREADY_EXISTS -> {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Already in Anki",
                                    tint = Color(0xFFFFA726)
                                )
                            }
                            AnkiButtonState.IDLE -> {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_anki),
                                    contentDescription = "Export to Anki",
                                    tint = Color.Unspecified
                                )
                            }
                        }
                    }
                    // Delete button
                    IconButton(onClick = { showDeleteDialog = true }, enabled = entry != null) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                entry == null -> {
                    Text(
                        text = "Entry not found",
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    val item = entry!!
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Screenshot
                        item.screenshotPath?.let { path ->
                            val file = File(path)
                            if (file.exists()) {
                                val bitmap = remember(path) {
                                    BitmapFactory.decodeFile(path)
                                }
                                bitmap?.let {
                                    Image(
                                        bitmap = it.asImageBitmap(),
                                        contentDescription = "Screenshot",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp)),
                                        contentScale = ContentScale.FillWidth
                                    )
                                }
                            }
                        }

                        // Sentence context
                        item.sentence?.let { sentence ->
                            if (sentence.isNotBlank()) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Context",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            IconButton(
                                                onClick = { copyToClipboard(context, "sentence", sentence) },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.ContentCopy,
                                                    contentDescription = "Copy sentence",
                                                    modifier = Modifier.size(18.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = sentence,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                    }
                                }
                            }
                        }

                        // Dictionary entry — rich WebView if re-lookup succeeded, plain fallback otherwise
                        if (dictEntries.isNotEmpty()) {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                DictionaryEntryWebView(
                                    entries = dictEntries,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        } else {
                            // Fallback: plain word + reading card
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = item.word,
                                            style = MaterialTheme.typography.headlineMedium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                        IconButton(
                                            onClick = { copyToClipboard(context, "word", item.word) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.ContentCopy,
                                                contentDescription = "Copy word",
                                                modifier = Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                            )
                                        }
                                    }
                                    if (item.reading.isNotEmpty() && item.reading != item.word) {
                                        Text(
                                            text = item.reading,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    val definitions = item.definition.split("; ")
                                    definitions.forEachIndexed { index, def ->
                                        Row(modifier = Modifier.padding(vertical = 2.dp)) {
                                            Text(
                                                text = "${index + 1}.",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.width(24.dp)
                                            )
                                            Text(
                                                text = def,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Timestamp
                        Text(
                            text = formatFullTimestamp(item.timestamp),
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }
            }
        }
    }
}

private fun formatFullTimestamp(timestamp: Long): String {
    val format = SimpleDateFormat("MMMM d, yyyy 'at' h:mm a", Locale.getDefault())
    return format.format(Date(timestamp))
}
