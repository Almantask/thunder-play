package com.thunderplay.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ModalBottomSheet
import androidx.media3.common.Player
import com.thunderplay.playback.QueueEntry
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thunderplay.data.TrackEntity
import com.thunderplay.library.TrackDescriptors
import com.thunderplay.playback.NowPlaying
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Docked bar above the navigation; visible whenever something is queued. */
@Composable
fun MiniPlayer(
    state: NowPlaying,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state.active,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = modifier,
    ) {
        Surface(tonalElevation = 3.dp) {
            Column {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onExpand)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            state.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onPlayPause) {
                        Icon(
                            if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                        )
                    }
                    IconButton(onClick = onNext, enabled = state.hasNext) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    state: NowPlaying,
    track: TrackEntity?,
    rating: Int,
    playCount: Int,
    crossfading: Boolean,
    queue: List<QueueEntry>,
    onCollapse: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Float) -> Unit,
    onRate: (Int) -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onPlayQueueIndex: (Int) -> Unit,
    onMoveInQueue: (Int, Int) -> Unit,
    onRemoveFromQueue: (Int) -> Unit,
) {
    // While dragging, follow the finger rather than the player, or the thumb fights the ticker.
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    var queueOpen by remember { mutableStateOf(false) }

    if (queueOpen) {
        ModalBottomSheet(onDismissRequest = { queueOpen = false }) {
            QueueSheet(
                queue = queue,
                onPlay = onPlayQueueIndex,
                onMove = onMoveInQueue,
                onRemove = onRemoveFromQueue,
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCollapse) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Close")
            }
            Spacer(Modifier.weight(1f))
            if (crossfading) {
                Text("Crossfading", style = MaterialTheme.typography.labelMedium)
            }
            IconButton(onClick = { queueOpen = true }) {
                Icon(
                    Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = "Up next (${queue.size})",
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Text(state.title, style = MaterialTheme.typography.headlineSmall, maxLines = 3)
        Text(state.subtitle, style = MaterialTheme.typography.bodyMedium)

        Row(verticalAlignment = Alignment.CenterVertically) {
            StarRating(rating = rating, onRate = onRate)
            Spacer(Modifier.width(12.dp))
            if (playCount > 0) {
                Text(
                    if (playCount == 1) "played once" else "played $playCount times",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        Column {
            Slider(
                value = scrubbing ?: state.progress,
                onValueChange = { scrubbing = it },
                onValueChangeFinished = {
                    scrubbing?.let(onSeek)
                    scrubbing = null
                },
            )
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Text(formatTime(state.positionMs), style = MaterialTheme.typography.labelSmall)
                Text(formatTime(state.durationMs), style = MaterialTheme.typography.labelSmall)
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onToggleShuffle) {
                Icon(
                    Icons.Default.Shuffle,
                    contentDescription = if (state.shuffleEnabled) {
                        "Shuffle on"
                    } else {
                        "Shuffle off"
                    },
                    tint = if (state.shuffleEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        LocalContentColor.current
                    },
                )
            }
            IconButton(onClick = onPrevious, enabled = state.hasPrevious) {
                Icon(
                    Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    modifier = Modifier.size(36.dp),
                )
            }
            IconButton(onClick = onPlayPause) {
                Icon(
                    if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(48.dp),
                )
            }
            IconButton(onClick = onNext, enabled = state.hasNext) {
                Icon(
                    Icons.Default.SkipNext,
                    contentDescription = "Next",
                    modifier = Modifier.size(36.dp),
                )
            }
            IconButton(onClick = onCycleRepeat) {
                Icon(
                    if (state.repeatMode == Player.REPEAT_MODE_ONE) {
                        Icons.Default.RepeatOne
                    } else {
                        Icons.Default.Repeat
                    },
                    contentDescription = when (state.repeatMode) {
                        Player.REPEAT_MODE_ONE -> "Repeat one"
                        Player.REPEAT_MODE_ALL -> "Repeat all"
                        else -> "Repeat off"
                    },
                    tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) {
                        LocalContentColor.current
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }

        Spacer(Modifier.weight(1f))

        TrackDetails(track = track, state = state)
    }
}

/**
 * The queue, as a list that can be played from, reordered and pruned.
 *
 * Reordering is by menu rather than by dragging: a drag-and-drop LazyColumn needs a library the
 * project does not otherwise want, and moving a track one or two places is what this is actually
 * for - the whole list is only ever the view it was played from.
 */
@Composable
private fun QueueSheet(
    queue: List<QueueEntry>,
    onPlay: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    Text(
        if (queue.isEmpty()) "Nothing queued" else "Up next",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )

    LazyColumn(Modifier.fillMaxWidth()) {
        items(queue, key = { it.index }) { entry ->
            var menuOpen by remember { mutableStateOf(false) }

            ListItem(
                modifier = Modifier.clickable { onPlay(entry.index) },
                leadingContent = {
                    Text(
                        "${entry.index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                headlineContent = {
                    Text(
                        entry.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (entry.isCurrent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                },
                supportingContent = {
                    Text(entry.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                trailingContent = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Queue actions")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (entry.index > 0) {
                                DropdownMenuItem(
                                    text = { Text("Move up") },
                                    onClick = {
                                        onMove(entry.index, entry.index - 1)
                                        menuOpen = false
                                    },
                                )
                            }
                            if (entry.index < queue.lastIndex) {
                                DropdownMenuItem(
                                    text = { Text("Move down") },
                                    onClick = {
                                        onMove(entry.index, entry.index + 1)
                                        menuOpen = false
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Remove from queue") },
                                onClick = {
                                    onRemove(entry.index)
                                    menuOpen = false
                                },
                            )
                        }
                    }
                },
            )
        }
    }
}

/**
 * What is actually known about the playing track.
 *
 * Three sources, in descending order of authority: the generator's own RIFF tags, read out of the
 * source WAV once the header has been indexed; the filename convention, which is all there was
 * before that and all there is for a track not yet read; and what Drive, the decoder and the app
 * itself have recorded. The prompt is the one field that exists nowhere else - the filename only
 * carries a truncated slug of it.
 */
@Composable
private fun TrackDetails(track: TrackEntity?, state: NowPlaying) {
    if (track == null) return
    var open by remember { mutableStateOf(false) }
    val descriptors = remember(track.title) { TrackDescriptors.parse(track.title) }
    val instruments = remember(track.instruments) { track.instrumentList }

    HorizontalDivider()
    TextButton(onClick = { open = !open }, modifier = Modifier.fillMaxWidth()) {
        Text(if (open) "Hide details" else "Details")
        Spacer(Modifier.width(6.dp))
        Icon(
            if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
        )
    }
    if (!open) return

    // The instruments the generator actually listed, falling back to the style words guessed out
    // of the filename for a track whose header has not been read yet.
    val chips = instruments.ifEmpty { descriptors.styles }
    if (chips.isNotEmpty()) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            chips.forEach { chip ->
                AssistChip(onClick = {}, label = { Text(chip.replaceFirstChar(Char::uppercase)) })
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        track.prompt?.let { DetailRow("Prompt", it, lines = 6) }
        if (track.prompt == null && descriptors.phrase.isNotEmpty()) {
            DetailRow("Description", descriptors.phrase.replaceFirstChar(Char::uppercase))
        }
        track.genre?.let { DetailRow("Genre", it) }
        DetailRow("Category", track.category)
        track.level?.let { DetailRow("Level", it) }
        // The generator's own intensity, which is not always the folder the file landed in.
        track.intensity?.takeIf { it != track.level }?.let { DetailRow("Intensity", it) }
        // The decoder's figure is authoritative once it has one; the indexed header covers the
        // gap before the stream has been parsed.
        DetailRow("Duration", formatTime(state.durationMs.takeIf { it > 0 } ?: track.durationMs ?: 0))

        val codec = listOfNotNull(
            state.codec?.uppercase(),
            state.bitrateBps?.let { "${it / 1000} kbps" },
            state.sampleRateHz?.let { "${it / 1000} kHz" },
            state.channels?.let { if (it == 2) "stereo" else if (it == 1) "mono" else "$it ch" },
        )
        if (codec.isNotEmpty()) DetailRow("Audio", codec.joinToString(" · "))

        track.sizeBytes?.let { DetailRow("Size", formatSize(it)) }
        track.addedAt?.let { DetailRow("Added", formatDate(it)) }
        DetailRow("Plays", if (track.playCount == 0) "never" else track.playCount.toString())
        track.lastPlayedAt?.let { DetailRow("Last played", formatDate(it)) }
        if (track.rating > 0) DetailRow("Rating", "★".repeat(track.rating))
        descriptors.fingerprint?.let { DetailRow("Fingerprint", it) }
    }
}

@Composable
private fun DetailRow(label: String, value: String, lines: Int = 2) {
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = lines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.UK, "%.0f KB", bytes / 1024f)
    else -> String.format(Locale.UK, "%.1f MB", bytes / 1024f / 1024f)
}

private fun formatDate(epochMs: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(epochMs))

@Composable
fun StarRating(
    rating: Int,
    onRate: (Int) -> Unit,
    modifier: Modifier = Modifier,
    starSize: Dp = 32.dp,
    iconSize: Dp = 20.dp,
) {
    Row(modifier) {
        (1..5).forEach { star ->
            IconButton(
                // Tapping the current rating clears it, which is the usual way to un-rate.
                onClick = { onRate(if (rating == star) 0 else star) },
                modifier = Modifier.size(starSize),
            ) {
                Icon(
                    if (star <= rating) Icons.Default.Star else Icons.Outlined.StarBorder,
                    contentDescription = "$star star",
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:" + seconds.toString().padStart(2, '0')
}
