package com.yomidroid.ui.library

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
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
import com.yomidroid.grammar.GrammarLibrary
import com.yomidroid.grammar.GrammarLibraryEntry
import com.yomidroid.ui.components.GrammarResourceButton
import com.yomidroid.ui.components.GrammarSourcePills

private data class SourceOption(val key: String?, val label: String) {
    companion object {
        val ALL = SourceOption(null, "All")
    }
}

private val SOURCE_OPTIONS = listOf(
    SourceOption.ALL,
    SourceOption("gamegengo", "GameGengo"),
    SourceOption("dojg", "DOJG"),
    SourceOption("hjg", "HJG"),
    SourceOption("donnatoki", "文型辞典"),
    SourceOption("taekim", "Tae Kim"),
    SourceOption("imabi", "Imabi"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen() {
    val context = LocalContext.current
    val library = remember { GrammarLibrary.getInstance(context) }

    var selectedSource by remember { mutableStateOf<String?>(null) }
    var grammarPoints by remember { mutableStateOf<List<GrammarLibraryEntry>>(emptyList()) }
    var expandedCardId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedSource) {
        grammarPoints = if (selectedSource == null) {
            library.getGrammarPoints()
        } else {
            library.getGrammarPointsBySource(selectedSource!!)
        }
    }

    val totalCount = remember { library.getTotalCount() }
    val countBySource = remember { library.getCountBySource() }

    val openUrl = remember(context) {
        { url: String ->
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Library") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "filters") {
                Column {
                    Text(
                        text = "SOURCE",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SOURCE_OPTIONS.forEach { option ->
                            val count = when (option.key) {
                                null -> totalCount
                                else -> countBySource[option.key] ?: 0
                            }
                            if (option.key != null && count == 0) return@forEach
                            FilterChip(
                                selected = selectedSource == option.key,
                                onClick = { selectedSource = option.key },
                                label = { Text("${option.label} ($count)") }
                            )
                        }
                    }
                }
            }

            item(key = "header") {
                val label = SOURCE_OPTIONS.firstOrNull { it.key == selectedSource }?.label ?: "All"
                Text(
                    text = "$label (${grammarPoints.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (grammarPoints.isEmpty()) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No grammar points found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(items = grammarPoints, key = { it.id }) { entry ->
                    GrammarBrowseCard(
                        entry = entry,
                        isExpanded = expandedCardId == entry.id,
                        onToggleExpand = {
                            expandedCardId = if (expandedCardId == entry.id) null else entry.id
                        },
                        onOpenUrl = openUrl
                    )
                }
            }

            item(key = "footer") {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Video Content",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Grammar videos by GameGengo - Learn Japanese through video games",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        TextButton(
                            onClick = { openUrl("https://www.youtube.com/@GameGengo") },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("Visit GameGengo Channel →", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GrammarBrowseCard(
    entry: GrammarLibraryEntry,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleExpand),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = entry.pattern,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            entry.headline?.takeIf { it != entry.pattern }?.let { hl ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = hl,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (isExpanded) Int.MAX_VALUE else 2,
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
                    maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isExpanded) {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider()
                    Text(
                        text = "RESOURCES",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    entry.resources.forEach { resource ->
                        GrammarResourceButton(resource = resource, onOpenUrl = onOpenUrl)
                    }
                }
            }
        }
    }
}
