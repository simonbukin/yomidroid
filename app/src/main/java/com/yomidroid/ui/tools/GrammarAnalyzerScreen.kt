package com.yomidroid.ui.tools

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yomidroid.data.AppDatabase
import com.yomidroid.data.GrammarSentenceEntity
import com.yomidroid.dictionary.DictionaryEngine
import com.yomidroid.dictionary.DictionaryEntryWithPosition
import com.yomidroid.grammar.*
import com.yomidroid.ocr.OcrResultRepository
import com.yomidroid.tts.TtsManager
import com.yomidroid.ui.components.DictionaryEntryWebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// POS badge colors — muted, cohesive palette
private fun getPosBadgeColor(posColor: PosColor): Color = when (posColor) {
    PosColor.NOUN -> Color(0xFF5C7CBA)       // Steel blue
    PosColor.VERB -> Color(0xFF5A9E6F)       // Sage green
    PosColor.ADJECTIVE -> Color(0xFFCB8A4E)  // Warm amber
    PosColor.ADVERB -> Color(0xFF8B6BAE)     // Soft purple
    PosColor.PARTICLE -> Color(0xFF888888)   // Neutral gray
    PosColor.AUXILIARY -> Color(0xFF5B9EA6)  // Muted teal
    PosColor.OTHER -> Color(0xFF888888)      // Neutral gray
}

// Particle accent colors — subtle, not competing
private fun getParticleColor(role: ParticleRole): Color = when (role) {
    ParticleRole.TOPIC -> Color(0xFFCF7B8A)    // Dusty rose
    ParticleRole.SUBJECT -> Color(0xFFD4756A)  // Muted coral
    ParticleRole.OBJECT -> Color(0xFF6B9BD2)   // Soft blue
    ParticleRole.TARGET -> Color(0xFF6BA5A0)   // Sage teal
    ParticleRole.MEANS -> Color(0xFFD4A84B)    // Warm gold
    ParticleRole.POSSESSIVE -> Color(0xFFA0A0A0) // Light gray
    ParticleRole.NONE -> Color.Transparent
}

// DOJG level colors
private fun getDojgLevelColor(level: String): Color = when (level) {
    "basic" -> Color(0xFF4CAF50)           // Green
    "intermediate" -> Color(0xFFFF9800)     // Orange
    "advanced" -> Color(0xFFF44336)         // Red
    else -> Color(0xFF9E9E9E)               // Gray
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GrammarAnalyzerScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Get latest OCR text
    val latestOcrText by OcrResultRepository.latestOcrText.collectAsState()

    // Analysis state
    var inputText by remember { mutableStateOf("") }
    var analysisResult by remember { mutableStateOf<GrammarAnalysisResult?>(null) }
    var dictionaryMatches by remember { mutableStateOf<List<DictionaryEntryWithPosition>>(emptyList()) }
    var isAnalyzing by remember { mutableStateOf(false) }

    var resolvedGrammarPoints by remember { mutableStateOf<List<ResolvedGrammarPoint>>(emptyList()) }

    val analyzer = remember { GrammarAnalyzer.getInstance() }
    val dictionaryEngine = remember { DictionaryEngine(context) }
    val grammarResolver = remember { GrammarResolver.getInstance(context) }
    val database = remember { AppDatabase.getInstance(context) }
    val ttsManager = remember { TtsManager(context) }

    DisposableEffect(Unit) {
        onDispose { ttsManager.shutdown() }
    }

    // Sentence history state
    var savedSentences by remember { mutableStateOf<List<GrammarSentenceEntity>>(emptyList()) }
    var showHistory by remember { mutableStateOf(false) }

    // Load saved sentences on startup
    LaunchedEffect(Unit) {
        savedSentences = withContext(Dispatchers.IO) {
            database.grammarSentenceDao().getRecent(20)
        }
        if (latestOcrText.isNullOrBlank() && inputText.isBlank()) {
            val mostRecent = savedSentences.firstOrNull()
            if (mostRecent != null) {
                inputText = mostRecent.sentence
            }
        }
    }

    // Flag to trigger auto-analysis after OCR text arrives
    var pendingAutoAnalyze by remember { mutableStateOf(false) }

    LaunchedEffect(latestOcrText) {
        if (!latestOcrText.isNullOrBlank() && inputText.isBlank()) {
            inputText = latestOcrText!!
            pendingAutoAnalyze = true
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

    fun runAnalysis() {
        if (inputText.isBlank()) return
        scope.launch {
            isAnalyzing = true

            saveSentence(inputText.trim())

            // Run morphological analysis
            val result = analyzer.analyze(inputText)

            // Find grammar points with library cross-references
            val resolved = grammarResolver.resolveGrammar(inputText)

            // Find all dictionary matches (longest-match scan)
            val matches = withContext(Dispatchers.IO) {
                dictionaryEngine.findAllMatches(inputText)
            }

            analysisResult = result
            resolvedGrammarPoints = resolved
            dictionaryMatches = matches
            isAnalyzing = false
        }
    }

    // Auto-analyze when OCR text arrives
    LaunchedEffect(pendingAutoAnalyze) {
        if (pendingAutoAnalyze) {
            pendingAutoAnalyze = false
            runAnalysis()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Grammar Analyzer") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { runAnalysis() },
                        enabled = inputText.isNotBlank() && !isAnalyzing
                    ) {
                        Icon(Icons.Default.Refresh, "Analyze")
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
            // Input section
            item {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Japanese text") },
                    placeholder = { Text("Paste or scan text to analyze...") },
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
                        onClick = { runAnalysis() },
                        modifier = Modifier.weight(1f),
                        enabled = inputText.isNotBlank() && !isAnalyzing
                    ) {
                        if (isAnalyzing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Analyzing...")
                        } else {
                            Text("Analyze")
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
                    SentenceHistorySection(
                        sentences = savedSentences,
                        onSelect = { sentence ->
                            inputText = sentence.sentence
                            showHistory = false
                            analysisResult = null
                            resolvedGrammarPoints = emptyList()
                            dictionaryMatches = emptyList()
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

            // Analysis results
            analysisResult?.let { result ->
                // Grammar Breakdown header
                item {
                    Text(
                        text = "GRAMMAR BREAKDOWN",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Bunsetsu chips in a horizontal scrolling row
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(result.bunsetsu.size) { index ->
                            BunsetsuChip(result.bunsetsu[index])
                        }
                    }
                }

                // DOJG section with library cross-references
                if (resolvedGrammarPoints.isNotEmpty()) {
                    item {
                        DojgSection(
                            grammarPoints = resolvedGrammarPoints,
                            onOpenUrl = { url ->
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                context.startActivity(intent)
                            }
                        )
                    }
                }

                // Dictionary section
                if (dictionaryMatches.isNotEmpty()) {
                    item {
                        Text(
                            text = "DICTIONARY (${dictionaryMatches.size} matches)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    items(dictionaryMatches.size) { index ->
                        DictionaryCard(dictionaryMatches[index])
                    }
                }
            }

            // Empty state
            if (analysisResult == null && !isAnalyzing) {
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
                                "Tap Analyze to break down the sentence",
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

@Composable
private fun BunsetsuChip(bunsetsu: Bunsetsu) {
    val showReading = bunsetsu.reading != bunsetsu.text
    val hasParticle = bunsetsu.particleRole != ParticleRole.NONE
    val particleColor = getParticleColor(bunsetsu.particleRole)
    val posBadgeColor = bunsetsu.headWord?.let { getPosBadgeColor(it.posColor) }

    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (hasParticle) {
                    Modifier.drawBehind {
                        val strokeWidth = 2.dp.toPx()
                        drawLine(
                            color = particleColor,
                            start = Offset(0f, size.height - strokeWidth / 2),
                            end = Offset(size.width, size.height - strokeWidth / 2),
                            strokeWidth = strokeWidth
                        )
                    }
                } else Modifier
            ),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Japanese text
            Text(
                text = bunsetsu.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Reading (furigana)
            if (showReading) {
                Text(
                    text = bunsetsu.reading,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // POS + particle labels
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // POS badge
                bunsetsu.headWord?.let { head ->
                    val color = posBadgeColor ?: Color(0xFF888888)
                    Surface(
                        color = color.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = head.posLabel,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = color
                        )
                    }
                }

                // Particle role badge
                if (hasParticle && bunsetsu.trailingParticle != null) {
                    Surface(
                        color = particleColor.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "${bunsetsu.trailingParticle.surface} ${bunsetsu.particleRole.label}",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = particleColor
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DojgSection(
    grammarPoints: List<ResolvedGrammarPoint>,
    onOpenUrl: (String) -> Unit
) {
    var selectedPoint by remember { mutableStateOf<ResolvedGrammarPoint?>(null) }

    Column {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            grammarPoints.distinctBy { it.pattern }.forEach { point ->
                val isSelected = point.pattern == selectedPoint?.pattern
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        selectedPoint = if (isSelected) null else point
                    },
                    label = { Text(point.pattern) },
                    leadingIcon = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Surface(
                                color = getDojgLevelColor(point.level),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = point.level.take(1).uppercase(),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White
                                )
                            }
                            point.jlptLevel?.let { jlpt ->
                                Surface(
                                    color = Color(0xFF4CAF50),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = jlpt,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                )
            }
        }

        AnimatedVisibility(
            visible = selectedPoint != null,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            selectedPoint?.let { point ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = point.pattern,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = point.meaning,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        // Show library meaning if available and different
                        point.libraryMeaning?.let { libMeaning ->
                            if (libMeaning != point.meaning) {
                                Text(
                                    text = libMeaning,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                                )
                            }
                        }
                        if (point.formation.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            point.formation.take(2).forEach { formation ->
                                Text(
                                    text = formation,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { onOpenUrl(point.sourceUrl) },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("DOJG", color = Color(0xFF4CAF50))
                            }
                            point.videoUrl?.let { url ->
                                TextButton(
                                    onClick = { onOpenUrl(url) },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("\u25B6 Video", color = Color(0xFFFF0000))
                                }
                            }
                            point.jlptsenseiUrl?.let { url ->
                                TextButton(
                                    onClick = { onOpenUrl(url) },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("JLPTSensei", color = Color(0xFF2196F3))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DictionaryCard(match: DictionaryEntryWithPosition) {
    var expanded by remember { mutableStateOf(false) }
    val entry = match.entry

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header row (always visible)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = entry.expression,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (entry.reading.isNotBlank() && entry.reading != entry.expression) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "【${entry.reading}】",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // First definition preview (always visible)
            Text(
                text = entry.glossary.firstOrNull() ?: "",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Expanded content — full entry rendered via popup.js WebView
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    DictionaryEntryWebView(
                        entries = listOf(entry),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

// Time filter for sentence history
private enum class SentenceTimeFilter(val label: String, val durationMs: Long) {
    ALL("All", 0L),
    LAST_HOUR("1h", 60 * 60 * 1000L),
    TODAY("24h", 24 * 60 * 60 * 1000L),
    THIS_WEEK("Week", 7 * 24 * 60 * 60 * 1000L)
}

@Composable
private fun SentenceHistorySection(
    sentences: List<GrammarSentenceEntity>,
    onSelect: (GrammarSentenceEntity) -> Unit,
    onDelete: (GrammarSentenceEntity) -> Unit,
    onDeleteAll: () -> Unit
) {
    var selectedFilter by remember { mutableStateOf(SentenceTimeFilter.ALL) }
    var showClearDialog by remember { mutableStateOf(false) }

    val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    val filteredSentences = remember(sentences, selectedFilter) {
        if (selectedFilter == SentenceTimeFilter.ALL) {
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
                SentenceTimeFilter.entries.forEach { filter ->
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
