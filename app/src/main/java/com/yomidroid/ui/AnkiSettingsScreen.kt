package com.yomidroid.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
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
import com.yomidroid.anki.AnkiConfig
import com.yomidroid.anki.AnkiConfigManager
import com.yomidroid.anki.AnkiDroidExporter
import com.yomidroid.anki.YomidroidField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnkiSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val exporter = remember { AnkiDroidExporter(context) }
    val configManager = remember { AnkiConfigManager(context) }

    var isAnkiInstalled by remember { mutableStateOf(exporter.isAnkiDroidInstalled()) }
    var hasPermission by remember { mutableStateOf(exporter.hasPermission()) }
    var decks by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var models by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var modelFields by remember { mutableStateOf<List<String>>(emptyList()) }

    var selectedDeckId by remember { mutableLongStateOf(-1L) }
    var selectedDeckName by remember { mutableStateOf("") }
    var selectedModelId by remember { mutableLongStateOf(-1L) }
    var selectedModelName by remember { mutableStateOf("") }
    var fieldMappings by remember { mutableStateOf<Map<YomidroidField, String>>(emptyMap()) }
    var duplicateCheckField by remember { mutableStateOf(YomidroidField.EXPRESSION) }

    var isLoading by remember { mutableStateOf(true) }
    var deckDropdownExpanded by remember { mutableStateOf(false) }
    var modelDropdownExpanded by remember { mutableStateOf(false) }
    var duplicateFieldDropdownExpanded by remember { mutableStateOf(false) }

    // Permission request launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted || exporter.hasPermission()
        if (hasPermission) {
            // Reload data after permission granted
            decks = exporter.getDecks()
            models = exporter.getModels()
        }
    }

    // Function to load data
    fun loadData() {
        isLoading = true
        isAnkiInstalled = exporter.isAnkiDroidInstalled()
        hasPermission = exporter.hasPermission()

        if (isAnkiInstalled && hasPermission) {
            decks = exporter.getDecks()
            models = exporter.getModels()

            // Load saved config
            val config = configManager.getConfig()
            if (config.deckId > 0) {
                selectedDeckId = config.deckId
                selectedDeckName = config.deckName
            }
            if (config.modelId > 0) {
                selectedModelId = config.modelId
                selectedModelName = config.modelName
                modelFields = exporter.getModelFields(config.modelId)
            }
            fieldMappings = config.fieldMappings
            duplicateCheckField = config.duplicateCheckField
        }
        isLoading = false
    }

    // Reload when screen resumes (user might have granted permission in settings)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                loadData()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Load initial state
    LaunchedEffect(Unit) {
        loadData()
    }

    // Update fields when model changes
    LaunchedEffect(selectedModelId) {
        if (selectedModelId > 0) {
            modelFields = exporter.getModelFields(selectedModelId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Anki Export Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Refresh button
                    IconButton(onClick = { loadData() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    // Save button
                    IconButton(
                        onClick = {
                            val config = AnkiConfig(
                                deckId = selectedDeckId,
                                deckName = selectedDeckName,
                                modelId = selectedModelId,
                                modelName = selectedModelName,
                                fieldMappings = fieldMappings,
                                duplicateCheckField = duplicateCheckField
                            )
                            configManager.saveConfig(config)
                            Toast.makeText(context, "Settings saved", Toast.LENGTH_SHORT).show()
                            onBack()
                        },
                        enabled = selectedDeckId > 0 && selectedModelId > 0 && fieldMappings.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "Save")
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (!isAnkiInstalled) {
            // AnkiDroid not installed
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "AnkiDroid Not Installed",
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Install AnkiDroid from the Play Store to enable flashcard export.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = {
                        context.startActivity(exporter.getPlayStoreIntent())
                    }
                ) {
                    Text("Open Play Store")
                }
            }
        } else if (!hasPermission) {
            // AnkiDroid installed but permission not granted
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.tertiary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Enable AnkiDroid API",
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Yomidroid needs API access in AnkiDroid to read your decks and add cards.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(24.dp))

                // Step-by-step instructions
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Setup Instructions:",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "1. Open AnkiDroid\n" +
                                   "2. Tap ⋮ menu → Settings\n" +
                                   "3. Tap Advanced\n" +
                                   "4. Tap AnkiDroid API\n" +
                                   "5. Find \"Yomidroid\" and enable it\n" +
                                   "6. Return here and tap Refresh",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        // Try to open AnkiDroid directly
                        try {
                            val launchIntent = context.packageManager.getLaunchIntentForPackage("com.ichi2.anki")
                            if (launchIntent != null) {
                                context.startActivity(launchIntent)
                            }
                        } catch (e: Exception) {
                            // Fallback to permission request
                            permissionLauncher.launch("com.ichi2.anki.permission.READ_WRITE_DATABASE")
                        }
                    }
                ) {
                    Text("Open AnkiDroid")
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        permissionLauncher.launch("com.ichi2.anki.permission.READ_WRITE_DATABASE")
                    }
                ) {
                    Text("Request Permission")
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "After enabling API access in AnkiDroid, return here and tap the refresh button in the top right.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // Settings form
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                // Status indicator
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
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("AnkiDroid connected")
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Deck selection
                Text(
                    text = "Deck",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                ExposedDropdownMenuBox(
                    expanded = deckDropdownExpanded,
                    onExpandedChange = { deckDropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedDeckName.ifEmpty { "Select a deck" },
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = deckDropdownExpanded)
                        }
                    )
                    ExposedDropdownMenu(
                        expanded = deckDropdownExpanded,
                        onDismissRequest = { deckDropdownExpanded = false }
                    ) {
                        decks.forEach { (id, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    selectedDeckId = id
                                    selectedDeckName = name
                                    deckDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Model selection
                Text(
                    text = "Note Type",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                ExposedDropdownMenuBox(
                    expanded = modelDropdownExpanded,
                    onExpandedChange = { modelDropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedModelName.ifEmpty { "Select a note type" },
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelDropdownExpanded)
                        }
                    )
                    ExposedDropdownMenu(
                        expanded = modelDropdownExpanded,
                        onDismissRequest = { modelDropdownExpanded = false }
                    ) {
                        models.forEach { (id, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    selectedModelId = id
                                    selectedModelName = name
                                    modelDropdownExpanded = false
                                    // Clear mappings when model changes
                                    fieldMappings = emptyMap()
                                }
                            )
                        }
                    }
                }

                // Field mappings
                if (modelFields.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = "Field Mappings",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Map Yomidroid data to your note type fields",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    YomidroidField.entries.forEach { vnField ->
                        FieldMappingRow(
                            vnFieldName = vnField.displayName,
                            ankiFields = modelFields,
                            selectedField = fieldMappings[vnField] ?: "",
                            onFieldSelected = { ankiField ->
                                fieldMappings = if (ankiField.isEmpty()) {
                                    fieldMappings - vnField
                                } else {
                                    fieldMappings + (vnField to ankiField)
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Duplicate check field selector
                    Text(
                        text = "Duplicate Check Field",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Check for existing cards based on this field (must be mapped to first Anki field)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ExposedDropdownMenuBox(
                        expanded = duplicateFieldDropdownExpanded,
                        onExpandedChange = { duplicateFieldDropdownExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = duplicateCheckField.displayName,
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = duplicateFieldDropdownExpanded)
                            }
                        )
                        ExposedDropdownMenu(
                            expanded = duplicateFieldDropdownExpanded,
                            onDismissRequest = { duplicateFieldDropdownExpanded = false }
                        ) {
                            // Only show fields that are mapped
                            fieldMappings.keys.forEach { field ->
                                DropdownMenuItem(
                                    text = { Text(field.displayName) },
                                    onClick = {
                                        duplicateCheckField = field
                                        duplicateFieldDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Info text
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "How it works",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "When you tap the Anki button in a definition popup, Yomidroid will create a card with the mapped fields. Screenshots are automatically saved to your Anki media folder. All cards are tagged with \"Yomidroid\" for easy filtering.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldMappingRow(
    vnFieldName: String,
    ankiFields: List<String>,
    selectedField: String,
    onFieldSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = vnFieldName,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.width(16.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(1.5f)
        ) {
            OutlinedTextField(
                value = selectedField.ifEmpty { "(none)" },
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                textStyle = MaterialTheme.typography.bodySmall,
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                },
                singleLine = true
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("(none)") },
                    onClick = {
                        onFieldSelected("")
                        expanded = false
                    }
                )
                ankiFields.forEach { field ->
                    DropdownMenuItem(
                        text = { Text(field) },
                        onClick = {
                            onFieldSelected(field)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
