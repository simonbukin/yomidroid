package com.yomidroid.ui.tools

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
import com.yomidroid.dictionary.LookupResultRepository
import com.yomidroid.ui.components.DictionaryEntryWebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveLookupScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val entries by LookupResultRepository.latestEntries.collectAsState()
    val sentence by LookupResultRepository.latestSentence.collectAsState()
    val ankiExporter = remember { AnkiDroidExporter(context) }
    val scope = rememberCoroutineScope()

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
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Anki export button for the primary entry
                entries.firstOrNull()?.let { entry ->
                    Button(
                        onClick = {
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    ankiExporter.exportCard(entry, sentence ?: "", null)
                                }
                                val message = when (result) {
                                    is ExportResult.Success -> "Exported to Anki"
                                    is ExportResult.AlreadyExists -> "Card already exists"
                                    is ExportResult.Error -> "Export failed: ${result.message}"
                                    is ExportResult.AnkiNotInstalled -> "AnkiDroid not installed"
                                    is ExportResult.PermissionDenied -> "Permission denied"
                                    is ExportResult.ApiNotEnabled -> "Enable AnkiDroid API in settings"
                                    is ExportResult.NotConfigured -> "Configure Anki export in settings"
                                }
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            }
                        },
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
