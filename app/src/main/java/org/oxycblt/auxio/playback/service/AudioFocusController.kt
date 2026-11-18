/*
 * Copyright (c) 2026 Auxio Project
 * AudioFocusController.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.oxycblt.auxio.playback.service

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import org.oxycblt.auxio.util.getSystemServiceCompat
import timber.log.Timber as L

/**
 * Custom audio focus controller supporting delayed audio focus gain (API 26+) so playback can
 * start while a call holds a transient/locked audio focus and seamlessly retain/regain focus once
 * the call ends.
 */
@OptIn(UnstableApi::class)
class AudioFocusController(
    context: Context,
    private val player: ExoPlayer,
    private val audioAttributes: AudioAttributes,
) {
    enum class State {
        NOT_REQUESTED,
        NO_FOCUS,
        HAVE_FOCUS,
        DELAYED,
        LOSS_TRANSIENT,
        LOSS_TRANSIENT_DUCK,
    }

    private val audioManager =
        context.applicationContext.getSystemServiceCompat(AudioManager::class)
    private val handler = Handler(Looper.getMainLooper())
    private val focusListener = AudioManager.OnAudioFocusChangeListener(::handleAudioFocusChange)
    private val focusRequest: AudioFocusRequest? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            buildAudioFocusRequest()
        } else {
            null
        }
    }

    var state: State = State.NOT_REQUESTED
        private set

    private var pausedForFocus = false
    private var isChangingPlayerState = false

    /**
     * Synchronize audio focus with the current [playWhenReady] and [playbackState] of [player].
     */
    fun updateForPlayerState(playWhenReady: Boolean, playbackState: Int) {
        if (playbackState == Player.STATE_IDLE) {
            pausedForFocus = false
            abandonAudioFocusIfHeld()
            setState(State.NOT_REQUESTED)
            return
        }

        if (playWhenReady) {
            pausedForFocus = false
            if (state == State.HAVE_FOCUS || state == State.DELAYED) {
                return
            }

            when (val result = requestAudioFocus()) {
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                    L.d("AudioFocus request result: GRANTED")
                    setState(State.HAVE_FOCUS)
                }
                AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                    L.d("AudioFocus request result: DELAYED")
                    setState(State.DELAYED)
                }
                AudioManager.AUDIOFOCUS_REQUEST_FAILED -> {
                    L.d("AudioFocus request result: FAILED")
                    setState(State.NO_FOCUS)
                    pausePlayer(forTransientFocusLoss = false)
                }
                else -> {
                    L.w("AudioFocus request result: UNKNOWN($result)")
                    setState(State.NO_FOCUS)
                    pausePlayer(forTransientFocusLoss = false)
                }
            }
            return
        }

        if (isChangingPlayerState || pausedForFocus) {
            return
        }

        abandonAudioFocusIfHeld()
        if (state != State.NO_FOCUS) {
            setState(State.NOT_REQUESTED)
        }
    }

    /**
     * Abandon any held or queued audio focus, clear pending callbacks, and restore default volume.
     */
    fun release() {
        pausedForFocus = false
        handler.removeCallbacksAndMessages(null)
        abandonAudioFocusIfHeld()
        setState(State.NOT_REQUESTED)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildAudioFocusRequest(): AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes.audioAttributesV21.audioAttributes)
            .setWillPauseWhenDucked(false)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(focusListener, handler)
            .build()

    private fun requestAudioFocus(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = requireNotNull(focusRequest)
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }

    private fun abandonAudioFocusIfHeld() {
        if (state == State.NOT_REQUESTED || state == State.NO_FOCUS) {
            return
        }
        L.d("Abandoning audio focus from state $state")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = requireNotNull(focusRequest)
            audioManager.abandonAudioFocusRequest(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                L.d("AudioFocus change received: GAIN")
                val shouldResume = pausedForFocus
                pausedForFocus = false
                setState(State.HAVE_FOCUS)
                if (shouldResume) {
                    isChangingPlayerState = true
                    try {
                        player.playWhenReady = true
                    } finally {
                        isChangingPlayerState = false
                    }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                L.d("AudioFocus change received: LOSS_TRANSIENT")
                if (player.playWhenReady) {
                    pausePlayer(forTransientFocusLoss = true)
                }
                setState(State.LOSS_TRANSIENT)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                L.d("AudioFocus change received: LOSS_TRANSIENT_CAN_DUCK")
                setState(State.LOSS_TRANSIENT_DUCK)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                L.d("AudioFocus change received: LOSS")
                pausedForFocus = false
                pausePlayer(forTransientFocusLoss = false)
                abandonAudioFocusIfHeld()
                setState(State.NO_FOCUS)
            }
            else -> {
                L.w("Unknown audio focus change: $focusChange")
            }
        }
    }

    private fun pausePlayer(forTransientFocusLoss: Boolean) {
        pausedForFocus = forTransientFocusLoss
        isChangingPlayerState = true
        try {
            player.playWhenReady = false
        } finally {
            isChangingPlayerState = false
        }
    }

    private fun setState(newState: State) {
        if (state != newState) {
            L.d("AudioFocus state transition: $state -> $newState")
            state = newState
        }
        val targetVolume =
            if (newState == State.LOSS_TRANSIENT_DUCK) {
                VOLUME_MULTIPLIER_DUCK
            } else {
                VOLUME_MULTIPLIER_DEFAULT
            }
        if (player.volume != targetVolume) {
            player.volume = targetVolume
        }
    }

    private companion object {
        const val VOLUME_MULTIPLIER_DUCK = 0.2f
        const val VOLUME_MULTIPLIER_DEFAULT = 1.0f
    }
}
