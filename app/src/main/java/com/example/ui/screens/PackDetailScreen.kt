package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.local.StickerEntity
import com.example.i18n.Strings
import com.example.ui.components.formatKb
import com.example.ui.components.formatSec
import com.example.ui.theme.MonoFontFamily
import com.example.ui.viewmodel.PacksViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackDetailScreen(
    packId: String,
    viewModel: PacksViewModel,
    strings: Strings,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val packWithStickers by viewModel.observePack(packId).collectAsStateWithLifecycle(initialValue = null)
    val playingId by viewModel.playingStickerId.collectAsStateWithLifecycle()
    val feedback by viewModel.feedbackMessage.collectAsStateWithLifecycle()
    val isSuccessFeedback by viewModel.isSuccessFeedback.collectAsStateWithLifecycle()
    val isRootAvailable by viewModel.isRootAvailable.collectAsStateWithLifecycle()
    val isInjectingRoot by viewModel.isInjectingRoot.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(packWithStickers?.pack?.name ?: strings.packsTitle, style = MaterialTheme.typography.titleMedium)
                        packWithStickers?.let {
                            Text(
                                text = strings.stickersCount.format(it.stickers.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.stopAudio()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.backLabel)
                    }
                }
            )
        },
        bottomBar = {
            packWithStickers?.let { p ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isRootAvailable) {
                        Button(
                            onClick = {
                                viewModel.injectPackWithRoot(
                                    packWithStickers = p,
                                    successTemplate = strings.rootInjectSuccessPack,
                                    noAudioPackMsg = strings.rootNoAudioPackError
                                )
                            },
                            enabled = p.stickers.isNotEmpty() && !isInjectingRoot,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier
                                .widthIn(max = 600.dp)
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .testTag("detail_root_inject_pack_button")
                        ) {
                            if (isInjectingRoot) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (isInjectingRoot) strings.rootInjectingStatus
                                else strings.rootInjectPackBtn
                            )
                        }
                    }

                    Button(
                        onClick = {
                            viewModel.addPackToWhatsApp(context, p.pack.packId, p.pack.name, strings.whatsappNotInstalled)
                        },
                        enabled = p.stickers.size in 3..30 && !isInjectingRoot,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary
                        ),
                        modifier = Modifier
                            .widthIn(max = 600.dp)
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("detail_add_whatsapp_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.addToWhatsAppBtn)
                    }
                }
            }
        }
    ) { innerPadding ->
        val stickers = packWithStickers?.stickers.orEmpty()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            feedback?.let { msg ->
                Surface(
                    color = if (isSuccessFeedback) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
                    },
                    shape = RoundedCornerShape(12.dp),
                    onClick = { viewModel.clearFeedback() },
                    modifier = Modifier
                        .widthIn(max = 680.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = msg,
                        color = if (isSuccessFeedback) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 156.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.widthIn(max = 680.dp).weight(1f).fillMaxWidth()
            ) {
                items(stickers, key = { it.id }) { sticker ->
                    StickerItemCard(
                        sticker = sticker,
                        isPlaying = playingId == sticker.id,
                        showRootInject = isRootAvailable && sticker.oggBytes > 0 && !isInjectingRoot,
                        file = viewModel.repository.stickerFile(sticker.packId, sticker.fileName),
                        context = context,
                        strings = strings,
                        onPlayToggle = { viewModel.playStickerSound(sticker) },
                        onRootInject = {
                            val packMeta = packWithStickers?.pack
                            viewModel.injectStickerWithRoot(
                                sticker = sticker,
                                packName = packMeta?.name ?: "Audio Stickers",
                                publisher = packMeta?.publisher ?: "Audio Stickers",
                                successTemplate = strings.rootInjectSuccessSingle,
                                missingOpusMsg = strings.rootMissingOpusError
                            )
                        },
                        onShare = { viewModel.shareStickerMp4OrRaw(context, sticker, strings.shareMp4WaBtn) },
                        onDelete = { viewModel.deleteSticker(sticker) }
                    )
                }
            }
        }
    }
}

@Composable
private fun StickerItemCard(
    sticker: StickerEntity,
    isPlaying: Boolean,
    showRootInject: Boolean,
    file: java.io.File,
    context: android.content.Context,
    strings: Strings,
    onPlayToggle: () -> Unit,
    onRootInject: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(file)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Text(
                text = "${formatSec(sticker.audioDurationMs / 1000.0)} · ${sticker.side}px · ${formatKb(sticker.webpBytes)}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onPlayToggle, modifier = Modifier.minimumInteractiveComponentSize()) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = strings.playTogetherBtn,
                        tint = if (isPlaying) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                    )
                }
                if (showRootInject) {
                    IconButton(
                        onClick = onRootInject,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("sticker_root_inject_${sticker.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = strings.rootInjectSingleBtn,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                IconButton(onClick = onShare, modifier = Modifier.minimumInteractiveComponentSize()) {
                    Icon(Icons.Default.Share, contentDescription = strings.shareMp4WaBtn)
                }
                IconButton(onClick = onDelete, modifier = Modifier.minimumInteractiveComponentSize()) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = strings.deleteSticker, tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
