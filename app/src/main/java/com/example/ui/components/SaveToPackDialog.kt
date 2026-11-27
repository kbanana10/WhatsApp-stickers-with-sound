package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.local.StickerPackEntity
import com.example.data.local.StickerPackWithStickers
import com.example.i18n.Strings

@Composable
fun SaveToPackDialog(
    packs: List<StickerPackWithStickers>,
    defaultPackName: String,
    defaultPublisher: String,
    strings: Strings,
    onDismiss: () -> Unit,
    onSelectExistingPack: (StickerPackEntity) -> Unit,
    onCreateNewPack: (name: String, publisher: String) -> Unit
) {
    var creatingNew by remember { mutableStateOf(packs.isEmpty()) }
    var newName by remember { mutableStateOf(defaultPackName) }
    var newPublisher by remember { mutableStateOf(defaultPublisher) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (creatingNew) strings.newPackBtn else strings.saveToPackBtn,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            if (creatingNew) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(strings.packNameField) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("new_pack_name_input")
                    )
                    OutlinedTextField(
                        value = newPublisher,
                        onValueChange = { newPublisher = it },
                        label = { Text(strings.publisherField) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("new_pack_publisher_input")
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().height(200.dp)
                    ) {
                        items(packs, key = { it.pack.packId }) { item ->
                            val full = item.stickers.size >= 30
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !full) { onSelectExistingPack(item.pack) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        Icons.Default.FolderSpecial,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(item.pack.name, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            text = strings.stickersCount.format(item.stickers.size),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(6.dp))
                    TextButton(
                        onClick = { creatingNew = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text(strings.newPackBtn)
                    }
                }
            }
        },
        confirmButton = {
            if (creatingNew) {
                Button(
                    onClick = { onCreateNewPack(newName, newPublisher) },
                    modifier = Modifier.testTag("confirm_new_pack_button")
                ) {
                    Text(strings.createPackConfirm)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.cancelBtn)
            }
        }
    )
}
