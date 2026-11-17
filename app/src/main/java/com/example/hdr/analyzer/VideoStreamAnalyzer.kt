package com.example.hdr.analyzer

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaCodecInfo.CodecProfileLevel
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.example.hdr.model.ColorPrimaries
import com.example.hdr.model.ColorRangeType
import com.example.hdr.model.HdrStaticMetadata
import com.example.hdr.model.TransferCharacteristic
import com.example.hdr.model.VideoAnalysisReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Module A: Real-time Video Stream Analyzer.
 * Inspects elementary video tracks via MediaExtractor and MediaMetadataRetriever
 * to extract resolution, frame rate, codec, bit depth, transfer function,
 * color primaries, color range, and CTA-861.3 / SMPTE ST.2086 HDR static metadata.
 */
class VideoStreamAnalyzer(private val context: Context) {

    suspend fun analyze(uri: Uri): VideoAnalysisReport = withContext(Dispatchers.IO) {
        val displayName = resolveDisplayName(uri)
        val extractor = MediaExtractor()
        val retriever = MediaMetadataRetriever()

        try {
            if (uri.scheme == "http" || uri.scheme == "https") {
                extractor.setDataSource(uri.toString(), emptyMap())
                runCatching { retriever.setDataSource(uri.toString(), HashMap()) }
            } else {
                extractor.setDataSource(context, uri, null)
                runCatching { retriever.setDataSource(context, uri) }
            }

            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") && videoFormat == null) {
                    videoFormat = format
                } else if (mime.startsWith("audio/") && audioFormat == null) {
                    audioFormat = format
                }
            }

            if (videoFormat == null) {
                return@withContext VideoAnalysisReport(
                    sourceUri = uri.toString(),
                    displayName = displayName,
                    analysisError = "No elementary video track found in container."
                )
            }

            val mime = videoFormat.getString(MediaFormat.KEY_MIME) ?: "Unknown"
            val width = videoFormat.safeInt(MediaFormat.KEY_WIDTH)
                ?: retriever.safeInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?: 0
            val height = videoFormat.safeInt(MediaFormat.KEY_HEIGHT)
                ?: retriever.safeInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?: 0

            val durationMs = videoFormat.safeLong(MediaFormat.KEY_DURATION)?.div(1000L)
                ?: retriever.safeLong(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?: 0L

            val fps = extractFrameRate(videoFormat, retriever, durationMs)
            val bitrate = videoFormat.safeLong(MediaFormat.KEY_BIT_RATE)
                ?: retriever.safeLong(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?: 0L

            val profile = videoFormat.safeInt(MediaFormat.KEY_PROFILE)
            val level = videoFormat.safeInt(MediaFormat.KEY_LEVEL)
            val profileName = mapCodecProfileName(mime, profile, level)

            val bitDepth = inferBitDepth(mime, profile, videoFormat, retriever)
            val transfer = extractTransferCharacteristic(videoFormat, retriever)
            val primaries = extractColorPrimaries(videoFormat, retriever)
            val range = extractColorRange(videoFormat)
            val staticMeta = videoFormat.getByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO)
                ?.let { parseHdrStaticInfo(it) }

            val hasHdr10Plus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                videoFormat.containsKey(MediaFormat.KEY_HDR10_PLUS_INFO)
            } else false

            val audioSummary = audioFormat?.let { af ->
                val aMime = af.getString(MediaFormat.KEY_MIME)?.removePrefix("audio/")?.uppercase() ?: "AUDIO"
                val channels = af.safeInt(MediaFormat.KEY_CHANNEL_COUNT) ?: 2
                val sampleRate = af.safeInt(MediaFormat.KEY_SAMPLE_RATE)?.div(1000) ?: 48
                "$aMime ${channels}ch ${sampleRate}kHz"
            } ?: "No Audio Track"

            VideoAnalysisReport(
                sourceUri = uri.toString(),
                displayName = displayName,
                width = width,
                height = height,
                frameRateFps = fps,
                codecMime = mime,
                codecProfileName = profileName,
                bitDepth = bitDepth,
                transferCharacteristic = transfer,
                colorPrimaries = primaries,
                colorRange = range,
                staticMetadata = staticMeta,
                hasDynamicHdr10Plus = hasHdr10Plus,
                bitrateBps = bitrate,
                durationMs = durationMs,
                audioSummary = audioSummary
            )
        } catch (e: Exception) {
            VideoAnalysisReport(
                sourceUri = uri.toString(),
                displayName = displayName,
                analysisError = e.localizedMessage ?: "Failed to inspect stream metadata"
            )
        } finally {
            runCatching { extractor.release() }
            runCatching { retriever.release() }
        }
    }

    private fun extractFrameRate(
        format: MediaFormat,
        retriever: MediaMetadataRetriever,
        durationMs: Long
    ): Float {
        if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            runCatching { return format.getFloat(MediaFormat.KEY_FRAME_RATE) }
            runCatching { return format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
        }
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            ?.toFloatOrNull()
            ?.takeIf { it > 0f }
            ?.let { return it }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && durationMs > 0L) {
            val frameCount = retriever.safeLong(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
            if (frameCount != null && frameCount > 0L) {
                return (frameCount * 1000f) / durationMs.toFloat()
            }
        }
        return 0f
    }

    private fun inferBitDepth(
        mime: String,
        profile: Int?,
        format: MediaFormat,
        retriever: MediaMetadataRetriever
    ): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            retriever.safeInt(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.let {
                if (it >= 8) return it
            }
        }
        if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC && profile != null) {
            if (profile == CodecProfileLevel.HEVCProfileMain10 ||
                profile == CodecProfileLevel.HEVCProfileMain10HDR10 ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    profile == CodecProfileLevel.HEVCProfileMain10HDR10Plus)
            ) {
                return 10
            }
        }
        if (mime == MediaFormat.MIMETYPE_VIDEO_AV1 && profile != null) {
            if (profile == CodecProfileLevel.AV1ProfileMain10 ||
                profile == CodecProfileLevel.AV1ProfileMain10HDR10 ||
                profile == CodecProfileLevel.AV1ProfileMain10HDR10Plus
            ) {
                return 10
            }
        }
        if (mime == MediaFormat.MIMETYPE_VIDEO_VP9 && profile != null) {
            if (profile == CodecProfileLevel.VP9Profile2 ||
                profile == CodecProfileLevel.VP9Profile2HDR ||
                profile == CodecProfileLevel.VP9Profile2HDR10Plus ||
                profile == CodecProfileLevel.VP9Profile3 ||
                profile == CodecProfileLevel.VP9Profile3HDR
            ) {
                return 10
            }
        }
        if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
            val transfer = format.safeInt(MediaFormat.KEY_COLOR_TRANSFER)
            if (transfer == MediaFormat.COLOR_TRANSFER_ST2084 ||
                transfer == MediaFormat.COLOR_TRANSFER_HLG
            ) {
                return 10
            }
        }
        return 8
    }

    private fun extractTransferCharacteristic(
        format: MediaFormat,
        retriever: MediaMetadataRetriever
    ): TransferCharacteristic {
        val code = format.safeInt(MediaFormat.KEY_COLOR_TRANSFER)
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                retriever.safeInt(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)
            } else null

        return when (code) {
            MediaFormat.COLOR_TRANSFER_ST2084 -> TransferCharacteristic.PQ_ST2084
            MediaFormat.COLOR_TRANSFER_HLG -> TransferCharacteristic.HLG
            MediaFormat.COLOR_TRANSFER_SDR_VIDEO -> TransferCharacteristic.SDR_GAMMA
            MediaFormat.COLOR_TRANSFER_LINEAR -> TransferCharacteristic.LINEAR
            else -> TransferCharacteristic.UNKNOWN
        }
    }

    private fun extractColorPrimaries(
        format: MediaFormat,
        retriever: MediaMetadataRetriever
    ): ColorPrimaries {
        val code = format.safeInt(MediaFormat.KEY_COLOR_STANDARD)
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                retriever.safeInt(MediaMetadataRetriever.METADATA_KEY_COLOR_STANDARD)
            } else null

        return when (code) {
            MediaFormat.COLOR_STANDARD_BT2020 -> ColorPrimaries.BT_2020
            MediaFormat.COLOR_STANDARD_BT709 -> ColorPrimaries.BT_709
            MediaFormat.COLOR_STANDARD_BT601_PAL,
            MediaFormat.COLOR_STANDARD_BT601_NTSC -> ColorPrimaries.BT_601
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && code == 10) {
                    ColorPrimaries.DISPLAY_P3
                } else {
                    ColorPrimaries.UNKNOWN
                }
            }
        }
    }

    private fun extractColorRange(format: MediaFormat): ColorRangeType {
        return when (format.safeInt(MediaFormat.KEY_COLOR_RANGE)) {
            MediaFormat.COLOR_RANGE_LIMITED -> ColorRangeType.LIMITED
            MediaFormat.COLOR_RANGE_FULL -> ColorRangeType.FULL
            else -> ColorRangeType.UNKNOWN
        }
    }

    private fun mapCodecProfileName(mime: String, profile: Int?, level: Int?): String {
        val codecTag = when (mime) {
            MediaFormat.MIMETYPE_VIDEO_HEVC -> "HEVC / H.265"
            MediaFormat.MIMETYPE_VIDEO_AVC -> "AVC / H.264"
            MediaFormat.MIMETYPE_VIDEO_AV1 -> "AV1"
            MediaFormat.MIMETYPE_VIDEO_VP9 -> "VP9"
            else -> mime.removePrefix("video/").uppercase()
        }
        val profileTag = when {
            mime == MediaFormat.MIMETYPE_VIDEO_HEVC && profile != null -> when (profile) {
                CodecProfileLevel.HEVCProfileMain -> "Main"
                CodecProfileLevel.HEVCProfileMain10 -> "Main10"
                CodecProfileLevel.HEVCProfileMain10HDR10 -> "Main10 HDR10"
                else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    profile == CodecProfileLevel.HEVCProfileMain10HDR10Plus
                ) "Main10 HDR10+" else "Profile $profile"
            }
            mime == MediaFormat.MIMETYPE_VIDEO_AVC && profile != null -> when (profile) {
                CodecProfileLevel.AVCProfileBaseline -> "Baseline"
                CodecProfileLevel.AVCProfileMain -> "Main"
                CodecProfileLevel.AVCProfileHigh -> "High"
                CodecProfileLevel.AVCProfileHigh10 -> "High10"
                else -> "Profile $profile"
            }
            mime == MediaFormat.MIMETYPE_VIDEO_AV1 && profile != null -> when (profile) {
                CodecProfileLevel.AV1ProfileMain8 -> "Main 8-bit"
                CodecProfileLevel.AV1ProfileMain10 -> "Main 10-bit"
                CodecProfileLevel.AV1ProfileMain10HDR10 -> "Main10 HDR10"
                else -> "Profile $profile"
            }
            else -> "Standard"
        }
        val levelTag = level?.let { " L$it" }.orEmpty()
        return "$codecTag ($profileTag$levelTag)"
    }

    private fun resolveDisplayName(uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) return cursor.getString(idx)
                        }
                    }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
    }

    companion object {
        /**
         * Parses CTA-861.3 Type 1 Static Metadata Descriptor (25 bytes, little-endian).
         */
        fun parseHdrStaticInfo(buffer: ByteBuffer): HdrStaticMetadata {
            val dup = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val remaining = dup.remaining()
            if (remaining < 25) {
                return HdrStaticMetadata(rawByteLength = remaining)
            }
            dup.position(dup.position() + 17) // Skip descriptor ID (1) + 3 primaries (12) + white point (4)
            val maxMastering = (dup.short.toInt() and 0xFFFF).toFloat()
            val minMasteringRaw = dup.short.toInt() and 0xFFFF
            val minMastering = minMasteringRaw * 0.0001f
            val maxCll = dup.short.toInt() and 0xFFFF
            val maxFall = dup.short.toInt() and 0xFFFF
            return HdrStaticMetadata(
                masteringMaxLuminanceNits = if (maxMastering > 0f) maxMastering else null,
                masteringMinLuminanceNits = if (minMastering > 0f) minMastering else null,
                maxContentLightLevelNits = if (maxCll > 0) maxCll else null,
                maxFrameAverageLightLevelNits = if (maxFall > 0) maxFall else null,
                rawByteLength = remaining
            )
        }
    }
}

private fun MediaFormat.safeInt(key: String): Int? =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

private fun MediaFormat.safeLong(key: String): Long? =
    if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null

private fun MediaMetadataRetriever.safeInt(key: Int): Int? =
    extractMetadata(key)?.toIntOrNull()

private fun MediaMetadataRetriever.safeLong(key: Int): Long? =
    extractMetadata(key)?.toLongOrNull()
