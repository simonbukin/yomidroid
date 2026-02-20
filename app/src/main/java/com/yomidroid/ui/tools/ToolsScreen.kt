package com.yomidroid.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.yomidroid.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    onNavigateToGrammar: () -> Unit,
    onNavigateToGrammarLibrary: () -> Unit,
    onNavigateToTranslation: () -> Unit,
    onNavigateToDictionarySearch: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Tools") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Analysis Section
            Text(
                text = "Analysis",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            ToolItem(
                title = "Grammar Analyzer",
                subtitle = "Analyze morphology, bunsetsu, and grammar patterns",
                onClick = onNavigateToGrammar
            )

            ToolItem(
                title = "Translation",
                subtitle = "Translate OCR'd text with natural/literal/interlinear modes",
                onClick = onNavigateToTranslation
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Reference Section
            Text(
                text = "Reference",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            ToolItem(
                title = "Dictionary Search",
                subtitle = "Search the dictionary manually by word or reading",
                onClick = onNavigateToDictionarySearch
            )

            ToolItem(
                title = "Grammar Library",
                subtitle = "Browse JLPT grammar with GameGengo video lessons",
                onClick = onNavigateToGrammarLibrary
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ToolItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
