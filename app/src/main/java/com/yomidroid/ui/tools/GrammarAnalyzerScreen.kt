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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// POS background colors (pastel)
private fun getPosBackgroundColor(posColor: PosColor): Color = when (posColor) {
    PosColor.NOUN -> Color(0xFFE3F2FD)       // Light blue
    PosColor.VERB -> Color(0xFFE8F5E9)       // Light green
    PosColor.ADJECTIVE -> Color(0xFFFFF3E0)  // Light orange
    PosColor.ADVERB -> Color(0xFFF3E5F5)     // Light purple
    PosColor.PARTICLE -> Color(0xFFF5F5F5)   // Light gray
    PosColor.AUXILIARY -> Color(0xFFE0F7FA)  // Light cyan
    PosColor.OTHER -> Color(0xFFFAFAFA)      // Very light gray
}

// POS badge text colors (saturated)
private fun getPosBadgeColor(posColor: PosColor): Color = when (posColor) {
    PosColor.NOUN -> Color(0xFF1976D2)       // Blue
    PosColor.VERB -> Color(0xFF388E3C)       // Green
    PosColor.ADJECTIVE -> Color(0xFFF57C00)  // Orange
    PosColor.ADVERB -> Color(0xFF7B1FA2)     // Purple
    PosColor.PARTICLE -> Color(0xFF616161)   // Gray
    PosColor.AUXILIARY -> Color(0xFF00796B)  // Teal
    PosColor.OTHER -> Color(0xFF757575)      // Default gray
}

// Particle underline colors (vibrant)
private fun getParticleColor(role: ParticleRole): Color = when (role) {
    ParticleRole.TOPIC -> Color(0xFFE91E63)    // Pink
    ParticleRole.SUBJECT -> Color(0xFFF44336)  // Red
    ParticleRole.OBJECT -> Color(0xFF2196F3)   // Blue
    ParticleRole.TARGET -> Color(0xFF009688)   // Teal
    ParticleRole.MEANS -> Color(0xFFFFC107)    // Amber
    ParticleRole.POSSESSIVE -> Color(0xFF9E9E9E) // Gray
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

    val analyzer = remember { GrammarAnalyzer.getInstance() }
    val dictionaryEngine = remember { DictionaryEngine(context) }
    val dojgMatcher = remember { DojgMatcher.getInstance(context) }
    val database = remember { AppDatabase.getInstance(context) }

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

    fun runAnalysis() {
        if (inputText.isBlank()) return
        scope.launch {
            isAnalyzing = true

            saveSentence(inputText.trim())

            // Run morphological analysis
            val result = analyzer.analyze(inputText)

            // Find DOJG grammar points
            val grammarPoints = dojgMatcher.findGrammarPoints(inputText)

            // Find all dictionary matches (longest-match scan)
            val matches = withContext(Dispatchers.IO) {
                dictionaryEngine.findAllMatches(inputText)
            }

            analysisResult = result.copy(grammarPoints = grammarPoints)
            dictionaryMatches = matches
            isAnalyzing = false
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
                    singleLine = false
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

                // DOJG section
                if (result.grammarPoints.isNotEmpty()) {
                    item {
                        DojgSection(
                            grammarPoints = result.grammarPoints,
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
    val bgColor = getPosBackgroundColor(bunsetsu.headPosColor)
    val hasParticle = bunsetsu.particleRole != ParticleRole.NONE
    val particleColor = getParticleColor(bunsetsu.particleRole)

    val posLabel = bunsetsu.headWord?.posLabel
    val posBadgeColor = bunsetsu.headWord?.let { getPosBadgeColor(it.posColor) }
    val particleLabel = if (hasParticle && bunsetsu.trailingParticle != null)
        "${bunsetsu.trailingParticle.surface} ${bunsetsu.particleRole.label}"
    else null

    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (hasParticle) {
                    Modifier.drawBehind {
                        val strokeWidth = 3.dp.toPx()
                        drawLine(
                            color = particleColor,
                            start = Offset(0f, size.height - strokeWidth / 2),
                            end = Offset(size.width, size.height - strokeWidth / 2),
                            strokeWidth = strokeWidth
                        )
                    }
                } else Modifier
            ),
        color = bgColor,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = bunsetsu.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )

            if (showReading) {
                Text(
                    text = bunsetsu.reading,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // POS badge
                if (posLabel != null && posBadgeColor != null) {
                    Surface(
                        color = posBadgeColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = posLabel,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = posBadgeColor
                        )
                    }
                }

                // Particle role badge
                if (particleLabel != null) {
                    Surface(
                        color = particleColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = particleLabel,
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
    grammarPoints: List<DetectedGrammarPoint>,
    onOpenUrl: (String) -> Unit
) {
    var selectedPoint by remember { mutableStateOf<DetectedGrammarPoint?>(null) }

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
                        TextButton(
                            onClick = { onOpenUrl(point.sourceUrl) },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("View Full Entry →")
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

            // Expanded content
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // All definitions
                    entry.glossary.drop(1).forEachIndexed { index, gloss ->
                        Text(
                            text = "${index + 2}. $gloss",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                    }

                    // POS tags
                    if (entry.partsOfSpeech.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            entry.partsOfSpeech.take(4).forEach { pos ->
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = pos,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }

                    // Frequency badge
                    entry.frequencyRank?.let { rank ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            color = Color(0xFF4CAF50).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "Freq: #$rank",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF2E7D32)
                            )
                        }
                    }
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
