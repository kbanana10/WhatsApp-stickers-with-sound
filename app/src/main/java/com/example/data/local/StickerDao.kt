package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface StickerDao {
    @Transaction
    @Query("SELECT * FROM sticker_packs ORDER BY createdAt DESC")
    fun observePacksWithStickers(): Flow<List<StickerPackWithStickers>>

    @Transaction
    @Query("SELECT * FROM sticker_packs WHERE packId = :packId LIMIT 1")
    fun observePackById(packId: String): Flow<StickerPackWithStickers?>

    @Transaction
    @Query("SELECT * FROM sticker_packs ORDER BY createdAt DESC")
    fun getAllPacksSync(): List<StickerPackWithStickers>

    @Transaction
    @Query("SELECT * FROM sticker_packs WHERE packId = :packId LIMIT 1")
    fun getPackByIdSync(packId: String): StickerPackWithStickers?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPack(pack: StickerPackEntity)

    @Update
    suspend fun updatePack(pack: StickerPackEntity)

    @Query("DELETE FROM sticker_packs WHERE packId = :packId")
    suspend fun deletePack(packId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSticker(sticker: StickerEntity): Long

    @Query("DELETE FROM stickers WHERE id = :stickerId")
    suspend fun deleteSticker(stickerId: Long)

    @Query("SELECT COUNT(*) FROM stickers WHERE packId = :packId")
    suspend fun countStickersInPack(packId: String): Int

    @Query("SELECT * FROM stickers WHERE id = :stickerId LIMIT 1")
    suspend fun getStickerById(stickerId: Long): StickerEntity?
}
