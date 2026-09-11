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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thunderplay.data.TrackEntity
import com.thunderplay.library.AbBracket
import com.thunderplay.library.AbDuel
import com.thunderplay.library.AbGroup
import com.thunderplay.library.WavInfo
import com.thunderplay.playback.NowPlaying
import com.thunderplay.ui.player.MiniPlayer
import kotlinx.coroutines.launch

/**
 * Picks one keeper per cue, one pair at a time.
 *
 * The card is the screen, and the prompt is the card. What is on trial is the sentence each take
 * was generated from, so that sentence gets the room: the takes themselves shrink to a play control
 * each, and the answer is a swipe towards the side that wins - or a tie, when nothing separates
 * them.
 */
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

    // No title bar: it would only repeat the tab's own label, and every row of chrome is a row the
    // prompt does not get - on a small phone, the difference between a prompt and no prompt.
    Scaffold(
        // Not the bottom: the app's navigation bar sits there and already clears the system one.
        // Padding for it again here left a strip of dead space under the card on gesture-nav phones.
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        ),
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
            Row(
                Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SingleChoiceSegmentedButtonRow {
                    AbTab.entries.forEachIndexed { index, tab ->
                        SegmentedButton(
                            selected = state.tab == tab,
                            onClick = { viewModel.selectTab(tab) },
                            shape = SegmentedButtonDefaults.itemShape(index, AbTab.entries.size),
                        ) { Text(tab.label) }
                    }
                }
                Spacer(Modifier.weight(1f))
                // Up here rather than in a row of their own under the card, which cost the prompt
                // a whole row of height for two buttons that are used far less than it is read.
                val bracket = state.bracket
                if (state.tab == AbTab.Judge && bracket != null) {
                    IconButton(
                        onClick = viewModel::undo,
                        // Nothing is filed until a cue's last round, so an undo can never un-move
                        // a file.
                        enabled = bracket.decided > 0,
                    ) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo") }
                    IconButton(
                        onClick = viewModel::skip,
                        // The last cue in the deck has nowhere to go, so putting it off is a no-op.
                        enabled = state.deck.size > 1,
                    ) { Icon(Icons.Default.Snooze, contentDescription = "Later") }
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

    // Keyed on the round, so a pair that changes underneath the sheet - a refresh, a cue that would
    // not file coming back - does not leave it showing the previous pair's words.
    var reading by remember(bracket.group.key, duel.a.driveId, duel.b.driveId) {
        mutableStateOf<FullPrompt?>(null)
    }
    reading?.let { FullPromptSheet(it, onDismiss = { reading = null }) }

    Column(Modifier.fillMaxSize()) {
        DeckProgress(state)

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
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
                onDraw = viewModel::callDraw,
                onTogglePlayPause = viewModel.player::togglePlayPause,
                onRead = { reading = it },
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
                .padding(top = 4.dp),
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
 * The pair, and the decision.
 *
 * The gesture is horizontal only: a card that can be thrown in four directions turns a two-way
 * question into a memory test, which is also why a tie is a button rather than a third gesture.
 * Every answer is a plain button - a swipe is a nice way to say "this one", not the only way, and
 * TalkBack cannot throw a card.
 */
@Composable
private fun DuelCard(
    duel: AbDuel,
    bracket: AbBracket,
    state: AbTestUiState,
    nowPlaying: NowPlaying,
    onPlay: (String) -> Unit,
    onKeep: (String) -> Unit,
    onDraw: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onRead: (FullPrompt) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val round = Triple(bracket.group.key, duel.a.driveId, duel.b.driveId)
    // Keyed on the round, so every new pair deals a fresh card at rest in the middle.
    val offsetX = remember(round) { Animatable(0f) }
    // A new card starts a hair small so it reads as dealt, and a tie fades the card out where it
    // stands, so it does not look like a swipe that went nowhere.
    val dealt = remember(round) { Animatable(0.94f) }
    val settle = remember(round) { Animatable(1f) }
    LaunchedEffect(dealt) { dealt.animateTo(1f, tween(180)) }
    var widthPx by remember { mutableIntStateOf(0) }

    // -1 fully towards A, +1 fully towards B, and the point at which a release commits.
    val lean = if (widthPx == 0) 0f else (offsetX.value / (widthPx * COMMIT_FRACTION)).coerceIn(-1f, 1f)

    fun throwTo(driveId: String) = scope.launch {
        val towardsA = driveId == duel.a.driveId
        offsetX.animateTo(widthPx * if (towardsA) -1.4f else 1.4f, tween(200))
        onKeep(driveId)
    }

    fun callDraw() = scope.launch {
        settle.animateTo(0f, tween(160))
        onDraw()
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
                alpha = settle.value
            }
            .pointerInput(round) {
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
        // The prompt takes whatever height the card has left over, which is fine until there is
        // none: in landscape, or on a small phone with the mini player up, the fixed rows would
        // squeeze it to nothing. Below that point the prompt gets a height of its own and the card
        // scrolls to its buttons instead - an extra flick is a far better trade than no prompt.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxHeight < COMPACT_CARD_HEIGHT
            Column(
                Modifier
                    .fillMaxSize()
                    .then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
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

                // No slug headline: it is a lowercased, truncated copy of the prompt, so it would spend
                // two lines saying less than the text below. It is the fallback when nothing was read.
                Box(
                    (if (compact) Modifier.height(COMPACT_PROMPT_HEIGHT) else Modifier.weight(1f))
                        .padding(top = 10.dp, bottom = 12.dp),
                ) {
                    if (state.mixedPrompts) {
                        RivalPrompts(duel, state, nowPlaying, lean, onPlay, onTogglePlayPause, onRead)
                    } else {
                        SharedPrompt(
                            duel, bracket.group, state, nowPlaying, lean, onPlay, onTogglePlayPause, onRead,
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KeepButton(
                        label = "Keep A",
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        iconFirst = true,
                        onClick = { throwTo(duel.a.driveId) },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { callDraw() }, contentPadding = ANSWER_PADDING) {
                        Icon(Icons.Default.Balance, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Tie")
                    }
                    KeepButton(
                        label = "Keep B",
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        iconFirst = false,
                        onClick = { throwTo(duel.b.driveId) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "Tap a prompt to read it in full · Tie keeps both",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
            }
        }
    }
}

/**
 * One prompt, the width of the card, with the two takes as controls underneath.
 *
 * Takes of a cue usually share a prompt, and printing the same sentence twice would halve the space
 * the thing on trial gets in exchange for nothing.
 */
@Composable
private fun SharedPrompt(
    duel: AbDuel,
    group: AbGroup,
    state: AbTestUiState,
    nowPlaying: NowPlaying,
    lean: Float,
    onPlay: (String) -> Unit,
    onTogglePlayPause: () -> Unit,
    onRead: (FullPrompt) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        val prompt = state.sharedPrompt
        val unread = state.unreadSide
        // Whose prompt this is. With only one side read it is that side's, not the pair's - the
        // other is an assumption until it has been read too.
        val (owner, source) = when (unread) {
            "A" -> "Take B" to duel.b
            "B" -> "Take A" to duel.a
            else -> "Takes A and B" to duel.a
        }
        FittedPrompts(
            texts = listOf(prompt ?: group.slug),
            onRead = {
                onRead(
                    FullPrompt(
                        owner = owner,
                        fingerprint = source.fingerprint().takeIf { unread != null },
                        text = prompt ?: group.slug,
                        isFilename = prompt == null,
                        take = source,
                    ),
                )
            },
            modifier = Modifier.weight(1f),
        )
        // Without a prompt the slug is all there is, and it has to say it is a filename rather than
        // pass itself off as the sentence the take was generated from.
        val caption = when {
            prompt == null && state.loadingPrompts -> "Reading the prompt…"
            prompt == null -> "No prompt could be read, so this is the filename"
            unread != null && state.loadingPrompts -> "Take $unread's prompt is still being read"
            unread != null -> "Take $unread's prompt could not be read"
            else -> null
        }
        caption?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TakeControl(
                label = "A",
                take = duel.a,
                nowPlaying = nowPlaying,
                highlight = (-lean).coerceAtLeast(0f),
                onPlay = onPlay,
                onTogglePlayPause = onTogglePlayPause,
                modifier = Modifier.weight(1f),
            )
            TakeControl(
                label = "B",
                take = duel.b,
                nowPlaying = nowPlaying,
                highlight = lean.coerceAtLeast(0f),
                onPlay = onPlay,
                onTogglePlayPause = onTogglePlayPause,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Two prompts side by side, each under its own take.
 *
 * The case where the swipe is a judgement about wording rather than about a render, so each
 * sentence gets half the card. It is also the case the 48-character filename truncation creates
 * when two unrelated cues collide on a key, hence the warning above them.
 */
@Composable
private fun RivalPrompts(
    duel: AbDuel,
    state: AbTestUiState,
    nowPlaying: NowPlaying,
    lean: Float,
    onPlay: (String) -> Unit,
    onTogglePlayPause: () -> Unit,
    onRead: (FullPrompt) -> Unit,
) {
    val sides = listOf("Take A" to duel.a, "Take B" to duel.b)
    val prompts = sides.map { (_, take) -> state.prompts[take.driveId] }
    Column(Modifier.fillMaxSize()) {
        // Short on purpose: on a narrow phone every line of it is a line the prompts lose. The
        // cause - two cues whose truncated filenames collided - is in AbGrouping for whoever asks.
        Text(
            "Different prompts - these may be two cues whose filenames collided.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
        // The controls get a row of their own above the prompts rather than one per column, so
        // the prompt area is a single rectangle and both sentences can be fitted into it together.
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TakeControl(
                label = "A",
                take = duel.a,
                nowPlaying = nowPlaying,
                highlight = (-lean).coerceAtLeast(0f),
                onPlay = onPlay,
                onTogglePlayPause = onTogglePlayPause,
                modifier = Modifier.weight(1f),
            )
            TakeControl(
                label = "B",
                take = duel.b,
                nowPlaying = nowPlaying,
                highlight = lean.coerceAtLeast(0f),
                onPlay = onPlay,
                onTogglePlayPause = onTogglePlayPause,
                modifier = Modifier.weight(1f),
            )
        }
        FittedPrompts(
            texts = prompts.map { it ?: "No prompt could be read for this take" },
            onRead = { index ->
                val (owner, take) = sides[index]
                val prompt = prompts[index]
                onRead(
                    FullPrompt(
                        owner = owner,
                        fingerprint = take.fingerprint(),
                        text = prompt ?: take.title,
                        isFilename = prompt == null,
                        take = take,
                    ),
                )
            },
            modifier = Modifier
                .weight(1f)
                .padding(top = 10.dp),
        )
    }
}

/**
 * Prompts in equal columns, at the largest size that fits every one of them, each a tap target
 * that opens it in full.
 *
 * One size for all of them, never one each: fitted separately, the shorter of two prompts would be
 * printed larger, and a comparison should not hand one side a bigger voice. The size never drops
 * below [PromptSizing.MIN_SP] - when even that will not fit, the text is cut with an ellipsis and
 * says to tap, rather than shrinking into something that has to be squinted at.
 */
@Composable
private fun FittedPrompts(
    texts: List<String>,
    onRead: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // A prompt at the generator's cap has lost its tail; the ellipsis says the cut is not ours.
    val shown = texts.map(::withCapMark)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val gapPx = with(density) { COLUMN_GAP.roundToPx() }
        val columnWidth = ((constraints.maxWidth - gapPx * (shown.size - 1)) / shown.size)
            .coerceAtLeast(0)
        val fit = rememberPromptFit(shown, columnWidth, constraints.maxHeight)

        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP)) {
            shown.forEachIndexed { index, text ->
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(onClickLabel = "Read the full prompt") { onRead(index) },
                ) {
                    Text(
                        text,
                        style = fit.style,
                        maxLines = fit.maxLines[index],
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (fit.cut[index]) {
                        Text(
                            "Tap for the full prompt",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The size prompts are drawn at, and how many lines of each fit at it. */
private class PromptFit(
    val style: TextStyle,
    /** Per prompt: every line when it fits, otherwise as many as leave room for the tap hint. */
    val maxLines: List<Int>,
    /** Per prompt: whether it had to be cut even at the readable floor. */
    val cut: List<Boolean>,
)

/**
 * Measures [texts] at each candidate size, largest first, until all of them fit a column
 * [width] wide and [height] tall.
 *
 * Remembered against the texts and the space, so the measuring happens when a card is dealt or the
 * screen changes size, not on every frame of a drag.
 */
@Composable
private fun rememberPromptFit(texts: List<String>, width: Int, height: Int): PromptFit {
    val measurer = rememberTextMeasurer()
    val base = MaterialTheme.typography.headlineMedium.copy(lineHeight = 1.25.em)
    val hintPx = with(LocalDensity.current) { TAP_HINT_HEIGHT.roundToPx() }
    return remember(texts, width, height, base, hintPx) {
        fun layout(sizeSp: Float, text: String): TextLayoutResult = measurer.measure(
            text = text,
            style = base.copy(fontSize = sizeSp.sp),
            constraints = Constraints(maxWidth = width),
        )

        val sizeSp = if (height == Constraints.Infinity) {
            PromptSizing.MAX_SP
        } else {
            PromptSizing.largestFitting { size -> texts.all { layout(size, it).size.height <= height } }
        }
        val layouts = texts.map { layout(sizeSp, it) }
        val cut = layouts.map { height != Constraints.Infinity && it.size.height > height }
        PromptFit(
            style = base.copy(fontSize = sizeSp.sp),
            maxLines = layouts.mapIndexed { index, result ->
                if (!cut[index]) {
                    Int.MAX_VALUE
                } else {
                    (0 until result.lineCount)
                        .count { result.getLineBottom(it) <= height - hintPx }
                        .coerceAtLeast(1)
                }
            },
            cut = cut,
        )
    }
}

/** A prompt opened to be read in full, and whose it is. */
private data class FullPrompt(
    /** "Take A", "Take B", or "Takes A and B" when the pair share it. */
    val owner: String,
    /** The owning take's fingerprint; null when the prompt belongs to both, so no one take is named. */
    val fingerprint: String?,
    /** The prompt - or the filename, when no prompt could be read. */
    val text: String,
    val isFilename: Boolean,
    /** The take the generator's other notes are read from. */
    val take: TrackEntity,
)

/**
 * The whole prompt at a reading size, for when the card's size is wrong for reading.
 *
 * Selectable, because a prompt worth keeping is a prompt worth running again. The generator's own
 * notes on the take sit underneath: its instrument list is the only other place it recorded what
 * the prompt asked for, and on a prompt cut at the cap it can name things the sentence lost.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullPromptSheet(full: FullPrompt, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
            Text(
                listOfNotNull(full.owner, full.fingerprint).joinToString(" · "),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            SelectionContainer {
                Text(
                    withCapMark(full.text),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = PromptSizing.READING_SP.sp,
                        lineHeight = 1.45.em,
                    ),
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            val note = when {
                full.isFilename ->
                    "No prompt could be read from this take's WAV, so this is its filename."
                full.text.length >= WavInfo.PROMPT_CAP ->
                    "The generator keeps only the first ${WavInfo.PROMPT_CAP} characters of a " +
                        "prompt, so the rest was never written to the file."
                else -> null
            }
            note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            val about = listOfNotNull(full.take.genre, full.take.intensity?.let { "Intensity $it" })
            val instruments = full.take.instrumentList
            if (about.isNotEmpty() || instruments.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                if (about.isNotEmpty()) {
                    Text(about.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                }
                if (instruments.isNotEmpty()) {
                    Text(
                        "Instruments: ${instruments.joinToString(", ")}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** The eight hex characters that tell one render of a cue from another. */
private fun TrackEntity.fingerprint(): String = title.substringAfterLast('-')

/** The prompt with an ellipsis when the generator cut it at its cap, so the cut is not read as ours. */
private fun withCapMark(text: String): String =
    if (text.length >= WavInfo.PROMPT_CAP) "$text…" else text

/**
 * One take as a control: which side it is, whether it is the one sounding, and a tap to hear it.
 *
 * Compact on purpose. Two renders are told apart by ear, not by an eight-character fingerprint, so
 * the fingerprint is a label rather than a headline and the prompt gets the space.
 */
@Composable
private fun TakeControl(
    label: String,
    take: TrackEntity,
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
            .fillMaxWidth()
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
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (playing) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                contentDescription = if (playing) "Pause take $label" else "Play take $label",
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.padding(start = 8.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text(
                    // The fingerprint is the only thing telling two takes apart by name, so it
                    // stays on screen - just not at headline size.
                    take.fingerprint(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "KEEP",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                // Always laid out, so the card does not jump as a drag crosses the threshold, and
                // never spoken: it labels a gesture TalkBack cannot make, and the Keep buttons say
                // the same thing in a form it can.
                modifier = Modifier
                    .alpha(highlight)
                    .clearAndSetSemantics { },
            )
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
    Button(onClick = onClick, modifier = modifier, contentPadding = ANSWER_PADDING) {
        if (iconFirst) {
            Icon(icon, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(label, maxLines = 1)
        if (!iconFirst) {
            Spacer(Modifier.width(4.dp))
            Icon(icon, contentDescription = null, Modifier.size(18.dp))
        }
    }
}

@Composable
private fun Results(state: AbTestUiState) {
    if (state.good.isEmpty() && state.drawn.isEmpty() && state.bad.isEmpty()) {
        Empty("Nothing judged yet.")
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item { Section("Kept (${state.good.size})") }
        items(state.good, key = { it.driveId }) { JudgedRow(it, kept = true) }
        if (state.drawn.isNotEmpty()) {
            item { HorizontalDivider() }
            item { Section("Kept as a tie (${state.drawn.size})") }
            items(state.drawn, key = { it.driveId }) { JudgedRow(it, kept = true) }
        }
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

/** Between the two prompt columns, and between the two take controls above them. */
private val COLUMN_GAP = 10.dp

/** Room kept under a cut prompt for the line that says to tap it. */
private val TAP_HINT_HEIGHT = 28.dp

/**
 * Below this the card's fixed rows would leave the prompt too little room to be read, so the card
 * switches to scrolling. Roughly those rows plus four lines of prompt at the readable floor.
 */
private val COMPACT_CARD_HEIGHT = 340.dp

/** The prompt area's own height once the card scrolls: a take control plus several lines of text. */
private val COMPACT_PROMPT_HEIGHT = 240.dp

/** Three answers have to fit one row on a narrow phone, so the buttons give up some padding. */
private val ANSWER_PADDING = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
