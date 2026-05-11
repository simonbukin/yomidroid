package com.yomidroid.ui.kanji

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yomidroid.kanji.KanjiInfo
import com.yomidroid.kanji.KanjiLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class KanjiFilter(val label: String) {
    All("All"),
    N5("JLPT N5"),
    N4("JLPT N4"),
    N3("JLPT N3"),
    N2("JLPT N2"),
    N1("JLPT N1"),
    Joyo("Jōyō"),
    Grade1("Grade 1"),
    Grade2("Grade 2"),
    Grade3("Grade 3"),
    Grade4("Grade 4"),
    Grade5("Grade 5"),
    Grade6("Grade 6"),
    Jinmeiyo("Jinmeiyō"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KanjiGridScreen(onOpenKanji: (String) -> Unit) {
    val context = LocalContext.current
    val library = remember { KanjiLibrary.getInstance(context) }

    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(KanjiFilter.All) }
    var displayed by remember { mutableStateOf<List<KanjiInfo>>(emptyList()) }
    var totalCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(query, filter) {
        val q = query.trim()
        val base = withContext(Dispatchers.Default) {
            when (filter) {
                KanjiFilter.All -> library.all()
                KanjiFilter.N5 -> library.byJlpt(5)
                KanjiFilter.N4 -> library.byJlpt(4)
                KanjiFilter.N3 -> library.byJlpt(3)
                KanjiFilter.N2 -> library.byJlpt(2)
                KanjiFilter.N1 -> library.byJlpt(1)
                KanjiFilter.Joyo -> library.all().filter { it.grade != null && it.grade <= 8 }
                KanjiFilter.Grade1 -> library.byGrade(1)
                KanjiFilter.Grade2 -> library.byGrade(2)
                KanjiFilter.Grade3 -> library.byGrade(3)
                KanjiFilter.Grade4 -> library.byGrade(4)
                KanjiFilter.Grade5 -> library.byGrade(5)
                KanjiFilter.Grade6 -> library.byGrade(6)
                KanjiFilter.Jinmeiyo -> library.all().filter { it.grade == 9 || it.grade == 10 }
            }
        }
        val filtered = if (q.isEmpty()) base else withContext(Dispatchers.Default) {
            val searched = library.search(q).toSet()
            base.filter { it in searched }.ifEmpty { library.search(q) }
        }
        displayed = filtered
        totalCount = filtered.size
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Kanji") }) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search by meaning, reading, or kanji") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                KanjiFilter.values().forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(f.label) }
                    )
                }
            }

            Text(
                text = "$totalCount kanji",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 56.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(displayed, key = { it.character }) { info ->
                    KanjiCell(info = info, onClick = { onOpenKanji(info.character) })
                }
            }
        }
    }
}

@Composable
private fun KanjiCell(info: KanjiInfo, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp)),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
        onClick = onClick
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = info.character,
                fontSize = 30.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
