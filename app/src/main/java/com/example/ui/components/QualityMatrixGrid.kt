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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.engine.AnalysisResult
import com.example.engine.CellStatus
import com.example.engine.GridCell
import com.example.engine.StickerLimits
import com.example.i18n.Strings
import com.example.ui.theme.MonoFontFamily

private data class QualityPresetItem(
    val title: String,
    val cell: GridCell,
    val isBest: Boolean
)

@Composable
fun QualityPresetRow(
    analysis: AnalysisResult,
    selectedSide: Int?,
    selectedFps: Int?,
    strings: Strings,
    onSelectCell: (side: Int, fps: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val validCells = analysis.cells.filter {
        it.status == CellStatus.OK || it.status == CellStatus.LOW_QUALITY
    }
    if (validCells.isEmpty()) return

    val best = analysis.recommended
    val sharpest = validCells
        .sortedWith(compareByDescending<GridCell> { it.side }.thenByDescending { it.fps })
        .firstOrNull()
    val smoothest = validCells
        .sortedWith(compareByDescending<GridCell> { it.fps }.thenByDescending { it.side })
        .firstOrNull()
    val compact = validCells
        .filter { it.side in 192..320 }
        .minByOrNull { it.estBytes }
        ?: validCells.minByOrNull { it.estBytes }

    val presets = buildList {
        if (best != null) add(QualityPresetItem(strings.presetBestTitle, best, isBest = true))
        if (sharpest != null && none { it.cell.side == sharpest.side && it.cell.fps == sharpest.fps }) {
            add(QualityPresetItem(strings.presetSharpTitle, sharpest, isBest = false))
        }
        if (smoothest != null && none { it.cell.side == smoothest.side && it.cell.fps == smoothest.fps }) {
            add(QualityPresetItem(strings.presetSmoothTitle, smoothest, isBest = false))
        }
        if (compact != null && none { it.cell.side == compact.side && it.cell.fps == compact.fps }) {
            add(QualityPresetItem(strings.presetCompactTitle, compact, isBest = false))
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = strings.availableQualitiesTitle,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presets.forEach { preset ->
                val isSel = selectedSide == preset.cell.side && selectedFps == preset.cell.fps
                val isLow = preset.cell.status == CellStatus.LOW_QUALITY
                Surface(
                    onClick = { onSelectCell(preset.cell.side, preset.cell.fps) },
                    shape = RoundedCornerShape(12.dp),
                    color = when {
                        isSel -> MaterialTheme.colorScheme.primaryContainer
                        preset.isBest -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    },
                    border = BorderStroke(
                        width = if (isSel) 2.dp else 1.dp,
                        color = when {
                            isSel -> MaterialTheme.colorScheme.primary
                            preset.isBest -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        }
                    ),
                    modifier = Modifier
                        .width(136.dp)
                        .testTag("quality_preset_${preset.cell.side}_${preset.cell.fps}")
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (preset.isBest) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = strings.bestChoiceBadge,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Text(
                                text = if (preset.isBest) strings.bestChoiceBadge else preset.title,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (preset.isBest) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = if (preset.cell.fps <= 1) "${preset.cell.side}×${preset.cell.side}"
                            else "${preset.cell.side}px · ${preset.cell.fps}fps",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = MonoFontFamily,
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "~${formatKb(preset.cell.estBytes)}" + if (isLow) " · !" else "",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                            color = if (isLow) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

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
                            modifier = Modifier.width(54.dp),
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

                            val bg = when {
                                isAuto && cell?.status == CellStatus.OK -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.30f)
                                cell?.status == CellStatus.OK -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f)
                                cell?.status == CellStatus.LOW_QUALITY -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.22f)
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
                                border = when {
                                    isSel -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                                    isAuto -> BorderStroke(1.dp, MaterialTheme.colorScheme.secondary)
                                    else -> null
                                },
                                modifier = Modifier.width(54.dp).height(34.dp)
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
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegendChip(color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f), text = strings.legendOk)
            LegendChip(color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f), text = strings.legendWarn)
            LegendChip(color = MaterialTheme.colorScheme.surfaceVariant, text = strings.legendNo)
            Text(
                text = strings.legendStar,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.Bold
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
