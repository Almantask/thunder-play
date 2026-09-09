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
import androidx.media3.common.MediaItem
import androidx.media3.common.Rating
import androidx.media3.common.StarRating
import com.thunderplay.R
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

    private var settingsCrossfadeMs = com.thunderplay.settings.AppSettings.DEFAULT_CROSSFADE_MS
    /** Set while a screen needs blending suppressed; null means "follow the setting". */
    private var crossfadeOverrideMs: Int? = null

    private fun applyCrossfade() {
        crossfadePlayer?.crossfadeMs = crossfadeOverrideMs ?: settingsCrossfadeMs
    }

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
            .setMediaButtonPreferences(listOf(starRatingButton(rating = 0)))
            .build()

        scope.launch { refreshRatingButton() }

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                scope.launch { refreshRatingButton() }
            }
        })

        // Crossfade length is a setting, so follow it rather than reading it once at startup.
        scope.launch {
            settings.settings.collect {
                settingsCrossfadeMs = it.crossfadeMs
                applyCrossfade()
            }
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
                        .add(SessionCommand(ACTION_RATE_STARS, android.os.Bundle.EMPTY))
                        .add(SessionCommand(ACTION_TOGGLE_LIKE, android.os.Bundle.EMPTY))
                        .add(SessionCommand(ACTION_SET_CROSSFADE_OVERRIDE, android.os.Bundle.EMPTY))
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
            // Not about the current track, so it is handled before the mediaId guard - the
            // override has to take effect even with nothing loaded.
            if (customCommand.customAction == ACTION_SET_CROSSFADE_OVERRIDE) {
                val ms = args.getInt(EXTRA_CROSSFADE_MS, NO_CROSSFADE_OVERRIDE)
                crossfadeOverrideMs = ms.takeIf { it >= 0 }
                applyCrossfade()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            val mediaId = session.player.currentMediaItem?.mediaId
                ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))

            when (customCommand.customAction) {
                ACTION_RATE_STARS, ACTION_TOGGLE_LIKE -> {
                    scope.launch {
                        val current = stats.rating(mediaId)
                        val next = if (current >= 5) 0 else current + 1
                        stats.setRating(mediaId, next)
                        refreshRatingButton()
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
        }

        override fun onSetRating(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            rating: Rating,
        ): ListenableFuture<SessionResult> {
            val mediaId = session.player.currentMediaItem?.mediaId
                ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            if (rating is StarRating) {
                val stars = rating.starRating.toInt().coerceIn(0, 5)
                scope.launch {
                    stats.setRating(mediaId, stars)
                    refreshRatingButton()
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    /** Rebuilds the button so the star rating reflects the new state in the notification. */
    private suspend fun refreshRatingButton() {
        val current = session ?: return
        val mediaId = current.player.currentMediaItem?.mediaId
        val rating = mediaId?.let { stats.rating(it) } ?: 0
        current.setMediaButtonPreferences(listOf(starRatingButton(rating)))
    }

    private fun starRatingButton(rating: Int): CommandButton {
        val hasRating = rating in 1..5
        val iconRes = if (hasRating) R.drawable.ic_star_filled else R.drawable.ic_star_outline
        val displayName = if (hasRating) {
            "$rating star${if (rating == 1) "" else "s"}"
        } else {
            "Rate"
        }
        return CommandButton.Builder()
            .setIconResId(iconRes)
            .setDisplayName(displayName)
            .setSessionCommand(SessionCommand(ACTION_RATE_STARS, android.os.Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()
    }

    companion object {
        const val ACTION_RATE_STARS = "com.thunderplay.RATE_STARS"
        const val ACTION_TOGGLE_LIKE = "com.thunderplay.TOGGLE_LIKE"

        /**
         * Suspends crossfading for as long as a screen needs it, without touching the setting.
         *
         * A/B judging is the case: blending two takes of the same cue overlaps them, which is
         * precisely what makes them impossible to compare. Writing 0 into the user's setting and
         * restoring it on exit would leave crossfade permanently off after a crash.
         */
        const val ACTION_SET_CROSSFADE_OVERRIDE = "com.thunderplay.SET_CROSSFADE_OVERRIDE"
        const val EXTRA_CROSSFADE_MS = "crossfadeMs"
        const val NO_CROSSFADE_OVERRIDE = -1
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
