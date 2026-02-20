package com.yomidroid

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.yomidroid.ui.settings.SettingsScreen
import com.yomidroid.ui.settings.DictionarySettingsScreen
import com.yomidroid.ui.settings.TranslationSettingsScreen
import com.yomidroid.ui.theme.YomidroidTheme
import com.yomidroid.ui.tools.DictionarySearchScreen
import com.yomidroid.ui.tools.GrammarAnalyzerScreen
import com.yomidroid.ui.tools.GrammarLibraryScreen
import com.yomidroid.ui.tools.ToolsScreen
import com.yomidroid.ui.tools.TranslationToolScreen

sealed class Screen(val route: String) {
    object History : Screen("history")
    data class HistoryDetail(val id: Long) : Screen("history_detail")
    object Tools : Screen("tools")
    object GrammarAnalyzer : Screen("grammar_analyzer")
    object GrammarLibrary : Screen("grammar_library")
    object TranslationTool : Screen("translation_tool")
    object DictionarySearch : Screen("dictionary_search")
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
                var currentScreen by remember { mutableStateOf<Screen>(Screen.Tools) }

                when (currentScreen) {
                    Screen.AnkiSettings -> {
                        AnkiSettingsScreen(onBack = { currentScreen = Screen.Settings })
                    }
                    Screen.ColorSettings -> {
                        ColorSettingsScreen(onBack = { currentScreen = Screen.Settings })
                    }
                    Screen.OcrSettings -> {
                        OcrSettingsScreen(onBack = { currentScreen = Screen.Settings })
                    }
                    Screen.TranslationSettings -> {
                        TranslationSettingsScreen(onNavigateBack = { currentScreen = Screen.Settings })
                    }
                    Screen.InputSettings -> {
                        InputSettingsScreen(onBack = { currentScreen = Screen.Settings })
                    }
                    Screen.DictionarySettings -> {
                        DictionarySettingsScreen(onBack = { currentScreen = Screen.Settings })
                    }
                    Screen.GrammarAnalyzer -> {
                        GrammarAnalyzerScreen(onBack = { currentScreen = Screen.Tools })
                    }
                    Screen.GrammarLibrary -> {
                        GrammarLibraryScreen(onBack = { currentScreen = Screen.Tools })
                    }
                    Screen.TranslationTool -> {
                        TranslationToolScreen(onBack = { currentScreen = Screen.Tools })
                    }
                    Screen.DictionarySearch -> {
                        DictionarySearchScreen(onBack = { currentScreen = Screen.Tools })
                    }
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
                            onOpenAccessibilitySettings = { openAccessibilitySettings() }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppContent(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentScreen == Screen.History,
                    onClick = { onNavigate(Screen.History) },
                    icon = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_history),
                            contentDescription = "History"
                        )
                    },
                    label = { Text("History") }
                )
                NavigationBarItem(
                    selected = currentScreen == Screen.Tools,
                    onClick = { onNavigate(Screen.Tools) },
                    icon = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_tools),
                            contentDescription = "Tools"
                        )
                    },
                    label = { Text("Tools") }
                )
                NavigationBarItem(
                    selected = currentScreen == Screen.Settings,
                    onClick = { onNavigate(Screen.Settings) },
                    icon = {
                        Icon(
                            if (currentScreen == Screen.Settings) Icons.Filled.Settings else Icons.Outlined.Settings,
                            contentDescription = "Settings"
                        )
                    },
                    label = { Text("Settings") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (currentScreen) {
                Screen.History -> HistoryScreen(
                    onNavigateToDetail = { id -> onNavigate(Screen.HistoryDetail(id)) }
                )
                Screen.Tools -> ToolsScreen(
                    onNavigateToGrammar = { onNavigate(Screen.GrammarAnalyzer) },
                    onNavigateToGrammarLibrary = { onNavigate(Screen.GrammarLibrary) },
                    onNavigateToTranslation = { onNavigate(Screen.TranslationTool) },
                    onNavigateToDictionarySearch = { onNavigate(Screen.DictionarySearch) }
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
                else -> ToolsScreen(
                    onNavigateToGrammar = { onNavigate(Screen.GrammarAnalyzer) },
                    onNavigateToGrammarLibrary = { onNavigate(Screen.GrammarLibrary) },
                    onNavigateToTranslation = { onNavigate(Screen.TranslationTool) },
                    onNavigateToDictionarySearch = { onNavigate(Screen.DictionarySearch) }
                )
            }
        }
    }
}

