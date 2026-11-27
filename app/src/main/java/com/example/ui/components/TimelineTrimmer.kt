package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.i18n.Strings
import com.example.ui.theme.MonoFontFamily
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private enum class DragTarget { START, END, PLAYHEAD }

@Composable
fun TimelineTrimmer(
    durationSec: Double,
    startSec: Double,
    endSec: Double,
    cursorSec: Double,
    peaks: FloatArray,
    peakMax: Float,
    isPlaying: Boolean,
    strings: Strings,
    onTrimChange: (start: Double, end: Double, cursor: Double) -> Unit,
    onCursorChange: (Double) -> Unit,
    onPlayClip: () -> Unit,
    onPlayFromCursor: () -> Unit,
    onPlayLast2s: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val safeDur = durationSec.coerceAtLeast(0.3)
    val latestStart by rememberUpdatedState(startSec)
    val latestEnd by rememberUpdatedState(endSec)
    val latestDur by rememberUpdatedState(safeDur)

    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val trackBg = MaterialTheme.colorScheme.surfaceVariant
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val cursorColor = MaterialTheme.colorScheme.onSurface

    var widthPx by remember { mutableStateOf(1f) }
    var activeDrag by remember { mutableStateOf<DragTarget?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        // Always render timeline LTR so time progresses left-to-right accurately
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
                    .pointerInput(safeDur) {
                        detectTapGestures { offset ->
                            val tappedSec = (offset.x / widthPx).coerceIn(0f, 1f) * latestDur
                            onCursorChange(tappedSec)
                        }
                    }
                    .pointerInput(safeDur) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                val xFrac = (offset.x / widthPx).coerceIn(0f, 1f)
                                val sFrac = (latestStart / latestDur).toFloat()
                                val eFrac = (latestEnd / latestDur).toFloat()
                                val distS = abs(xFrac - sFrac)
                                val distE = abs(xFrac - eFrac)
                                activeDrag = when {
                                    distS < 0.08f && distS <= distE -> DragTarget.START
                                    distE < 0.08f -> DragTarget.END
                                    else -> DragTarget.PLAYHEAD
                                }
                            },
                            onDragEnd = { activeDrag = null },
                            onDragCancel = { activeDrag = null },
                            onDrag = { change, _ ->
                                change.consume()
                                val sec = (change.position.x / widthPx).coerceIn(0f, 1f) * latestDur
                                when (activeDrag) {
                                    DragTarget.START -> {
                                        val s = min(sec, latestEnd - 0.3).coerceAtLeast(0.0)
                                        onTrimChange(s, latestEnd, s)
                                    }
                                    DragTarget.END -> {
                                        val e = max(sec, latestStart + 0.3).coerceAtMost(latestDur)
                                        onTrimChange(latestStart, e, e)
                                    }
                                    DragTarget.PLAYHEAD -> onCursorChange(sec)
                                    null -> Unit
                                }
                            }
                        )
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize().padding(vertical = 8.dp)) {
                    val w = size.width
                    val h = size.height
                    drawRoundRect(
                        color = trackBg,
                        cornerRadius = CornerRadius(12f, 12f),
                        size = Size(w, h)
                    )

                    // Waveform bars
                    if (peaks.isNotEmpty()) {
                        val cols = (w / 4f).toInt().coerceIn(32, 180)
                        val norm = 1f / peakMax.coerceAtLeast(0.01f)
                        val mid = h / 2f
                        val barW = (w / cols) * 0.65f
                        for (c in 0 until cols) {
                            val pIdx = ((c.toFloat() / cols) * peaks.size).toInt().coerceIn(0, peaks.size - 1)
                            val amp = (peaks[pIdx] * norm).coerceIn(0.06f, 1f)
                            val barH = amp * (h - 8f)
                            val x = (c.toFloat() / cols) * w
                            val frac = c.toFloat() / cols
                            val inSel = frac >= (startSec / safeDur) && frac <= (endSec / safeDur)
                            drawRoundRect(
                                color = if (inSel) secondaryColor.copy(alpha = 0.78f) else mutedColor.copy(alpha = 0.35f),
                                topLeft = Offset(x, mid - barH / 2f),
                                size = Size(barW.coerceAtLeast(2f), barH),
                                cornerRadius = CornerRadius(2f, 2f)
                            )
                        }
                    }

                    // Selected highlight window
                    val sX = ((startSec / safeDur) * w).toFloat().coerceIn(0f, w)
                    val eX = ((endSec / safeDur) * w).toFloat().coerceIn(sX, w)
                    drawRect(
                        color = secondaryColor.copy(alpha = 0.18f),
                        topLeft = Offset(sX, 0f),
                        size = Size((eX - sX).coerceAtLeast(2f), h)
                    )
                    drawLine(secondaryColor, Offset(sX, 0f), Offset(eX, 0f), strokeWidth = 4f)
                    drawLine(secondaryColor, Offset(sX, h), Offset(eX, h), strokeWidth = 4f)

                    // Start & End handles
                    drawRoundRect(
                        color = secondaryColor,
                        topLeft = Offset((sX - 7f).coerceAtLeast(0f), -4f),
                        size = Size(14f, h + 8f),
                        cornerRadius = CornerRadius(7f, 7f)
                    )
                    drawRoundRect(
                        color = secondaryColor,
                        topLeft = Offset((eX - 7f).coerceAtMost(w - 14f), -4f),
                        size = Size(14f, h + 8f),
                        cornerRadius = CornerRadius(7f, 7f)
                    )

                    // Playhead
                    val cX = ((cursorSec / safeDur) * w).toFloat().coerceIn(0f, w)
                    drawLine(
                        color = cursorColor,
                        start = Offset(cX, -8f),
                        end = Offset(cX, h + 8f),
                        strokeWidth = 3f
                    )
                    drawCircle(
                        color = primaryColor,
                        radius = 6f,
                        center = Offset(cX, -6f)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("0.00s", style = MaterialTheme.typography.labelSmall, color = mutedColor)
                Text(
                    "${strings.cursorAt}: ${formatSec(cursorSec)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = primaryColor
                )
                Text(formatSec(safeDur), style = MaterialTheme.typography.labelSmall, color = mutedColor)
            }
        }

        Spacer(Modifier.height(10.dp))

        // Playback controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(
                onClick = if (isPlaying) onStop else onPlayClip,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.minimumInteractiveComponentSize().testTag("play_clip_button")
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) strings.stopPlay else strings.playClip,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(if (isPlaying) strings.stopPlay else strings.playClip, style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(
                onClick = onPlayFromCursor,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Text(strings.playFromCursor, style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(
                onClick = onPlayLast2s,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Text(strings.playLast2s, style = MaterialTheme.typography.labelMedium)
            }
        }

        Spacer(Modifier.height(10.dp))

        // Precise frame steppers for start and end + duration pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TrimEndpointControl(
                label = strings.startLabel,
                valueSec = startSec,
                onStep = { delta ->
                    val next = (startSec + delta).coerceIn(0.0, (endSec - 0.3).coerceAtLeast(0.0))
                    onTrimChange(next, endSec, next)
                },
                onSetFromCursor = {
                    val next = cursorSec.coerceIn(0.0, (endSec - 0.3).coerceAtLeast(0.0))
                    onTrimChange(next, endSec, next)
                },
                setTooltip = strings.setFromCursor
            )

            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = "${strings.lengthLabel}: ${formatSec(endSec - startSec)}",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = MonoFontFamily,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            TrimEndpointControl(
                label = strings.endLabel,
                valueSec = endSec,
                onStep = { delta ->
                    val next = (endSec + delta).coerceIn((startSec + 0.3).coerceAtMost(safeDur), safeDur)
                    onTrimChange(startSec, next, next)
                },
                onSetFromCursor = {
                    val next = cursorSec.coerceIn((startSec + 0.3).coerceAtMost(safeDur), safeDur)
                    onTrimChange(startSec, next, next)
                },
                setTooltip = strings.setFromCursor
            )
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = strings.trimHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TrimEndpointControl(
    label: String,
    valueSec: Double,
    onStep: (Double) -> Unit,
    onSetFromCursor: () -> Unit,
    setTooltip: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onStep(-1.0 / 30.0) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Remove, contentDescription = "-1 frame", modifier = Modifier.size(16.dp))
            }
            Text(
                text = formatSec(valueSec),
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
                modifier = Modifier
                    .pointerInput(Unit) { detectTapGestures { onSetFromCursor() } }
            )
            IconButton(
                onClick = { onStep(1.0 / 30.0) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "+1 frame", modifier = Modifier.size(16.dp))
            }
        }
        Text(
            text = setTooltip,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .pointerInput(Unit) { detectTapGestures { onSetFromCursor() } }
                .padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

fun formatSec(sec: Double): String {
    val s = sec.coerceAtLeast(0.0)
    return if (s >= 60.0) {
        val m = (s / 60.0).toInt()
        val rem = s % 60.0
        "%d:%05.2f".format(m, rem)
    } else {
        "%.2fs".format(s)
    }
}

fun formatKb(bytes: Number): String {
    val b = bytes.toLong()
    return if (b >= 1_000_000L) "%.2f MB".format(b / 1_000_000.0) else "${(b / 1000.0).toInt()} KB"
}
