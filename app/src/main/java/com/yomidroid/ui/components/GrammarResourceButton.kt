package com.yomidroid.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yomidroid.grammar.GrammarResource

/** Label shown on a resource button. */
fun grammarResourceLabel(source: String): String = when (source) {
    "gamegengo" -> "Watch GameGengo Video"
    "dojg" -> "Open DOJG"
    "taekim" -> "Open Tae Kim"
    "imabi" -> "Open Imabi"
    "hjg" -> "Open HJG"
    "donnatoki" -> "Open Pattern Dictionary"
    "masterref" -> "Open Master Reference"
    else -> "Open ${source.replaceFirstChar { it.uppercase() }}"
}

/** Short source label for source-pill rows and inline parse-tab buttons. */
fun grammarSourceShortLabel(source: String): String = when (source) {
    "gamegengo" -> "GameGengo"
    "dojg" -> "DOJG"
    "taekim" -> "Tae Kim"
    "imabi" -> "Imabi"
    "hjg" -> "HJG"
    "donnatoki" -> "文型辞典"
    "masterref" -> "Master Ref"
    else -> source.replaceFirstChar { it.uppercase() }
}

/** Accent color associated with a source. Used for badges and inline buttons. */
fun grammarSourceColor(source: String): Color = when (source) {
    "gamegengo" -> Color(0xFFFF0000)
    "dojg" -> Color(0xFF4CAF50)
    "taekim" -> Color(0xFF7E57C2)
    "imabi" -> Color(0xFFFF9800)
    "hjg" -> Color(0xFF00897B)
    "donnatoki" -> Color(0xFFE91E63)
    "masterref" -> Color(0xFF607D8B)
    else -> Color(0xFF9E9E9E)
}

/**
 * Full-width outlined button for a single grammar resource link.
 * Use in expandable cards (Search, Library).
 */
@Composable
fun GrammarResourceButton(
    resource: GrammarResource,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = { onOpenUrl(resource.url) },
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (resource.source == "gamegengo") {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = grammarSourceColor(resource.source)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(grammarResourceLabel(resource.source), fontSize = 13.sp)
    }
}

/**
 * Render a row of small colored pills, one per unique source on `resources`.
 * Used in card headers across Search, Library, and the parse-tab DOJG section.
 * Sources are sorted by the resources' existing order (already prioritized at load time).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GrammarSourcePills(
    resources: List<GrammarResource>,
    modifier: Modifier = Modifier
) {
    if (resources.isEmpty()) return
    val seen = mutableSetOf<String>()
    val unique = resources.filter { seen.add(it.source) }
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        unique.forEach { r ->
            val accent = grammarSourceColor(r.source)
            Surface(
                color = accent.copy(alpha = 0.15f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = grammarSourceShortLabel(r.source),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = accent,
                    fontSize = 10.sp
                )
            }
        }
    }
}

/**
 * Compact text button for inline rows where space is tight (parse-tab DOJG details).
 */
@Composable
fun GrammarResourceTextButton(
    resource: GrammarResource,
    onOpenUrl: (String) -> Unit
) {
    TextButton(
        onClick = { onOpenUrl(resource.url) },
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(grammarSourceShortLabel(resource.source), color = grammarSourceColor(resource.source))
    }
}
