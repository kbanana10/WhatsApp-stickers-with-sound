package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GifBox
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.engine.CellStatus
import com.example.engine.CompressionMode
import com.example.engine.FramingMode
import com.example.engine.MediaEngine
import com.example.engine.MixFitMode
import com.example.engine.SizeCapMode
import com.example.i18n.Strings
import com.example.ui.components.QualityMatrixGrid
import com.example.ui.components.QualityPresetRow
import com.example.ui.components.SaveToPackDialog
import com.example.ui.components.StickerPreviewBox
import com.example.ui.components.TimelineTrimmer
import com.example.ui.components.VideoPreviewPlayer
import com.example.ui.components.formatKb
import com.example.ui.components.formatSec
import com.example.ui.theme.MonoFontFamily
import com.example.ui.viewmodel.StudioMode
import com.example.ui.viewmodel.StudioUiState
import com.example.ui.viewmodel.StudioViewModel
import com.example.whatsapp.WebSendBridge
import java.io.File

const val FAVORITES_PACK_NAME = "المفضلة"

@Composable
fun StudioScreen(
    viewModel: StudioViewModel,
    strings: Strings,
    onOpenWeb: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val packs by viewModel.packs.collectAsStateWithLifecycle(initialValue = emptyList())
    val uiContext = LocalContext.current
    var showSaveDialog by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }

    val sendViaWeb: () -> Unit = sendViaWeb@{
        val res = state.generatedSticker ?: return@sendViaWeb
        WebSendBridge.pendingStickerBase64 =
            android.util.Base64.encodeToString(res.webpBytes, android.util.Base64.NO_WRAP)
        WebSendBridge.navigatedToSelf = false
        val dir = File(uiContext.cacheDir, "web_send").apply { mkdirs() }
        val f = File(dir, "sticker_${System.currentTimeMillis()}.webp")
        f.writeBytes(res.webpBytes)
        WebSendBridge.pendingStickerUri = FileProvider.getUriForFile(
            uiContext,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            f
        )
        val fav = packs.firstOrNull { it.pack.name == FAVORITES_PACK_NAME }
        if (fav != null) {
            if (fav.stickers.size < 30) {
                viewModel.saveToPack(fav.pack, strings.savedToPackMsg)
            }
        } else {
            viewModel.createNewPackAndSave(FAVORITES_PACK_NAME, state.publisher, strings.savedToPackMsg)
        }
        onOpenWeb()
    }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? -> uri?.let { viewModel.onVisualPicked(it) } }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? -> uri?.let { viewModel.onVisualPicked(it) } }

    val mixPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.onVisualPicked(it) } }

    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.onAudioPicked(it) } }

    val isBusy = state.isAnalyzing || state.isCreating || state.isExportingMp4

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 680.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Mode selector pills
            ModeSelectorCard(
                activeMode = state.mode,
                enabled = !isBusy,
                strings = strings,
                onSelect = viewModel::selectMode
            )

            // Step 1: Choose Media
            StepCard(
                stepNumber = 1,
                title = strings.stepPickMedia,
                done = state.visualUri != null && (state.mode == StudioMode.VIDEO || state.audioUri != null),
                enabled = !isBusy
            ) {
                MediaPickerContent(
                    state = state,
                    strings = strings,
                    onPickVisual = {
                        when (state.mode) {
                            StudioMode.VIDEO -> videoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            )
                            StudioMode.IMAGE -> imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                            StudioMode.MIX -> mixPicker.launch(
                                arrayOf("video/*", "image/gif", "image/webp")
                            )
                        }
                    },
                    onPickAudio = {
                        audioPicker.launch(arrayOf("audio/*", "video/*"))
                    }
                )
            }

            // Step 2: Trim & Waveform (for Video or Audio/Mix)
            val showTrimStep = state.visualUri != null &&
                (state.mode != StudioMode.IMAGE || state.audioUri != null)
            StepCard(
                stepNumber = 2,
                title = strings.stepTrim,
                done = state.sourcesReady,
                enabled = !isBusy && showTrimStep
            ) {
                TrimSectionContent(
                    state = state,
                    strings = strings,
                    viewModel = viewModel
                )
            }

            // Step 3: Format & Multi-Core Quality Matrix
            StepCard(
                stepNumber = 3,
                title = strings.stepFormat,
                done = state.isAnalysisFresh && state.selectedSide != null,
                enabled = !isBusy && state.sourcesReady
            ) {
                FormatSectionContent(
                    state = state,
                    strings = strings,
                    showAdvanced = showAdvanced,
                    onToggleAdvanced = { showAdvanced = !showAdvanced },
                    viewModel = viewModel
                )
            }

            // Step 4: Metadata, Create & Direct WhatsApp Actions
            StepCard(
                stepNumber = 4,
                title = strings.stepCreate,
                done = state.isResultFresh,
                enabled = state.sourcesReady
            ) {
                CreateAndExportSection(
                    state = state,
                    strings = strings,
                    isBusy = isBusy,
                    viewModel = viewModel,
                    onOpenSaveDialog = { showSaveDialog = true },
                    onSendViaWeb = sendViaWeb
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showSaveDialog) {
        SaveToPackDialog(
            packs = packs,
            defaultPackName = state.packName,
            defaultPublisher = state.publisher,
            strings = strings,
            onDismiss = { showSaveDialog = false },
            onSelectExistingPack = { pack ->
                showSaveDialog = false
                viewModel.saveToPack(pack, strings.savedToPackMsg)
            },
            onCreateNewPack = { name, pub ->
                showSaveDialog = false
                viewModel.createNewPackAndSave(name, pub, strings.savedToPackMsg)
            }
        )
    }
}

@Composable
private fun ModeSelectorCard(
    activeMode: StudioMode,
    enabled: Boolean,
    strings: Strings,
    onSelect: (StudioMode) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ModePill(
                    title = strings.modeVideoTitle,
                    icon = Icons.Default.Movie,
                    selected = activeMode == StudioMode.VIDEO,
                    enabled = enabled,
                    tag = "mode_video_tab",
                    onClick = { onSelect(StudioMode.VIDEO) },
                    modifier = Modifier.weight(1f)
                )
                ModePill(
                    title = strings.modeImageTitle,
                    icon = Icons.Default.Image,
                    selected = activeMode == StudioMode.IMAGE,
                    enabled = enabled,
                    tag = "mode_image_tab",
                    onClick = { onSelect(StudioMode.IMAGE) },
                    modifier = Modifier.weight(1f)
                )
                ModePill(
                    title = strings.modeMixTitle,
                    icon = Icons.Default.GifBox,
                    selected = activeMode == StudioMode.MIX,
                    enabled = enabled,
                    tag = "mode_mix_tab",
                    onClick = { onSelect(StudioMode.MIX) },
                    modifier = Modifier.weight(1f)
                )
            }
            val desc = when (activeMode) {
                StudioMode.VIDEO -> strings.modeVideoDesc
                StudioMode.IMAGE -> strings.modeImageDesc
                StudioMode.MIX -> strings.modeMixDesc
            }
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun ModePill(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = modifier.minimumInteractiveComponentSize().testTag(tag)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                ),
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StepCard(
    stepNumber: Int,
    title: String,
    done: Boolean,
    enabled: Boolean,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.52f)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(
                            if (done) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.primary
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (done) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        Text(
                            text = stepNumber.toString(),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
private fun MediaPickerContent(
    state: StudioUiState,
    strings: Strings,
    onPickVisual: () -> Unit,
    onPickAudio: () -> Unit
) {
    val visualBtnLabel = when {
        state.visualUri != null -> strings.changeFile
        state.mode == StudioMode.VIDEO -> strings.pickVideoBtn
        state.mode == StudioMode.IMAGE -> strings.pickImageBtn
        else -> strings.pickMixBtn
    }
    val visualHint = when (state.mode) {
        StudioMode.VIDEO -> strings.pickVideoHint
        StudioMode.IMAGE -> strings.pickImageHint
        StudioMode.MIX -> strings.pickMixHint
    }

    PickerRow(
        buttonText = visualBtnLabel,
        icon = if (state.mode == StudioMode.IMAGE) Icons.Default.Image else Icons.Default.VideoFile,
        isLoading = state.isProbingVisual,
        selectedSummary = if (state.visualUri != null) {
            val dim = "${state.visualWidth}×${state.visualHeight}"
            val dur = if (state.visualDurationSec > 0) " · ${formatSec(state.visualDurationSec)}" else ""
            val noAudio = if (state.mode == StudioMode.VIDEO && !state.visualHasAudio) " · ${strings.noSoundDetected}" else ""
            "${state.visualName} ($dim$dur$noAudio)"
        } else null,
        hint = if (state.isProbingVisual) strings.readingFile else visualHint,
        testTag = "pick_media_button",
        onClick = onPickVisual
    )

    if (state.isAnimatedImageWarning) {
        Text(
            text = strings.animImageNote,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary
        )
    }

    if (state.mode != StudioMode.VIDEO) {
        PickerRow(
            buttonText = if (state.audioUri != null) strings.changeFile else strings.pickAudioBtn,
            icon = Icons.Default.AudioFile,
            isLoading = state.isProbingAudio,
            selectedSummary = if (state.audioUri != null) {
                "${state.audioName} · ${formatSec(state.audioDurationSec)}" +
                    if (state.audioPeakMax < 0.001f) " (${strings.noSoundDetected})" else ""
            } else null,
            hint = if (state.isProbingAudio) strings.readingFile else strings.pickAudioHint,
            testTag = "pick_audio_button",
            onClick = onPickAudio
        )
    }
}

@Composable
private fun PickerRow(
    buttonText: String,
    icon: ImageVector,
    isLoading: Boolean,
    selectedSummary: String?,
    hint: String,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onClick,
                enabled = !isLoading,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                modifier = Modifier.minimumInteractiveComponentSize().testTag(testTag)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(buttonText, style = MaterialTheme.typography.labelLarge)
            }
            Text(
                text = selectedSummary ?: hint,
                style = MaterialTheme.typography.bodySmall,
                color = if (selectedSummary != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun TrimSectionContent(
    state: StudioUiState,
    strings: Strings,
    viewModel: StudioViewModel
) {
    if (state.visualUri == null) {
        Text(
            text = strings.trimHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    // Visual Video Player for VIDEO mode, or Frame Thumbnail for IMAGE/MIX
    val activeVideoUri = state.visualUri
    if (state.mode == StudioMode.VIDEO && activeVideoUri != null) {
        VideoPreviewPlayer(
            uri = activeVideoUri,
            startSec = state.visualStartSec,
            endSec = state.visualEndSec,
            playing = state.isPlayingAudio,
            cursorSec = state.visualCursorSec,
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
    } else {
        state.visualPreviewBitmap?.let { bmp ->
            if (!bmp.isRecycled) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    // Visual Trimmer for Video or Non-GIF Mix Video
    if (state.mode == StudioMode.VIDEO || (state.mode == StudioMode.MIX && !state.isGif)) {
        TimelineTrimmer(
            durationSec = state.visualDurationSec,
            startSec = state.visualStartSec,
            endSec = state.visualEndSec,
            cursorSec = state.visualCursorSec,
            peaks = if (state.mode == StudioMode.VIDEO) state.audioPeaks else FloatArray(0),
            peakMax = state.audioPeakMax,
            isPlaying = state.isPlayingAudio && state.mode == StudioMode.VIDEO,
            strings = strings,
            onTrimChange = viewModel::updateVisualTrim,
            onCursorChange = viewModel::updateVisualCursor,
            onPlayClip = {
                if (state.mode == StudioMode.VIDEO) {
                    viewModel.playAudioRange(state.visualStartSec, state.visualEndSec, forVisualTrim = true)
                }
            },
            onPlayFromCursor = {
                val from = if (state.visualCursorSec in state.visualStartSec..(state.visualEndSec - 0.1)) {
                    state.visualCursorSec
                } else state.visualStartSec
                viewModel.playAudioRange(from, state.visualEndSec, forVisualTrim = true)
            },
            onPlayLast2s = {
                viewModel.playAudioRange(
                    maxOf(state.visualStartSec, state.visualEndSec - 2.0),
                    state.visualEndSec,
                    forVisualTrim = true
                )
            },
            onStop = viewModel::stopPlayback
        )
    }

    // Separate Audio Trimmer for IMAGE or MIX modes
    if (state.mode != StudioMode.VIDEO && state.audioUri != null) {
        if (state.mode == StudioMode.MIX && !state.isGif) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        }
        TimelineTrimmer(
            durationSec = state.audioDurationSec,
            startSec = state.audioStartSec,
            endSec = state.audioEndSec,
            cursorSec = state.audioCursorSec,
            peaks = state.audioPeaks,
            peakMax = state.audioPeakMax,
            isPlaying = state.isPlayingAudio,
            strings = strings,
            onTrimChange = viewModel::updateAudioTrim,
            onCursorChange = viewModel::updateAudioCursor,
            onPlayClip = {
                viewModel.playAudioRange(state.audioStartSec, state.audioEndSec, forVisualTrim = false)
            },
            onPlayFromCursor = {
                val from = if (state.audioCursorSec in state.audioStartSec..(state.audioEndSec - 0.1)) {
                    state.audioCursorSec
                } else state.audioStartSec
                viewModel.playAudioRange(from, state.audioEndSec, forVisualTrim = false)
            },
            onPlayLast2s = {
                viewModel.playAudioRange(
                    maxOf(state.audioStartSec, state.audioEndSec - 2.0),
                    state.audioEndSec,
                    forVisualTrim = false
                )
            },
            onStop = viewModel::stopPlayback
        )
    }

    // Mix duration fit selector
    if (state.mode == StudioMode.MIX && state.audioUri != null) {
        val vLen = if (state.isGif) state.visualDurationSec else state.visualClipLen
        val aLen = state.audioClipLen
        val differs = kotlin.math.abs(vLen - aLen) > 0.05
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (!differs) strings.mixSummarySame
                    else "${formatSec(vLen)} / ${formatSec(aLen)} — ${strings.ifLengthsDiffer}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                if (differs) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val options = listOf(
                            MixFitMode.INDEPENDENT to strings.fitIndependent,
                            MixFitMode.REPEAT to strings.fitRepeat,
                            MixFitMode.FREEZE to strings.fitFreeze
                        )
                        options.forEachIndexed { idx, (fit, label) ->
                            SegmentedButton(
                                selected = state.mixFitMode == fit,
                                onClick = { viewModel.setMixFitMode(fit) },
                                shape = SegmentedButtonDefaults.itemShape(index = idx, count = options.size)
                            ) {
                                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FormatSectionContent(
    state: StudioUiState,
    strings: Strings,
    showAdvanced: Boolean,
    onToggleAdvanced: () -> Unit,
    viewModel: StudioViewModel
) {
    // Framing mode segmented selector
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(strings.framingLabel, style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
            SegmentedButton(
                selected = state.framingMode == FramingMode.CONTAIN,
                onClick = { viewModel.setFramingMode(FramingMode.CONTAIN) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
            ) {
                Text(strings.fitContain, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
            }
            SegmentedButton(
                selected = state.framingMode == FramingMode.COVER,
                onClick = { viewModel.setFramingMode(FramingMode.COVER) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
            ) {
                Text(strings.fitCover, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
            }
        }
    }

    // Size target mode (WhatsApp 500 KB Pack vs 1 MB Max Quality)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(strings.sizeCapLabel, style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.sizeCapMode == SizeCapMode.MAX_QUALITY_1MB,
                onClick = { viewModel.setSizeCapMode(SizeCapMode.MAX_QUALITY_1MB) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
            ) {
                Text(strings.sizeCap1Mb, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
            }
            SegmentedButton(
                selected = state.sizeCapMode == SizeCapMode.WHATSAPP_PACK_500KB,
                onClick = { viewModel.setSizeCapMode(SizeCapMode.WHATSAPP_PACK_500KB) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
            ) {
                Text(strings.sizeCap500Kb, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    // Compression / WebP quality level selector
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(strings.compressionLabel, style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val compOptions = listOf(
                CompressionMode.AUTO to strings.compAuto,
                CompressionMode.HIGH to strings.compHigh,
                CompressionMode.BALANCED to strings.compBalanced,
                CompressionMode.COMPACT to strings.compCompact
            )
            compOptions.forEachIndexed { idx, (mode, label) ->
                SegmentedButton(
                    selected = state.compressionMode == mode,
                    onClick = { viewModel.setCompressionMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = idx, count = compOptions.size)
                ) {
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    // Multi-core compute button + speed pill
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        FilledTonalButton(
            onClick = viewModel::runAnalysis,
            enabled = state.sourcesReady && !state.isAnalyzing && !state.isCreating,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("calculate_options_button")
        ) {
            Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(strings.calcOptionsBtn)
        }
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(
                text = strings.processingSpeedNote.format(MediaEngine.cpuCores),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }

    if (state.isAnalyzing) {
        LinearProgressIndicator(
            progress = { state.analyzeProgress },
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
        )
    } else if (state.analysis != null && !state.isAnalysisFresh) {
        Text(
            text = strings.staleGrid,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary
        )
    } else if (state.analysis == null) {
        Text(
            text = strings.calcHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // Available quality presets + selected summary + matrix
    state.analysis?.let { an ->
        QualityPresetRow(
            analysis = an,
            selectedSide = state.selectedSide,
            selectedFps = state.selectedFps,
            strings = strings,
            onSelectCell = viewModel::selectGridCell
        )

        val selCell = an.cells.firstOrNull { it.side == state.selectedSide && it.fps == state.selectedFps }
        val isRecommended = an.recommended?.side == state.selectedSide && an.recommended?.fps == state.selectedFps
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (selCell != null) {
                    val starPrefix = if (isRecommended) "★ " else ""
                    "$starPrefix${selCell.side}px · ${selCell.fps}fps · ~${formatKb(selCell.estBytes)} (${an.elapsedMs}ms)"
                } else strings.noCellFits,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
                color = if (selCell?.status == CellStatus.LOW_QUALITY) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = viewModel::toggleMatrixVisible,
                modifier = Modifier.testTag("toggle_matrix_button")
            ) {
                Icon(Icons.Default.GridView, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (state.showMatrix) strings.hideMatrixBtn else strings.showMatrixBtn)
            }
        }

        AnimatedVisibility(
            visible = state.showMatrix,
            enter = androidx.compose.animation.fadeIn(spring(stiffness = Spring.StiffnessMedium)) +
                androidx.compose.animation.expandVertically()
        ) {
            QualityMatrixGrid(
                analysis = an,
                selectedSide = state.selectedSide,
                selectedFps = state.selectedFps,
                strings = strings,
                onSelectCell = viewModel::selectGridCell
            )
        }
    }

    // Advanced sync offset + experimental still option
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleAdvanced)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(strings.advancedTitle, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Icon(
            if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    AnimatedVisibility(visible = showAdvanced) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.mode != StudioMode.IMAGE) {
                OutlinedTextField(
                    value = state.syncOffsetMs.toString(),
                    onValueChange = { raw -> viewModel.setSyncOffsetMs(raw.toIntOrNull() ?: 0) },
                    label = { Text(strings.syncOffsetLabel) },
                    supportingText = { Text(strings.syncOffsetHint) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable {
                        viewModel.setStillExperimental(!state.stillExperimental)
                    }
                ) {
                    Checkbox(
                        checked = state.stillExperimental,
                        onCheckedChange = viewModel::setStillExperimental
                    )
                    Column {
                        Text(strings.stillExperimentalLabel, style = MaterialTheme.typography.bodyMedium)
                        Text(strings.stillExperimentalHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateAndExportSection(
    state: StudioUiState,
    strings: Strings,
    isBusy: Boolean,
    viewModel: StudioViewModel,
    onOpenSaveDialog: () -> Unit,
    onSendViaWeb: () -> Unit
) {
    val uiContext = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = state.packName,
            onValueChange = { viewModel.updateMetadata(it, state.publisher) },
            label = { Text(strings.packNameLabel) },
            placeholder = { Text(strings.packNameHint) },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag("pack_name_input")
        )
        OutlinedTextField(
            value = state.publisher,
            onValueChange = { viewModel.updateMetadata(state.packName, it) },
            label = { Text(strings.authorLabel) },
            placeholder = { Text(strings.authorHint) },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag("author_name_input")
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = viewModel::createSticker,
            enabled = state.sourcesReady && !isBusy,
            modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("create_sticker_button")
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                if (state.generatedSticker != null && !state.isResultFresh) strings.recreateStickerBtn
                else strings.createStickerBtn
            )
        }

        if (state.isCreating || state.isAnalyzing) {
            OutlinedButton(
                onClick = viewModel::cancelActiveTask,
                modifier = Modifier.minimumInteractiveComponentSize().testTag("cancel_task_button")
            ) {
                Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(strings.cancelBtn)
            }
        }
    }

    if (state.isCreating) {
        LinearProgressIndicator(
            progress = { state.createProgress },
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
        )
    }

    state.errorMessage?.let { err ->
        Surface(
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "${strings.errorPrefix}: $err",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(12.dp)
            )
        }
    }

    state.statusBanner?.let { banner ->
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = banner,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(12.dp)
            )
        }
    }

    state.generatedSticker?.let { res ->
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StickerPreviewBox(
                        frames = res.previewFrames,
                        fps = res.fps,
                        isAnimated = res.isAnimated,
                        durationSec = res.durationSec,
                        sizeDp = 112.dp
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(strings.stickerReadyTitle, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
                        Text(
                            text = buildString {
                                append("${formatSec(res.durationSec)} · ${res.side}px")
                                if (res.isAnimated) append(" · ${res.fps}fps")
                                append(" · WebP ${formatKb(res.webpBytes.size)}")
                                if (res.oggBytes != null) append(" · OGG ${formatKb(res.oggBytes.size)}")
                                append(" · ${res.elapsedMs}ms")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FilledTonalButton(
                            onClick = {
                                if (state.isPlayingStickerPreview) viewModel.stopPlayback()
                                else viewModel.playGeneratedAudioPreview()
                            },
                            modifier = Modifier.minimumInteractiveComponentSize().testTag("play_together_button")
                        ) {
                            Icon(
                                if (state.isPlayingStickerPreview) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (state.isPlayingStickerPreview) strings.stopPlay else strings.playTogetherBtn)
                        }
                    }
                }

                // Primary: Save MP4 to Gallery (for WhatsApp's official Create Sticker flow)
                Button(
                    onClick = {
                        viewModel.exportAndShareMp4(
                            uiContext = uiContext,
                            chooserTitle = strings.saveMp4GalleryBtn,
                            shareDirectlyToWhatsApp = false,
                            galleryMsg = strings.savedToGalleryMsg
                        )
                    },
                    enabled = !state.isExportingMp4,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    ),
                    modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("save_mp4_gallery_button")
                ) {
                    if (state.isExportingMp4) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSecondary)
                    } else {
                        Icon(Icons.Default.SaveAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(strings.saveMp4GalleryBtn)
                }

                // Secondary actions: Save to Pack + Direct Share MP4 (sends as video message, not sticker)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = onOpenSaveDialog,
                        modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("save_to_pack_button")
                    ) {
                        Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(strings.saveToPackBtn, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = {
                            viewModel.exportAndShareMp4(
                                uiContext = uiContext,
                                chooserTitle = strings.shareMp4WaBtn,
                                shareDirectlyToWhatsApp = true,
                                galleryMsg = strings.savedToGalleryMsg
                            )
                        },
                        enabled = !state.isExportingMp4,
                        modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("share_mp4_button")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(strings.shareMp4WaBtn, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                    }
                }

                OutlinedButton(
                    onClick = {
                        viewModel.shareRawPair(uiContext, strings.exportRawBtn, strings.exportedFilesMsg)
                    },
                    modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("export_raw_button")
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(strings.exportRawBtn)
                }

                OutlinedButton(
                    onClick = onSendViaWeb,
                    modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("send_via_web_button")
                ) {
                    Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(strings.sendViaWebBtn)
                }

                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = strings.waAudioTip,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        }
    }
}
