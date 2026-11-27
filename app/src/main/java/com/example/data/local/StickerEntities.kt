package com.example.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "sticker_packs")
data class StickerPackEntity(
    @PrimaryKey val packId: String,
    val name: String,
    val publisher: String,
    val trayIconFileName: String,
    val imageDataVersion: Int = 1,
    val animatedPack: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "stickers",
    foreignKeys = [
        ForeignKey(
            entity = StickerPackEntity::class,
            parentColumns = ["packId"],
            childColumns = ["packId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("packId")]
)
data class StickerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packId: String,
    val fileName: String,
    val oggFileName: String,
    val mp4FileName: String?,
    val sourceKind: String,
    val soundSha256: String,
    val durationMs: Long,
    val audioDurationMs: Long,
    val side: Int,
    val fps: Int,
    val isAnimated: Boolean,
    val webpBytes: Long,
    val oggBytes: Long,
    val emojis: String = "🔊",
    val createdAt: Long = System.currentTimeMillis()
)

data class StickerPackWithStickers(
    @Embedded val pack: StickerPackEntity,
    @Relation(
        parentColumn = "packId",
        entityColumn = "packId"
    )
    val stickers: List<StickerEntity>
)
