package com.thunderplay.playback

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.thunderplay.data.TrackDao
import com.thunderplay.stats.PlayQualifier
import com.thunderplay.stats.StatsGateway
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * Keeps audio playing with the screen off and owns the system media controls.
 *
 * The heart button is a session command rather than a rating widget: phone lock screens reliably
 * render custom action buttons but not star ratings, so the five-star control lives in the app and
 * the lock screen gets a single toggle. [Player.COMMAND_SET_RATING] is wired too, which is what
 * Android Auto and Wear would use.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var okHttpClient: OkHttpClient

    @Inject lateinit var driveAuth: com.thunderplay.drive.ServiceAccountAuth

    @Inject lateinit var stats: StatsGateway

    @Inject lateinit var trackDao: TrackDao

    @Inject lateinit var settings: com.thunderplay.settings.SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: MediaSession? = null
    private var playTracker: PlayTracker? = null
    private var crossfadePlayer: CrossfadePlayer? = null

    override fun onCreate() {
        super.onCreate()

        val player = CrossfadePlayer(
            context = this,
            dataSourceFactory = driveCacheDataSourceFactory(this, okHttpClient, driveAuth),
        )
        crossfadePlayer = player

        playTracker = PlayTracker(player, scope, stats, trackDao)

        session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .setMediaButtonPreferences(listOf(likeButton(liked = false)))
            .build()

        scope.launch { refreshLikeButton() }

        // Crossfade length is a setting, so follow it rather than reading it once at startup.
        scope.launch {
            settings.settings.collect { crossfadePlayer?.crossfadeMs = it.crossfadeMs }
        }

        // Surfaced through session extras so the app UI can show a transition indicator; a
        // MediaController has no other way to see inside a custom player.
        scope.launch {
            player.crossfading.collect { active ->
                session?.setSessionExtras(
                    android.os.Bundle().apply { putBoolean(EXTRA_CROSSFADING, active) },
                )
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away should stop a paused session rather than leave a dead notification.
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        playTracker?.release()
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private inner class SessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val default = MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    default.availableSessionCommands.buildUpon()
                        .add(SessionCommand(ACTION_TOGGLE_LIKE, android.os.Bundle.EMPTY))
                        .build(),
                )
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: android.os.Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction != ACTION_TOGGLE_LIKE) {
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            val mediaId = session.player.currentMediaItem?.mediaId
                ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))

            scope.launch {
                stats.toggleLike(mediaId)
                refreshLikeButton()
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    /** Rebuilds the button so the icon reflects the new state on the lock screen. */
    private suspend fun refreshLikeButton() {
        val current = session ?: return
        val mediaId = current.player.currentMediaItem?.mediaId
        val liked = mediaId?.let { stats.rating(it) >= 1 } ?: false
        current.setMediaButtonPreferences(listOf(likeButton(liked)))
    }

    private fun likeButton(liked: Boolean) = CommandButton.Builder(
        if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED,
    )
        .setDisplayName(if (liked) "Unlike" else "Like")
        .setSessionCommand(SessionCommand(ACTION_TOGGLE_LIKE, android.os.Bundle.EMPTY))
        .setSlots(CommandButton.SLOT_OVERFLOW)
        .build()

    companion object {
        const val ACTION_TOGGLE_LIKE = "com.thunderplay.TOGGLE_LIKE"
        const val EXTRA_CROSSFADING = "com.thunderplay.CROSSFADING"
    }
}

/**
 * Watches playback and records a play once it passes the qualifying threshold.
 *
 * Elapsed time is accumulated rather than read from the final position, so seeking backwards and
 * re-listening does not double count, and a track paused halfway still records what was heard.
 */
@OptIn(UnstableApi::class)
private class PlayTracker(
    private val player: Player,
    private val scope: CoroutineScope,
    private val stats: StatsGateway,
    private val trackDao: TrackDao,
) {
    private var currentId: String? = null
    private var startedAt: Long = 0
    private var accumulatedMs: Long = 0
    private var lastResumeAt: Long = 0
    private var recorded = false

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(
            mediaItem: androidx.media3.common.MediaItem?,
            reason: Int,
        ) {
            flush()
            currentId = mediaItem?.mediaId
            startedAt = System.currentTimeMillis()
            accumulatedMs = 0
            recorded = false
            lastResumeAt = if (player.isPlaying) System.currentTimeMillis() else 0
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                lastResumeAt = System.currentTimeMillis()
            } else {
                accrue()
                maybeRecord()
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                accrue()
                maybeRecord(completed = true)
            }
        }
    }

    init {
        player.addListener(listener)
    }

    private fun accrue() {
        if (lastResumeAt > 0) {
            accumulatedMs += System.currentTimeMillis() - lastResumeAt
            lastResumeAt = 0
        }
    }

    private fun maybeRecord(completed: Boolean = false) {
        if (recorded) return
        val id = currentId ?: return
        val duration = player.duration.takeIf { it != C.TIME_UNSET }
        if (!PlayQualifier.qualifies(accumulatedMs, duration)) return

        recorded = true
        val startedAtSnapshot = startedAt
        val playedSnapshot = accumulatedMs
        scope.launch {
            stats.recordPlay(id, startedAtSnapshot, playedSnapshot, completed)
        }
    }

    private fun flush() {
        accrue()
        maybeRecord()
    }

    fun release() {
        flush()
        player.removeListener(listener)
    }
}
