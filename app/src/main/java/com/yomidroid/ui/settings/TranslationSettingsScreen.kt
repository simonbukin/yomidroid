package com.yomidroid.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import java.text.DecimalFormat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yomidroid.translation.BackendStatus
import com.yomidroid.translation.TranslationService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationSettingsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val translationService = remember { TranslationService.getInstance(context) }
    val remoteBackend = remember { translationService.getRemoteApiBackend() }
    val onDeviceBackend = remember { translationService.getOnDeviceLlmBackend() }
    val mlKitBackend = remember { translationService.getMlKitBackend() }

    // Remote API settings
    var remoteEnabled by remember { mutableStateOf(remoteBackend?.enabled ?: false) }
    var remoteEndpoint by remember { mutableStateOf(remoteBackend?.endpoint ?: "") }
    var remoteModel by remember { mutableStateOf(remoteBackend?.modelName ?: "") }
    var remoteStatus by remember { mutableStateOf<BackendStatus?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    // On-Device LLM settings
    var onDeviceEnabled by remember { mutableStateOf(onDeviceBackend?.enabled ?: false) }
    var onDeviceStatus by remember { mutableStateOf<BackendStatus?>(null) }
    var isModelDownloaded by remember { mutableStateOf(false) }
    var isDownloadingModel by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }

    // ML Kit settings
    var mlKitStatus by remember { mutableStateOf<BackendStatus?>(null) }
    var isDownloadingMlKit by remember { mutableStateOf(false) }

    // Check statuses on load
    LaunchedEffect(Unit) {
        remoteBackend?.let {
            remoteStatus = it.getStatus()
        }
        onDeviceBackend?.let {
            onDeviceStatus = it.getStatus()
            isModelDownloaded = it.isModelDownloaded()
        }
        mlKitBackend?.let {
            mlKitStatus = it.getStatus()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Translation") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
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
        ) {
            // Remote API Section
            Text(
                text = "Remote API",
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Enable Remote API",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Use Ollama, llama.cpp, or LM Studio",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = remoteEnabled,
                            onCheckedChange = { enabled ->
                                remoteEnabled = enabled
                                remoteBackend?.enabled = enabled
                            }
                        )
                    }

                    if (remoteEnabled) {
                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = remoteEndpoint,
                            onValueChange = { value ->
                                remoteEndpoint = value
                                remoteBackend?.endpoint = value
                            },
                            label = { Text("API Endpoint") },
                            placeholder = { Text("http://192.168.1.x:11434/v1") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = remoteModel,
                            onValueChange = { value ->
                                remoteModel = value
                                remoteBackend?.modelName = value
                            },
                            label = { Text("Model Name") },
                            placeholder = { Text("vntl-llama3-8b-v2") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Status indicator
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                when (val status = remoteStatus) {
                                    is BackendStatus.Available -> {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "Connected",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            "Connected",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    is BackendStatus.Error -> {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Error",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            status.message,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                    else -> {
                                        Text(
                                            "Not tested",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            // Test button
                            Button(
                                onClick = {
                                    scope.launch {
                                        isTesting = true
                                        remoteStatus = remoteBackend?.getStatus()
                                        isTesting = false
                                    }
                                },
                                enabled = !isTesting
                            ) {
                                if (isTesting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Test")
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Recommended models info
            if (remoteEnabled) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Recommended Models",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "vntl-llama3-8b-v2 - Best balance (259k downloads)",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "vntl-gemma2-27b - Highest quality (70.6%)",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Get models from: huggingface.co/lmg-anon",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ML Kit Section
            Text(
                text = "ML Kit (Fallback)",
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
                        text = "Google ML Kit Translation",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Fast fallback (~30MB). Quality comparable to Google Translate.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when (val status = mlKitStatus) {
                            is BackendStatus.Available -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Ready",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        "Model downloaded",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            is BackendStatus.Downloading -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        progress = { status.progress },
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        "Downloading... ${(status.progress * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            else -> {
                                Text(
                                    "Not downloaded",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Button(
                                    onClick = {
                                        scope.launch {
                                            isDownloadingMlKit = true
                                            mlKitBackend?.downloadModel { progress ->
                                                mlKitStatus = BackendStatus.Downloading(progress)
                                            }
                                            mlKitStatus = mlKitBackend?.getStatus()
                                            isDownloadingMlKit = false
                                        }
                                    },
                                    enabled = !isDownloadingMlKit
                                ) {
                                    Text("Download")
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // On-Device LLM Section
            Text(
                text = "On-Device LLM",
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Enable On-Device LLM",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "gemma-2-2b-jpn-it-translate (1.71 GB)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = onDeviceEnabled,
                            onCheckedChange = { enabled ->
                                onDeviceEnabled = enabled
                                onDeviceBackend?.enabled = enabled
                            },
                            enabled = isModelDownloaded
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Model download status and button
                    if (isDownloadingModel) {
                        Column {
                            Text(
                                text = "Downloading: ${(downloadProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else if (isModelDownloaded) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Downloaded",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "Model downloaded",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        onDeviceBackend?.deleteModel()
                                        isModelDownloaded = false
                                        onDeviceEnabled = false
                                        onDeviceBackend?.enabled = false
                                    }
                                }
                            ) {
                                Text("Delete")
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Model not downloaded",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = {
                                    scope.launch {
                                        isDownloadingModel = true
                                        downloadProgress = 0f
                                        val success = onDeviceBackend?.downloadModel { progress ->
                                            downloadProgress = progress
                                        } ?: false
                                        isDownloadingModel = false
                                        isModelDownloaded = success
                                        if (success) {
                                            onDeviceEnabled = true
                                            onDeviceBackend?.enabled = true
                                        }
                                    }
                                }
                            ) {
                                Text("Download (1.71 GB)")
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Info card about on-device translation
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "On-Device Translation",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Runs entirely on your phone - no internet required. Specialized for Japanese visual novel/game translation.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Expected: 2-6 seconds per sentence",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
