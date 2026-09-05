package com.thunderplay.ui.playlists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thunderplay.data.PlaylistEntity
import com.thunderplay.ui.library.LibraryEvent
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(viewModel: PlaylistsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is LibraryEvent.Message -> snackbars.showSnackbar(event.text)
                is LibraryEvent.Launch -> context.startActivity(event.intent)
                is LibraryEvent.Shared -> {
                    clipboard.setText(AnnotatedString(event.link.url))
                    snackbars.showSnackbar("Link copied - expires in 7 days")
                }
            }
        }
    }

    if (creating) {
        NameDialog(
            title = "New playlist",
            confirm = "Create",
            onDismiss = { creating = false },
            onConfirm = {
                viewModel.create(it)
                creating = false
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = { TopAppBar(title = { Text("Playlists") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) {
                Icon(Icons.Default.Add, contentDescription = "New playlist")
            }
        },
    ) { insets ->
        Column(
            Modifier
                .padding(insets)
                .fillMaxSize(),
        ) {
            state.busy?.let {
                Column(Modifier.padding(16.dp)) {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }

            if (state.playlists.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(
                        8.dp,
                        Alignment.CenterVertically,
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("No playlists yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Create one here, or share the library view directly from the Library tab.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.playlists, key = { it.id }) { playlist ->
                        PlaylistRow(
                            playlist = playlist,
                            isPlaying = state.isPlaying(playlist.id),
                            onPlayPause = { viewModel.playOrPause(playlist.id) },
                            onShare = { viewModel.share(playlist.id) },
                            onRevoke = { viewModel.revoke(playlist.id) },
                            onDelete = { viewModel.delete(playlist.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistRow(
    playlist: PlaylistEntity,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onShare: () -> Unit,
    onRevoke: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val live = playlist.isShareLive(System.currentTimeMillis())

    ListItem(
        modifier = Modifier.clickable(onClick = onPlayPause),
        headlineContent = { Text(playlist.name) },
        supportingContent = {
            val tracks = "${playlist.size} track" + if (playlist.size == 1) "" else "s"
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (live) {
                    Icon(
                        Icons.Default.Link,
                        contentDescription = "Shared",
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(if (live) "$tracks · ${shareRemaining(playlist)}" else tracks)
            }
        },
        leadingContent = {
            IconButton(onClick = onPlayPause) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                )
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
                DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (live) "Re-share (new 7-day link)" else "Share link") },
                        leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                        onClick = {
                            onShare()
                            menuOpen = false
                        },
                    )
                    if (live) {
                        DropdownMenuItem(
                            text = { Text("Revoke link now") },
                            leadingIcon = {
                                Icon(Icons.Default.LinkOff, contentDescription = null)
                            },
                            onClick = {
                                onRevoke()
                                menuOpen = false
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Delete playlist") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = {
                            onDelete()
                            menuOpen = false
                        },
                    )
                }
            }
        },
    )
}

private fun shareRemaining(playlist: PlaylistEntity): String {
    val ms = (playlist.shareExpiresAt ?: 0L) - System.currentTimeMillis()
    if (ms <= 0) return "link expired"
    val hours = TimeUnit.MILLISECONDS.toHours(ms)
    if (hours < 1) return "link expires within the hour"
    if (hours < 24) return "link expires in ${hours}h"
    val days = TimeUnit.MILLISECONDS.toDays(ms)
    return "link expires in ${days}d"
}

@Composable
fun NameDialog(
    title: String,
    confirm: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text.trim()) },
                enabled = text.isNotBlank(),
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
