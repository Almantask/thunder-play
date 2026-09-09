package com.thunderplay.ui.abtest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thunderplay.data.TrackDao
import com.thunderplay.data.TrackEntity
import com.thunderplay.library.AbGroup
import com.thunderplay.library.AbGrouping
import com.thunderplay.library.AbVerdict
import com.thunderplay.playback.PlayerConnection
import com.thunderplay.settings.SettingsRepository
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
    Candidates("Candidates"),
    Results("Results"),
}

data class AbTestUiState(
    val tab: AbTab = AbTab.Candidates,
    val candidates: List<AbGroup> = emptyList(),
    val good: List<TrackEntity> = emptyList(),
    val bad: List<TrackEntity> = emptyList(),
    val openGroup: AbGroup? = null,
    val selectedWinner: String? = null,
    /** Drive id to prompt, filled in lazily as groups are opened. */
    val prompts: Map<String, String> = emptyMap(),
    val loadingPrompts: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
) {
    /**
     * True when the open group's takes turn out to have been generated from different prompts.
     *
     * The generator truncates its filename slug at 48 characters, so two unrelated cues can collide
     * on a key. Reading the real prompts is the only way to notice, and noticing has to be the
     * user's job - hence a warning rather than a silent regrouping.
     */
    val mixedPrompts: Boolean
        get() {
            val group = openGroup ?: return false
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
        val tab: AbTab = AbTab.Candidates,
        val openGroupKey: String? = null,
        val selectedWinner: String? = null,
        val busy: Boolean = false,
        val message: String? = null,
        val loadingPrompts: Boolean = false,
    )

    // One state object rather than six flows: combine takes at most five sources, and the
    // interaction fields always change together anyway.
    private val interaction = MutableStateFlow(Interaction())
    private val prompts = MutableStateFlow<Map<String, String>>(emptyMap())

    val uiState: StateFlow<AbTestUiState> = combine(
        trackDao.observeUnjudged(),
        trackDao.observeJudged(),
        prompts,
        interaction,
        settings.settings,
    ) { unjudged, judged, loadedPrompts, ix, _ ->
        val candidates = AbGrouping.candidates(unjudged)
        val (good, bad) = judged.partition { it.abVerdict == AbVerdict.Good.stored }
        AbTestUiState(
            tab = ix.tab,
            candidates = candidates,
            good = good,
            bad = bad,
            openGroup = candidates.firstOrNull { it.key == ix.openGroupKey },
            selectedWinner = ix.selectedWinner,
            prompts = loadedPrompts,
            loadingPrompts = ix.loadingPrompts,
            busy = ix.busy,
            message = ix.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AbTestUiState())

    init {
        player.connect()
    }

    fun selectTab(tab: AbTab) = update { it.copy(tab = tab) }

    fun openGroup(group: AbGroup) {
        update { it.copy(openGroupKey = group.key, selectedWinner = null) }
        loadPrompts(group)
    }

    fun closeGroup() = update { it.copy(openGroupKey = null, selectedWinner = null) }

    /** Winner selection is its own tap: tying it to playback would re-pick on every skip. */
    fun selectWinner(driveId: String) = update { it.copy(selectedWinner = driveId) }

    fun play(group: AbGroup, index: Int) = player.playQueue(group.takes, index)

    fun dismissMessage() = update { it.copy(message = null) }

    /**
     * Suppresses crossfading while judging, and restores it on the way out.
     *
     * Blending two takes of one cue overlaps them, which is exactly what makes a comparison
     * impossible - and manual Next blends by default.
     */
    fun suppressCrossfade(suppress: Boolean) =
        player.setCrossfadeOverride(if (suppress) 0 else null)

    /**
     * Reads each take's prompt from its source WAV.
     *
     * Deliberately per group rather than for the whole tab: a few hundred candidates would mean a
     * few hundred range requests on open, for prompts nobody has asked to see yet.
     */
    private fun loadPrompts(group: AbGroup) {
        val missing = group.takes.filter { it.driveId !in prompts.value }
        if (missing.isEmpty()) return
        viewModelScope.launch {
            update { it.copy(loadingPrompts = true) }
            val found = mutableMapOf<String, String>()
            for (take in missing) {
                val prompt = take.abPrompt ?: abTest.readPrompt(take)?.prompt
                if (prompt != null) found[take.driveId] = prompt
            }
            prompts.value = prompts.value + found
            update { it.copy(loadingPrompts = false) }
        }
    }

    fun judge(group: AbGroup) {
        val winner = interaction.value.selectedWinner ?: return
        val takeIds = group.takes.mapTo(mutableSetOf(), TrackEntity::driveId)
        viewModelScope.launch {
            update { it.copy(busy = true) }
            val result = runCatching {
                abTest.judge(group, winner, prompts.value.filterKeys { it in takeIds })
            }
            update {
                it.copy(
                    busy = false,
                    openGroupKey = null,
                    selectedWinner = null,
                    message = result.fold(::describe) { error ->
                        "Could not file this cue: ${error.message ?: "unknown error"}"
                    },
                )
            }
        }
    }

    private fun describe(result: com.thunderplay.sync.AbJudgeResult): String = buildString {
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
