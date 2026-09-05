package com.thunderplay.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thunderplay.data.PlayDao
import com.thunderplay.data.TrackDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.TimeUnit
import javax.inject.Inject

enum class HistoryWindow(val label: String, val spanMs: Long?) {
    Week("7 days", TimeUnit.DAYS.toMillis(7)),
    Month("30 days", TimeUnit.DAYS.toMillis(30)),
    AllTime("All time", null),
}

data class TopRow(val trackId: String, val title: String, val category: String, val plays: Int, val totalMs: Long)

data class RecentRow(val id: String, val title: String, val startedAt: Long, val msPlayed: Long)

data class HistoryUiState(
    val window: HistoryWindow = HistoryWindow.Month,
    val top: List<TopRow> = emptyList(),
    val recent: List<RecentRow> = emptyList(),
    val listeningMs: Long = 0,
) {
    val summary: String
        get() {
            if (listeningMs <= 0) return "No listening recorded in this period"
            val minutes = TimeUnit.MILLISECONDS.toMinutes(listeningMs)
            val label = if (minutes < 60) {
                "$minutes minutes"
            } else {
                "${minutes / 60}h ${minutes % 60}m"
            }
            return "$label of listening"
        }
}

/**
 * History reads entirely from the local Room rollup.
 *
 * The play log lives in Firestore, but aggregating there on every render would burn reads for no
 * benefit; a cursor-based pull keeps Room current and the queries here cost nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val playDao: PlayDao,
    private val trackDao: TrackDao,
) : ViewModel() {

    private val window = MutableStateFlow(HistoryWindow.Month)

    private val data = window.flatMapLatest { selected ->
        val since = selected.spanMs?.let { System.currentTimeMillis() - it } ?: 0L
        combine(
            playDao.observeTopSince(since, TOP_LIMIT),
            playDao.observeRecent(RECENT_LIMIT),
            playDao.observeListeningMsSince(since),
            trackDao.observeAll(),
        ) { top, recent, listeningMs, tracks ->
            val titles = tracks.associateBy { it.driveId }
            HistoryUiState(
                window = selected,
                top = top.mapNotNull { row ->
                    val track = titles[row.trackId] ?: return@mapNotNull null
                    TopRow(row.trackId, track.title, track.category, row.plays, row.totalMs)
                },
                recent = recent.map { play ->
                    RecentRow(
                        id = play.id,
                        // A trashed track still belongs in history, so fall back to its id.
                        title = titles[play.trackId]?.title ?: "(removed track)",
                        startedAt = play.startedAt,
                        msPlayed = play.msPlayed,
                    )
                },
                listeningMs = listeningMs,
            )
        }
    }

    val uiState: StateFlow<HistoryUiState> =
        data.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun setWindow(next: HistoryWindow) {
        window.value = next
    }

    private companion object {
        const val TOP_LIMIT = 20
        const val RECENT_LIMIT = 50
    }
}
