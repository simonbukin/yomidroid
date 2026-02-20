package com.yomidroid.ui

import android.content.DialogInterface
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yomidroid.config.BindableAction
import com.yomidroid.config.InputConfig
import com.yomidroid.config.InputConfigManager
import com.yomidroid.config.keyCodeToDisplayName
import com.yomidroid.service.YomidroidAccessibilityService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val configManager = remember { InputConfigManager(context) }
    var config by remember { mutableStateOf(configManager.getConfig()) }

    // Key capture dialog state
    var capturingAction by remember { mutableStateOf<BindableAction?>(null) }
    var capturingLayerKey by remember { mutableStateOf(false) }

    fun saveAndApply(newConfig: InputConfig) {
        config = newConfig
        configManager.saveConfig(newConfig)
        YomidroidAccessibilityService.instance?.reloadInputConfig()
    }

    // Key capture dialog
    if (capturingAction != null || capturingLayerKey) {
        val label = if (capturingLayerKey) "Layer Key" else capturingAction?.displayName ?: ""
        KeyCaptureDialog(
            actionName = label,
            onKeyCapture = { keyCode ->
                if (capturingLayerKey) {
                    saveAndApply(config.copy(layerKeyCode = keyCode))
                    capturingLayerKey = false
                } else {
                    capturingAction?.let { action ->
                        val newBindings = config.bindings.toMutableMap()
                        if (keyCode == 0) {
                            newBindings.remove(action)
                        } else {
                            newBindings[action] = keyCode
                        }
                        saveAndApply(config.copy(bindings = newBindings))
                    }
                    capturingAction = null
                }
            },
            onDismiss = {
                capturingAction = null
                capturingLayerKey = false
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Controls") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        configManager.resetToDefaults()
                        config = configManager.getConfig()
                        YomidroidAccessibilityService.instance?.reloadInputConfig()
                        Toast.makeText(context, "Reset to defaults", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reset")
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
                .padding(16.dp)
        ) {
            // Master toggle
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (config.enabled)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Hardware Controls",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Bind gamepad/keyboard buttons to Yomidroid actions",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.enabled,
                        onCheckedChange = { saveAndApply(config.copy(enabled = it)) }
                    )
                }
            }

            if (config.enabled) {
                Spacer(modifier = Modifier.height(8.dp))

                // Warning about accessibility service
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Text(
                        text = "If buttons aren't working, re-enable the Accessibility Service in system settings to apply key interception permissions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Layer key section
            Text(
                text = "Layer Key",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Hold this button to activate Yomidroid bindings. Prevents conflicts with game controls.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            BindingRow(
                label = "Layer Key",
                keyCode = config.layerKeyCode,
                onClick = { capturingLayerKey = true }
            )

            // Option to disable layer key (direct bindings)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        saveAndApply(config.copy(layerKeyCode = 0))
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = config.layerKeyCode == 0,
                    onClick = { saveAndApply(config.copy(layerKeyCode = 0)) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "No layer key (direct bindings)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Buttons are always active - may conflict with games",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action bindings grouped by category
            BindingSection(
                title = "Actions",
                actions = listOf(
                    BindableAction.SCAN,
                    BindableAction.DISMISS,
                    BindableAction.TOGGLE_CURSOR,
                    BindableAction.OPEN_APP
                ),
                bindings = config.bindings,
                onBindAction = { capturingAction = it }
            )

            Spacer(modifier = Modifier.height(16.dp))

            BindingSection(
                title = "Cursor Movement",
                subtitle = "Pixel-by-pixel cursor control (D-pad)",
                actions = listOf(
                    BindableAction.MOVE_UP,
                    BindableAction.MOVE_DOWN,
                    BindableAction.MOVE_LEFT,
                    BindableAction.MOVE_RIGHT
                ),
                bindings = config.bindings,
                onBindAction = { capturingAction = it }
            )

            Spacer(modifier = Modifier.height(16.dp))

            BindingSection(
                title = "Block Navigation",
                subtitle = "Jump between OCR text regions",
                actions = listOf(
                    BindableAction.BLOCK_UP,
                    BindableAction.BLOCK_DOWN,
                    BindableAction.BLOCK_LEFT,
                    BindableAction.BLOCK_RIGHT
                ),
                bindings = config.bindings,
                onBindAction = { capturingAction = it }
            )

            Spacer(modifier = Modifier.height(16.dp))

            BindingSection(
                title = "Popup Scroll",
                actions = listOf(
                    BindableAction.SCROLL_UP,
                    BindableAction.SCROLL_DOWN
                ),
                bindings = config.bindings,
                onBindAction = { capturingAction = it }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Sensitivity section
            Text(
                text = "Sensitivity",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Cursor speed
            Text(
                text = "Cursor Speed: ${config.cursorSpeed.toInt()}",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = config.cursorSpeed,
                onValueChange = { saveAndApply(config.copy(cursorSpeed = it)) },
                valueRange = 2f..30f,
                steps = 27
            )

            // Cursor acceleration toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Cursor Acceleration",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Speed ramps up while holding D-pad",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = config.cursorAcceleration,
                    onCheckedChange = { saveAndApply(config.copy(cursorAcceleration = it)) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Scroll speed
            Text(
                text = "Scroll Speed: ${config.scrollSpeed.toInt()}",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = config.scrollSpeed,
                onValueChange = { saveAndApply(config.copy(scrollSpeed = it)) },
                valueRange = 20f..200f,
                steps = 17
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun BindingSection(
    title: String,
    subtitle: String? = null,
    actions: List<BindableAction>,
    bindings: Map<BindableAction, Int>,
    onBindAction: (BindableAction) -> Unit
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
    if (subtitle != null) {
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(modifier = Modifier.height(8.dp))

    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            actions.forEachIndexed { index, action ->
                BindingRow(
                    label = action.displayName,
                    keyCode = bindings[action] ?: 0,
                    onClick = { onBindAction(action) }
                )
                if (index < actions.size - 1) {
                    Divider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun BindingRow(
    label: String,
    keyCode: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (keyCode != 0)
                MaterialTheme.colorScheme.secondaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.defaultMinSize(minWidth = 72.dp)
        ) {
            Text(
                text = keyCodeToDisplayName(keyCode),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun KeyCaptureDialog(
    actionName: String,
    onKeyCapture: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    DisposableEffect(Unit) {
        val dialog = android.app.AlertDialog.Builder(context)
            .setTitle("Bind: $actionName")
            .setMessage("Press any button on your controller or keyboard...\n\nSystem keys (Volume, Back, Home) cannot be bound.")
            .setPositiveButton("Cancel") { d, _ ->
                d.dismiss()
                onDismiss()
            }
            .setNegativeButton("Clear") { d, _ ->
                d.dismiss()
                onKeyCapture(0)
            }
            .setOnCancelListener { onDismiss() }
            .create()

        dialog.setOnKeyListener(DialogInterface.OnKeyListener { d, keyCode, event ->
            Log.d("Yomidroid", "KeyCaptureDialog: keyCode=$keyCode, action=${event.action}, source=0x${Integer.toHexString(event.source)}")
            if (event.action == KeyEvent.ACTION_DOWN) {
                // Let system keys pass through (BACK dismisses dialog naturally)
                if (keyCode in InputConfig.NEVER_CONSUME || keyCode == KeyEvent.KEYCODE_UNKNOWN) {
                    return@OnKeyListener false
                }
                onKeyCapture(keyCode)
                return@OnKeyListener true
            }
            // Consume ACTION_UP to prevent dialog navigation
            true
        })

        dialog.show()

        // Handle hat-switch D-pad (many gamepads report D-pad as MotionEvent, not KeyEvent)
        dialog.window?.decorView?.setOnGenericMotionListener { _, event ->
            if (event.source and android.view.InputDevice.SOURCE_JOYSTICK != 0 ||
                event.source and android.view.InputDevice.SOURCE_GAMEPAD != 0) {
                val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
                val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
                if (hatX != 0f || hatY != 0f) {
                    Log.d("Yomidroid", "KeyCaptureDialog MotionEvent: hatX=$hatX, hatY=$hatY, action=${event.action}")
                    val keyCode = when {
                        hatY < -0.5f -> KeyEvent.KEYCODE_DPAD_UP
                        hatY > 0.5f -> KeyEvent.KEYCODE_DPAD_DOWN
                        hatX < -0.5f -> KeyEvent.KEYCODE_DPAD_LEFT
                        hatX > 0.5f -> KeyEvent.KEYCODE_DPAD_RIGHT
                        else -> 0
                    }
                    if (keyCode != 0 && event.action == MotionEvent.ACTION_MOVE) {
                        onKeyCapture(keyCode)
                        return@setOnGenericMotionListener true
                    }
                }
            }
            false
        }

        onDispose {
            dialog.dismiss()
        }
    }
}
