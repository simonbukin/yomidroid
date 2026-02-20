package com.yomidroid.ui.tools

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yomidroid.dictionary.DictionaryEngine
import com.yomidroid.dictionary.DictionaryEntry
import com.yomidroid.tts.TtsManager
import com.yomidroid.ui.components.DictionaryEntryWebView
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun DictionarySearchScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val dictionaryEngine = remember { DictionaryEngine(context) }
    val ttsManager = remember { TtsManager(context) }

    DisposableEffect(Unit) {
        onDispose { ttsManager.shutdown() }
    }

    var searchQuery by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<DictionaryEntry>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    // Debounced search
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            results = emptyList()
            isSearching = false
            return@LaunchedEffect
        }
        isSearching = true
        kotlinx.coroutines.delay(300)
        val found = dictionaryEngine.searchTerm(searchQuery)
        results = found
        isSearching = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dictionary Search") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
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
            // Search input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Type Japanese word or reading...") },
                singleLine = true,
                trailingIcon = {
                    if (isSearching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            )

            // Results
            if (searchQuery.isNotBlank() && results.isEmpty() && !isSearching) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No results found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(results, key = { "${it.id}_${it.source}" }) { entry ->
                    SearchResultCard(entry = entry, ttsManager = ttsManager)
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(entry: DictionaryEntry, ttsManager: TtsManager) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header row
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

                // Badges row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    entry.frequencyBadge?.let { badge ->
                        val badgeColor = Color(entry.frequencyBadgeColor)
                        Surface(
                            color = badgeColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = badge,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = badgeColor
                            )
                        }
                    }
                    entry.jpdbBadge?.let { badge ->
                        val jpdbColor = Color(entry.jpdbBadgeColor)
                        Surface(
                            color = jpdbColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = badge,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = jpdbColor
                            )
                        }
                    }
                    entry.nameTypeLabel?.let { label ->
                        Surface(
                            color = Color(0xFFFF9800).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = label,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFFF9800)
                            )
                        }
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

            // POS label
            entry.posDisplayLabel?.let { posLabel ->
                Text(
                    text = posLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF82B4FF)
                )
            }

            // Pitch accent indicator
            entry.pitchDownstep?.let { downstep ->
                Text(
                    text = "Pitch: $downstep" + when {
                        downstep == 0 -> " (heiban)"
                        downstep == 1 -> " (atamadaka)"
                        else -> " (nakadaka)"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF82B4FF).copy(alpha = 0.7f)
                )
            }

            // First definition preview
            Text(
                text = entry.glossary.firstOrNull() ?: "",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
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
