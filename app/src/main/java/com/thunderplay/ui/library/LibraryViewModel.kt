package com.thunderplay.ui.library

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thunderplay.data.PlaylistEntity
import com.thunderplay.diagnostics.DiagnosticsLog
import com.thunderplay.drive.DriveApiException
import com.thunderplay.data.TrackDao
import com.thunderplay.data.TrackEntity
import com.thunderplay.drive.ServiceAccountAuth
import com.thunderplay.library.LibraryView
import com.thunderplay.library.ordered
import com.thunderplay.playback.DownloadsRepository
import com.thunderplay.playback.LocalState
import com.thunderplay.playback.PlayerConnection
import com.thunderplay.playlist.PlaylistRepository
import com.thunderplay.playlist.ShareLink
import com.thunderplay.playlist.ShareService
import com.thunderplay.playlist.TrackExporter
import com.thunderplay.stats.StatsGateway
import com.thunderplay.sync.IndexProgress
import com.thunderplay.sync.LibraryRefresher
import com.thunderplay.sync.MetadataIndexer
import com.thunderplay.sync.RefreshProgress
import com.thunderplay.sync.RefreshResult
import com.thunderplay.sync.TrashService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

sealed interface RefreshState {
    data object Idle : RefreshState

    /** Tracks found so far are already visible; this is just the running commentary. */
    data class Running(
        val tracksFound: Int = 0,
        val foldersScanned: Int = 0,
        val linking: Boolean = false,
        /** Set while WAV headers are being read, which happens after the walk has finished. */
        val reading: Reading? = null,
    ) : RefreshState {
        data class Reading(val done: Int, val total: Int)

        val label: String
            get() = when {
                reading != null -> "Reading details ${reading.done} of ${reading.total}..."
                linking -> "Matching source files..."
                tracksFound == 0 -> "Scanning Drive..."
                else -> "Found $tracksFound track(s) in $foldersScanned folder(s)..."
            }
    }

    data class Done(val result: RefreshResult) : RefreshState
    data class Failed(val message: String) : RefreshState
}

/** One-shot outcomes the screen reacts to, rather than state it renders. */
sealed interface LibraryEvent {
    data class Message(val text: String) : LibraryEvent
    data class Launch(val intent: Intent) : LibraryEvent
    data class Shared(val link: ShareLink) : LibraryEvent
}

data class LibraryUiState(
    val view: LibraryView = LibraryView(),
    val tracks: List<TrackEntity> = emptyList(),
    val categories: List<String> = emptyList(),
    val levels: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val playlists: List<PlaylistEntity> = emptyList(),
    val localStates: Map<String, LocalState> = emptyMap(),
    val refresh: RefreshState = RefreshState.Idle,
    val driveConfigured: Boolean = true,
    val busy: String? = null,
    /** Drive ids the user has ticked. Deliberately survives filter changes. */
    val selection: Set<String> = emptySet(),
    val libraryTotal: Int = 0,
) {
    val isEmpty: Boolean get() = tracks.isEmpty()

    fun localStateOf(driveId: String): LocalState = localStates[driveId] ?: LocalState.Remote

    val downloadedCount: Int
        get() = tracks.count { localStateOf(it.driveId) == LocalState.Downloaded }

    /** A tick on any row is what puts the list into selection mode; clearing leaves it. */
    val selectionMode: Boolean get() = selection.isNotEmpty()

    fun isSelected(driveId: String): Boolean = driveId in selection

    /** How many of the selected tracks are actually on the device, so Remove reads honestly. */
    val selectedDownloaded: Int
        get() = selection.count { localStates[it] == LocalState.Downloaded }

    val selectedNotDownloaded: Int get() = selection.size - selectedDownloaded

    /** True when every visible row is already ticked, so the action can be hidden. */
    val allVisibleSelected: Boolean
        get() = tracks.isNotEmpty() && tracks.all { it.driveId in selection }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val trackDao: TrackDao,
    private val refresher: LibraryRefresher,
    private val indexer: MetadataIndexer,
    private val auth: ServiceAccountAuth,
    private val downloads: DownloadsRepository,
    private val stats: StatsGateway,
    private val playlistRepo: PlaylistRepository,
    private val shareService: ShareService,
    private val exporter: TrackExporter,
    private val trash: TrashService,
    private val diagnostics: DiagnosticsLog,
    val player: PlayerConnection,
) : ViewModel() {

    private val view = MutableStateFlow(LibraryView())
    private val refreshState = MutableStateFlow<RefreshState>(RefreshState.Idle)
    private val busy = MutableStateFlow<String?>(null)
    private val selection = MutableStateFlow<Set<String>>(emptySet())

    private val _events = MutableSharedFlow<LibraryEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<LibraryEvent> = _events

    // Filtering happens in SQL; ordering is applied here so the seeded shuffle stays stable.
    private val filtered = view.flatMapLatest { v ->
        trackDao.observeTracks(
            category = v.category,
            level = v.level,
            genre = v.genre,
            minStars = v.stars.minimum,
            query = v.query.trim(),
        )
    }

    /** The values the dropdowns offer, folded together so the main combine stays within five. */
    private val facets = combine(
        trackDao.observeCategories(),
        trackDao.observeLevels(),
        trackDao.observeGenres(),
        ::Facets,
    )

    private data class Facets(
        val categories: List<String>,
        val levels: List<String>,
        val genres: List<String>,
    )

    private val listState = combine(
        view,
        filtered,
        facets,
        trackDao.observeAll(),
    ) { v, t, f, all ->
        ListSnapshot(v, t.ordered(v), f, all.size)
    }

    private data class ListSnapshot(
        val view: LibraryView,
        val tracks: List<TrackEntity>,
        val facets: Facets,
        val libraryTotal: Int,
    )

    private data class Interaction(val busy: String?, val selection: Set<String>)

    // combine() only has typed overloads up to five flows, so the two smallest are folded first.
    private val interaction = combine(busy, selection, ::Interaction)

    val uiState: StateFlow<LibraryUiState> = combine(
        listState,
        downloads.observeStates(),
        playlistRepo.observeAll(),
        refreshState,
        interaction,
    ) { snapshot, localStates, playlists, refresh, interacting ->
        LibraryUiState(
            view = snapshot.view,
            tracks = snapshot.tracks,
            categories = snapshot.facets.categories,
            levels = snapshot.facets.levels,
            genres = snapshot.facets.genres,
            playlists = playlists,
            localStates = localStates,
            refresh = refresh,
            driveConfigured = auth.isConfigured(),
            busy = interacting.busy,
            selection = interacting.selection,
            libraryTotal = snapshot.libraryTotal,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    init {
        player.connect()
    }

    // ------------------------------------------------------------- view state

    fun setStars(stars: LibraryView.Stars) = update { it.copy(stars = stars) }

    fun setCategory(category: String?) = update { it.copy(category = category) }

    fun setLevel(level: String?) = update { it.copy(level = level) }

    fun setGenre(genre: String?) = update { it.copy(genre = genre) }

    /** Resets the three dropdowns. The search text is left alone; it is visibly its own control. */
    fun clearFilters() = update { it.cleared() }

    fun setOrder(order: LibraryView.Order) = update {
        // Entering Random needs a fresh seed, or it would repeat the previous permutation.
        if (order == LibraryView.Order.Random) it.reshuffled() else it.copy(order = order)
    }

    fun reshuffle() = update { it.reshuffled(Random.nextLong()) }

    fun setQuery(query: String) = update { it.copy(query = query) }

    // ------------------------------------------------------------- playback

    /** Plays the visible list from [index]; the queue is always what the user is looking at. */
    fun play(index: Int) {
        val tracks = uiState.value.tracks
        if (index in tracks.indices) player.playQueue(tracks, index)
    }

    fun shuffleAndPlay() {
        val seed = Random.nextLong()
        update { it.reshuffled(seed) }
        // Compute the same permutation here rather than waiting for the next state emission.
        val shuffled = uiState.value.tracks.ordered(view.value)
        if (shuffled.isNotEmpty()) player.playQueue(shuffled, 0)
    }

    // ------------------------------------------------------------- stats

    fun setRating(driveId: String, rating: Int) = viewModelScope.launch {
        stats.setRating(driveId, rating)
    }

    fun toggleLike(driveId: String) = viewModelScope.launch { stats.toggleLike(driveId) }

    // ------------------------------------------------------------- downloads

    fun download(track: TrackEntity) = downloads.download(track)

    fun removeDownload(driveId: String) = downloads.removeDownload(driveId)

    /** Pre-fetches everything currently visible, so sync respects the active filter. */
    fun syncVisible() {
        val pending = uiState.value.tracks.filter {
            uiState.value.localStateOf(it.driveId) != LocalState.Downloaded
        }
        downloads.downloadAll(pending)
        emit(LibraryEvent.Message("Queued ${pending.size} download(s)"))
    }

    // ------------------------------------------------------------- selection

    fun toggleSelection(driveId: String) {
        selection.value = selection.value.let {
            if (driveId in it) it - driveId else it + driveId
        }
    }

    /**
     * Adds everything the current filter is showing.
     *
     * The selection is not reset when the filter changes, so this can be used repeatedly to build
     * up a set across several categories.
     */
    fun selectAllVisible() {
        selection.value = selection.value + uiState.value.tracks.map { it.driveId }
    }

    /** Adds the whole library, ignoring the active filter. */
    fun selectAll() = viewModelScope.launch {
        selection.value = selection.value + trackDao.allActive().map { it.driveId }
    }

    fun clearSelection() {
        selection.value = emptySet()
    }

    /** Queues every selected track that is not already on the device. */
    fun downloadSelected() = viewModelScope.launch {
        val ids = selection.value
        if (ids.isEmpty()) return@launch
        val local = uiState.value.localStates
        val pending = trackDao.allActive()
            .filter { it.driveId in ids && local[it.driveId] != LocalState.Downloaded }

        if (pending.isEmpty()) {
            emit(LibraryEvent.Message("Those ${ids.size} track(s) are already downloaded"))
            return@launch
        }
        downloads.downloadAll(pending)
        emit(LibraryEvent.Message("Queued ${pending.size} download(s)"))
        clearSelection()
    }

    /** Frees space by deleting the local copies; the tracks stay listed and streamable. */
    fun removeSelectedDownloads() {
        val ids = selection.value
        val local = uiState.value.localStates
        val downloaded = ids.filter { local[it] == LocalState.Downloaded }

        if (downloaded.isEmpty()) {
            emit(LibraryEvent.Message("None of those are downloaded"))
            return
        }
        downloaded.forEach(downloads::removeDownload)
        emit(LibraryEvent.Message("Removed ${downloaded.size} download(s)"))
        clearSelection()
    }

    // ------------------------------------------------------------- playlists

    fun createPlaylistFromView(name: String) = viewModelScope.launch {
        val ids = uiState.value.tracks.map { it.driveId }
        playlistRepo.create(name, ids)
        emit(LibraryEvent.Message("Created \"$name\" with ${ids.size} track(s)"))
    }

    fun addToPlaylist(playlistId: String, driveId: String) = viewModelScope.launch {
        playlistRepo.addTracks(playlistId, listOf(driveId))
        emit(LibraryEvent.Message("Added to playlist"))
    }

    /** Adds the whole selection at once; one-by-one is unusable at 40 tracks. */
    fun addSelectionToPlaylist(playlistId: String) = viewModelScope.launch {
        val ids = selection.value.toList()
        if (ids.isEmpty()) return@launch
        val before = playlistRepo.find(playlistId)?.trackIds.orEmpty().size
        playlistRepo.addTracks(playlistId, ids)
        val added = (playlistRepo.find(playlistId)?.trackIds.orEmpty().size) - before

        // Report what actually changed: silently ignoring duplicates looks like a failure.
        val skipped = ids.size - added
        emit(
            LibraryEvent.Message(
                if (skipped > 0) "Added $added track(s); $skipped already there"
                else "Added $added track(s)",
            ),
        )
        clearSelection()
    }

    /** Creates a playlist straight from the selection, so a set can be captured in one step. */
    fun createPlaylistFromSelection(name: String) = viewModelScope.launch {
        val ids = selection.value.toList()
        if (ids.isEmpty()) return@launch
        playlistRepo.create(name, ids)
        emit(LibraryEvent.Message("Created \"$name\" with ${ids.size} track(s)"))
        clearSelection()
    }

    /** Publishes just the selection, rather than everything the filter happens to show. */
    fun shareSelection(name: String) = viewModelScope.launch {
        val ids = selection.value
        val tracks = trackDao.allActive().filter { it.driveId in ids }
        if (tracks.isEmpty()) return@launch
        busy.value = "Uploading ${tracks.size} track(s)"
        runCatching { shareService.createShare(name, tracks) }
            .onSuccess {
                emit(LibraryEvent.Shared(it))
                clearSelection()
            }
            .onFailure {
                diagnostics.error("Share", "Sharing the selection failed", it)
                emit(LibraryEvent.Message(readable(it)))
            }
        busy.value = null
    }

    // ------------------------------------------------------------- sharing

    fun shareTrack(track: TrackEntity) = viewModelScope.launch {
        busy.value = "Preparing ${track.title}"
        runCatching { exporter.export(track) }
            .onSuccess { emit(LibraryEvent.Launch(exporter.shareIntent(it, track.title))) }
            .onFailure {
                diagnostics.error("Export", "Exporting ${track.title} failed", it)
                emit(LibraryEvent.Message("Could not share: " + readable(it)))
            }
        busy.value = null
    }

    /**
     * Publishes whatever the library is currently showing.
     *
     * This is what makes "share everything I have liked" a single action: filter to Liked, then
     * share the view, with no playlist to assemble first.
     */
    fun shareCurrentView(name: String) = viewModelScope.launch {
        val tracks = uiState.value.tracks
        busy.value = "Uploading ${tracks.size} track(s)"
        runCatching { shareService.createShare(name, tracks) }
            .onSuccess { emit(LibraryEvent.Shared(it)) }
            .onFailure {
                diagnostics.error("Share", "Creating share link failed", it)
                emit(LibraryEvent.Message(readable(it)))
            }
        busy.value = null
    }

    // ------------------------------------------------------------- trash

    fun moveToTrash(track: TrackEntity) = viewModelScope.launch {
        busy.value = "Moving ${track.title}"
        runCatching { trash.moveToTrash(track) }
            .onSuccess { result ->
                emit(
                    LibraryEvent.Message(
                        when {
                            !result.movedPlayable -> "Could not move ${track.title}"
                            result.movedSource -> "Moved to _ThunderPlayTrash (audio + source)"
                            // Worth saying: without the WAV, a transcode run can bring it back.
                            else -> "Moved audio, but the source WAV stayed put"
                        },
                    ),
                )
            }
            .onFailure {
                diagnostics.error("Trash", "Moving ${track.title} to trash failed", it)
                emit(LibraryEvent.Message(readable(it)))
            }
        busy.value = null
    }

    // ------------------------------------------------------------- refresh

    fun refresh() {
        if (refreshState.value is RefreshState.Running) return
        refreshState.value = RefreshState.Running()
        viewModelScope.launch {
            runCatching {
                var walk = RefreshResult(0, 0, 0, 0)

                // Each batch is already in the database by the time it is reported, so the list
                // grows underneath this while the count ticks up.
                refresher.refreshProgressively().collect { progress ->
                    when (progress) {
                        is RefreshProgress.Scanning -> refreshState.value = RefreshState.Running(
                            tracksFound = progress.tracksFound,
                            foldersScanned = progress.foldersScanned,
                        )

                        is RefreshProgress.Linking -> refreshState.value = RefreshState.Running(
                            tracksFound = progress.tracksFound,
                            linking = true,
                        )

                        is RefreshProgress.Complete -> walk = progress.result
                    }
                }

                // Headers are read only after the catalog is on screen. A track with no prompt yet
                // is perfectly playable, so making the list wait on a few hundred range requests
                // would trade the thing the user asked for against one they did not.
                indexer.indexProgressively().collect { progress ->
                    if (progress is IndexProgress.Reading) {
                        refreshState.value = RefreshState.Running(
                            tracksFound = walk.tracks,
                            reading = RefreshState.Running.Reading(progress.done, progress.total),
                        )
                    }
                }

                refreshState.value = RefreshState.Done(walk)
            }.onFailure { cause ->
                diagnostics.error("Refresh", "Library refresh failed", cause)
                refreshState.value = RefreshState.Failed(readable(cause))
            }
        }
    }

    fun dismissRefreshStatus() {
        refreshState.value = RefreshState.Idle
    }

    /** Prefers Drive's own explanation over a bare status code. */
    private fun readable(cause: Throwable): String = when (cause) {
        is DriveApiException -> cause.short
        else -> cause.message ?: cause::class.simpleName.orEmpty()
    }

    private fun emit(event: LibraryEvent) {
        _events.tryEmit(event)
    }

    private fun update(block: (LibraryView) -> LibraryView) {
        view.value = block(view.value)
    }
}
