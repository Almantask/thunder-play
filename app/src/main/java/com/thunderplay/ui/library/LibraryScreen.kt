package com.thunderplay.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thunderplay.data.PlaylistEntity
import com.thunderplay.data.TrackEntity
import com.thunderplay.library.LibraryView
import com.thunderplay.playback.LocalState
import com.thunderplay.ui.player.MiniPlayer
import com.thunderplay.ui.player.NowPlayingScreen
import com.thunderplay.ui.playlists.NameDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(viewModel: LibraryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.player.nowPlaying.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    var searching by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var namingShare by remember { mutableStateOf(false) }
    var namingPlaylist by remember { mutableStateOf(false) }
    var namingSelectionPlaylist by remember { mutableStateOf(false) }
    var namingSelectionShare by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is LibraryEvent.Message -> snackbars.showSnackbar(event.text)
                is LibraryEvent.Launch -> context.startActivity(event.intent)
                is LibraryEvent.Shared -> {
                    clipboard.setText(AnnotatedString(event.link.url))
                    snackbars.showSnackbar(
                        "Link for ${event.link.trackCount} track(s) copied - expires in 7 days",
                    )
                }
            }
        }
    }

    LaunchedEffect(state.refresh) {
        when (val outcome = state.refresh) {
            is RefreshState.Failed -> {
                snackbars.showSnackbar(outcome.message)
                viewModel.dismissRefreshStatus()
            }

            is RefreshState.Done -> {
                val pending = outcome.result.awaitingTranscode
                val suffix = if (pending > 0) " ($pending awaiting transcode)" else ""
                snackbars.showSnackbar("${outcome.result.tracks} tracks" + suffix)
                viewModel.dismissRefreshStatus()
            }

            else -> Unit
        }
    }

    if (expanded && nowPlaying.active) {
        val current = state.tracks.firstOrNull { it.driveId == nowPlaying.mediaId }
        NowPlayingScreen(
            state = nowPlaying,
            track = current,
            rating = current?.rating ?: 0,
            playCount = current?.playCount ?: 0,
            crossfading = nowPlaying.crossfading,
            onCollapse = { expanded = false },
            onPlayPause = viewModel.player::togglePlayPause,
            onNext = { viewModel.player.next() },
            onPrevious = viewModel.player::previous,
            onSeek = viewModel.player::seekTo,
            onRate = { rating -> nowPlaying.mediaId?.let { viewModel.setRating(it, rating) } },
            onToggleLike = { nowPlaying.mediaId?.let { viewModel.toggleLike(it) } },
        )
        return
    }

    if (namingShare) {
        NameDialog(
            title = "Share ${state.tracks.size} track(s)",
            confirm = "Create link",
            initial = defaultShareName(state.view),
            onDismiss = { namingShare = false },
            onConfirm = {
                viewModel.shareCurrentView(it)
                namingShare = false
            },
        )
    }

    if (namingPlaylist) {
        NameDialog(
            title = "Save ${state.tracks.size} track(s) as a playlist",
            confirm = "Save",
            initial = defaultShareName(state.view),
            onDismiss = { namingPlaylist = false },
            onConfirm = {
                viewModel.createPlaylistFromView(it)
                namingPlaylist = false
            },
        )
    }

    if (namingSelectionPlaylist) {
        NameDialog(
            title = "New playlist from ${state.selection.size} track(s)",
            confirm = "Create",
            onDismiss = { namingSelectionPlaylist = false },
            onConfirm = {
                viewModel.createPlaylistFromSelection(it)
                namingSelectionPlaylist = false
            },
        )
    }

    if (namingSelectionShare) {
        NameDialog(
            title = "Share ${state.selection.size} selected track(s)",
            confirm = "Create link",
            initial = defaultShareName(state.view),
            onDismiss = { namingSelectionShare = false },
            onConfirm = {
                viewModel.shareSelection(it)
                namingSelectionShare = false
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            if (state.selectionMode) {
                SelectionBar(
                    state = state,
                    onClose = viewModel::clearSelection,
                    onSelectAllVisible = viewModel::selectAllVisible,
                    onSelectAll = viewModel::selectAll,
                    onDownload = viewModel::downloadSelected,
                    onRemove = viewModel::removeSelectedDownloads,
                    onAddToPlaylist = viewModel::addSelectionToPlaylist,
                    onNewPlaylist = { namingSelectionPlaylist = true },
                    onShare = { namingSelectionShare = true },
                )
                return@Scaffold
            }
            TopAppBar(
                title = { Text("Library") },
                actions = {
                    IconButton(onClick = { searching = !searching }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    OrderMenu(state.view.order, viewModel::setOrder)
                    IconButton(onClick = viewModel::shuffleAndPlay) {
                        Icon(Icons.Default.Shuffle, contentDescription = "Shuffle and play")
                    }
                    ViewMenu(
                        enabled = state.tracks.isNotEmpty(),
                        onSync = viewModel::syncVisible,
                        onShare = { namingShare = true },
                        onSaveAsPlaylist = { namingPlaylist = true },
                        onSelect = viewModel::selectAllVisible,
                    )
                    IconButton(
                        onClick = viewModel::refresh,
                        enabled = state.refresh != RefreshState.Running,
                    ) {
                        if (state.refresh == RefreshState.Running) {
                            CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                        }
                    }
                },
            )
        },
        bottomBar = {
            MiniPlayer(
                state = nowPlaying,
                onExpand = { expanded = true },
                onPlayPause = viewModel.player::togglePlayPause,
                onNext = { viewModel.player.next() },
            )
        },
    ) { insets ->
        Column(
            Modifier
                .padding(insets)
                .fillMaxSize(),
        ) {
            state.busy?.let { label ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(label, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }

            if (searching) {
                OutlinedTextField(
                    value = state.view.query,
                    onValueChange = viewModel::setQuery,
                    label = { Text("Search titles") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            ScopeChips(
                scope = state.view.scope,
                likedOnly = state.view.likedOnly,
                categories = state.categories,
                onScope = viewModel::setScope,
                onToggleLiked = viewModel::toggleLikedOnly,
            )

            when {
                !state.driveConfigured -> Guidance(
                    title = "Drive is not connected",
                    body = "Add app/src/main/assets/drive-service-account.json, then share the " +
                        "library folder with that service account as Editor. See docs/SETUP.md.",
                )

                state.isEmpty && state.refresh == RefreshState.Running ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }

                state.isEmpty -> Guidance(
                    title = "Nothing here yet",
                    body = "Tap Refresh to list what is in Drive. If the library is new, run " +
                        "tools/transcode/transcode.ps1 first and let Drive finish uploading.",
                )

                else -> {
                    Text(
                        "${state.tracks.size} tracks - ${state.downloadedCount} downloaded",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    TrackList(
                        state = state,
                        playingId = nowPlaying.mediaId,
                        onPlay = viewModel::play,
                        onToggleSelect = viewModel::toggleSelection,
                        onToggleLike = viewModel::toggleLike,
                        onDownload = viewModel::download,
                        onRemoveDownload = viewModel::removeDownload,
                        onShare = viewModel::shareTrack,
                        onTrash = viewModel::moveToTrash,
                        onAddToPlaylist = viewModel::addToPlaylist,
                    )
                }
            }
        }
    }
}

private fun defaultShareName(view: LibraryView): String = when (val scope = view.scope) {
    is LibraryView.Scope.Category -> scope.name
    LibraryView.Scope.Liked -> "Liked tracks"
    LibraryView.Scope.All -> if (view.likedOnly) "Liked tracks" else "Thunder Play selection"
}

@Composable
private fun TrackList(
    state: LibraryUiState,
    playingId: String?,
    onPlay: (Int) -> Unit,
    onToggleSelect: (String) -> Unit,
    onToggleLike: (String) -> Unit,
    onDownload: (TrackEntity) -> Unit,
    onRemoveDownload: (String) -> Unit,
    onShare: (TrackEntity) -> Unit,
    onTrash: (TrackEntity) -> Unit,
    onAddToPlaylist: (String, String) -> Unit,
) {
    val listState = rememberLazyListState()
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        itemsIndexed(state.tracks, key = { _, track -> track.driveId }) { index, track ->
            TrackRow(
                track = track,
                local = state.localStateOf(track.driveId),
                playlists = state.playlists,
                isPlaying = track.driveId == playingId,
                selectionMode = state.selectionMode,
                selected = state.isSelected(track.driveId),
                onToggleSelect = { onToggleSelect(track.driveId) },
                onPlay = { onPlay(index) },
                onToggleLike = { onToggleLike(track.driveId) },
                onDownload = { onDownload(track) },
                onRemoveDownload = { onRemoveDownload(track.driveId) },
                onShare = { onShare(track) },
                onTrash = { onTrash(track) },
                onAddToPlaylist = { onAddToPlaylist(it, track.driveId) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(
    track: TrackEntity,
    local: LocalState,
    playlists: List<PlaylistEntity>,
    isPlaying: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onPlay: () -> Unit,
    onToggleLike: () -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    onShare: () -> Unit,
    onTrash: () -> Unit,
    onAddToPlaylist: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var playlistMenuOpen by remember { mutableStateOf(false) }
    var confirmingTrash by remember { mutableStateOf(false) }

    if (confirmingTrash) {
        ConfirmTrashDialog(
            title = track.title,
            onDismiss = { confirmingTrash = false },
            onConfirm = {
                onTrash()
                confirmingTrash = false
            },
        )
    }

    ListItem(
        modifier = Modifier.combinedClickable(
            // Long-press is what starts a selection; once started, a tap ticks instead of plays,
            // which is the standard behaviour and stops accidental playback while bulk-selecting.
            onClick = { if (selectionMode) onToggleSelect() else onPlay() },
            onLongClick = onToggleSelect,
        ),
        headlineContent = {
            Text(
                track.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isPlaying) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        },
        supportingContent = {
            val level = track.level?.let { " / " + it }.orEmpty()
            Text(track.category + level, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
            } else {
                LocalStateIcon(local)
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (track.playCount > 0) {
                    Text(
                        track.playCount.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                IconButton(onClick = onToggleLike) {
                    Icon(
                        if (track.isLiked) {
                            Icons.Default.Favorite
                        } else {
                            Icons.Outlined.FavoriteBorder
                        },
                        contentDescription = if (track.isLiked) "Unlike" else "Like",
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (local == LocalState.Downloaded) {
                            DropdownMenuItem(
                                text = { Text("Remove download") },
                                leadingIcon = {
                                    Icon(Icons.Default.Delete, contentDescription = null)
                                },
                                onClick = {
                                    onRemoveDownload()
                                    menuOpen = false
                                },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Download") },
                                leadingIcon = {
                                    Icon(Icons.Default.Download, contentDescription = null)
                                },
                                onClick = {
                                    onDownload()
                                    menuOpen = false
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Share track") },
                            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                            onClick = {
                                onShare()
                                menuOpen = false
                            },
                        )
                        if (playlists.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Add to playlist") },
                                leadingIcon = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.PlaylistAdd,
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    playlistMenuOpen = true
                                },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Move to Drive trash") },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                confirmingTrash = true
                            },
                        )
                    }
                    DropdownMenu(playlistMenuOpen, onDismissRequest = { playlistMenuOpen = false }) {
                        playlists.forEach { playlist ->
                            DropdownMenuItem(
                                text = { Text(playlist.name) },
                                onClick = {
                                    onAddToPlaylist(playlist.id)
                                    playlistMenuOpen = false
                                },
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun ConfirmTrashDialog(title: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to Drive trash?") },
        text = {
            Text(
                "\"$title\" moves to _ThunderPlayTrash in Drive, along with its source WAV. " +
                    "Nothing is deleted, and it also disappears from music/ on your PC.",
            )
        },
        confirmButton = {
            androidx.compose.material3.Button(onClick = onConfirm) { Text("Move") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(
    state: LibraryUiState,
    onClose: () -> Unit,
    onSelectAllVisible: () -> Unit,
    onSelectAll: () -> Unit,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    onAddToPlaylist: (String) -> Unit,
    onNewPlaylist: () -> Unit,
    onShare: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var playlistMenuOpen by remember { mutableStateOf(false) }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Clear selection")
            }
        },
        title = {
            Column {
                Text("${state.selection.size} selected")
                // Spelling out the split makes it obvious which of the two actions will do
                // anything before you tap it.
                Text(
                    "${state.selectedDownloaded} downloaded, " +
                        "${state.selectedNotDownloaded} not",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
        actions = {
            IconButton(onClick = onDownload, enabled = state.selectedNotDownloaded > 0) {
                Icon(Icons.Default.Download, contentDescription = "Download selected")
            }
            IconButton(onClick = onRemove, enabled = state.selectedDownloaded > 0) {
                Icon(Icons.Default.DeleteSweep, contentDescription = "Remove selected downloads")
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Selection options")
                }
                DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Add to playlist") },
                        leadingIcon = {
                            Icon(
                                Icons.AutoMirrored.Filled.PlaylistAdd,
                                contentDescription = null,
                            )
                        },
                        enabled = state.playlists.isNotEmpty(),
                        onClick = {
                            menuOpen = false
                            playlistMenuOpen = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("New playlist from selection") },
                        leadingIcon = {
                            Icon(
                                Icons.AutoMirrored.Filled.PlaylistAdd,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            onNewPlaylist()
                            menuOpen = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Share selected") },
                        leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                        onClick = {
                            onShare()
                            menuOpen = false
                        },
                    )
                    HorizontalDivider()
                    if (!state.allVisibleSelected) {
                        DropdownMenuItem(
                            text = { Text("Select all ${state.tracks.size} visible") },
                            leadingIcon = {
                                Icon(Icons.Default.SelectAll, contentDescription = null)
                            },
                            onClick = {
                                onSelectAllVisible()
                                menuOpen = false
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Select all ${state.libraryTotal} in library") },
                        leadingIcon = { Icon(Icons.Default.DoneAll, contentDescription = null) },
                        onClick = {
                            onSelectAll()
                            menuOpen = false
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Clear selection") },
                        leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
                        onClick = {
                            onClose()
                            menuOpen = false
                        },
                    )
                }
                DropdownMenu(playlistMenuOpen, onDismissRequest = { playlistMenuOpen = false }) {
                    state.playlists.forEach { playlist ->
                        DropdownMenuItem(
                            text = { Text("${playlist.name} (${playlist.size})") },
                            onClick = {
                                onAddToPlaylist(playlist.id)
                                playlistMenuOpen = false
                            },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun LocalStateIcon(local: LocalState) {
    val (icon, description) = when (local) {
        LocalState.Downloaded -> Icons.Default.OfflinePin to "Downloaded"
        LocalState.Downloading -> Icons.Default.Downloading to "Downloading"
        LocalState.Failed -> Icons.Default.ErrorOutline to "Download failed"
        LocalState.Remote -> Icons.Default.CloudDownload to "Streams from Drive"
    }
    Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
}

@Composable
private fun ScopeChips(
    scope: LibraryView.Scope,
    likedOnly: Boolean,
    categories: List<String>,
    onScope: (LibraryView.Scope) -> Unit,
    onToggleLiked: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = scope is LibraryView.Scope.All,
            onClick = { onScope(LibraryView.Scope.All) },
            label = { Text("All") },
        )
        FilterChip(
            selected = scope is LibraryView.Scope.Liked,
            onClick = { onScope(LibraryView.Scope.Liked) },
            label = { Text("Liked") },
        )
        categories.forEach { category ->
            FilterChip(
                selected = (scope as? LibraryView.Scope.Category)?.name == category,
                onClick = { onScope(LibraryView.Scope.Category(category)) },
                label = { Text(category) },
            )
        }
        // Redundant under the Liked scope, which already implies it.
        if (scope !is LibraryView.Scope.Liked) {
            FilterChip(
                selected = likedOnly,
                onClick = onToggleLiked,
                label = { Text("Liked only") },
                leadingIcon = {
                    Icon(
                        if (likedOnly) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = null,
                    )
                },
            )
        }
    }
}

@Composable
private fun ViewMenu(
    enabled: Boolean,
    onSync: () -> Unit,
    onShare: () -> Unit,
    onSaveAsPlaylist: () -> Unit,
    onSelect: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(Icons.Default.SyncAlt, contentDescription = "Actions for this view")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Download these tracks") },
                leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                onClick = {
                    onSync()
                    open = false
                },
            )
            DropdownMenuItem(
                text = { Text("Share these tracks") },
                leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                onClick = {
                    onShare()
                    open = false
                },
            )
            DropdownMenuItem(
                text = { Text("Select these tracks") },
                leadingIcon = { Icon(Icons.Default.Checklist, contentDescription = null) },
                onClick = {
                    onSelect()
                    open = false
                },
            )
            DropdownMenuItem(
                text = { Text("Save as playlist") },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null)
                },
                onClick = {
                    onSaveAsPlaylist()
                    open = false
                },
            )
        }
    }
}

@Composable
private fun OrderMenu(current: LibraryView.Order, onPick: (LibraryView.Order) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.Sort, contentDescription = "Sort order")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LibraryView.Order.entries.forEach { order ->
                DropdownMenuItem(
                    text = { Text(order.label()) },
                    onClick = {
                        onPick(order)
                        open = false
                    },
                    trailingIcon = if (order == current) {
                        { Icon(Icons.Default.Check, contentDescription = "Selected") }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

private fun LibraryView.Order.label(): String = when (this) {
    LibraryView.Order.Name -> "Name"
    LibraryView.Order.MostPlayed -> "Most played"
    LibraryView.Order.HighestRated -> "Highest rated"
    LibraryView.Order.RecentlyAdded -> "Recently added"
    LibraryView.Order.Random -> "Random"
}

@Composable
private fun Guidance(title: String, body: String) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}
