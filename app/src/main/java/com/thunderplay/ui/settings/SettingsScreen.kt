package com.thunderplay.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.widget.Toast
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thunderplay.settings.AppSettings
import com.thunderplay.settings.SyncInterval

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.handleGoogleSignIn(result.data)
    }

    LaunchedEffect(state.pendingShare) {
        state.pendingShare?.let {
            context.startActivity(it)
            viewModel.shareLaunched()
        }
    }

    state.logText?.let { text ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDiagnostics,
            title = { Text("Error log") },
            text = {
                // Monospace and scrollable: stack traces are unreadable reflowed.
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text.ifBlank { "Nothing logged yet." },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissDiagnostics) { Text("Close") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        clipboardManager.setText(AnnotatedString(text))
                        Toast.makeText(context, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Copy")
                    }
                    TextButton(onClick = viewModel::shareDiagnostics) { Text("Send") }
                }
            },
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { insets ->
        Column(
            Modifier
                .padding(insets)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("Automatic refresh")
            SwitchRow(
                title = "Refresh the track list",
                subtitle = "Checks Drive for new or removed tracks",
                checked = state.settings.autoRefreshEnabled,
                onCheckedChange = viewModel::setAutoRefresh,
            )
            IntervalRow(
                enabled = state.settings.autoRefreshEnabled,
                current = state.settings.autoRefreshInterval,
                onPick = viewModel::setRefreshInterval,
            )

            HorizontalDivider()
            SectionHeader("Automatic sync")
            SwitchRow(
                title = "Download new tracks",
                subtitle = "Pre-fetches anything not already on the device",
                checked = state.settings.autoSyncEnabled,
                onCheckedChange = viewModel::setAutoSync,
            )
            IntervalRow(
                enabled = state.settings.autoSyncEnabled,
                current = state.settings.autoSyncInterval,
                onPick = viewModel::setSyncInterval,
            )
            SwitchRow(
                title = "Wi-Fi only",
                subtitle = "The library is around 280 MB in full",
                checked = state.settings.syncOnWifiOnly,
                enabled = state.settings.autoSyncEnabled,
                onCheckedChange = viewModel::setWifiOnly,
            )
            SwitchRow(
                title = "Only while charging",
                subtitle = null,
                checked = state.settings.syncOnlyWhenCharging,
                enabled = state.settings.autoSyncEnabled,
                onCheckedChange = viewModel::setChargingOnly,
            )
            Text(
                "Android will not run background work more often than every " +
                    "${SyncInterval.MINIMUM_MINUTES} minutes, and some phones delay it further to " +
                    "save battery.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            TextButton(
                onClick = { openBatterySettings(context) },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) { Text("Battery optimisation settings") }

            HorizontalDivider()
            SectionHeader("Playback")
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("Crossfade: " + crossfadeLabel(state.settings.crossfadeMs))
                Slider(
                    value = state.settings.crossfadeMs.toFloat(),
                    onValueChange = { viewModel.setCrossfade(it.toInt()) },
                    valueRange = 0f..AppSettings.MAX_CROSSFADE_MS.toFloat(),
                    steps = 11,
                )
                Text(
                    "Zero turns crossfading off entirely and uses a single player.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            HorizontalDivider()
            SectionHeader("A/B testing")
            SwitchRow(
                title = "Judge rival takes",
                subtitle = "Adds a tab for picking one keeper per cue and filing the rest",
                checked = state.settings.abTestingEnabled,
                onCheckedChange = viewModel::setAbTesting,
            )

            HorizontalDivider()
            SectionHeader("Storage")
            ListItem(
                headlineContent = { Text("Downloaded audio") },
                supportingContent = { Text(state.cacheLabel) },
                trailingContent = {
                    Button(onClick = viewModel::clearDownloads) { Text("Clear") }
                },
            )

            HorizontalDivider()
            SectionHeader("Diagnostics")
            ListItem(
                headlineContent = { Text("Error log") },
                supportingContent = {
                    Text(
                        state.diagnosticsStatus
                            ?: "Read it here, or send it to yourself. The app cannot write it to " +
                            "Drive directly - a service account has no storage quota, so Drive " +
                            "rejects the upload.",
                    )
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = viewModel::viewDiagnostics) { Text("View") }
                        Button(onClick = viewModel::shareDiagnostics) { Text("Send") }
                    }
                },
            )
            TextButton(
                onClick = viewModel::clearDiagnostics,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) { Text("Clear log") }

            HorizontalDivider()
            SectionHeader("Connections")
            ListItem(
                headlineContent = { Text("Google Drive") },
                supportingContent = {
                    Text(
                        if (state.driveConfigured) {
                            "Service account key loaded"
                        } else {
                            "No key - see docs/SETUP.md"
                        },
                    )
                },
            )
            ListItem(
                headlineContent = { Text("Firebase") },
                supportingContent = {
                    Text(
                        if (state.firebaseConfigured) {
                            state.accountEmail?.let {
                                "Signed in as $it"
                            } ?: "Active (Anonymous session)"
                        } else {
                            "Not configured - stats stay on this device only"
                        },
                    )
                },
                trailingContent = if (state.firebaseConfigured && state.accountEmail == null) {
                    {
                        TextButton(onClick = { signInLauncher.launch(viewModel.getGoogleSignInIntent()) }) {
                            Text("Sign In")
                        }
                    }
                } else null,
            )
            ListItem(
                headlineContent = { Text("Source folder") },
                supportingContent = { Text(state.settings.sourceRoot) },
                trailingContent = {
                    TextButton(onClick = viewModel::toggleSourceRoot) { Text("Switch") }
                },
            )
        }
    }
}

private fun crossfadeLabel(ms: Int): String =
    if (ms <= 0) "off" else String.format("%.1f seconds", ms / 1000f)

private fun openBatterySettings(context: Context) {
    // Opens the app's own settings page; the exemption toggle lives under Battery there.
    val intent = Intent(
        AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
    runCatching { context.startActivity(intent) }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        },
    )
}

@Composable
private fun IntervalRow(
    enabled: Boolean,
    current: SyncInterval,
    onPick: (SyncInterval) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = { open = true }, enabled = enabled) { Text(current.label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SyncInterval.entries.forEach { interval ->
                DropdownMenuItem(
                    text = { Text(interval.label) },
                    onClick = {
                        onPick(interval)
                        open = false
                    },
                )
            }
        }
    }
}
