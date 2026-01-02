package com.yomidroid

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.yomidroid.config.ColorConfigManager
import com.yomidroid.data.DictionaryLoader
import com.yomidroid.service.YomidroidAccessibilityService
import com.yomidroid.ui.AnkiSettingsScreen
import com.yomidroid.ui.ColorSettingsScreen
import com.yomidroid.ui.history.HistoryDetailScreen
import com.yomidroid.ui.history.HistoryScreen
import com.yomidroid.ui.settings.SettingsScreen
import com.yomidroid.ui.theme.YomidroidTheme
import com.yomidroid.ui.tools.GrammarAnalyzerScreen
import com.yomidroid.ui.tools.ToolsScreen

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object History : Screen("history")
    data class HistoryDetail(val id: Long) : Screen("history_detail")
    object Tools : Screen("tools")
    object GrammarAnalyzer : Screen("grammar_analyzer")
    object Settings : Screen("settings")
    object AnkiSettings : Screen("anki_settings")
    object ColorSettings : Screen("color_settings")
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
                var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }

                when (currentScreen) {
                    Screen.AnkiSettings -> {
                        AnkiSettingsScreen(onBack = { currentScreen = Screen.Settings })
                    }
                    Screen.ColorSettings -> {
                        ColorSettingsScreen(onBack = { currentScreen = Screen.Settings })
                    }
                    Screen.GrammarAnalyzer -> {
                        GrammarAnalyzerScreen(onBack = { currentScreen = Screen.Tools })
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
                    selected = currentScreen == Screen.Home,
                    onClick = { onNavigate(Screen.Home) },
                    icon = {
                        Icon(
                            if (currentScreen == Screen.Home) Icons.Filled.Home else Icons.Outlined.Home,
                            contentDescription = "Home"
                        )
                    },
                    label = { Text("Home") }
                )
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
                Screen.Home -> HomeScreen(onOpenAccessibilitySettings = onOpenAccessibilitySettings)
                Screen.History -> HistoryScreen(
                    onNavigateToDetail = { id -> onNavigate(Screen.HistoryDetail(id)) }
                )
                Screen.Tools -> ToolsScreen(
                    onNavigateToGrammar = { onNavigate(Screen.GrammarAnalyzer) }
                )
                Screen.Settings -> SettingsScreen(
                    onOpenAnkiSettings = { onNavigate(Screen.AnkiSettings) },
                    onOpenColorSettings = { onNavigate(Screen.ColorSettings) },
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings
                )
                else -> HomeScreen(onOpenAccessibilitySettings = onOpenAccessibilitySettings)
            }
        }
    }
}

@Composable
fun HomeScreen(
    onOpenAccessibilitySettings: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isAccessibilityEnabled by remember { mutableStateOf(false) }
    var isDictionaryLoaded by remember { mutableStateOf<Boolean?>(null) }
    var isLoadingDictionary by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val serviceName = "${context.packageName}/${YomidroidAccessibilityService::class.java.canonicalName}"
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                isAccessibilityEnabled = !TextUtils.isEmpty(enabledServices) && enabledServices.contains(serviceName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        isLoadingDictionary = true
        isDictionaryLoaded = DictionaryLoader.ensureDictionaryLoaded(context)
        isLoadingDictionary = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Yomidroid",
            style = MaterialTheme.typography.headlineLarge
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Japanese OCR Dictionary",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Dictionary status
        when {
            isLoadingDictionary -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Loading dictionary...")
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            isDictionaryLoaded == false -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = "Dictionary not found", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "Place dictionary.db in app assets and rebuild.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            isDictionaryLoaded == true -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Dictionary loaded",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // Accessibility status
        if (isAccessibilityEnabled) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Ready to scan!",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "The floating button should be visible. Open any app and tap the button to scan Japanese text.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Accessibility Service Required", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Yomidroid needs accessibility permission to capture the screen for OCR.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onOpenAccessibilitySettings, modifier = Modifier.fillMaxWidth()) {
                        Text("Enable in Settings")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Settings > Accessibility > Yomidroid > Enable",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
