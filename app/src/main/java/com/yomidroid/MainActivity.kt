package com.yomidroid

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.yomidroid.config.ColorConfigManager
import com.yomidroid.service.YomidroidAccessibilityService
import com.yomidroid.ui.AnkiSettingsScreen
import com.yomidroid.ui.ColorSettingsScreen
import com.yomidroid.ui.InputSettingsScreen
import com.yomidroid.ui.OcrSettingsScreen
import com.yomidroid.ui.history.HistoryDetailScreen
import com.yomidroid.ui.history.HistoryScreen
import com.yomidroid.ui.history.HistoryScreenState
import com.yomidroid.ui.history.rememberHistoryScreenState
import com.yomidroid.ui.library.LibraryScreen
import com.yomidroid.ui.now.NowScreen
import com.yomidroid.ui.search.SearchScreen
import com.yomidroid.ui.settings.DictionarySettingsScreen
import com.yomidroid.ui.settings.SettingsScreen
import com.yomidroid.ui.settings.TranslationSettingsScreen
import com.yomidroid.ui.theme.YomidroidTheme

sealed class Screen(val route: String) {
    object Now : Screen("now")
    object Search : Screen("search")
    object Library : Screen("library")
    object History : Screen("history")
    data class HistoryDetail(val id: Long) : Screen("history_detail")
    object Settings : Screen("settings")
    object AnkiSettings : Screen("anki_settings")
    object ColorSettings : Screen("color_settings")
    object OcrSettings : Screen("ocr_settings")
    object TranslationSettings : Screen("translation_settings")
    object InputSettings : Screen("input_settings")
    object DictionarySettings : Screen("dictionary_settings")
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val colorConfigManager = remember { ColorConfigManager(context) }
            var colorConfig by remember { mutableStateOf(colorConfigManager.getConfig()) }

            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        colorConfig = colorConfigManager.getConfig()
                        YomidroidAccessibilityService.instance?.loadColors()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            YomidroidTheme(accentColor = Color(colorConfig.accentColor)) {
                var currentScreen by remember { mutableStateOf<Screen>(Screen.Now) }
                val historyState = rememberHistoryScreenState()

                when (currentScreen) {
                    Screen.AnkiSettings -> AnkiSettingsScreen(onBack = { currentScreen = Screen.Settings })
                    Screen.ColorSettings -> ColorSettingsScreen(onBack = { currentScreen = Screen.Settings })
                    Screen.OcrSettings -> OcrSettingsScreen(onBack = { currentScreen = Screen.Settings })
                    Screen.TranslationSettings -> TranslationSettingsScreen(onNavigateBack = { currentScreen = Screen.Settings })
                    Screen.InputSettings -> InputSettingsScreen(onBack = { currentScreen = Screen.Settings })
                    Screen.DictionarySettings -> DictionarySettingsScreen(onBack = { currentScreen = Screen.Settings })
                    is Screen.HistoryDetail -> {
                        val detailScreen = currentScreen as Screen.HistoryDetail
                        HistoryDetailScreen(
                            historyId = detailScreen.id,
                            onBack = { currentScreen = Screen.History }
                        )
                    }
                    else -> {
                        MainAppContent(
                            currentScreen = currentScreen,
                            onNavigate = { currentScreen = it },
                            onOpenAccessibilitySettings = { openAccessibilitySettings() },
                            historyState = historyState
                        )
                    }
                }
            }
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}

private data class TopDest(
    val screen: Screen,
    val label: String,
    val iconSelected: ImageVector? = null,
    val iconUnselected: ImageVector? = null,
    val iconDrawable: Int? = null
)

private val TOP_DESTINATIONS = listOf(
    TopDest(Screen.Now, "Now",
        iconSelected = Icons.Filled.Translate, iconUnselected = Icons.Outlined.Translate),
    TopDest(Screen.Search, "Search",
        iconSelected = Icons.Filled.Search, iconUnselected = Icons.Outlined.Search),
    TopDest(Screen.Library, "Library",
        iconSelected = Icons.Filled.MenuBook, iconUnselected = Icons.Outlined.MenuBook),
    TopDest(Screen.History, "History", iconDrawable = R.drawable.ic_history),
    TopDest(Screen.Settings, "Settings",
        iconSelected = Icons.Filled.Settings, iconUnselected = Icons.Outlined.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppContent(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    historyState: HistoryScreenState
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(
        bottomBar = {
            if (!isLandscape) {
                NavigationBar {
                    TOP_DESTINATIONS.forEach { dest ->
                        NavigationBarItem(
                            selected = currentScreen == dest.screen,
                            onClick = { onNavigate(dest.screen) },
                            icon = { DestIcon(dest, currentScreen == dest.screen) },
                            label = { Text(dest.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (currentScreen) {
                Screen.Now -> NowScreen()
                Screen.Search -> SearchScreen()
                Screen.Library -> LibraryScreen()
                Screen.History -> HistoryScreen(
                    historyState = historyState,
                    onNavigateToDetail = { id -> onNavigate(Screen.HistoryDetail(id)) }
                )
                Screen.Settings -> SettingsScreen(
                    onOpenAnkiSettings = { onNavigate(Screen.AnkiSettings) },
                    onOpenColorSettings = { onNavigate(Screen.ColorSettings) },
                    onOpenOcrSettings = { onNavigate(Screen.OcrSettings) },
                    onOpenTranslationSettings = { onNavigate(Screen.TranslationSettings) },
                    onOpenInputSettings = { onNavigate(Screen.InputSettings) },
                    onOpenDictionarySettings = { onNavigate(Screen.DictionarySettings) },
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings
                )
                else -> NowScreen()
            }

            if (isLandscape) {
                FloatingNavPill(
                    currentScreen = currentScreen,
                    onNavigate = onNavigate,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                )
            }
        }
    }
}

@Composable
private fun DestIcon(dest: TopDest, selected: Boolean) {
    when {
        dest.iconDrawable != null ->
            Icon(
                painter = painterResource(id = dest.iconDrawable),
                contentDescription = dest.label
            )
        else -> {
            val icon = if (selected) dest.iconSelected else dest.iconUnselected
            icon?.let { Icon(it, contentDescription = dest.label) }
        }
    }
}

@Composable
private fun FloatingNavPill(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TOP_DESTINATIONS.forEach { dest ->
                PillNavItem(
                    selected = currentScreen == dest.screen,
                    onClick = { onNavigate(dest.screen) }
                ) { tint ->
                    when {
                        dest.iconDrawable != null -> Icon(
                            painter = painterResource(id = dest.iconDrawable),
                            contentDescription = dest.label,
                            tint = tint
                        )
                        else -> {
                            val icon = if (currentScreen == dest.screen) dest.iconSelected else dest.iconUnselected
                            icon?.let { Icon(it, contentDescription = dest.label, tint = tint) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PillNavItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable (tint: Color) -> Unit
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
            icon(fg)
        }
    }
}
