package io.github.madtreasures.videoeditor.export

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.CodecSpecificDataUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Muxer
import androidx.media3.muxer.MuxerException
import com.google.common.collect.ImmutableList
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Lets Media3's exporter write Matroska (.mkv) files with H.264, VP9 or VP8 pictures and AAC or
 * Opus sound. Media3 itself only writes WebM, which allows no H.264 and no AAC.
 *
 * Matroska has no rotation flag, so the exporter must hand over pictures the right way up;
 * rotation metadata is ignored here.
 */
@OptIn(UnstableApi::class)
class MatroskaMuxer private constructor(path: String) : Muxer {

    class Factory : Muxer.Factory {
        override fun create(path: String): Muxer = MatroskaMuxer(path)

        override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> = when (trackType) {
            C.TRACK_TYPE_VIDEO -> ImmutableList.of(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_VP9, MimeTypes.VIDEO_VP8)
            C.TRACK_TYPE_AUDIO -> ImmutableList.of(MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_OPUS)
            else -> ImmutableList.of()
        }
    }

    private val writer = try {
        MatroskaWriter(RandomAccessFile(path, "rw").apply { setLength(0) })
    } catch (e: IOException) {
        throw MuxerException("Cannot open $path", e)
    }

    private val avcTracks = HashSet<Int>()

    override fun addTrack(format: Format): Int {
        val init = format.initializationData
        val track = when (format.sampleMimeType) {
            MimeTypes.VIDEO_H264 -> writer.addVideoTrack(
                "V_MPEG4/ISO/AVC",
                CodecData.avcConfig(init.flatMap { CodecData.nalUnits(it) }),
                format.width,
                format.height,
            ).also { avcTracks += it.number - 1 }
            MimeTypes.VIDEO_VP9 -> writer.addVideoTrack("V_VP9", null, format.width, format.height)
            MimeTypes.VIDEO_VP8 -> writer.addVideoTrack("V_VP8", null, format.width, format.height)
            MimeTypes.AUDIO_AAC -> writer.addAudioTrack(
                "A_AAC",
                init.firstOrNull() ?: CodecData.aacConfig(format.sampleRate, format.channelCount),
                format.sampleRate,
                format.channelCount,
            )
            MimeTypes.AUDIO_OPUS -> {
                // Android's encoder wraps the header in its own structure; Media3 unwraps it.
                val head = runCatching { CodecSpecificDataUtil.getOpusInitializationData(format) }.getOrNull()
                    ?.takeIf { CodecData.isOpusHead(it) }
                    ?: CodecData.opusHead(format.channelCount, OPUS_DEFAULT_PRE_SKIP, format.sampleRate)
                writer.addAudioTrack(
                    "A_OPUS",
                    head,
                    format.sampleRate,
                    format.channelCount,
                    codecDelayNs = CodecData.opusPreSkip(head) * 1_000_000_000L / 48_000,
                    seekPreRollNs = OPUS_SEEK_PRE_ROLL_NS,
                )
            }
            else -> throw MuxerException(
                "Unsupported format",
                IllegalArgumentException("Matroska output does not support ${format.sampleMimeType}"),
            )
        }
        return track.number - 1
    }

    override fun writeSampleData(trackId: Int, byteBuffer: ByteBuffer, bufferInfo: BufferInfo) {
        val track = writer.trackList.getOrNull(trackId)
            ?: throw MuxerException("Unknown track", IllegalArgumentException("No track $trackId"))
        val data = ByteArray(byteBuffer.remaining()).also { byteBuffer.get(it) }
        val keyFrame = bufferInfo.flags and C.BUFFER_FLAG_KEY_FRAME != 0
        val sample = if (trackId in avcTracks) {
            // An encoder that did not report its parameter sets sends them with the first key frame.
            if (track.codecPrivate == null && keyFrame) {
                track.codecPrivate = CodecData.avcConfig(CodecData.nalUnits(data))
            }
            CodecData.toLengthPrefixed(data)
        } else {
            data
        }
        try {
            writer.writeSample(track, bufferInfo.presentationTimeUs, keyFrame, sample)
        } catch (e: IOException) {
            throw MuxerException("Failed to write sample", e)
        }
    }

    override fun addMetadataEntry(metadataEntry: Metadata.Entry) {
        // Rotation and other metadata have no place in this file type.
    }

    override fun close() {
        try {
            writer.close()
        } catch (e: IOException) {
            throw MuxerException("Failed to finish the file", e)
        }
    }

    private companion object {
        const val OPUS_DEFAULT_PRE_SKIP = 312
        const val OPUS_SEEK_PRE_ROLL_NS = 80_000_000L
    }
}
