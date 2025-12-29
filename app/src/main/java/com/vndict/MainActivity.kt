package com.vndict

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.vndict.config.ColorConfigManager
import com.vndict.data.DictionaryLoader
import com.vndict.service.VNDictAccessibilityService
import com.vndict.ui.AnkiSettingsScreen
import com.vndict.ui.ColorSettingsScreen
import com.vndict.ui.theme.VNDictTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val colorConfigManager = remember { ColorConfigManager(context) }
            var colorConfig by remember { mutableStateOf(colorConfigManager.getConfig()) }

            // Reload colors when returning to MainActivity
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        colorConfig = colorConfigManager.getConfig()
                        // Also reload colors in the service if it's running
                        VNDictAccessibilityService.instance?.loadColors()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            VNDictTheme(accentColor = Color(colorConfig.accentColor)) {
                var showAnkiSettings by remember { mutableStateOf(false) }
                var showColorSettings by remember { mutableStateOf(false) }

                when {
                    showAnkiSettings -> {
                        AnkiSettingsScreen(
                            onBack = { showAnkiSettings = false }
                        )
                    }
                    showColorSettings -> {
                        ColorSettingsScreen(
                            onBack = { showColorSettings = false }
                        )
                    }
                    else -> {
                        MainScreen(
                            onOpenAccessibilitySettings = { openAccessibilitySettings() },
                            onOpenAnkiSettings = { showAnkiSettings = true },
                            onOpenColorSettings = { showColorSettings = true }
                        )
                    }
                }
            }
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "$packageName/${VNDictAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return !TextUtils.isEmpty(enabledServices) && enabledServices.contains(serviceName)
    }
}

@Composable
fun MainScreen(
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAnkiSettings: () -> Unit,
    onOpenColorSettings: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isAccessibilityEnabled by remember { mutableStateOf(false) }
    var isDictionaryLoaded by remember { mutableStateOf<Boolean?>(null) }
    var isLoadingDictionary by remember { mutableStateOf(false) }

    // Check accessibility status when screen resumes (user might have just enabled it)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val serviceName = "${context.packageName}/${VNDictAccessibilityService::class.java.canonicalName}"
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                isAccessibilityEnabled = !TextUtils.isEmpty(enabledServices) && enabledServices.contains(serviceName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Check dictionary status on launch
    LaunchedEffect(Unit) {
        isLoadingDictionary = true
        isDictionaryLoaded = DictionaryLoader.ensureDictionaryLoaded(context)
        isLoadingDictionary = false
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "VN Dict",
                style = MaterialTheme.typography.headlineLarge
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Japanese OCR Dictionary for Visual Novels",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Dictionary status
            when {
                isLoadingDictionary -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Loading dictionary...")
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                isDictionaryLoaded == false -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = "Dictionary not found",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Place dictionary.db in app assets and rebuild.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                isDictionaryLoaded == true -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
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

            // Accessibility service status
            if (isAccessibilityEnabled) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Accessibility Service Enabled",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "The floating button should be visible. Open your VN and tap the button to scan text!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Accessibility Service Required",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "VNDict needs accessibility permission to capture the screen for OCR.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onOpenAccessibilitySettings,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Enable in Settings")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Settings > Accessibility > VN Dict > Enable",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Anki settings button
            Button(
                onClick = onOpenAnkiSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Configure Anki Export")
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Color settings button
            Button(
                onClick = onOpenColorSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Customize Colors")
            }

            Spacer(modifier = Modifier.height(12.dp))

            // History button (future feature)
            OutlinedButton(
                onClick = { /* TODO: Navigate to history */ },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("View Lookup History")
            }
        }
    }
}
