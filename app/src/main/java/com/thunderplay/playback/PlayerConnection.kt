package com.thunderplay.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.thunderplay.data.TrackEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class NowPlaying(
    val mediaId: String? = null,
    val title: String = "",
    val subtitle: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val crossfading: Boolean = false,
    /** One of [Player.REPEAT_MODE_OFF], [Player.REPEAT_MODE_ONE] or [Player.REPEAT_MODE_ALL]. */
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffleEnabled: Boolean = false,
    /** Codec details read from the decoder, absent until the stream has been parsed. */
    val codec: String? = null,
    val sampleRateHz: Int? = null,
    val channels: Int? = null,
    val bitrateBps: Int? = null,
) {
    val active: Boolean get() = mediaId != null
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** One line of the Up next list. */
data class QueueEntry(
    val mediaId: String,
    val title: String,
    val subtitle: String,
    /** Position in the queue, which is what the reorder and remove commands address. */
    val index: Int,
    val isCurrent: Boolean,
)

/**
 * The UI's handle on [PlaybackService].
 *
 * A [MediaController] rather than a direct player reference, so the service stays the single owner
 * of playback: the notification, the lock screen and the app all drive the same session, and the
 * UI can come and go without interrupting audio.
 */
@Singleton
class PlayerConnection @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _nowPlaying = MutableStateFlow(NowPlaying())
    val nowPlaying: StateFlow<NowPlaying> = _nowPlaying.asStateFlow()

    private val _queue = MutableStateFlow<List<QueueEntry>>(emptyList())
    val queue: StateFlow<List<QueueEntry>> = _queue.asStateFlow()

    private var controller: MediaController? = null
    private var connecting = false

    /** Survives a reconnect, so a screen holding an override does not silently lose it. */
    private var crossfadeOverrideMs: Int? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()
    }

    // The custom player lives in the service; a MediaController can only see inside it through
    // session extras, which is how the crossfade indicator gets its state.
    private val extrasListener = object : MediaController.Listener {
        override fun onExtrasChanged(controller: MediaController, extras: android.os.Bundle) {
            publish()
        }
    }

    fun connect() {
        if (controller != null || connecting) return
        connecting = true
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token)
            .setListener(extrasListener)
            .buildAsync()
        future.addListener({
            connecting = false
            controller = runCatching { future.get() }.getOrNull()?.also {
                it.addListener(listener)
                publish()
                startPositionTicker()
                // A screen can ask for an override before the controller exists; re-send it now
                // rather than losing it, or A/B judging would silently blend its takes.
                sendCrossfadeOverride()
            }
        }, MoreExecutors.directExecutor())
    }

    /** The controller only emits on events, so the seek bar needs its own tick while playing. */
    private fun startPositionTicker() = scope.launch {
        while (true) {
            if (controller?.isPlaying == true) publish()
            delay(500)
        }
    }

    private fun publish() {
        val player = controller
        if (player == null || player.currentMediaItem == null) {
            _nowPlaying.value = NowPlaying()
            _queue.value = emptyList()
            return
        }
        publishQueue(player)
        val metadata = player.mediaMetadata
        val format = runCatching { audioFormat(player) }.getOrNull()
        _nowPlaying.value = NowPlaying(
            mediaId = player.currentMediaItem?.mediaId,
            title = metadata.title?.toString().orEmpty(),
            subtitle = listOfNotNull(
                metadata.artist?.toString(),
                metadata.albumTitle?.toString()?.takeIf { it != metadata.artist?.toString() },
            ).joinToString(" / "),
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.takeIf { it > 0 } ?: 0,
            hasNext = player.hasNextMediaItem(),
            hasPrevious = player.hasPreviousMediaItem(),
            crossfading = player.sessionExtras.getBoolean(PlaybackService.EXTRA_CROSSFADING),
            repeatMode = player.repeatMode,
            shuffleEnabled = player.shuffleModeEnabled,
            codec = format?.sampleMimeType?.substringAfter('/'),
            sampleRateHz = format?.sampleRate?.takeIf { it > 0 },
            channels = format?.channelCount?.takeIf { it > 0 },
            bitrateBps = format?.bitrate?.takeIf { it > 0 },
        )
    }

    /** The selected audio format, if the decoder has reported one yet. */
    private fun audioFormat(player: MediaController): androidx.media3.common.Format? =
        player.currentTracks.groups
            .firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO && it.isSelected }
            ?.let { group -> (0 until group.length).firstOrNull(group::isTrackSelected)
                ?.let(group::getTrackFormat) }

    /**
     * Replaces the queue with the current library view and starts at [startIndex].
     *
     * The queue is always the list the user is looking at, which is what makes "shuffle within a
     * category" work without any special case.
     */
    fun playQueue(tracks: List<TrackEntity>, startIndex: Int) {
        val player = controller ?: run { connect(); return }
        player.setMediaItems(tracks.map(TrackEntity::toMediaItem), startIndex, 0L)
        player.prepare()
        player.play()
    }

    /**
     * Mirrors the session's timeline as the Up next list.
     *
     * Rebuilt on every event rather than diffed: the queue is the list the user was looking at,
     * so a couple of hundred entries at most, and a stale queue view is worse than a cheap copy.
     */
    private fun publishQueue(player: MediaController) {
        val count = player.mediaItemCount
        val current = player.currentMediaItemIndex
        _queue.value = (0 until count).map { index ->
            val item = player.getMediaItemAt(index)
            QueueEntry(
                mediaId = item.mediaId,
                title = item.mediaMetadata.title?.toString().orEmpty(),
                subtitle = item.mediaMetadata.artist?.toString().orEmpty(),
                index = index,
                isCurrent = index == current,
            )
        }
    }

    fun togglePlayPause() {
        val player = controller ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    /** Off, then all, then one - the order every other player cycles them in. */
    fun cycleRepeat() {
        val player = controller ?: return
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun toggleShuffle() {
        val player = controller ?: return
        player.shuffleModeEnabled = !player.shuffleModeEnabled
    }

    fun playQueueIndex(index: Int) {
        controller?.seekTo(index, 0L)
    }

    fun moveInQueue(from: Int, to: Int) {
        val player = controller ?: return
        if (from == to) return
        player.moveMediaItem(from, to)
    }

    fun removeFromQueue(index: Int) {
        controller?.removeMediaItem(index)
    }

    /**
     * Queues [tracks] to play once the current one finishes, rather than at the end.
     *
     * With nothing playing there is no "next", so this starts them instead - otherwise the action
     * would silently do nothing on a cold start.
     */
    fun playNext(tracks: List<TrackEntity>) {
        if (tracks.isEmpty()) return
        val player = controller ?: run { connect(); return }
        if (player.currentMediaItem == null) {
            playQueue(tracks, 0)
            return
        }
        player.addMediaItems(player.currentMediaItemIndex + 1, tracks.map(TrackEntity::toMediaItem))
    }

    fun addToQueue(tracks: List<TrackEntity>) {
        if (tracks.isEmpty()) return
        val player = controller ?: run { connect(); return }
        if (player.currentMediaItem == null) {
            playQueue(tracks, 0)
            return
        }
        player.addMediaItems(tracks.map(TrackEntity::toMediaItem))
    }

    fun next() = controller?.seekToNextMediaItem()

    fun previous() {
        val player = controller ?: return
        // Match the usual convention: restart the track first, jump back only near the start.
        if (player.currentPosition > RESTART_THRESHOLD_MS) player.seekTo(0)
        else player.seekToPreviousMediaItem()
    }

    fun seekTo(fraction: Float) {
        val player = controller ?: return
        val duration = player.duration
        if (duration > 0) player.seekTo((duration * fraction).toLong())
    }

    fun rateStars() {
        val player = controller ?: return
        player.sendCustomCommand(
            SessionCommand(PlaybackService.ACTION_RATE_STARS, android.os.Bundle.EMPTY),
            android.os.Bundle.EMPTY,
        )
    }

    fun toggleLike() {
        rateStars()
    }

    /**
     * Overrides the crossfade length for as long as a screen needs it; null restores the setting.
     *
     * A/B judging sets 0. Blending two takes of the same cue overlaps them, and manual Next blends
     * by default, so without this every comparison would be heard through the other take.
     */
    fun setCrossfadeOverride(ms: Int?) {
        crossfadeOverrideMs = ms
        sendCrossfadeOverride()
    }

    private fun sendCrossfadeOverride() {
        val player = controller ?: return
        player.sendCustomCommand(
            SessionCommand(PlaybackService.ACTION_SET_CROSSFADE_OVERRIDE, android.os.Bundle.EMPTY),
            android.os.Bundle().apply {
                putInt(
                    PlaybackService.EXTRA_CROSSFADE_MS,
                    crossfadeOverrideMs ?: PlaybackService.NO_CROSSFADE_OVERRIDE,
                )
            },
        )
    }

    fun currentMediaItem(): MediaItem? = controller?.currentMediaItem

    fun release() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }

    private companion object {
        const val RESTART_THRESHOLD_MS = 3_000L
    }
}
