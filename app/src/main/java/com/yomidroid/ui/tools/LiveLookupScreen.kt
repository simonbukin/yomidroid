package com.yomidroid.ui.tools

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yomidroid.anki.AnkiDroidExporter
import com.yomidroid.anki.ExportResult
import com.yomidroid.dictionary.DictionaryEntry
import com.yomidroid.dictionary.LookupResultRepository
import com.yomidroid.ui.components.DictionaryEntryWebView
import com.yomidroid.ui.components.rememberDictionaryWebViewController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveLookupScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val entries by LookupResultRepository.latestEntries.collectAsState()
    val sentence by LookupResultRepository.latestSentence.collectAsState()
    val screenshotPath by LookupResultRepository.latestScreenshotPath.collectAsState()
    val ankiExporter = remember { AnkiDroidExporter(context) }
    val scope = rememberCoroutineScope()
    val webViewController = rememberDictionaryWebViewController()

    fun exportEntry(entry: DictionaryEntry, index: Int) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val screenshot = screenshotPath?.let { path ->
                    val file = File(path)
                    if (file.exists()) BitmapFactory.decodeFile(path) else null
                }
                ankiExporter.exportCard(entry, sentence ?: "", screenshot)
            }
            val (status, message, length) = when (result) {
                is ExportResult.Success ->
                    Triple("success", "Added to Anki!", Toast.LENGTH_SHORT)
                is ExportResult.AlreadyExists ->
                    Triple("exists", "Already in Anki", Toast.LENGTH_SHORT)
                is ExportResult.AnkiNotInstalled ->
                    Triple("error", "AnkiDroid not installed", Toast.LENGTH_LONG)
                is ExportResult.NotConfigured ->
                    Triple("error", "Configure Anki in settings first", Toast.LENGTH_LONG)
                is ExportResult.PermissionDenied ->
                    Triple("error", "AnkiDroid permission denied", Toast.LENGTH_LONG)
                is ExportResult.ApiNotEnabled ->
                    Triple("error", "Enable Yomidroid in AnkiDroid Settings → Advanced → AnkiDroid API", Toast.LENGTH_LONG)
                is ExportResult.Error ->
                    Triple("error", "Export failed: ${result.message}", Toast.LENGTH_LONG)
            }
            if (index >= 0) webViewController.setAnkiResult(index, status)
            Toast.makeText(context, message, length).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Live Lookup") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Tap text in the overlay to see results here",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
            ) {
                DictionaryEntryWebView(
                    entries = entries,
                    onAnkiExport = { entry, index -> exportEntry(entry, index) },
                    controller = webViewController,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Anki export button for the primary entry
                entries.firstOrNull()?.let { entry ->
                    Button(
                        onClick = { exportEntry(entry, 0) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Text("Export to Anki")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
