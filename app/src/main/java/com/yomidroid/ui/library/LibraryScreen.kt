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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yomidroid.grammar.GrammarLibrary
import com.yomidroid.grammar.GrammarLibraryEntry

private val JLPT_COLORS = mapOf(
    "N5" to Color(0xFF4CAF50),
    "N4" to Color(0xFF8BC34A),
    "N3" to Color(0xFFFFB300),
    "N2" to Color(0xFFFF7043),
    "N1" to Color(0xFFF44336)
)
private val DEFAULT_LEVEL_COLOR = Color(0xFF9E9E9E)

private val SOURCE_COLORS = mapOf(
    "gamegengo" to Color(0xFFFF0000),
    "jlptsensei" to Color(0xFF2196F3),
    "dojg" to Color(0xFF4CAF50)
)
private val SOURCE_LABELS = mapOf(
    "gamegengo" to "GG",
    "jlptsensei" to "JS",
    "dojg" to "DOJG"
)
private val SOURCE_BG_COLORS = mapOf(
    "gamegengo" to Color(0x26FF0000),
    "jlptsensei" to Color(0x262196F3),
    "dojg" to Color(0x264CAF50)
)
private val JLPT_BG_COLORS = mapOf(
    "N5" to Color(0x264CAF50),
    "N4" to Color(0x268BC34A),
    "N3" to Color(0x26FFB300),
    "N2" to Color(0x26FF7043),
    "N1" to Color(0x26F44336)
)
private val DEFAULT_BG_COLOR = Color(0x269E9E9E)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen() {
    val context = LocalContext.current
    val library = remember { GrammarLibrary.getInstance(context) }
    val availableLevels = remember { library.getAvailableLevels() }

    var selectedLevel by remember { mutableStateOf<String?>(null) }
    var grammarPoints by remember { mutableStateOf<List<GrammarLibraryEntry>>(emptyList()) }
    var expandedCardId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedLevel) {
        grammarPoints = library.getGrammarPoints(selectedLevel)
    }

    val totalCount = remember { library.getTotalCount() }
    val countByLevel = remember { library.getCountByLevel() }

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
                        text = "JLPT LEVEL",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedLevel == null,
                            onClick = { selectedLevel = null },
                            label = { Text("All ($totalCount)") }
                        )
                        availableLevels.forEach { level ->
                            val count = countByLevel[level] ?: 0
                            val levelColor = JLPT_COLORS[level] ?: DEFAULT_LEVEL_COLOR
                            FilterChip(
                                selected = selectedLevel == level,
                                onClick = {
                                    selectedLevel = if (selectedLevel == level) null else level
                                },
                                label = { Text("$level ($count)") },
                                leadingIcon = {
                                    Surface(
                                        color = levelColor,
                                        shape = RoundedCornerShape(4.dp),
                                        modifier = Modifier.size(8.dp)
                                    ) {}
                                }
                            )
                        }
                    }
                }
            }

            item(key = "header") {
                Text(
                    text = if (selectedLevel != null) {
                        "$selectedLevel GRAMMAR (${grammarPoints.size})"
                    } else {
                        "ALL GRAMMAR (${grammarPoints.size})"
                    },
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
    val levelColor = JLPT_COLORS[entry.jlptLevel] ?: DEFAULT_LEVEL_COLOR
    val levelBgColor = JLPT_BG_COLORS[entry.jlptLevel] ?: DEFAULT_BG_COLOR

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleExpand),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(color = levelBgColor, shape = RoundedCornerShape(4.dp)) {
                        Text(
                            text = entry.jlptLevel,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = levelColor
                        )
                    }
                    Text(
                        text = entry.pattern,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (entry.gamegengoSource != null) SourceBadge("gamegengo")
                    if (entry.jlptsenseiSource != null) SourceBadge("jlptsensei")
                    if (entry.dojgSource != null) SourceBadge("dojg")
                }
            }

            if (entry.meaning != null) {
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
                            Text("Watch GameGengo Video")
                        }
                    }
                    if (entry.jlptsenseiUrl != null) {
                        OutlinedButton(
                            onClick = { onOpenUrl(entry.jlptsenseiUrl) },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text("View on JLPTSensei")
                        }
                    }
                    if (entry.dojgUrl != null) {
                        OutlinedButton(
                            onClick = { onOpenUrl(entry.dojgUrl) },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text("Open DOJG Reference")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceBadge(sourceName: String) {
    val color = SOURCE_COLORS[sourceName] ?: Color.Gray
    val bgColor = SOURCE_BG_COLORS[sourceName] ?: DEFAULT_BG_COLOR
    val label = SOURCE_LABELS[sourceName] ?: "?"
    Surface(color = bgColor, shape = RoundedCornerShape(4.dp)) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            fontSize = 10.sp
        )
    }
}
