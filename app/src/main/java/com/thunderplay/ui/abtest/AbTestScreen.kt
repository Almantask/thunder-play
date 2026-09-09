package com.thunderplay.ui.abtest

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thunderplay.data.TrackEntity
import com.thunderplay.ui.player.MiniPlayer

/**
 * Picks one keeper per cue and files the rest.
 *
 * The group card is the screen: takes of one cue differ only by an eight-character fingerprint, so
 * they have to be compared side by side with their real prompts on show, not one at a time through
 * Now Playing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AbTestScreen(viewModel: AbTestViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.player.nowPlaying.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }

    // Scoped to the screen, so it cannot leak a permanently silenced crossfade if this dies badly.
    DisposableEffect(Unit) {
        viewModel.suppressCrossfade(true)
        onDispose { viewModel.suppressCrossfade(false) }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbars.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("A/B") }) },
        snackbarHost = { SnackbarHost(snackbars) },
        bottomBar = {
            MiniPlayer(
                state = nowPlaying,
                onExpand = {},
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
            SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                AbTab.entries.forEachIndexed { index, tab ->
                    SegmentedButton(
                        selected = state.tab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        shape = SegmentedButtonDefaults.itemShape(index, AbTab.entries.size),
                    ) { Text(tab.label) }
                }
            }

            when (state.tab) {
                AbTab.Candidates -> Candidates(state, viewModel)
                AbTab.Results -> Results(state)
            }
        }
    }
}

@Composable
private fun Candidates(state: AbTestUiState, viewModel: AbTestViewModel) {
    if (state.candidates.isEmpty()) {
        Empty("No cue has more than one take, so there is nothing to compare.")
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        items(state.candidates, key = { it.key }) { group ->
            val open = state.openGroup?.key == group.key
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                ListItem(
                    headlineContent = {
                        Text(group.slug, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        val level = group.level?.let { " / $it" }.orEmpty()
                        Text("${group.category}$level · ${group.durationLabel} · ${group.takes.size} takes")
                    },
                    modifier = Modifier.clickable {
                        if (open) viewModel.closeGroup() else viewModel.openGroup(group)
                    },
                )

                if (open) {
                    if (state.loadingPrompts) {
                        Row(
                            Modifier.fillMaxWidth().padding(8.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) { CircularProgressIndicator(Modifier.padding(4.dp)) }
                    }

                    if (state.mixedPrompts) {
                        Text(
                            "These takes came from different prompts. The generator truncates its " +
                                "filenames, so unrelated cues can collide - check before judging.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }

                    group.takes.forEachIndexed { index, take ->
                        TakeRow(
                            take = take,
                            prompt = state.prompts[take.driveId],
                            selected = state.selectedWinner == take.driveId,
                            onPlay = { viewModel.play(group, index) },
                            onSelect = { viewModel.selectWinner(take.driveId) },
                        )
                    }

                    Button(
                        onClick = { viewModel.judge(group) },
                        enabled = state.selectedWinner != null && !state.busy,
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(12.dp),
                    ) { Text("Keep this one") }
                }
            }
        }
    }
}

@Composable
private fun TakeRow(
    take: TrackEntity,
    prompt: String?,
    selected: Boolean,
    onPlay: () -> Unit,
    onSelect: () -> Unit,
) {
    ListItem(
        leadingContent = {
            IconButton(onClick = onPlay) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play this take")
            }
        },
        // The eight-character fingerprint is the only thing telling two takes apart by name.
        headlineContent = { Text(take.title.substringAfterLast('-')) },
        supportingContent = {
            Text(
                prompt ?: if (take.sourceWavDriveId == null) {
                    "Source WAV not linked, so its prompt cannot be read"
                } else {
                    "Prompt not loaded"
                },
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        trailingContent = {
            RadioButton(selected = selected, onClick = onSelect)
        },
        modifier = Modifier.clickable(onClick = onSelect),
    )
}

@Composable
private fun Results(state: AbTestUiState) {
    if (state.good.isEmpty() && state.bad.isEmpty()) {
        Empty("Nothing judged yet.")
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item { Section("Kept (${state.good.size})") }
        items(state.good, key = { it.driveId }) { JudgedRow(it, kept = true) }
        item { HorizontalDivider() }
        item { Section("Set aside (${state.bad.size})") }
        items(state.bad, key = { it.driveId }) { JudgedRow(it, kept = false) }
    }
}

@Composable
private fun JudgedRow(track: TrackEntity, kept: Boolean) {
    ListItem(
        leadingContent = if (kept) {
            { Icon(Icons.Default.EmojiEvents, contentDescription = "Kept") }
        } else {
            null
        },
        headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = track.abPrompt?.let {
            {
                Text(
                    it,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    )
}

@Composable
private fun Section(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun Empty(text: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
