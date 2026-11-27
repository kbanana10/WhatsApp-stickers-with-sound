package com.example.engine

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Pure Kotlin Ogg (RFC 3533) + Opus (RFC 7845) container builder and hardware/platform Opus encoder.
 */
object OggOpusMuxer {

    private val CRC_TABLE: IntArray = IntArray(256).apply {
        for (i in 0 until 256) {
            var r = i shl 24
            repeat(8) {
                r = if ((r and 0x80000000.toInt()) != 0) {
                    (r shl 1) xor 0x04c11db7
                } else {
                    r shl 1
                }
            }
            this[i] = r
        }
    }

    private fun oggCrc(bytes: ByteArray): Int {
        var c = 0
        for (b in bytes) {
            c = (c shl 8) xor CRC_TABLE[((c ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
        }
        return c
    }

    private fun oggPage(
        headerType: Int,
        granule: Long,
        serial: Int,
        seq: Int,
        packets: List<ByteArray>
    ): ByteArray {
        val lacing = mutableListOf<Byte>()
        for (p in packets) {
            var len = p.size
            while (len >= 255) {
                lacing.add(255.toByte())
                len -= 255
            }
            lacing.add(len.toByte())
        }
        require(lacing.size <= 255) { "Ogg page exceeds 255 segments" }
        val dataLen = packets.sumOf { it.size }
        val page = ByteArray(27 + lacing.size + dataLen)
        val dv = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN)
        System.arraycopy("OggS".toByteArray(Charsets.US_ASCII), 0, page, 0, 4)
        page[4] = 0
        page[5] = headerType.toByte()
        dv.putLong(6, granule)
        dv.putInt(14, serial)
        dv.putInt(18, seq)
        dv.putInt(22, 0)
        page[26] = lacing.size.toByte()
        for (i in lacing.indices) page[27 + i] = lacing[i]
        var offset = 27 + lacing.size
        for (p in packets) {
            System.arraycopy(p, 0, page, offset, p.size)
            offset += p.size
        }
        dv.putInt(22, oggCrc(page))
        return page
    }

    fun buildOggOpus(packets: List<ByteArray>, preSkip: Int, totalSamples48k: Long): ByteArray {
        val serial = Random.nextInt()
        val head = ByteArray(19)
        System.arraycopy("OpusHead".toByteArray(Charsets.US_ASCII), 0, head, 0, 8)
        head[8] = 1 // version
        head[9] = 1 // mono
        ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(10, preSkip.toShort())
            putInt(12, StickerLimits.SAMPLE_RATE)
        }

        val vendor = "WAAudioStickers".toByteArray(Charsets.UTF_8)
        val tags = ByteArray(8 + 4 + vendor.size + 4)
        System.arraycopy("OpusTags".toByteArray(Charsets.US_ASCII), 0, tags, 0, 8)
        ByteBuffer.wrap(tags).order(ByteOrder.LITTLE_ENDIAN).putInt(8, vendor.size)
        System.arraycopy(vendor, 0, tags, 12, vendor.size)

        val pages = mutableListOf<ByteArray>()
        pages.add(oggPage(0x02, 0L, serial, 0, listOf(head)))
        pages.add(oggPage(0x00, 0L, serial, 1, listOf(tags)))

        val finalGranule = preSkip + totalSamples48k
        val perPage = 50 // ~1s per page at 20ms frames
        var seq = 2
        var idx = 0
        while (idx < packets.size) {
            val end = minOf(idx + perPage, packets.size)
            val group = packets.subList(idx, end)
            val isLast = end >= packets.size
            val granule = if (isLast) {
                finalGranule
            } else {
                minOf(finalGranule, preSkip + end.toLong() * 960L)
            }
            pages.add(oggPage(if (isLast) 0x04 else 0x00, granule, serial, seq++, group))
            idx = end
        }
        val out = ByteArrayOutputStream(pages.sumOf { it.size })
        for (p in pages) out.write(p)
        return out.toByteArray()
    }

    /**
     * Encodes 48kHz Mono 16-bit PCM into Ogg/Opus bytes using Android's MediaCodec Opus encoder (API 29+).
     * Returns null if Opus encoding is unavailable on an older device (API < 29).
     */
    fun encodePcm48kMonoToOgg(pcm: ShortArray, bitrate: Int): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val encoder = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        } catch (_: Exception) {
            return null
        }
        return try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_OPUS,
                StickerLimits.SAMPLE_RATE,
                1
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 960 * 4)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val packets = mutableListOf<ByteArray>()
            var preSkip = 312
            val frameSize = 960 // 20ms at 48kHz
            var sampleIndex = 0
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = encoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = encoder.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        if (sampleIndex >= pcm.size) {
                            encoder.queueInputBuffer(
                                inIdx,
                                0,
                                0,
                                (sampleIndex * 1_000_000L) / StickerLimits.SAMPLE_RATE,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val count = minOf(frameSize, pcm.size - sampleIndex)
                            inBuf.order(ByteOrder.LITTLE_ENDIAN)
                            for (i in 0 until frameSize) {
                                val s = if (i < count) pcm[sampleIndex + i] else 0
                                inBuf.putShort(s)
                            }
                            val pts = (sampleIndex * 1_000_000L) / StickerLimits.SAMPLE_RATE
                            encoder.queueInputBuffer(inIdx, 0, frameSize * 2, pts, 0)
                            sampleIndex += count
                        }
                    }
                }

                val outIdx = encoder.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    val outBuf = encoder.getOutputBuffer(outIdx)!!
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        val cfg = ByteArray(info.size)
                        outBuf.position(info.offset)
                        outBuf.get(cfg)
                        if (cfg.size >= 12 && String(cfg, 0, 8, Charsets.US_ASCII) == "OpusHead") {
                            preSkip = (cfg[10].toInt() and 0xFF) or ((cfg[11].toInt() and 0xFF) shl 8)
                        }
                    } else if (info.size > 0) {
                        val pkt = ByteArray(info.size)
                        outBuf.position(info.offset)
                        outBuf.get(pkt)
                        packets.add(pkt)
                    }
                    encoder.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                }
            }
            if (packets.isEmpty()) null else buildOggOpus(packets, preSkip, pcm.size.toLong())
        } catch (_: Exception) {
            null
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
        }
    }
}
