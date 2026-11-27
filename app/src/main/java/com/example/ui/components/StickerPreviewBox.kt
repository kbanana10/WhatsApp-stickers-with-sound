package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun StickerPreviewBox(
    frames: List<Bitmap>,
    fps: Int,
    isAnimated: Boolean,
    sizeDp: Dp = 128.dp,
    showAudioBadge: Boolean = true,
    modifier: Modifier = Modifier
) {
    var frameIndex by remember(frames) { mutableIntStateOf(0) }

    LaunchedEffect(frames, fps, isAnimated) {
        if (isAnimated && frames.size > 1) {
            val stepMs = (1000L / fps.coerceIn(1, 30)).coerceAtLeast(33L)
            while (true) {
                delay(stepMs)
                frameIndex = (frameIndex + 1) % frames.size
            }
        } else {
            frameIndex = 0
        }
    }

    val checkerA = MaterialTheme.colorScheme.surfaceVariant
    val checkerB = MaterialTheme.colorScheme.surface

    Box(
        modifier = modifier
            .size(sizeDp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val tile = 16f
            val cols = (size.width / tile).toInt() + 1
            val rows = (size.height / tile).toInt() + 1
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    drawRect(
                        color = if ((r + c) % 2 == 0) checkerA else checkerB,
                        topLeft = Offset(c * tile, r * tile),
                        size = Size(tile, tile)
                    )
                }
            }
        }

        val current = frames.getOrNull(frameIndex)
        if (current != null && !current.isRecycled) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = "Sticker preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (showAudioBadge) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(26.dp)
            )
        }
    }
}
