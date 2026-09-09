package com.thunderplay.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A single [Player] facade over two ExoPlayers, so tracks can overlap on the way out and in.
 *
 * ExoPlayer has no crossfade and `MediaSession` insists on exactly one player, so the queue is
 * owned here and each underlying player only ever holds one item. [SimpleBasePlayer] is the
 * sanctioned way to write a custom player: state is published as an immutable snapshot and every
 * command arrives as a `handle*` call.
 *
 * Setting [crossfadeMs] to 0 keeps the second player idle and turns this into an ordinary
 * single-player gapless queue, which is the fallback if a transition ever misbehaves.
 */
@OptIn(UnstableApi::class)
class CrossfadePlayer(
    private val context: Context,
    private val dataSourceFactory: DataSource.Factory,
    private val looper: Looper = Looper.getMainLooper(),
) : SimpleBasePlayer(looper) {

    private val handler = Handler(looper)
    private val audioAttributes = AudioAttributes.DEFAULT

    private fun buildPlayer() = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        .setLooper(looper)
        .build()
        .apply {
            repeatMode = Player.REPEAT_MODE_OFF
            setWakeMode(C.WAKE_MODE_NETWORK)
        }

    private var active: ExoPlayer = buildPlayer()
    private var standby: ExoPlayer = buildPlayer()

    private var queue: List<MediaItem> = emptyList()
    private var currentIndex: Int = 0
    private var wantsToPlay: Boolean = false
    private var released = false

    private var repeatMode: Int = Player.REPEAT_MODE_OFF
    private var shuffleEnabled: Boolean = false

    /**
     * The order the queue was in before it was shuffled, so switching shuffle off can restore it.
     *
     * Shuffling reorders the queue itself rather than keeping a separate permutation. The queue is
     * visible - it is what the Up next list shows - so a hidden order would mean the list and the
     * playback order disagreed about what comes next.
     */
    private var unshuffled: List<MediaItem> = emptyList()

    private var fading = false
    private val _crossfading = MutableStateFlow(false)
    val crossfading: StateFlow<Boolean> = _crossfading.asStateFlow()

    /** Fade length in milliseconds. 0 disables crossfading entirely. */
    var crossfadeMs: Int = 3_000
        set(value) {
            field = value.coerceAtLeast(0)
        }

    private val activeListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = invalidateState()

        override fun onPlaybackStateChanged(state: Int) {
            // Without a usable duration there is no fade window, so ENDED is the only cue to move on.
            if (state == Player.STATE_ENDED && !fading) advance(fadeMs = 0)
            invalidateState()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (released) return
            step()
            handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        // Only the active player negotiates audio focus; two competing requests is the classic way
        // to make a crossfading player duck or pause itself mid-transition.
        active.setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
        standby.setAudioAttributes(audioAttributes, /* handleAudioFocus = */ false)
        active.addListener(activeListener)
        handler.post(ticker)
    }

    // ---------------------------------------------------------------- state

    override fun getState(): State {
        val builder = State.Builder().setAvailableCommands(COMMANDS)

        if (queue.isEmpty()) {
            return builder
                .setPlaybackState(Player.STATE_IDLE)
                .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .build()
        }

        val items = queue.mapIndexed { index, item ->
            val duration = if (index == currentIndex) active.duration else C.TIME_UNSET
            MediaItemData.Builder(item.mediaId.ifEmpty { "item-$index" })
                .setMediaItem(item)
                .setDurationUs(
                    if (duration == C.TIME_UNSET) C.TIME_UNSET else duration * 1_000,
                )
                .setIsSeekable(true)
                .setIsDynamic(false)
                // SimpleBasePlayer does not derive this from the MediaItem, and without it the
                // notification and lock screen have no title to show at all.
                .setMediaMetadata(item.mediaMetadata)
                // Tracks hang off the playing item, and only the active player knows them. This
                // is how the UI gets real codec details a MediaController could not otherwise see.
                .apply { if (index == currentIndex) setTracks(active.currentTracks) }
                .build()
        }

        return builder
            .setPlaylist(items)
            .setCurrentMediaItemIndex(currentIndex)
            .setRepeatMode(repeatMode)
            .setShuffleModeEnabled(shuffleEnabled)
            .setPlaybackState(active.playbackState)
            .setPlayWhenReady(wantsToPlay, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(PositionSupplier { active.currentPosition.coerceAtLeast(0) })
            .setContentBufferedPositionMs(
                PositionSupplier { active.bufferedPosition.coerceAtLeast(0) },
            )
            .build()
    }

    // ---------------------------------------------------------------- commands

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        cancelFade()
        queue = mediaItems.toList()
        unshuffled = queue
        currentIndex = if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, maxOf(0, queue.size - 1))
        // A new queue arriving while shuffle is on is shuffled too, or turning it on once would
        // only ever apply to the list that happened to be loaded at the time.
        if (shuffleEnabled) shuffleQueue()
        loadActive(atMs = if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs)
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(
        index: Int,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<*> {
        // Any structural change can invalidate what the standby player is part-way through
        // fading in, so the blend is abandoned rather than left pointing at a stale position.
        cancelFade()
        val at = index.coerceIn(0, queue.size)
        queue = queue.toMutableList().apply { addAll(at, mediaItems) }
        unshuffled = unshuffled + mediaItems
        currentIndex = QueuePlan.indexAfterAdd(currentIndex, at, mediaItems.size)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleMoveMediaItems(
        fromIndex: Int,
        toIndex: Int,
        newIndex: Int,
    ): ListenableFuture<*> {
        cancelFade()
        val playing = queue.getOrNull(currentIndex)
        val from = fromIndex.coerceIn(0, queue.size)
        val to = toIndex.coerceIn(from, queue.size)
        if (from == to) return Futures.immediateVoidFuture()

        val moving = queue.subList(from, to).toList()
        val rest = queue.toMutableList().apply { subList(from, to).clear() }
        rest.addAll(newIndex.coerceIn(0, rest.size), moving)
        queue = rest

        // The index follows the playing item rather than the other way round: reordering the list
        // underneath a track must not change which track is audible.
        if (playing != null) {
            currentIndex = queue.indexOfFirst { it === playing }.takeIf { it >= 0 } ?: currentIndex
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        cancelFade()
        val from = fromIndex.coerceIn(0, queue.size)
        val to = toIndex.coerceIn(from, queue.size)
        if (from == to) return Futures.immediateVoidFuture()

        val dropped = queue.subList(from, to).toSet()
        val outcome = QueuePlan.afterRemoval(currentIndex, from, to, queue.size)
        queue = queue.toMutableList().apply { subList(from, to).clear() }
        unshuffled = unshuffled.filterNot { it in dropped }

        when (outcome) {
            QueuePlan.Removal.Emptied -> {
                currentIndex = 0
                wantsToPlay = false
                active.stop()
                active.clearMediaItems()
            }

            is QueuePlan.Removal.Reload -> {
                currentIndex = outcome.index
                loadActive(atMs = 0)
            }

            is QueuePlan.Removal.Keep -> currentIndex = outcome.index
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        wantsToPlay = playWhenReady
        active.playWhenReady = playWhenReady
        // A pause during a fade must stop both halves, or the incoming track keeps going alone.
        if (fading) standby.playWhenReady = playWhenReady
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        active.prepare()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        if (queue.isEmpty()) return Futures.immediateVoidFuture()

        if (mediaItemIndex == C.INDEX_UNSET || mediaItemIndex == currentIndex) {
            // Seeking inside the current track cancels any fade: the position it was scheduled
            // against no longer exists.
            cancelFade()
            active.seekTo(if (positionMs == C.TIME_UNSET) 0 else positionMs)
            return Futures.immediateVoidFuture()
        }

        val target = mediaItemIndex.coerceIn(0, queue.size - 1)
        val fade = CrossfadeCurve.manualSkipMs(crossfadeMs)
        if (fade > 0 && target == currentIndex + 1 && !fading) {
            // Skipping forward one track still blends, just briefly, so Next feels responsive.
            beginFade(fade, target)
        } else {
            cancelFade()
            currentIndex = target
            loadActive(atMs = if (positionMs == C.TIME_UNSET) 0 else positionMs)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        cancelFade()
        wantsToPlay = false
        active.stop()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        handler.removeCallbacksAndMessages(null)
        active.removeListener(activeListener)
        active.release()
        standby.release()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        this.repeatMode = repeatMode
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    /**
     * Shuffling reorders the queue in place, keeping the playing track where it is.
     *
     * Switching it off restores the order the queue arrived in, minus anything since removed;
     * tracks added while shuffled keep the position they were added at.
     */
    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        if (shuffleModeEnabled == shuffleEnabled) return Futures.immediateVoidFuture()
        shuffleEnabled = shuffleModeEnabled

        val playing = queue.getOrNull(currentIndex)
        if (shuffleModeEnabled) {
            shuffleQueue()
        } else if (unshuffled.isNotEmpty()) {
            val restored = unshuffled.filter { item -> queue.any { it === item } }
            val newcomers = queue.filterNot { item -> restored.any { it === item } }
            queue = restored + newcomers
            if (playing != null) {
                currentIndex = queue.indexOfFirst { it === playing }.takeIf { it >= 0 } ?: 0
            }
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    /** Leaves the playing track at its current position and shuffles everything around it. */
    private fun shuffleQueue() {
        if (queue.size < 2) return
        val playing = queue.getOrNull(currentIndex)
        val rest = queue.filterNot { it === playing }.shuffled()
        queue = listOfNotNull(playing) + rest
        currentIndex = if (playing == null) 0 else 0
    }

    private fun autoNextIndex(): Int? = QueuePlan.autoNext(currentIndex, queue.size, repeatMode)

    // ---------------------------------------------------------------- fading

    private fun step() {
        if (queue.isEmpty()) return

        if (!fading) {
            if (!active.isPlaying) return
            val next = autoNextIndex()
            val shouldFade = CrossfadeCurve.shouldStart(
                positionMs = active.currentPosition,
                durationMs = active.duration.takeIf { it != C.TIME_UNSET } ?: -1L,
                crossfadeMs = crossfadeMs,
                hasNext = next != null,
            )
            // Repeat-one fades the track into itself, which is what makes an ambience bed loop
            // without a seam.
            if (shouldFade && next != null) beginFade(crossfadeMs, next)
            return
        }

        val remaining = fadeEndsAtMs - System.currentTimeMillis()
        val progress = CrossfadeCurve.progress(remaining, fadeDurationMs)
        active.volume = CrossfadeCurve.outgoing(progress)
        standby.volume = CrossfadeCurve.incoming(progress)
        if (progress >= 1f) completeFade()
    }

    private var fadeEndsAtMs = 0L
    private var fadeDurationMs = 0

    /** Where the fade in progress is heading. Captured at the start, since the queue can move. */
    private var fadeTargetIndex = 0

    private fun beginFade(durationMs: Int, targetIndex: Int) {
        val target = queue.getOrNull(targetIndex) ?: return

        standby.setMediaItem(target)
        standby.prepare()
        standby.volume = 0f
        standby.playWhenReady = wantsToPlay

        fading = true
        fadeTargetIndex = targetIndex
        _crossfading.value = true
        fadeDurationMs = durationMs.coerceAtLeast(1)
        fadeEndsAtMs = System.currentTimeMillis() + fadeDurationMs
    }

    private fun completeFade() {
        swapPlayers()
        currentIndex = fadeTargetIndex.coerceIn(0, maxOf(0, queue.size - 1))
        fading = false
        _crossfading.value = false
        invalidateState()
    }

    /** Hands over audio focus before tearing the outgoing player down, so nothing gaps. */
    private fun swapPlayers() {
        standby.setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
        standby.volume = 1f
        active.setAudioAttributes(audioAttributes, /* handleAudioFocus = */ false)
        active.removeListener(activeListener)
        active.stop()
        active.clearMediaItems()
        active.volume = 1f

        val previous = active
        active = standby
        standby = previous
        active.addListener(activeListener)
    }

    private fun cancelFade() {
        if (!fading) return
        standby.stop()
        standby.clearMediaItems()
        standby.volume = 0f
        active.volume = 1f
        fading = false
        _crossfading.value = false
    }

    /** Hard transition, used when no duration was available to schedule a fade against. */
    private fun advance(fadeMs: Int) {
        val next = autoNextIndex()
        if (next == null) {
            wantsToPlay = false
            return
        }
        if (fadeMs > 0) {
            beginFade(fadeMs, next)
        } else {
            currentIndex = next
            loadActive(atMs = 0)
        }
    }

    private fun loadActive(atMs: Long) {
        val item = queue.getOrNull(currentIndex) ?: return
        active.volume = 1f
        active.setMediaItem(item, atMs)
        active.prepare()
        active.playWhenReady = wantsToPlay
        invalidateState()
    }

    private companion object {
        const val TICK_MS = 100L

        val COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_CHANGE_MEDIA_ITEMS,
                Player.COMMAND_SET_REPEAT_MODE,
                Player.COMMAND_SET_SHUFFLE_MODE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_TRACKS,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_SET_SPEED_AND_PITCH,
                Player.COMMAND_GET_VOLUME,
                Player.COMMAND_SET_VOLUME,
                Player.COMMAND_RELEASE,
            )
            .build()
    }
}
