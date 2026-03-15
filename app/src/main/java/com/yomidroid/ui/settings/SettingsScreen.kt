package com.yomidroid.ui.settings

import android.provider.Settings
import android.text.TextUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.yomidroid.config.ColorConfigManager
import com.yomidroid.service.YomidroidAccessibilityService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenAnkiSettings: () -> Unit,
    onOpenColorSettings: () -> Unit,
    onOpenOcrSettings: () -> Unit,
    onOpenTranslationSettings: () -> Unit,
    onOpenInputSettings: () -> Unit,
    onOpenDictionarySettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isAccessibilityEnabled by remember { mutableStateOf(false) }
    val colorConfigManager = remember { ColorConfigManager(context) }
    var isFabEnabled by remember { mutableStateOf(colorConfigManager.isFabEnabled()) }
    var isDecoupledMode by remember { mutableStateOf(colorConfigManager.isDecoupledMode()) }

    // Check accessibility status on resume
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

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Permissions Section
            Text(
                text = "Permissions",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            SettingsItem(
                title = "Accessibility Service",
                subtitle = if (isAccessibilityEnabled) "Enabled" else "Required for screen capture",
                onClick = onOpenAccessibilitySettings,
                trailing = {
                    if (isAccessibilityEnabled) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Enabled",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Required",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            )

            Divider(modifier = Modifier.padding(horizontal = 16.dp))

            // Recognition Section
            Text(
                text = "Recognition",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            SettingsItem(
                title = "Dictionaries",
                subtitle = "Manage dictionaries and frequency lists",
                onClick = onOpenDictionarySettings
            )

            SettingsItem(
                title = "OCR Engine",
                subtitle = "Choose between on-device or cloud OCR",
                onClick = onOpenOcrSettings
            )

            SettingsItem(
                title = "Translation",
                subtitle = "Configure remote API and on-device translation",
                onClick = onOpenTranslationSettings
            )

            Divider(modifier = Modifier.padding(horizontal = 16.dp))

            // Export Section
            Text(
                text = "Export",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            SettingsItem(
                title = "Anki Export",
                subtitle = "Configure deck, note type, and field mappings",
                onClick = onOpenAnkiSettings
            )

            Divider(modifier = Modifier.padding(horizontal = 16.dp))

            // Appearance Section
            Text(
                text = "Appearance",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            SettingsItem(
                title = "Colors",
                subtitle = "Customize FAB, highlights, and accent colors",
                onClick = onOpenColorSettings
            )

            // FAB visibility toggle (in-app alternative to Quick Settings tile)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Show Floating Button",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Toggle the FAB overlay on/off",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isFabEnabled,
                    onCheckedChange = { enabled ->
                        isFabEnabled = enabled
                        colorConfigManager.setFabEnabled(enabled)
                        YomidroidAccessibilityService.instance?.updateFabVisibility()
                    }
                )
            }

            // Decoupled mode toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Decoupled Mode",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Show definitions in app instead of overlay",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isDecoupledMode,
                    onCheckedChange = { enabled ->
                        isDecoupledMode = enabled
                        colorConfigManager.setDecoupledMode(enabled)
                        YomidroidAccessibilityService.instance?.loadColors()
                    }
                )
            }

            Divider(modifier = Modifier.padding(horizontal = 16.dp))

            // Controls Section
            Text(
                text = "Controls",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            SettingsItem(
                title = "Hardware Controls",
                subtitle = "Bind gamepad buttons and D-pad to actions",
                onClick = onOpenInputSettings
            )

            Divider(modifier = Modifier.padding(horizontal = 16.dp))

            // About Section
            Text(
                text = "About",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Yomidroid",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Japanese OCR Dictionary",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tap the floating button to capture text, then tap on recognized Japanese characters to look up words.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null
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
        if (trailing != null) {
            trailing()
        } else {
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
