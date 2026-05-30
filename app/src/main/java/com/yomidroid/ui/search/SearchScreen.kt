package com.yomidroid.ui.search

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import com.yomidroid.dictionary.DictionaryEngine
import com.yomidroid.dictionary.DictionaryEntry
import com.yomidroid.grammar.GrammarLibrary
import com.yomidroid.grammar.GrammarLibraryEntry
import com.yomidroid.grammar.Romaji
import com.yomidroid.kanji.KanjiInfo
import com.yomidroid.kanji.KanjiLibrary
import com.yomidroid.tts.TtsManager
import com.yomidroid.ui.components.DictionaryEntryWebView
import com.yomidroid.ui.components.GrammarResourceButton
import com.yomidroid.ui.components.GrammarSourcePills
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private fun String.isAsciiLetters(): Boolean =
    isNotEmpty() && all { it == ' ' || it == '-' || it == '\'' || it in 'a'..'z' || it in 'A'..'Z' }

private enum class SearchFilter(val label: String) {
    All("All"),
    Words("Words"),
    Kanji("Kanji"),
    Grammar("Grammar")
}

private data class GrammarSourceFilter(val key: String?, val label: String)

private val GRAMMAR_SOURCE_FILTERS = listOf(
    GrammarSourceFilter(null, "All"),
    GrammarSourceFilter("gamegengo", "GameGengo"),
    GrammarSourceFilter("dojg", "DOJG"),
    GrammarSourceFilter("hjg", "HJG"),
    GrammarSourceFilter("donnatoki", "文型辞典"),
    GrammarSourceFilter("taekim", "Tae Kim"),
    GrammarSourceFilter("imabi", "Imabi"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onOpenKanji: (String) -> Unit = {}) {
    val context = LocalContext.current
    val dictionaryEngine = remember { DictionaryEngine(context) }
    val grammarLibrary = remember { GrammarLibrary.getInstance(context) }
    val kanjiLibrary = remember { KanjiLibrary.getInstance(context) }
    val ttsManager = remember { TtsManager.getInstance(context) }

    var query by remember { mutableStateOf("") }
    var dictResults by remember { mutableStateOf<List<DictionaryEntry>>(emptyList()) }
    var grammarResults by remember { mutableStateOf<List<GrammarLibraryEntry>>(emptyList()) }
    var kanjiResults by remember { mutableStateOf<List<KanjiInfo>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(SearchFilter.All) }
    var grammarSourceFilter by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            dictResults = emptyList()
            grammarResults = emptyList()
            kanjiResults = emptyList()
            isSearching = false
            return@LaunchedEffect
        }
        isSearching = true
        delay(300)

        // If the user typed romaji ("mizu"), also try the kana variant ("みず") so
        // dict + kanji searches — which expect kana — get a real chance to match.
        // Grammar search already handles romaji via its pre-computed romaji index.
        val kanaVariant = if (query.isAsciiLetters()) {
            Romaji.romajiToHiragana(query).takeIf { it.isNotEmpty() && it != query }
        } else null

        val primaryDict = dictionaryEngine.searchTerm(query)
        val dict: List<DictionaryEntry> = if (kanaVariant == null) {
            primaryDict
        } else {
            val seen = primaryDict.mapTo(mutableSetOf()) { "${it.expression}|${it.reading}" }
            primaryDict + dictionaryEngine.searchTerm(kanaVariant)
                .filter { seen.add("${it.expression}|${it.reading}") }
        }
        val grammar = grammarLibrary.search(query)
        val kanji = withContext(Dispatchers.Default) {
            val primary = kanjiLibrary.search(query)
            if (kanaVariant == null) primary else {
                val seen = primary.mapTo(mutableSetOf()) { it.character }
                primary + kanjiLibrary.search(kanaVariant).filter { seen.add(it.character) }
            }
        }
        dictResults = dict
        grammarResults = grammar
        kanjiResults = kanji
        isSearching = false
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Search") }) }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Japanese, romaji, or English meaning…") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                trailingIcon = {
                    when {
                        isSearching -> CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        query.isNotEmpty() -> IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SearchFilter.values().forEach { option ->
                    val count = when (option) {
                        SearchFilter.All -> dictResults.size + grammarResults.size + kanjiResults.size
                        SearchFilter.Words -> dictResults.size
                        SearchFilter.Kanji -> kanjiResults.size
                        SearchFilter.Grammar -> grammarResults.size
                    }
                    val showCount = query.isNotBlank() && !isSearching
                    FilterChip(
                        selected = filter == option,
                        onClick = {
                            filter = option
                            if (option != SearchFilter.Grammar) grammarSourceFilter = null
                        },
                        label = {
                            Text(if (showCount) "${option.label} ($count)" else option.label)
                        }
                    )
                }
            }

            // Source sub-filter — only visible when Grammar filter is active
            AnimatedVisibility(visible = filter == SearchFilter.Grammar) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GRAMMAR_SOURCE_FILTERS.forEach { opt ->
                        val matchingCount = if (opt.key == null) {
                            grammarResults.size
                        } else {
                            grammarResults.count { entry -> entry.resources.any { it.source == opt.key } }
                        }
                        val showCount = query.isNotBlank() && !isSearching
                        // Skip sources with zero matches to keep the row tidy
                        if (opt.key != null && matchingCount == 0 && showCount) return@forEach
                        FilterChip(
                            selected = grammarSourceFilter == opt.key,
                            onClick = { grammarSourceFilter = opt.key },
                            label = {
                                Text(if (showCount) "${opt.label} ($matchingCount)" else opt.label)
                            }
                        )
                    }
                }
            }

            if (query.isBlank()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Type any Japanese — words, conjugated forms, grammar patterns",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp)
                    )
                }
            } else {
                val showWords = filter == SearchFilter.All || filter == SearchFilter.Words
                val showGrammar = filter == SearchFilter.All || filter == SearchFilter.Grammar
                val showKanji = filter == SearchFilter.All || filter == SearchFilter.Kanji
                val visibleWords = if (showWords) dictResults else emptyList()
                val visibleKanji = if (showKanji) kanjiResults else emptyList()
                val visibleGrammar = when {
                    !showGrammar -> emptyList()
                    grammarSourceFilter == null -> grammarResults
                    else -> grammarResults.filter { entry ->
                        entry.resources.any { it.source == grammarSourceFilter }
                    }
                }

                if (!isSearching && visibleWords.isEmpty() && visibleGrammar.isEmpty() && visibleKanji.isEmpty()) {
                    val emptyMessage = when (filter) {
                        SearchFilter.Words -> "No word results"
                        SearchFilter.Kanji -> "No kanji results"
                        SearchFilter.Grammar -> "No grammar results"
                        SearchFilter.All -> "No results in dictionary, kanji, or grammar library"
                    }
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = emptyMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (visibleWords.isNotEmpty()) {
                            item(key = "dict-header") {
                                Text(
                                    text = "WORDS (${visibleWords.size})",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            items(visibleWords, key = { "dict_${it.id}_${it.source}" }) { entry ->
                                DictResultCard(
                                    entry = entry,
                                    ttsManager = ttsManager,
                                    dictionaryEngine = dictionaryEngine,
                                    onOpenKanji = onOpenKanji
                                )
                            }
                        }

                        if (visibleKanji.isNotEmpty()) {
                            item(key = "kanji-header") {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "KANJI (${visibleKanji.size})",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            item(key = "kanji-row") {
                                KanjiResultStrip(
                                    results = visibleKanji,
                                    onOpenKanji = onOpenKanji,
                                )
                            }
                        }

                        if (visibleGrammar.isNotEmpty()) {
                            item(key = "grammar-header") {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "GRAMMAR (${visibleGrammar.size})",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            items(visibleGrammar, key = { "grammar_${it.id}" }) { entry ->
                                GrammarResultCard(
                                    entry = entry,
                                    onOpenUrl = { url ->
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun KanjiResultStrip(
    results: List<KanjiInfo>,
    onOpenKanji: (String) -> Unit,
) {
    val capped = remember(results) { results.take(60) }
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        capped.forEach { info ->
            Surface(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp)),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 1.dp,
                onClick = { onOpenKanji(info.character) }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = info.character,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
    if (results.size > capped.size) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Showing first ${capped.size} of ${results.size}. Refine the query for more specific matches.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DictResultCard(
    entry: DictionaryEntry,
    ttsManager: TtsManager,
    dictionaryEngine: DictionaryEngine,
    onOpenKanji: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Only the always-visible header toggles expansion. Keeping the
            // clickable off the expanded body means taps on structured-content
            // links inside the WebView don't also collapse the card.
            Column(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = entry.expression,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (entry.reading.isNotBlank() && entry.reading != entry.expression) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = entry.reading,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(
                    onClick = { ttsManager.speak(entry.reading.ifBlank { entry.expression }, showErrorToast = true) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Read aloud",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            entry.posDisplayLabel?.let { posLabel ->
                Text(
                    text = posLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF82B4FF)
                )
            }

            Text(
                text = entry.glossary.firstOrNull() ?: "",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    DictionaryEntryWebView(
                        entries = listOf(entry),
                        onOpenKanji = onOpenKanji,
                        dictionaryEngine = dictionaryEngine,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun GrammarResultCard(
    entry: GrammarLibraryEntry,
    onOpenUrl: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = entry.pattern,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            entry.headline?.takeIf { it != entry.pattern }?.let { hl ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = hl,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (entry.resources.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                GrammarSourcePills(resources = entry.resources)
            }
            if (entry.meaning != null && entry.meaning != entry.headline) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = entry.meaning,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider()
                    entry.resources.forEach { resource ->
                        GrammarResourceButton(resource = resource, onOpenUrl = onOpenUrl)
                    }
                }
            }
        }
    }
}
