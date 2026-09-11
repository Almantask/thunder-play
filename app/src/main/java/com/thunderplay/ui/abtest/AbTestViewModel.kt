package com.thunderplay.ui.abtest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thunderplay.data.TrackDao
import com.thunderplay.data.TrackEntity
import com.thunderplay.library.AbBracket
import com.thunderplay.library.AbDeck
import com.thunderplay.library.AbDuel
import com.thunderplay.library.AbGroup
import com.thunderplay.library.AbGrouping
import com.thunderplay.library.AbVerdict
import com.thunderplay.playback.PlayerConnection
import com.thunderplay.settings.SettingsRepository
import com.thunderplay.sync.AbJudgeResult
import com.thunderplay.sync.AbTestService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AbTab(val label: String) {
    Judge("Judge"),
    Results("Results"),
}

data class AbTestUiState(
    val tab: AbTab = AbTab.Judge,
    /** Cues still to judge, in the order they will come up. The first one is on the card. */
    val deck: List<AbGroup> = emptyList(),
    /** The ladder for the cue on top of the deck. */
    val bracket: AbBracket? = null,
    val good: List<TrackEntity> = emptyList(),
    val drawn: List<TrackEntity> = emptyList(),
    val bad: List<TrackEntity> = emptyList(),
    /**
     * Drive id to prompt for every take in the deck that has one.
     *
     * Mostly straight from Room, where the metadata indexer has already put the prompt of every
     * track it has reached; a network read fills in only the takes it has not got to yet.
     */
    val prompts: Map<String, String> = emptyMap(),
    val loadingPrompts: Boolean = false,
    /** How many cues are still being written to Drive. */
    val filing: Int = 0,
    /** Cues filed since the screen was opened, which is the only visible sense of progress. */
    val filed: Int = 0,
    val message: String? = null,
) {
    val duel: AbDuel? get() = bracket?.duel

    /**
     * The prompt for the card, when the two takes on it do not disagree about it.
     *
     * Takes of one cue almost always share a prompt, so showing it on each side would spend half
     * the card on a duplicate. When only one side's has been read it is still shown - it is far
     * more likely to be the cue's prompt than not, and [unreadSide] says which side is unconfirmed.
     * When they disagree see [mixedPrompts] - that is the case worth spending the room on.
     */
    val sharedPrompt: String?
        get() {
            val duel = duel ?: return null
            if (mixedPrompts) return null
            return prompts[duel.a.driveId] ?: prompts[duel.b.driveId]
        }

    /** "A" or "B" when exactly one side of the card has a prompt, so the other is unconfirmed. */
    val unreadSide: String?
        get() {
            val duel = duel ?: return null
            val a = duel.a.driveId in prompts
            val b = duel.b.driveId in prompts
            return when {
                a && !b -> "B"
                b && !a -> "A"
                else -> null
            }
        }

    /**
     * True when the two takes on the card were generated from different prompts.
     *
     * Judged per card rather than per cue: the card is what the decision is about, and a third
     * take with a different prompt says nothing about the pair in front of you until it comes up.
     * The generator truncates its filename slug at 48 characters, so two unrelated cues can
     * collide on a key; reading the real prompts is the only way to notice, and noticing has to
     * be the user's job - hence a warning rather than a silent regrouping.
     */
    val mixedPrompts: Boolean
        get() {
            val duel = duel ?: return false
            val a = prompts[duel.a.driveId] ?: return false
            val b = prompts[duel.b.driveId] ?: return false
            return a != b
        }
}

@HiltViewModel
class AbTestViewModel @Inject constructor(
    private val trackDao: TrackDao,
    private val abTest: AbTestService,
    private val settings: SettingsRepository,
    val player: PlayerConnection,
) : ViewModel() {

    private data class Interaction(
        val tab: AbTab = AbTab.Judge,
        /** The cue [outcomes] belong to; any other cue on top of the deck starts a fresh ladder. */
        val cueKey: String? = null,
        val outcomes: List<String?> = emptyList(),
        val skipped: List<String> = emptyList(),
        val filing: Set<String> = emptySet(),
        val filed: Set<String> = emptySet(),
        val message: String? = null,
        val loadingPrompts: Boolean = false,
    )

    // One state object rather than eight flows: combine takes at most five sources, and the
    // interaction fields always change together anyway.
    private val interaction = MutableStateFlow(Interaction())
    /** Prompts read over the network, for takes the indexer had not reached. */
    private val readPrompts = MutableStateFlow<Map<String, String>>(emptyMap())
    private val promptsInFlight = mutableSetOf<String>()

    val uiState: StateFlow<AbTestUiState> = combine(
        trackDao.observeUnjudged(),
        trackDao.observeJudged(),
        readPrompts,
        interaction,
        settings.settings,
    ) { unjudged, judged, read, ix, _ ->
        val deck = AbDeck.order(AbGrouping.candidates(unjudged), ix.skipped, ix.filing + ix.filed)
        val batches = judged.groupBy { AbVerdict.from(it.abVerdict) }
        AbTestUiState(
            tab = ix.tab,
            deck = deck,
            bracket = deck.firstOrNull()?.let { group ->
                // Outcomes are remembered per cue, so a refresh landing mid-ladder does not throw
                // away the rounds already decided.
                if (group.key == ix.cueKey) {
                    AbBracket.resume(group, ix.outcomes)
                } else {
                    AbBracket(group)
                }
            },
            good = batches[AbVerdict.Good].orEmpty(),
            drawn = batches[AbVerdict.Tie].orEmpty(),
            bad = batches[AbVerdict.Bad].orEmpty(),
            prompts = promptsFor(deck, read),
            loadingPrompts = ix.loadingPrompts,
            filing = ix.filing.size,
            filed = ix.filed.size,
            message = ix.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AbTestUiState())

    init {
        player.connect()
    }

    fun selectTab(tab: AbTab) = update { it.copy(tab = tab) }

    fun dismissMessage() = update { it.copy(message = null) }

    /**
     * Records the winner of the round on the card.
     *
     * A cue's last round files it and the deck moves on immediately rather than waiting on Drive:
     * the swipe is the decision, and a round trip per cue would make clearing a backlog feel like
     * filling in a form. A cue that will not file comes back to the deck with a message.
     */
    fun keep(driveId: String) = advance(uiState.value.bracket?.keep(driveId))

    /**
     * Records that nothing separated the two takes on the card.
     *
     * Both are kept, and on a three or four take cue the ladder carries on with the next
     * challenger - a draw is an answer to the question on screen, not a way of putting it off.
     * That is what *Later* is for.
     */
    fun callDraw() = advance(uiState.value.bracket?.draw())

    private fun advance(next: AbBracket?) {
        if (next == null) return
        val state = uiState.value
        if (!next.finished) {
            update { it.copy(cueKey = next.group.key, outcomes = next.outcomes) }
            // The take holding the card has just been heard; the challenger is the new one.
            follow(next.duel, next.duel?.b?.driveId)
            return
        }
        file(next)
        // Room has not caught up yet, so the cue behind this one is the one coming up.
        val upNext = state.deck.getOrNull(1)?.let(::AbBracket)
        follow(upNext?.duel, upNext?.duel?.a?.driveId)
    }

    /** Takes back the last decision. Nothing is filed until a cue's last round, so this is safe. */
    fun undo() {
        val bracket = uiState.value.bracket ?: return
        if (bracket.decided == 0) return
        val back = bracket.undo()
        update { it.copy(cueKey = back.group.key, outcomes = back.outcomes) }
    }

    /** Puts this cue off: it goes to the back of the deck, and its part-judged ladder is dropped. */
    fun skip() {
        val key = uiState.value.bracket?.group?.key ?: return
        update {
            it.copy(
                skipped = it.skipped.filterNot { skipped -> skipped == key } + key,
                cueKey = null,
                outcomes = emptyList(),
            )
        }
    }

    /**
     * Plays one side of the card.
     *
     * Both takes go into the queue, so Next - on the mini player, the notification or a headset
     * button - switches between them, which is how the comparison actually gets made.
     */
    fun play(duel: AbDuel, driveId: String) {
        val pair = listOf(duel.a, duel.b)
        player.playQueue(pair, pair.indexOfFirst { it.driveId == driveId }.coerceAtLeast(0))
    }

    /** Carries audio into the next pair, but never starts it: a silent screen stays silent. */
    private fun follow(duel: AbDuel?, driveId: String?) {
        if (duel == null || driveId == null) return
        if (!player.nowPlaying.value.isPlaying) return
        play(duel, driveId)
    }

    /**
     * Suppresses crossfading while judging, and restores it on the way out.
     *
     * Blending two takes of one cue overlaps them, which is exactly what makes a comparison
     * impossible - and manual Next blends by default.
     */
    fun suppressCrossfade(suppress: Boolean) =
        player.setCrossfadeOverride(if (suppress) 0 else null)

    /**
     * Reads the prompts Room does not have yet, for the card on screen and the one behind it.
     *
     * Usually a no-op: the metadata indexer stores every track's prompt, so a network read is only
     * needed for takes it has not reached - a fresh install, or renders that landed since the last
     * pass. Deliberately not the whole deck even then: that would be a few hundred range requests
     * for prompts nobody has reached yet, and one card ahead is enough for them to be there by the
     * time the swipe lands.
     *
     * Each prompt is published the moment it arrives rather than with the batch, so the card on
     * screen is not kept waiting on the reads for the one behind it.
     */
    fun prefetchPrompts() {
        val known = uiState.value.prompts
        val missing = uiState.value.deck.take(2)
            .flatMap { it.takes }
            .distinctBy { it.driveId }
            .filter { it.driveId !in known && it.driveId !in promptsInFlight }
        if (missing.isEmpty()) return
        promptsInFlight += missing.map(TrackEntity::driveId)
        update { it.copy(loadingPrompts = true) }
        viewModelScope.launch {
            for (take in missing) {
                val prompt = runCatching { abTest.readPrompt(take)?.prompt }.getOrNull()
                if (prompt != null) readPrompts.value = readPrompts.value + (take.driveId to prompt)
                promptsInFlight -= take.driveId
            }
            update { it.copy(loadingPrompts = promptsInFlight.isNotEmpty()) }
        }
    }

    /**
     * Every deck take's prompt, preferring what Room has.
     *
     * The indexed prompt wins over one captured at judging time, and both over a network read:
     * Room is refreshed whenever the file's checksum changes, which a read cached in this view
     * model never would be.
     */
    private fun promptsFor(deck: List<AbGroup>, read: Map<String, String>): Map<String, String> =
        buildMap {
            for (take in deck.asSequence().flatMap { it.takes }) {
                (take.prompt ?: take.abPrompt ?: read[take.driveId])?.let { put(take.driveId, it) }
            }
        }

    private fun file(bracket: AbBracket) {
        val group = bracket.group
        val takeIds = group.takes.mapTo(mutableSetOf(), TrackEntity::driveId)
        update { it.copy(filing = it.filing + group.key, cueKey = null, outcomes = emptyList()) }
        viewModelScope.launch {
            val result = runCatching {
                abTest.judge(
                    group = group,
                    winnerDriveId = bracket.winner?.driveId,
                    prompts = uiState.value.prompts.filterKeys { it in takeIds },
                    tiedDriveIds = bracket.tied.mapTo(mutableSetOf(), TrackEntity::driveId),
                )
            }
            update { ix ->
                ix.copy(
                    filing = ix.filing - group.key,
                    // Room drops a filed cue from the candidate list a moment later; holding its
                    // key until then is what stops it flashing back onto the card. Held only when
                    // every take moved: a cue that half-filed has to be reachable to be retried,
                    // and Room is the one that decides whether anything of it is still a candidate.
                    filed = if (result.getOrNull()?.failed?.isEmpty() == true) {
                        ix.filed + group.key
                    } else {
                        ix.filed
                    },
                    message = result.fold(::describe) { error ->
                        "Could not file this cue: ${error.message ?: "unknown error"}"
                    },
                )
            }
        }
    }

    private fun describe(result: AbJudgeResult): String = buildString {
        append("Filed ${result.moved} take${if (result.moved == 1) "" else "s"}")
        if (result.failed.isNotEmpty()) {
            append("; ${result.failed.size} would not move")
        }
        if (result.orphanedSources.isNotEmpty()) {
            // Worth saying out loud: a transcode run regenerates the .m4a from a WAV left behind,
            // and the cue reappears as a candidate.
            append("; moved audio but the source WAV stayed put for ${result.orphanedSources.size}")
        }
    }

    private fun update(block: (Interaction) -> Interaction) {
        interaction.value = block(interaction.value)
    }
}
