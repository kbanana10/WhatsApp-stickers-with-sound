package com.example.engine

import kotlin.math.roundToInt

enum class SourceKind {
    VIDEO,
    IMAGE,
    MIX_VIDEO,
    MIX_GIF
}

enum class FramingMode {
    CONTAIN,
    COVER
}

enum class MixFitMode {
    INDEPENDENT,
    REPEAT,
    FREEZE
}

enum class SizeCapMode(val maxBytes: Int, val safeBytes: Int) {
    WHATSAPP_PACK_500KB(500_000, 465_000),
    MAX_QUALITY_1MB(1_000_000, 900_000)
}

enum class CompressionMode(val targetQualityOverride: Float?) {
    AUTO(null),
    HIGH(0.85f),
    BALANCED(0.65f),
    COMPACT(0.40f)
}

enum class CellStatus {
    OK,
    LOW_QUALITY,
    OVER_LIMIT,
    UNAVAILABLE
}

data class GridCell(
    val side: Int,
    val fps: Int,
    val estBytes: Int,
    val estMinBytes: Int,
    val status: CellStatus,
    val score: Double
)

object StickerLimits {
    const val SIDE = 512
    const val MAX_STICKER_BYTES = 1_000_000
    const val SAFE_BYTES = 900_000
    const val WA_PACK_MAX_ANIMATED_BYTES = 500_000
    const val WA_PACK_SAFE_ANIMATED_BYTES = 470_000
    const val WA_PACK_MAX_STATIC_BYTES = 100_000
    const val MAX_AUDIO_BYTES = 1_000_000
    const val SAMPLE_RATE = 48_000
    const val CAPTURE_MEM_BYTES = 120_000_000L
    const val Q_MIN = 0.15f

    val SIDES = listOf(512, 448, 384, 320, 256, 224, 192, 160, 128, 96, 64)
    val FPS_LIST = listOf(30, 25, 20, 15, 12, 10, 8, 6, 5)

    val Q_BY_SIDE = mapOf(
        512 to 0.75f,
        448 to 0.70f,
        384 to 0.65f,
        320 to 0.60f,
        256 to 0.55f,
        224 to 0.55f,
        192 to 0.50f,
        160 to 0.50f,
        128 to 0.45f,
        96 to 0.45f,
        64 to 0.40f
    )

    private val FPS_SCORE = mapOf(
        30 to 101.0,
        25 to 100.5,
        20 to 100.0,
        15 to 80.0,
        12 to 55.0,
        10 to 40.0,
        8 to 25.0,
        6 to 12.0,
        5 to 5.0
    )

    fun score(side: Int, fps: Int): Double {
        val fpsScore = FPS_SCORE[fps] ?: 0.0
        val penalty = if (side < 192) (192 - side) / 3.0 else 0.0
        return fpsScore + (40.0 * side) / 512.0 - penalty
    }

    fun captureFpsFor(durSec: Double): Int = when {
        durSec <= 10.0 -> 30
        durSec <= 20.0 -> 25
        durSec <= 40.0 -> 20
        durSec <= 80.0 -> 15
        else -> 12
    }

    fun computeTargetAudioBitrate(durationSec: Double): Int {
        val safeDur = durationSec.coerceAtLeast(0.25)
        val raw = ((0.9 * MAX_AUDIO_BYTES * 8.0) / safeDur).roundToInt()
        return raw.coerceIn(12_000, 32_000)
    }
}
