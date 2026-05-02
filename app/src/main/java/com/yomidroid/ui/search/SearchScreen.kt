package com.yomidroid.ui.search

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yomidroid.dictionary.DictionaryEngine
import com.yomidroid.dictionary.DictionaryEntry
import com.yomidroid.grammar.GrammarLibrary
import com.yomidroid.grammar.GrammarLibraryEntry
import com.yomidroid.tts.TtsManager
import com.yomidroid.ui.components.DictionaryEntryWebView
import kotlinx.coroutines.delay

private val JLPT_BG_COLORS = mapOf(
    "N5" to Color(0x264CAF50),
    "N4" to Color(0x268BC34A),
    "N3" to Color(0x26FFB300),
    "N2" to Color(0x26FF7043),
    "N1" to Color(0x26F44336)
)
private val JLPT_FG_COLORS = mapOf(
    "N5" to Color(0xFF4CAF50),
    "N4" to Color(0xFF8BC34A),
    "N3" to Color(0xFFFFB300),
    "N2" to Color(0xFFFF7043),
    "N1" to Color(0xFFF44336)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen() {
    val context = LocalContext.current
    val dictionaryEngine = remember { DictionaryEngine(context) }
    val grammarLibrary = remember { GrammarLibrary.getInstance(context) }
    val ttsManager = remember { TtsManager(context) }

    DisposableEffect(Unit) {
        onDispose { ttsManager.shutdown() }
    }

    var query by remember { mutableStateOf("") }
    var dictResults by remember { mutableStateOf<List<DictionaryEntry>>(emptyList()) }
    var grammarResults by remember { mutableStateOf<List<GrammarLibraryEntry>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            dictResults = emptyList()
            grammarResults = emptyList()
            isSearching = false
            return@LaunchedEffect
        }
        isSearching = true
        delay(300)
        val dict = dictionaryEngine.searchTerm(query)
        val grammar = grammarLibrary.search(query)
        dictResults = dict
        grammarResults = grammar
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
                placeholder = { Text("Japanese word, phrase, or grammar pattern…") },
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
            } else if (!isSearching && dictResults.isEmpty() && grammarResults.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No results in dictionary or grammar library",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (dictResults.isNotEmpty()) {
                        item(key = "dict-header") {
                            Text(
                                text = "DICTIONARY (${dictResults.size})",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        items(dictResults, key = { "dict_${it.id}_${it.source}" }) { entry ->
                            DictResultCard(entry = entry, ttsManager = ttsManager)
                        }
                    }

                    if (grammarResults.isNotEmpty()) {
                        item(key = "grammar-header") {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "GRAMMAR (${grammarResults.size})",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        items(grammarResults, key = { "grammar_${it.id}" }) { entry ->
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

@Composable
private fun DictResultCard(entry: DictionaryEntry, ttsManager: TtsManager) {
    var expanded by remember { mutableStateOf(false) }

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
                    onClick = { ttsManager.speak(entry.reading.ifBlank { entry.expression }) },
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

@Composable
private fun GrammarResultCard(
    entry: GrammarLibraryEntry,
    onOpenUrl: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val levelFg = JLPT_FG_COLORS[entry.jlptLevel] ?: Color.Gray
    val levelBg = JLPT_BG_COLORS[entry.jlptLevel] ?: Color(0x269E9E9E)

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
                Surface(color = levelBg, shape = RoundedCornerShape(4.dp)) {
                    Text(
                        text = entry.jlptLevel,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = levelFg
                    )
                }
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
            if (entry.meaning != null) {
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
                    if (entry.videoUrl != null) {
                        OutlinedButton(
                            onClick = { onOpenUrl(entry.videoUrl) },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = Color(0xFFFF0000)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Watch GameGengo Video", fontSize = 13.sp)
                        }
                    }
                    if (entry.jlptsenseiUrl != null) {
                        OutlinedButton(
                            onClick = { onOpenUrl(entry.jlptsenseiUrl) },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text("View on JLPTSensei", fontSize = 13.sp)
                        }
                    }
                    if (entry.dojgUrl != null) {
                        OutlinedButton(
                            onClick = { onOpenUrl(entry.dojgUrl) },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text("Open DOJG Reference", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
