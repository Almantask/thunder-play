package com.thunderplay.ui.abtest

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thunderplay.data.TrackEntity
import com.thunderplay.library.AbBracket
import com.thunderplay.library.AbDuel
import com.thunderplay.library.AbGroup
import com.thunderplay.playback.NowPlaying
import com.thunderplay.ui.player.MiniPlayer
import kotlinx.coroutines.launch

/**
 * Picks one keeper per cue, one pair at a time.
 *
 * The card is the screen. Takes of one cue differ only by an eight-character fingerprint, so the
 * only useful question is "this one or that one", asked with both of them in front of you - and the
 * answer is a swipe towards the side that wins, because a backlog of cues is cleared in gestures,
 * not in dialogs.
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

    // Reads the prompt of the cue on the card and the one behind it, as the deck moves.
    LaunchedEffect(state.deck.firstOrNull()?.key) { viewModel.prefetchPrompts() }

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
                AbTab.Judge -> Judge(state, nowPlaying, viewModel)
                AbTab.Results -> Results(state)
            }
        }
    }
}

@Composable
private fun Judge(state: AbTestUiState, nowPlaying: NowPlaying, viewModel: AbTestViewModel) {
    val bracket = state.bracket
    val duel = state.duel
    if (bracket == null || duel == null) {
        Empty(
            when {
                state.filing > 0 -> "Filing the last cue…"
                state.filed > 0 ->
                    "Deck clear. ${state.filed} cue${if (state.filed == 1) "" else "s"} filed."
                else -> "No cue has more than one take, so there is nothing to compare."
            },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        DeckProgress(state)

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            // The cue behind, so the deck has visible depth and swiping feels like dealing.
            state.deck.getOrNull(1)?.let { NextCue(it) }

            DuelCard(
                duel = duel,
                bracket = bracket,
                state = state,
                nowPlaying = nowPlaying,
                onPlay = { driveId -> viewModel.play(duel, driveId) },
                onKeep = viewModel::keep,
                onTogglePlayPause = viewModel.player::togglePlayPause,
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            DeckAction(
                icon = Icons.AutoMirrored.Filled.Undo,
                label = "Undo",
                // Nothing is filed until a cue's last round, so an undo can never un-move a file.
                enabled = bracket.decided > 0,
                onClick = viewModel::undo,
            )
            DeckAction(
                icon = Icons.Default.SkipNext,
                label = "Later",
                // The last cue in the deck has nowhere to go, so putting it off is a no-op.
                enabled = state.deck.size > 1,
                onClick = viewModel::skip,
            )
        }
    }
}

@Composable
private fun DeckProgress(state: AbTestUiState) {
    val total = state.filed + state.deck.size
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${state.deck.size} cue${if (state.deck.size == 1) "" else "s"} to judge",
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.weight(1f))
            if (state.filing > 0) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                if (state.filing > 0) "Filing ${state.filing}" else "${state.filed} filed",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { if (total == 0) 0f else state.filed.toFloat() / total },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
    }
}

/** The cue behind the card: no content worth reading, only the sense that the deck goes on. */
@Composable
private fun NextCue(group: AbGroup) {
    Card(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = 0.94f
                scaleY = 0.94f
                translationY = -12.dp.toPx()
                alpha = 0.5f
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Text(
            group.slug,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/**
 * The pair, and the swipe that decides them.
 *
 * The gesture is horizontal only: vertical drags belong to whatever scrolls, and a card that can be
 * thrown in four directions turns a two-way question into a memory test. Both sides are also plain
 * buttons - a swipe is a nice way to answer, not the only way, and TalkBack cannot throw a card.
 */
@Composable
private fun DuelCard(
    duel: AbDuel,
    bracket: AbBracket,
    state: AbTestUiState,
    nowPlaying: NowPlaying,
    onPlay: (String) -> Unit,
    onKeep: (String) -> Unit,
    onTogglePlayPause: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Keyed on the pair, so every new round deals a fresh card at rest in the middle.
    val offsetX = remember(bracket.group.key, duel.a.driveId, duel.b.driveId) { Animatable(0f) }
    // The card the swipe reveals starts a hair small, so a new pair reads as dealt rather than as
    // the old one having changed its labels underneath you.
    val dealt = remember(bracket.group.key, duel.a.driveId, duel.b.driveId) { Animatable(0.94f) }
    LaunchedEffect(dealt) { dealt.animateTo(1f, tween(180)) }
    var widthPx by remember { mutableIntStateOf(0) }

    // -1 fully towards A, +1 fully towards B, and the point at which a release commits.
    val lean = if (widthPx == 0) 0f else (offsetX.value / (widthPx * COMMIT_FRACTION)).coerceIn(-1f, 1f)

    fun throwTo(driveId: String) = scope.launch {
        val towardsA = driveId == duel.a.driveId
        offsetX.animateTo(widthPx * if (towardsA) -1.4f else 1.4f, tween(200))
        onKeep(driveId)
    }

    ElevatedCard(
        Modifier
            .fillMaxSize()
            .onSizeChanged { widthPx = it.width }
            .graphicsLayer {
                translationX = offsetX.value
                rotationZ = lean * 5f
                scaleX = dealt.value
                scaleY = dealt.value
            }
            .pointerInput(bracket.group.key, duel.a.driveId, duel.b.driveId) {
                val commitAt = size.width * COMMIT_FRACTION
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val winner = when {
                            offsetX.value <= -commitAt -> duel.a.driveId
                            offsetX.value >= commitAt -> duel.b.driveId
                            else -> null
                        }
                        if (winner != null) {
                            throwTo(winner)
                        } else {
                            scope.launch {
                                offsetX.animateTo(0f, spring(Spring.DampingRatioMediumBouncy))
                            }
                        }
                    },
                    onDragCancel = { scope.launch { offsetX.animateTo(0f, spring()) } },
                ) { change, dragAmount ->
                    change.consume()
                    scope.launch { offsetX.snapTo(offsetX.value + dragAmount) }
                }
            },
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                bracket.group.slug,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(bracket.group.category)
                    bracket.group.level?.let { append(" / $it") }
                    append(" · ${bracket.group.durationLabel}")
                    // Three or four takes are judged as a ladder, so say where in it this is.
                    if (bracket.rounds > 1) append(" · Round ${duel.round} of ${bracket.rounds}")
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Prompt(state)

            Row(
                Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp),
            ) {
                TakePanel(
                    label = "A",
                    take = duel.a,
                    prompt = state.prompts[duel.a.driveId].takeIf { state.mixedPrompts },
                    nowPlaying = nowPlaying,
                    highlight = (-lean).coerceAtLeast(0f),
                    onPlay = onPlay,
                    onTogglePlayPause = onTogglePlayPause,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                TakePanel(
                    label = "B",
                    take = duel.b,
                    prompt = state.prompts[duel.b.driveId].takeIf { state.mixedPrompts },
                    nowPlaying = nowPlaying,
                    highlight = lean.coerceAtLeast(0f),
                    onPlay = onPlay,
                    onTogglePlayPause = onTogglePlayPause,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KeepButton(
                    label = "Keep A",
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    iconFirst = true,
                    onClick = { throwTo(duel.a.driveId) },
                    modifier = Modifier.weight(1f),
                )
                KeepButton(
                    label = "Keep B",
                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    iconFirst = false,
                    onClick = { throwTo(duel.b.driveId) },
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "Tap a side to hear it, swipe the card to the winner",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }
    }
}

/**
 * The prompt, shown once for the pair.
 *
 * Two takes of a cue come from one sentence, so it belongs to the card rather than to either side.
 * The exception is the collision case, where it is the whole point - then each side carries its own
 * and this becomes the warning.
 */
@Composable
private fun Prompt(state: AbTestUiState) {
    when {
        state.mixedPrompts -> Text(
            "These takes came from different prompts. The generator truncates its filenames, so " +
                "unrelated cues can collide - check before judging.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp),
        )

        state.sharedPrompt != null -> Text(
            state.sharedPrompt.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )

        state.loadingPrompts -> Text(
            "Reading the prompt…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** One side of the card: a letter, a fingerprint, and a big target that plays it. */
@Composable
private fun TakePanel(
    label: String,
    take: TrackEntity,
    prompt: String?,
    nowPlaying: NowPlaying,
    highlight: Float,
    onPlay: (String) -> Unit,
    onTogglePlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = nowPlaying.mediaId == take.driveId
    val playing = current && nowPlaying.isPlaying
    Surface(
        modifier
            .fillMaxSize()
            .clickable { if (current) onTogglePlayPause() else onPlay(take.driveId) },
        shape = MaterialTheme.shapes.large,
        color = lerp(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.primaryContainer,
            highlight,
        ),
        border = BorderStroke(
            width = if (current) 2.dp else 1.dp,
            color = if (current) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(label, style = MaterialTheme.typography.headlineSmall)
            Icon(
                if (playing) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                contentDescription = if (playing) "Pause take $label" else "Play take $label",
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .size(52.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                // The eight-character fingerprint is the only thing telling two takes apart by name.
                take.title.substringAfterLast('-'),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "KEEP",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                // Always laid out, so the card does not jump as a drag crosses the threshold, and
                // never spoken: it labels a gesture TalkBack cannot make, and the Keep buttons say
                // the same thing in a form it can.
                modifier = Modifier
                    .padding(top = 6.dp)
                    .alpha(highlight)
                    .clearAndSetSemantics { },
            )
            prompt?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun KeepButton(
    label: String,
    icon: ImageVector,
    iconFirst: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(onClick = onClick, modifier = modifier) {
        if (iconFirst) {
            Icon(icon, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label)
        if (!iconFirst) {
            Spacer(Modifier.width(6.dp))
            Icon(icon, contentDescription = null, Modifier.size(18.dp))
        }
    }
}

@Composable
private fun DeckAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
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
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

/** How far across the card a drag has to travel before letting go commits the swipe. */
private const val COMMIT_FRACTION = 0.25f
