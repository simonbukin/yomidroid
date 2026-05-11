@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.yomidroid.ui.kanji

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yomidroid.data.DictionaryDb
import com.yomidroid.dictionary.DictionaryEngine
import com.yomidroid.dictionary.DictionaryEntry
import com.yomidroid.kanji.KanjiInfo
import com.yomidroid.kanji.KanjiLibrary
import com.yomidroid.tts.TtsManager
import com.yomidroid.ui.components.DictionaryEntryWebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun KanjiDetailScreen(
    character: String,
    onBack: () -> Unit,
    onOpenKanji: (String) -> Unit,
) {
    val context = LocalContext.current
    val library = remember { KanjiLibrary.getInstance(context) }
    val ttsManager = remember { TtsManager.getInstance(context) }

    val info = remember(character) { library.get(character) }

    var exampleEntries by remember(character) { mutableStateOf<List<DictionaryEntry>>(emptyList()) }
    var exampleLoading by remember(character) { mutableStateOf(true) }

    LaunchedEffect(character) {
        exampleLoading = true
        val entries = withContext(Dispatchers.IO) {
            val db = DictionaryDb.getInstance(context)
            val terms = db.findWordsContainingKanji(character, limit = 10)
            terms.mapNotNull { td ->
                // Re-look-up each term so we get rich entries (pitch, frequency, glosses, etc.)
                DictionaryEngine(context).searchTerm(td.expression).firstOrNull()
            }
        }
        exampleEntries = entries
        exampleLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(character) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (info == null) {
                Text("Kanji not found: $character")
                return@Column
            }

            HeaderCard(info = info, onSpeak = { ttsManager.speak(character) })
            ReadingsCard(info = info, onSpeak = { ttsManager.speak(it) })
            MetaCard(info = info)
            ExampleWordsCard(
                loading = exampleLoading,
                entries = exampleEntries,
                onOpenKanji = onOpenKanji,
            )
        }
    }
}

@Composable
private fun HeaderCard(info: KanjiInfo, onSpeak: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = info.character,
                fontSize = 96.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (info.meanings.isNotEmpty()) {
                    Text(
                        text = info.meanings.joinToString(", "),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    KanjiLibrary.jlptLabel(info.jlpt)?.let { BadgeChip(it) }
                    KanjiLibrary.gradeLabel(info.grade)?.let { BadgeChip(it) }
                    BadgeChip("${info.strokeCount} strokes")
                }
            }
            IconButton(onClick = onSpeak) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Speak",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun BadgeChip(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun ReadingsCard(info: KanjiInfo, onSpeak: (String) -> Unit) {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Readings",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            if (info.onReadings.isNotEmpty()) {
                ReadingRow(label = "On", readings = info.onReadings, onSpeak = onSpeak)
            }
            if (info.kunReadings.isNotEmpty()) {
                ReadingRow(label = "Kun", readings = info.kunReadings, onSpeak = onSpeak)
            }
            if (info.nameReadings.isNotEmpty()) {
                ReadingRow(label = "Name", readings = info.nameReadings, onSpeak = onSpeak)
            }
            if (info.onReadings.isEmpty() && info.kunReadings.isEmpty() && info.nameReadings.isEmpty()) {
                Text(
                    text = "No readings recorded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ReadingRow(label: String, readings: List<String>, onSpeak: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            readings.forEach { reading ->
                AssistChip(
                    onClick = { onSpeak(reading) },
                    label = { Text(reading) },
                    leadingIcon = {
                        Icon(
                            Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = null,
                            modifier = Modifier.size(AssistChipDefaults.IconSize)
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun MetaCard(info: KanjiInfo) {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Details",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            MetaRow("Strokes", info.strokeCount.toString())
            KanjiLibrary.jlptLabel(info.jlpt)?.let { MetaRow("JLPT", it) }
            KanjiLibrary.gradeLabel(info.grade)?.let { MetaRow("Grade", it) }
            info.freqMainichi?.let { MetaRow("Frequency", "#$it (Mainichi)") }
            info.heisigEn?.let { MetaRow("Heisig", it) }
            MetaRow("Unicode", "U+${info.character.codePointAt(0).toString(16).uppercase()}")
        }
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp)
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ExampleWordsCard(
    loading: Boolean,
    entries: List<DictionaryEntry>,
    onOpenKanji: (String) -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Example words",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            when {
                loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Searching installed dictionaries…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                entries.isEmpty() -> {
                    Text(
                        text = "No example words found. Install a Japanese dictionary in Settings → Dictionaries to populate this section.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    DictionaryEntryWebView(
                        entries = entries,
                        onOpenKanji = onOpenKanji,
                    )
                }
            }
        }
    }
}
