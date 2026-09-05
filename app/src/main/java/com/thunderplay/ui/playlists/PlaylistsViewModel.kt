package com.thunderplay.ui.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thunderplay.data.PlaylistEntity
import com.thunderplay.data.TrackDao
import com.thunderplay.playback.PlayerConnection
import com.thunderplay.playlist.PlaylistRepository
import com.thunderplay.playlist.ShareService
import com.thunderplay.ui.library.LibraryEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaylistsUiState(
    val playlists: List<PlaylistEntity> = emptyList(),
    val busy: String? = null,
    /** The playlist the current queue came from, if the track playing still belongs to it. */
    val playingPlaylistId: String? = null,
    val isPlaying: Boolean = false,
) {
    fun isPlaying(playlistId: String): Boolean =
        playlistId == playingPlaylistId && isPlaying
}

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val repo: PlaylistRepository,
    private val trackDao: TrackDao,
    private val shareService: ShareService,
    private val player: PlayerConnection,
) : ViewModel() {

    private val busy = MutableStateFlow<String?>(null)
    private val startedPlaylistId = MutableStateFlow<String?>(null)

    private val _events = MutableSharedFlow<LibraryEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<LibraryEvent> = _events

    val uiState: StateFlow<PlaylistsUiState> = combine(
        repo.observeAll(),
        busy,
        startedPlaylistId,
        player.nowPlaying,
    ) { playlists, busyLabel, startedId, now ->
        // Only claim a playlist is playing while the current track actually belongs to it -
        // otherwise starting something from the Library would leave a stale pause button here.
        val stillOurs = startedId
            ?.let { id -> playlists.firstOrNull { it.id == id } }
            ?.takeIf { now.mediaId != null && now.mediaId in it.trackIds }
            ?.id

        PlaylistsUiState(
            playlists = playlists,
            busy = busyLabel,
            playingPlaylistId = stillOurs,
            isPlaying = now.isPlaying,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistsUiState())

    init {
        player.connect()
        viewModelScope.launch { repo.pull() }
    }

    fun create(name: String) = viewModelScope.launch { repo.create(name) }

    fun delete(id: String) = viewModelScope.launch {
        // Take the link down with the playlist; an orphaned share would keep serving audio.
        repo.find(id)?.shareId?.let { shareService.revoke(it) }
        repo.delete(id)
    }

    /**
     * Starts the playlist, or toggles playback if it is already the queue.
     *
     * Restarting from the top on every tap made the row impossible to pause, which is what the
     * button appeared to promise.
     */
    fun playOrPause(id: String) = viewModelScope.launch {
        if (uiState.value.playingPlaylistId == id) {
            player.togglePlayPause()
            return@launch
        }
        val tracks = tracksOf(id)
        if (tracks.isEmpty()) {
            _events.tryEmit(LibraryEvent.Message("That playlist is empty"))
            return@launch
        }
        startedPlaylistId.value = id
        player.playQueue(tracks, 0)
    }

    fun share(id: String) = viewModelScope.launch {
        val tracks = tracksOf(id)
        if (tracks.isEmpty()) {
            _events.tryEmit(LibraryEvent.Message("Nothing to share"))
            return@launch
        }
        busy.value = "Uploading ${tracks.size} track(s)"
        runCatching { shareService.sharePlaylist(id, tracks) }
            .onSuccess { _events.tryEmit(LibraryEvent.Shared(it)) }
            .onFailure { _events.tryEmit(LibraryEvent.Message(it.message ?: "Share failed")) }
        busy.value = null
    }

    fun revoke(id: String) = viewModelScope.launch {
        val shareId = repo.find(id)?.shareId ?: return@launch
        busy.value = "Revoking link"
        shareService.revoke(shareId)
        repo.setShare(id, null, null)
        busy.value = null
        _events.tryEmit(LibraryEvent.Message("Link revoked and audio deleted"))
    }

    /**
     * Resolves ids to tracks in playlist order.
     *
     * Ids missing from the catalog are dropped rather than failing the whole playlist: a track
     * can have been trashed since it was added.
     */
    private suspend fun tracksOf(id: String) = repo.find(id)?.trackIds.orEmpty()
        .mapNotNull { trackDao.find(it) }
}
