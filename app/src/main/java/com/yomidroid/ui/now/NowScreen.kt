package com.yomidroid.ui.now

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yomidroid.anki.AnkiDroidExporter
import com.yomidroid.anki.ExportResult
import com.yomidroid.config.ColorConfigManager
import com.yomidroid.dictionary.DictionaryEngine
import com.yomidroid.dictionary.DictionaryEntry
import com.yomidroid.dictionary.DictionaryWordMatch
import com.yomidroid.dictionary.LookupResultRepository
import com.yomidroid.grammar.*
import com.yomidroid.ocr.OcrResultRepository
import com.yomidroid.translation.BackendStatus
import com.yomidroid.translation.InterlinearMorpheme
import com.yomidroid.translation.TranslationMode
import com.yomidroid.translation.TranslationResult
import com.yomidroid.translation.TranslationService
import com.yomidroid.tts.TtsManager
import com.yomidroid.ui.components.DictionaryEntryWebView
import com.yomidroid.ui.components.GrammarResourceTextButton
import com.yomidroid.ui.components.GrammarSourcePills
import com.yomidroid.ui.components.rememberDictionaryWebViewController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class NowTab(val label: String) {
    LOOKUP("Lookup"),
    PARSE("Parse"),
    TRANSLATE("Translate")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowScreen(onOpenKanji: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val colorConfigManager = remember { ColorConfigManager(context) }
    val isDecoupledMode = remember { colorConfigManager.isDecoupledMode() }

    val timestamp by OcrResultRepository.lastUpdateTimestamp.collectAsState()
    val latestSelectedSentence by OcrResultRepository.latestSelectedSentence.collectAsState()
    val latestOcrText by OcrResultRepository.latestOcrText.collectAsState()

    var sentence by remember { mutableStateOf("") }
    var userInteracted by remember { mutableStateOf(false) }
    var lastConsumedTimestamp by remember { mutableStateOf(0L) }
    var lastConsumedSelected by remember { mutableStateOf<String?>(null) }
    var loadVersion by remember { mutableStateOf(0) }

    val hasFreshScan = (timestamp != 0L && timestamp != lastConsumedTimestamp) ||
        (latestSelectedSentence != null && latestSelectedSentence != lastConsumedSelected)

    fun loadLatestScan() {
        val newText = latestSelectedSentence ?: latestOcrText ?: return
        if (newText.isEmpty()) return
        sentence = newText
        userInteracted = false
        lastConsumedTimestamp = timestamp
        lastConsumedSelected = latestSelectedSentence
        loadVersion++
    }

    LaunchedEffect(timestamp, latestSelectedSentence) {
        if (hasFreshScan && !userInteracted) {
            loadLatestScan()
        }
    }

    val onSentenceChange: (String) -> Unit = { newValue ->
        sentence = newValue
        userInteracted = true
    }
    val onInteracted: () -> Unit = { userInteracted = true }
    val showFreshScanBanner = hasFreshScan && userInteracted

    // Lookup tab is only meaningful in decoupled mode (overlay popup is suppressed there).
    // In non-decoupled mode, dictionary entries appear in the overlay popup itself.
    val visibleTabs = remember(isDecoupledMode) {
        if (isDecoupledMode) NowTab.entries.toList()
        else listOf(NowTab.PARSE, NowTab.TRANSLATE)
    }
    var selectedTab by remember(visibleTabs) { mutableStateOf(visibleTabs.first()) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text("Now") })
                TabRow(
                    selectedTabIndex = visibleTabs.indexOf(selectedTab).coerceAtLeast(0)
                ) {
                    visibleTabs.forEach { tab ->
                        Tab(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            text = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                NowTab.LOOKUP -> LookupTab(onOpenKanji = onOpenKanji)
                NowTab.PARSE -> ParseTab(
                    sentence = sentence,
                    onSentenceChange = onSentenceChange,
                    onInteracted = onInteracted,
                    showFreshScanBanner = showFreshScanBanner,
                    onLoadLatestScan = ::loadLatestScan,
                    loadVersion = loadVersion
                )
                NowTab.TRANSLATE -> TranslateTab(
                    sentence = sentence,
                    onSentenceChange = onSentenceChange,
                    onInteracted = onInteracted,
                    showFreshScanBanner = showFreshScanBanner,
                    onLoadLatestScan = ::loadLatestScan,
                    loadVersion = loadVersion
                )
            }
        }
    }
}

@Composable
private fun FreshScanBanner(onLoadLatestScan: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onLoadLatestScan() }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "New scan available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Tap to replace with the latest scanned text",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            TextButton(onClick = onLoadLatestScan) {
                Text(
                    "Replace",
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    }
}

// ============================================================
//  Lookup tab — current scan dictionary entries (decoupled mode)
// ============================================================

@Composable
private fun LookupTab(onOpenKanji: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val entries by LookupResultRepository.latestEntries.collectAsState()
    val sentence by LookupResultRepository.latestSentence.collectAsState()
    val screenshotPath by LookupResultRepository.latestScreenshotPath.collectAsState()
    val matchedText by LookupResultRepository.latestMatchedText.collectAsState()
    val originalMatchedText by LookupResultRepository.latestOriginalMatchedText.collectAsState()
    val ankiExporter = remember { AnkiDroidExporter(context) }
    val scope = rememberCoroutineScope()
    val webViewController = rememberDictionaryWebViewController()
    val dictionaryEngine = remember { DictionaryEngine(context) }

    fun applyCorrection(corrected: String) {
        scope.launch {
            val newEntries = withContext(Dispatchers.IO) { dictionaryEngine.findTerms(corrected) }
            if (newEntries.isNotEmpty()) {
                LookupResultRepository.updateEntriesFromCorrection(
                    entries = newEntries,
                    matchedText = newEntries.first().matchedText
                )
            }
        }
    }

    fun computeRanking(originalChar: Char) {
        val base = matchedText ?: return
        val pos = base.indexOf(originalChar)
        if (pos < 0) return
        val neighbors = com.yomidroid.dictionary.KanjiSimilarity.neighbors(originalChar)
        if (neighbors.isEmpty()) return
        scope.launch {
            val ranked = withContext(Dispatchers.IO) {
                neighbors.map { c ->
                    val corrected = base.substring(0, pos) + c + base.substring(pos + 1)
                    c to dictionaryEngine.findTerms(corrected).size
                }.sortedByDescending { it.second }
            }
            webViewController.setCorrectionRanking(originalChar, ranked)
        }
    }

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

    if (entries.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Tap text in the overlay to see results here",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
                textAlign = TextAlign.Center
            )
        }
    } else {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            DictionaryEntryWebView(
                entries = entries,
                onAnkiExport = { entry, index -> exportEntry(entry, index) },
                onOpenKanji = onOpenKanji,
                onCorrection = { corrected -> applyCorrection(corrected) },
                onRequestRanking = { ch -> computeRanking(ch) },
                matchedText = matchedText,
                originalMatchedText = originalMatchedText,
                controller = webViewController,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            entries.firstOrNull()?.let { entry ->
                Button(
                    onClick = { exportEntry(entry, 0) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) { Text("Export to Anki") }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// ============================================================
//  Parse tab — Kuromoji morphology + DOJG grammar matches
// ============================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParseTab(
    sentence: String,
    onSentenceChange: (String) -> Unit,
    onInteracted: () -> Unit,
    showFreshScanBanner: Boolean,
    onLoadLatestScan: () -> Unit,
    loadVersion: Int
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val analyzer = remember { GrammarAnalyzer.getInstance() }
    val dictionaryEngine = remember { DictionaryEngine(context) }
    val grammarResolver = remember { GrammarResolver.getInstance(context) }
    val ttsManager = remember { TtsManager.getInstance(context) }

    var analysisResult by remember { mutableStateOf<GrammarAnalysisResult?>(null) }
    var resolvedGrammar by remember { mutableStateOf<List<ResolvedGrammarPoint>>(emptyList()) }
    var bunsetsuLookups by remember { mutableStateOf<List<BunsetsuLookups>>(emptyList()) }
    var isAnalyzing by remember { mutableStateOf(false) }

    LaunchedEffect(loadVersion) {
        if (loadVersion > 0) {
            analysisResult = null
            resolvedGrammar = emptyList()
            bunsetsuLookups = emptyList()
        }
    }

    fun runAnalysis() {
        if (sentence.isBlank()) return
        onInteracted()
        scope.launch {
            isAnalyzing = true
            val result = analyzer.analyze(sentence)
            val resolved = grammarResolver.resolveGrammar(sentence)
            val lookups = withContext(Dispatchers.IO) {
                var idx = 0
                result.bunsetsu.map { bs ->
                    val start = idx
                    val end = idx + bs.text.length
                    idx = end
                    BunsetsuLookups(
                        bunsetsu = bs,
                        startInSentence = start,
                        matches = dictionaryEngine.findAllMatchesGrouped(sentence, start, end)
                    )
                }
            }
            analysisResult = result
            resolvedGrammar = resolved
            bunsetsuLookups = lookups
            isAnalyzing = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (showFreshScanBanner) {
            item { FreshScanBanner(onLoadLatestScan) }
        }
        item {
            OutlinedTextField(
                value = sentence,
                onValueChange = onSentenceChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Japanese text") },
                placeholder = { Text("Paste or scan text to analyze…") },
                maxLines = 4,
                trailingIcon = {
                    if (sentence.isNotBlank()) {
                        IconButton(onClick = { ttsManager.speak(sentence, showErrorToast = true) }) {
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

        item {
            Button(
                onClick = { runAnalysis() },
                modifier = Modifier.fillMaxWidth(),
                enabled = sentence.isNotBlank() && !isAnalyzing
            ) {
                if (isAnalyzing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Analyzing…")
                } else {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Analyze")
                }
            }
        }

        analysisResult?.let { result ->
            item {
                Text(
                    text = "GRAMMAR BREAKDOWN",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(result.bunsetsu.size) { i -> BunsetsuChip(result.bunsetsu[i]) }
                }
            }

            if (resolvedGrammar.isNotEmpty()) {
                item {
                    DojgSection(
                        grammarPoints = resolvedGrammar,
                        onOpenUrl = { url ->
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    )
                }
            }

            val nonEmptyLookups = bunsetsuLookups.filter { it.matches.isNotEmpty() }
            if (nonEmptyLookups.isNotEmpty()) {
                val totalMatches = nonEmptyLookups.sumOf { it.matches.size }
                item {
                    Text(
                        text = "DICTIONARY ($totalMatches words)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                nonEmptyLookups.forEach { lookup ->
                    item(key = "bunsetsu-header-${lookup.startInSentence}") {
                        BunsetsuHeader(lookup.bunsetsu)
                    }
                    items(
                        items = lookup.matches,
                        key = { match -> "match-${lookup.startInSentence}-${match.startIndex}-${match.matchedText}" }
                    ) { match ->
                        DictionaryWordCard(match)
                    }
                }
            }
        }

        if (analysisResult == null && !isAnalyzing) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Edit the sentence above and tap Analyze",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

// ============================================================
//  Translate tab — Natural / Literal / Leipzig (Gemini Flash)
// ============================================================

@Composable
private fun TranslateTab(
    sentence: String,
    onSentenceChange: (String) -> Unit,
    onInteracted: () -> Unit,
    showFreshScanBanner: Boolean,
    onLoadLatestScan: () -> Unit,
    loadVersion: Int
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val translationService = remember { TranslationService.getInstance(context) }
    val ttsManager = remember { TtsManager.getInstance(context) }

    var result by remember { mutableStateOf<TranslationResult?>(null) }
    var isTranslating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var backendStatuses by remember { mutableStateOf<Map<String, BackendStatus>>(emptyMap()) }
    var selectedBackend by remember { mutableStateOf<String?>(null) }
    var showBackendPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        backendStatuses = translationService.getBackendStatuses()
        if (selectedBackend == null) {
            selectedBackend = backendStatuses.entries
                .firstOrNull { it.value is BackendStatus.Available }?.key
        }
    }

    LaunchedEffect(loadVersion) {
        if (loadVersion > 0) {
            result = null
            error = null
        }
    }

    fun runTranslate() {
        if (sentence.isBlank()) return
        onInteracted()
        scope.launch {
            isTranslating = true
            error = null
            try {
                val r = translationService.translate(
                    text = sentence.trim(),
                    modes = setOf(
                        TranslationMode.NATURAL,
                        TranslationMode.LITERAL,
                        TranslationMode.INTERLINEAR
                    ),
                    includeInterlinear = true,
                    preferredBackend = selectedBackend
                )
                result = r
                if (r == null) {
                    error = "No translation backend is configured. Open Settings → Translation to set up Gemini Flash or another backend."
                }
            } catch (e: Exception) {
                error = e.message ?: "Unknown error"
            }
            isTranslating = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (showFreshScanBanner) {
            item { FreshScanBanner(onLoadLatestScan) }
        }
        item {
            OutlinedTextField(
                value = sentence,
                onValueChange = onSentenceChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Japanese text") },
                placeholder = { Text("Paste or scan text to translate…") },
                maxLines = 4,
                trailingIcon = {
                    if (sentence.isNotBlank()) {
                        IconButton(onClick = { ttsManager.speak(sentence, showErrorToast = true) }) {
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

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { runTranslate() },
                    modifier = Modifier.weight(1f),
                    enabled = sentence.isNotBlank() && !isTranslating
                ) {
                    if (isTranslating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Translating…")
                    } else {
                        Icon(Icons.Default.Translate, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Translate")
                    }
                }
                AssistChip(
                    onClick = { showBackendPicker = true },
                    label = { Text(selectedBackend ?: "Backend") }
                )
            }
        }

        if (showBackendPicker) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "BACKEND",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        backendStatuses.entries.forEach { (name, status) ->
                            val available = status is BackendStatus.Available
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = available) {
                                        selectedBackend = name
                                        showBackendPicker = false
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedBackend == name,
                                    onClick = null,
                                    enabled = available
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (available) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = when (status) {
                                            is BackendStatus.Available -> "Available"
                                            is BackendStatus.Unavailable -> "Not configured"
                                            is BackendStatus.Downloading -> "Downloading…"
                                            is BackendStatus.Error -> "Error: ${status.message}"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        error?.let { msg ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = msg,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        result?.let { r ->
            r.natural?.let {
                item { TranslationSection(label = "NATURAL", text = it, big = true) }
            }
            r.literal?.let {
                item { TranslationSection(label = "LITERAL", text = it, monospace = true) }
            }
            r.interlinear?.let { interlinear ->
                if (interlinear.morphemes.isNotEmpty()) {
                    item {
                        Column {
                            Text(
                                text = "LEIPZIG GLOSS",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LeipzigGlossRow(morphemes = interlinear.morphemes)
                        }
                    }
                }
            }
            r.notes?.let {
                item { TranslationSection(label = "NOTES", text = it) }
            }
            item {
                Text(
                    text = "via ${r.backend}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (result == null && !isTranslating && error == null) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Edit the sentence above and tap Translate",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun TranslationSection(
    label: String,
    text: String,
    big: Boolean = false,
    monospace: Boolean = false
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = text,
            style = if (big) MaterialTheme.typography.bodyLarge
            else MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun LeipzigGlossRow(morphemes: List<InterlinearMorpheme>) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(morphemes.size) { i ->
            val m = morphemes[i]
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = m.surface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                if (m.reading.isNotBlank() && m.reading != m.surface) {
                    Text(
                        text = m.reading,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = m.gloss,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ============================================================
//  Shared visual helpers (BunsetsuChip, DojgSection, DictionaryMatchCard)
// ============================================================

private fun getPosBadgeColor(posColor: PosColor): Color = when (posColor) {
    PosColor.NOUN -> Color(0xFF5C7CBA)
    PosColor.VERB -> Color(0xFF5A9E6F)
    PosColor.ADJECTIVE -> Color(0xFFCB8A4E)
    PosColor.ADVERB -> Color(0xFF8B6BAE)
    PosColor.PARTICLE -> Color(0xFF888888)
    PosColor.AUXILIARY -> Color(0xFF5B9EA6)
    PosColor.OTHER -> Color(0xFF888888)
}

private fun getParticleColor(role: ParticleRole): Color = when (role) {
    ParticleRole.TOPIC -> Color(0xFFCF7B8A)
    ParticleRole.SUBJECT -> Color(0xFFD4756A)
    ParticleRole.OBJECT -> Color(0xFF6B9BD2)
    ParticleRole.TARGET -> Color(0xFF6BA5A0)
    ParticleRole.MEANS -> Color(0xFFD4A84B)
    ParticleRole.POSSESSIVE -> Color(0xFFA0A0A0)
    ParticleRole.NONE -> Color.Transparent
}

private fun getDojgLevelColor(level: String): Color = when (level) {
    "basic" -> Color(0xFF4CAF50)
    "intermediate" -> Color(0xFFFF9800)
    "advanced" -> Color(0xFFF44336)
    else -> Color(0xFF9E9E9E)
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
            Text(
                text = bunsetsu.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (showReading) {
                Text(
                    text = bunsetsu.reading,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                    onClick = { selectedPoint = if (isSelected) null else point },
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
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
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
                        point.headline?.takeIf { it != point.pattern }?.let { hl ->
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = hl,
                                style = MaterialTheme.typography.bodySmall,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.9f)
                            )
                        }
                        if (point.resources.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            GrammarSourcePills(resources = point.resources)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = point.meaning,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
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
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            point.resources.forEach { resource ->
                                GrammarResourceTextButton(resource = resource, onOpenUrl = onOpenUrl)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Pairs a Kuromoji bunsetsu with the dictionary lookups for the words inside
 * its substring. Lets the parse-tab render bunsetsu boundaries as headers
 * with their words' candidate dictionary entries grouped underneath.
 */
private data class BunsetsuLookups(
    val bunsetsu: Bunsetsu,
    val startInSentence: Int,
    val matches: List<DictionaryWordMatch>
)

@Composable
private fun BunsetsuHeader(bunsetsu: Bunsetsu) {
    val showReading = bunsetsu.reading != bunsetsu.text &&
        bunsetsu.reading.isNotBlank()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = bunsetsu.text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (showReading) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = "【${bunsetsu.reading}】",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        bunsetsu.headWord?.let { head ->
            Spacer(Modifier.width(8.dp))
            val color = getPosBadgeColor(head.posColor)
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
    }
}

/**
 * Headline (best entry's expression + reading + first gloss) with tap-to-
 * expand into a full WebView render of every candidate entry — same
 * candidates the hover-cursor popup shows. Expanded view supports scrolling
 * through multiple dictionaries / readings the way the overlay does.
 */
@Composable
private fun DictionaryWordCard(match: DictionaryWordMatch) {
    val context = LocalContext.current
    val ttsManager = remember { TtsManager.getInstance(context) }
    var expanded by remember { mutableStateOf(false) }
    val best = match.best ?: return

    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
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
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.weight(1f)) {
                    Text(
                        text = best.expression,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (best.reading.isNotBlank() && best.reading != best.expression) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "【${best.reading}】",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (match.candidates.size > 1) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "+${match.candidates.size - 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            ttsManager.speak(
                                best.reading.ifBlank { best.expression },
                                showErrorToast = true
                            )
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Read aloud",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = best.glossary.firstOrNull() ?: "",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    DictionaryEntryWebView(
                        entries = match.candidates,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
