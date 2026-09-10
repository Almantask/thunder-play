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
    val bad: List<TrackEntity> = emptyList(),
    /** Drive id to prompt, filled in as cues come up. */
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
     * The prompt both takes were generated from, when they agree on it.
     *
     * Takes of one cue almost always share a prompt, so showing it on each side would spend half
     * the card on a duplicate. When they disagree see [mixedPrompts] - that is the case worth
     * spending the room on.
     */
    val sharedPrompt: String?
        get() {
            val duel = duel ?: return null
            val a = prompts[duel.a.driveId] ?: return null
            return a.takeIf { it == prompts[duel.b.driveId] }
        }

    /**
     * True when the cue's takes turn out to have been generated from different prompts.
     *
     * The generator truncates its filename slug at 48 characters, so two unrelated cues can collide
     * on a key. Reading the real prompts is the only way to notice, and noticing has to be the
     * user's job - hence a warning rather than a silent regrouping.
     */
    val mixedPrompts: Boolean
        get() {
            val group = bracket?.group ?: return false
            val known = group.takes.mapNotNull { prompts[it.driveId] }
            return known.size > 1 && known.distinct().size > 1
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
        /** The cue [picks] belong to; any other cue on top of the deck starts a fresh ladder. */
        val cueKey: String? = null,
        val picks: List<String> = emptyList(),
        val skipped: List<String> = emptyList(),
        val filing: Set<String> = emptySet(),
        val filed: Set<String> = emptySet(),
        val message: String? = null,
        val loadingPrompts: Boolean = false,
    )

    // One state object rather than eight flows: combine takes at most five sources, and the
    // interaction fields always change together anyway.
    private val interaction = MutableStateFlow(Interaction())
    private val prompts = MutableStateFlow<Map<String, String>>(emptyMap())
    private val promptsInFlight = mutableSetOf<String>()

    val uiState: StateFlow<AbTestUiState> = combine(
        trackDao.observeUnjudged(),
        trackDao.observeJudged(),
        prompts,
        interaction,
        settings.settings,
    ) { unjudged, judged, loadedPrompts, ix, _ ->
        val deck = AbDeck.order(AbGrouping.candidates(unjudged), ix.skipped, ix.filing + ix.filed)
        val (good, bad) = judged.partition { it.abVerdict == AbVerdict.Good.stored }
        AbTestUiState(
            tab = ix.tab,
            deck = deck,
            bracket = deck.firstOrNull()?.let { group ->
                // Picks are remembered per cue, so a refresh landing mid-ladder does not throw
                // away the rounds already swiped.
                if (group.key == ix.cueKey) AbBracket.resume(group, ix.picks) else AbBracket(group)
            },
            good = good,
            bad = bad,
            prompts = loadedPrompts,
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
    fun keep(driveId: String) {
        val state = uiState.value
        val next = state.bracket?.keep(driveId) ?: return
        val winner = next.winner
        if (winner == null) {
            update { it.copy(cueKey = next.group.key, picks = next.picks) }
            // The leader has just been heard; the challenger is the one that needs listening to.
            follow(next.duel, next.duel?.b?.driveId)
            return
        }
        file(next.group, winner)
        // Room has not caught up yet, so the cue behind this one is the one coming up.
        val upNext = state.deck.getOrNull(1)?.let(::AbBracket)
        follow(upNext?.duel, upNext?.duel?.a?.driveId)
    }

    /** Takes back the last swipe. Nothing is filed until a cue's last round, so this is safe. */
    fun undo() {
        val bracket = uiState.value.bracket ?: return
        if (bracket.decided == 0) return
        val back = bracket.undo()
        update { it.copy(cueKey = back.group.key, picks = back.picks) }
    }

    /** Puts this cue off: it goes to the back of the deck, and its part-judged ladder is dropped. */
    fun skip() {
        val key = uiState.value.bracket?.group?.key ?: return
        update {
            it.copy(
                skipped = it.skipped.filterNot { skipped -> skipped == key } + key,
                cueKey = null,
                picks = emptyList(),
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
     * Reads the prompts for the card on screen and the one behind it.
     *
     * Deliberately not the whole deck: a few hundred candidates would mean a few hundred range
     * requests on open, for prompts nobody has reached yet. One card ahead is enough for them to
     * be there by the time the swipe lands.
     */
    fun prefetchPrompts() {
        val missing = uiState.value.deck.take(2)
            .flatMap { it.takes }
            .distinctBy { it.driveId }
            .filter { it.driveId !in prompts.value && it.driveId !in promptsInFlight }
        if (missing.isEmpty()) return
        val ids = missing.mapTo(mutableSetOf(), TrackEntity::driveId)
        promptsInFlight += ids
        viewModelScope.launch {
            update { it.copy(loadingPrompts = true) }
            val found = mutableMapOf<String, String>()
            for (take in missing) {
                val prompt = take.abPrompt ?: abTest.readPrompt(take)?.prompt
                if (prompt != null) found[take.driveId] = prompt
            }
            prompts.value = prompts.value + found
            promptsInFlight -= ids
            update { it.copy(loadingPrompts = promptsInFlight.isNotEmpty()) }
        }
    }

    private fun file(group: AbGroup, winner: TrackEntity) {
        val takeIds = group.takes.mapTo(mutableSetOf(), TrackEntity::driveId)
        update { it.copy(filing = it.filing + group.key, cueKey = null, picks = emptyList()) }
        viewModelScope.launch {
            val result = runCatching {
                abTest.judge(group, winner.driveId, prompts.value.filterKeys { it in takeIds })
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
