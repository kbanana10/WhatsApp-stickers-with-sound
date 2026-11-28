package com.example.ui.components

import android.media.MediaPlayer
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Real video preview player backed by Android's built-in MediaPlayer + SurfaceView.
 * Plays the trimmed video segment in sync with audio playback (muted to avoid echo).
 */
@Composable
fun VideoPreviewPlayer(
    uri: Uri,
    startSec: Double,
    endSec: Double,
    playing: Boolean,
    modifier: Modifier = Modifier,
    cursorSec: Double = startSec
) {
    val context = LocalContext.current
    val startMs = (startSec * 1000).toInt().coerceAtLeast(0)
    val endMs = (endSec * 1000).toInt().coerceAtLeast(startMs + 100)
    val cursorMs = (cursorSec * 1000).toInt().coerceIn(startMs, endMs)

    val latestPlaying by rememberUpdatedState(playing)
    val latestCursorMs by rememberUpdatedState(cursorMs)
    val latestStartMs by rememberUpdatedState(startMs)
    val latestEndMs by rememberUpdatedState(endMs)

    val holderRef = remember { arrayOfNulls<SurfaceHolder>(1) }
    val playerRef = remember { arrayOfNulls<MediaPlayer>(1) }
    val preparedRef = remember { booleanArrayOf(false) }

    fun releasePlayer() {
        preparedRef[0] = false
        runCatching { playerRef[0]?.stop() }
        runCatching { playerRef[0]?.release() }
        playerRef[0] = null
    }

    fun preparePlayer(targetUri: Uri) {
        val holder = holderRef[0] ?: return
        releasePlayer()
        runCatching {
            val mp = MediaPlayer()
            mp.setDataSource(context, targetUri)
            mp.setDisplay(holder)
            mp.setVolume(0f, 0f)
            mp.isLooping = false
            mp.setOnPreparedListener { preparedMp ->
                preparedRef[0] = true
                val targetPos = if (latestPlaying) latestCursorMs else latestCursorMs
                preparedMp.seekTo(targetPos)
                if (latestPlaying) {
                    preparedMp.start()
                }
            }
            mp.setOnSeekCompleteListener { seekedMp ->
                if (latestPlaying && seekedMp.currentPosition >= latestEndMs) {
                    runCatching {
                        seekedMp.pause()
                        seekedMp.seekTo(latestStartMs)
                    }
                }
            }
            mp.prepareAsync()
            playerRef[0] = mp
        }
    }

    LaunchedEffect(uri) {
        if (holderRef[0] != null) {
            preparePlayer(uri)
        }
    }

    DisposableEffect(Unit) {
        onDispose { releasePlayer() }
    }

    LaunchedEffect(playing, startMs, endMs) {
        val mp = playerRef[0] ?: return@LaunchedEffect
        if (!preparedRef[0]) return@LaunchedEffect
        runCatching {
            if (playing) {
                if (mp.currentPosition < startMs || mp.currentPosition >= endMs) {
                    mp.seekTo(cursorMs)
                }
                if (!mp.isPlaying) mp.start()
            } else {
                if (mp.isPlaying) mp.pause()
            }
        }
    }

    LaunchedEffect(cursorMs, playing) {
        if (!playing) {
            val mp = playerRef[0] ?: return@LaunchedEffect
            if (preparedRef[0]) {
                runCatching { mp.seekTo(cursorMs) }
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) {
                        holderRef[0] = h
                        preparePlayer(uri)
                    }

                    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {}

                    override fun surfaceDestroyed(h: SurfaceHolder) {
                        holderRef[0] = null
                        releasePlayer()
                    }
                })
            }
        }
    )
}
