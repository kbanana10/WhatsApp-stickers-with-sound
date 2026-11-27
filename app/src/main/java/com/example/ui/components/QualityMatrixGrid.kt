package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.engine.AnalysisResult
import com.example.engine.CellStatus
import com.example.engine.StickerLimits
import com.example.i18n.Strings
import com.example.ui.theme.MonoFontFamily

@Composable
fun QualityMatrixGrid(
    analysis: AnalysisResult,
    selectedSide: Int?,
    selectedFps: Int?,
    strings: Strings,
    onSelectCell: (side: Int, fps: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                // Header row (FPS)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "px\\fps",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(46.dp),
                        textAlign = TextAlign.End
                    )
                    StickerLimits.FPS_LIST.forEach { fps ->
                        Text(
                            text = fps.toString(),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(52.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                StickerLimits.SIDES.forEach { side ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(vertical = 2.dp)
                    ) {
                        Text(
                            text = side.toString(),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(46.dp),
                            textAlign = TextAlign.End
                        )
                        StickerLimits.FPS_LIST.forEach { fps ->
                            val cell = analysis.cells.firstOrNull { it.side == side && it.fps == fps }
                            val isAuto = analysis.recommended?.side == side && analysis.recommended.fps == fps
                            val isSel = selectedSide == side && selectedFps == fps
                            val enabled = cell != null && (cell.status == CellStatus.OK || cell.status == CellStatus.LOW_QUALITY)

                            val bg = when (cell?.status) {
                                CellStatus.OK -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f)
                                CellStatus.LOW_QUALITY -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.22f)
                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            }
                            val txtColor = when (cell?.status) {
                                CellStatus.OK, CellStatus.LOW_QUALITY -> MaterialTheme.colorScheme.onSurface
                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                            }
                            val label = when (cell?.status) {
                                null, CellStatus.UNAVAILABLE -> "·"
                                CellStatus.OVER_LIMIT -> "✕"
                                else -> (if (isAuto) "★" else "") + (cell.estBytes / 1000).toString()
                            }

                            Surface(
                                onClick = { if (enabled) onSelectCell(side, fps) },
                                enabled = enabled,
                                color = bg,
                                shape = RoundedCornerShape(7.dp),
                                border = if (isSel) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                                modifier = Modifier.width(52.dp).height(34.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = MonoFontFamily,
                                            fontWeight = if (isSel || isAuto) FontWeight.Bold else FontWeight.Medium
                                        ),
                                        color = txtColor
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Color legend
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegendChip(color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f), text = strings.legendOk)
            LegendChip(color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f), text = strings.legendWarn)
            LegendChip(color = MaterialTheme.colorScheme.surfaceVariant, text = strings.legendNo)
            Text(
                text = strings.legendStar,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LegendChip(color: androidx.compose.ui.graphics.Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
