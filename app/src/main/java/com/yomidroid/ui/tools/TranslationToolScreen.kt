package com.yomidroid.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yomidroid.data.AppDatabase
import com.yomidroid.data.GrammarSentenceEntity
import com.yomidroid.ocr.OcrResultRepository
import com.yomidroid.tts.TtsManager
import com.yomidroid.translation.BackendStatus
import com.yomidroid.translation.TranslationMode
import com.yomidroid.translation.TranslationResult
import com.yomidroid.translation.TranslationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationToolScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val translationService = remember { TranslationService.getInstance(context) }
    val database = remember { AppDatabase.getInstance(context) }
    val ttsManager = remember { TtsManager(context) }

    DisposableEffect(Unit) {
        onDispose { ttsManager.shutdown() }
    }

    // Get latest OCR text
    val latestOcrText by OcrResultRepository.latestOcrText.collectAsState()

    // Input and result state
    var inputText by remember { mutableStateOf("") }
    var translationResult by remember { mutableStateOf<TranslationResult?>(null) }
    var isTranslating by remember { mutableStateOf(false) }
    var isTranslatingLiteral by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // History state (reuse grammar sentence entity for now)
    var savedSentences by remember { mutableStateOf<List<GrammarSentenceEntity>>(emptyList()) }
    var showHistory by remember { mutableStateOf(false) }

    // Backend status and selection
    var backendStatuses by remember { mutableStateOf<Map<String, BackendStatus>>(emptyMap()) }
    var selectedBackend by remember { mutableStateOf<String?>(null) }

    // Load saved sentences and check backends on startup
    LaunchedEffect(Unit) {
        savedSentences = withContext(Dispatchers.IO) {
            database.grammarSentenceDao().getRecent(20)
        }
        backendStatuses = translationService.getBackendStatuses()

        // Select first available backend by default
        if (selectedBackend == null) {
            selectedBackend = backendStatuses.entries
                .firstOrNull { it.value is BackendStatus.Available }
                ?.key
        }

        // Pre-fill with latest OCR or most recent sentence
        if (latestOcrText.isNullOrBlank() && inputText.isBlank()) {
            val mostRecent = savedSentences.firstOrNull()
            if (mostRecent != null) {
                inputText = mostRecent.sentence
            }
        }
    }

    // Pre-fill with OCR text
    LaunchedEffect(latestOcrText) {
        if (!latestOcrText.isNullOrBlank() && inputText.isBlank()) {
            inputText = latestOcrText!!
        }
    }

    suspend fun saveSentence(sentence: String) {
        if (sentence.isBlank()) return
        withContext(Dispatchers.IO) {
            val exists = database.grammarSentenceDao().countBySentence(sentence) > 0
            if (!exists) {
                database.grammarSentenceDao().insert(GrammarSentenceEntity(sentence = sentence))
                savedSentences = database.grammarSentenceDao().getRecent(20)
            }
        }
    }

    fun runTranslation() {
        if (inputText.isBlank()) return
        scope.launch {
            isTranslating = true
            error = null

            saveSentence(inputText.trim())

            try {
                val result = translationService.translate(
                    text = inputText.trim(),
                    modes = setOf(TranslationMode.NATURAL),
                    includeInterlinear = false,
                    preferredBackend = selectedBackend
                )
                translationResult = result
                if (result == null) {
                    error = "Translation failed. Configure a backend in Settings > Translation."
                }
            } catch (e: Exception) {
                error = e.message ?: "Unknown error"
            }

            isTranslating = false
        }
    }

    fun runLiteralTranslation() {
        if (inputText.isBlank()) return
        scope.launch {
            isTranslatingLiteral = true

            try {
                val result = translationService.translate(
                    text = inputText.trim(),
                    modes = setOf(TranslationMode.LITERAL),
                    includeInterlinear = false,
                    preferredBackend = selectedBackend
                )
                // Merge literal into existing result
                if (result?.literal != null) {
                    translationResult = translationResult?.copy(literal = result.literal)
                        ?: result
                }
            } catch (e: Exception) {
                // Silently fail for literal - natural is the main result
            }

            isTranslatingLiteral = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Translation") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { runTranslation() },
                        enabled = inputText.isNotBlank() && !isTranslating
                    ) {
                        Icon(Icons.Default.Refresh, "Translate")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Backend selection chips (only show configured/available backends)
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    backendStatuses
                        .filter { (_, status) -> status is BackendStatus.Available }
                        .forEach { (name, _) ->
                        val isSelected = name == selectedBackend
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                selectedBackend = name
                                // Clear cached result when switching backends
                                translationResult = null
                            },
                            label = { Text(name, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
            }

            // Input section
            item {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Japanese text") },
                    placeholder = { Text("Paste or scan text to translate...") },
                    maxLines = 4,
                    singleLine = false,
                    trailingIcon = {
                        if (inputText.isNotBlank()) {
                            IconButton(onClick = { ttsManager.speak(inputText) }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = "Read aloud",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                )
            }

            // Buttons
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { runTranslation() },
                        modifier = Modifier.weight(1f),
                        enabled = inputText.isNotBlank() && !isTranslating
                    ) {
                        if (isTranslating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Translating...")
                        } else {
                            Text("Translate")
                        }
                    }
                    OutlinedButton(
                        onClick = { showHistory = !showHistory },
                        enabled = savedSentences.isNotEmpty()
                    ) {
                        Text(if (showHistory) "Hide" else "History (${savedSentences.size})")
                    }
                }
            }

            // History section
            if (showHistory && savedSentences.isNotEmpty()) {
                item {
                    TranslationHistorySection(
                        sentences = savedSentences,
                        onSelect = { sentence ->
                            inputText = sentence.sentence
                            showHistory = false
                            translationResult = null
                            error = null
                        },
                        onDelete = { sentence ->
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    database.grammarSentenceDao().delete(sentence.id)
                                    savedSentences = database.grammarSentenceDao().getRecent(20)
                                }
                            }
                        },
                        onDeleteAll = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    database.grammarSentenceDao().deleteAll()
                                }
                                savedSentences = emptyList()
                            }
                        }
                    )
                }
            }

            // Error message
            error?.let { errMsg ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = errMsg,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }

            // Translation result
            translationResult?.let { result ->
                // Natural translation
                result.natural?.let { natural ->
                    item {
                        Text(
                            text = "NATURAL TRANSLATION",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            )
                        ) {
                            Text(
                                text = natural,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }

                // Literal translation section
                item {
                    if (result.literal != null) {
                        // Show literal translation
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "LITERAL TRANSLATION",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                                )
                            ) {
                                Text(
                                    text = result.literal,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    } else {
                        // Show button to get literal translation
                        OutlinedButton(
                            onClick = { runLiteralTranslation() },
                            enabled = !isTranslatingLiteral,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isTranslatingLiteral) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Getting literal...")
                            } else {
                                Text("Get Literal Translation")
                            }
                        }
                    }
                }

                // Backend info
                item {
                    Text(
                        text = "via ${result.backend}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // Empty state
            if (translationResult == null && error == null && !isTranslating) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (latestOcrText.isNullOrBlank())
                                "Capture text with OCR first, or paste Japanese text above"
                            else
                                "Tap Translate to get the translation",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

// Time filter for sentence history
private enum class TranslationTimeFilter(val label: String, val durationMs: Long) {
    ALL("All", 0L),
    LAST_HOUR("1h", 60 * 60 * 1000L),
    TODAY("24h", 24 * 60 * 60 * 1000L),
    THIS_WEEK("Week", 7 * 24 * 60 * 60 * 1000L)
}

@Composable
private fun TranslationHistorySection(
    sentences: List<GrammarSentenceEntity>,
    onSelect: (GrammarSentenceEntity) -> Unit,
    onDelete: (GrammarSentenceEntity) -> Unit,
    onDeleteAll: () -> Unit
) {
    var selectedFilter by remember { mutableStateOf(TranslationTimeFilter.ALL) }
    var showClearDialog by remember { mutableStateOf(false) }

    val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    val filteredSentences = remember(sentences, selectedFilter) {
        if (selectedFilter == TranslationTimeFilter.ALL) {
            sentences
        } else {
            val cutoff = System.currentTimeMillis() - selectedFilter.durationMs
            sentences.filter { it.timestamp > cutoff }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear All Sentences") },
            text = { Text("Delete all ${sentences.size} saved sentences?") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteAll()
                    showClearDialog = false
                }) {
                    Text("Delete All", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
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
                    text = "Saved Sentences",
                    style = MaterialTheme.typography.labelMedium
                )
                IconButton(
                    onClick = { showClearDialog = true },
                    enabled = sentences.isNotEmpty(),
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete All",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TranslationTimeFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = { Text(filter.label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (filteredSentences.isEmpty()) {
                Text(
                    text = "No sentences in this time range",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    filteredSentences.take(8).forEach { sentence ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onSelect(sentence) }
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = sentence.sentence,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = dateFormat.format(Date(sentence.timestamp)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = { onDelete(sentence) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Delete",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
