package io.github.madtreasures.videoeditor.export

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.RandomAccessFile
import kotlin.math.max
import kotlin.random.Random

/**
 * Writes a Matroska (.mkv) file: an EBML header, a segment with seek index, info and tracks,
 * then the samples in clusters of a few seconds each, and an index of the clusters (cues) at
 * the end so players can seek.
 *
 * Plain Kotlin on purpose, so it can be tested without a device. [MatroskaMuxer] connects it to
 * Media3.
 *
 * Timestamps are written in milliseconds (the usual Matroska time scale) and counted from the
 * first sample, so a file always starts at 0.
 */
class MatroskaWriter(
    private val file: RandomAccessFile,
    private val docType: String = "matroska",
    private val appName: String = "Video Editor",
) : Closeable {

    class Track internal constructor(
        val number: Int,
        val isVideo: Boolean,
        val codecId: String,
        var codecPrivate: ByteArray?,
        val width: Int,
        val height: Int,
        val sampleRate: Int,
        val channels: Int,
        val codecDelayNs: Long,
        val seekPreRollNs: Long,
    ) {
        internal var lastTimeUs = Long.MIN_VALUE
        internal var lastStepUs = 0L
    }

    private class Block(val track: Track, val timeUs: Long, val keyFrame: Boolean, val data: ByteArray)

    private val tracks = ArrayList<Track>()
    private val pending = ArrayList<Block>()
    private var pendingBytes = 0L
    private val cues = ArrayList<Pair<Long, Long>>()

    private var headerWritten = false
    private var closed = false
    private var baseUs = 0L
    private var endUs = 0L
    private var segmentSizeAt = 0L
    private var segmentDataStart = 0L
    private var seekHeadAt = 0L
    private var durationAt = 0L
    private var infoPosition = 0L
    private var tracksPosition = 0L

    val trackList: List<Track> get() = tracks

    fun addVideoTrack(codecId: String, codecPrivate: ByteArray?, width: Int, height: Int): Track {
        check(!headerWritten) { "Tracks must be added before the first cluster is written" }
        return Track(tracks.size + 1, true, codecId, codecPrivate, width, height, 0, 0, 0, 0)
            .also { tracks += it }
    }

    fun addAudioTrack(
        codecId: String,
        codecPrivate: ByteArray?,
        sampleRate: Int,
        channels: Int,
        codecDelayNs: Long = 0,
        seekPreRollNs: Long = 0,
    ): Track {
        check(!headerWritten) { "Tracks must be added before the first cluster is written" }
        return Track(tracks.size + 1, false, codecId, codecPrivate, 0, 0, sampleRate, channels, codecDelayNs, seekPreRollNs)
            .also { tracks += it }
    }

    /**
     * Adds one sample. Samples of one track must come in decoding order; samples of different
     * tracks roughly interleaved by time, as Media3 delivers them.
     */
    fun writeSample(track: Track, timeUs: Long, keyFrame: Boolean, data: ByteArray) {
        check(!closed)
        if (pending.isNotEmpty() && startsNewCluster(track, timeUs, keyFrame)) flushCluster()
        pending += Block(track, timeUs, keyFrame, data)
        pendingBytes += data.size
        if (track.lastTimeUs != Long.MIN_VALUE && timeUs > track.lastTimeUs) {
            track.lastStepUs = timeUs - track.lastTimeUs
        }
        track.lastTimeUs = max(track.lastTimeUs, timeUs)
        endUs = max(endUs, timeUs + track.lastStepUs)
    }

    private val hasVideo: Boolean get() = tracks.any { it.isVideo }

    private fun startsNewCluster(track: Track, timeUs: Long, keyFrame: Boolean): Boolean {
        val firstUs = pending.first().timeUs
        // A key frame starts a new cluster, unless the cluster has no picture yet (sound that
        // arrived just before the first frame belongs with it).
        return (track.isVideo && keyFrame && pending.any { it.track.isVideo }) ||
            timeUs - firstUs > CLUSTER_MAX_US ||
            pendingBytes > CLUSTER_MAX_BYTES
    }

    private fun msOf(timeUs: Long): Long = max(0L, Math.floorDiv(timeUs - baseUs + 500, 1000L))

    private fun flushCluster() {
        if (pending.isEmpty()) return
        if (!headerWritten) {
            baseUs = pending.minOf { it.timeUs }
            writeHeader()
        }
        val clusterMs = pending.minOf { msOf(it.timeUs) }
        val body = ByteArrayOutputStream(pendingBytes.toInt() + pending.size * 8 + 16)
        body.write(uint(ID_TIMESTAMP, clusterMs))
        for (block in pending) {
            val relative = (msOf(block.timeUs) - clusterMs).coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong())
            val header = byteArrayOf(
                (0x80 or block.track.number).toByte(),
                (relative shr 8).toByte(),
                relative.toByte(),
                (if (block.keyFrame || !block.track.isVideo) 0x80 else 0x00).toByte(),
            )
            body.write(idBytes(ID_SIMPLE_BLOCK))
            body.write(sizeBytes((header.size + block.data.size).toLong()))
            body.write(header)
            body.write(block.data)
        }
        val position = file.filePointer - segmentDataStart
        // Players seek to the clusters listed here: those holding a key frame of the picture.
        val keyFrame = if (hasVideo) pending.firstOrNull { it.track.isVideo && it.keyFrame } else pending.first()
        if (keyFrame != null) cues += msOf(keyFrame.timeUs) to position
        file.write(idBytes(ID_CLUSTER))
        file.write(sizeBytes(body.size().toLong()))
        file.write(body.toByteArray())
        pending.clear()
        pendingBytes = 0
    }

    private fun writeHeader() {
        file.seek(0)
        file.write(
            master(
                ID_EBML,
                uint(ID_EBML_VERSION, 1),
                uint(ID_EBML_READ_VERSION, 1),
                uint(ID_EBML_MAX_ID_LENGTH, 4),
                uint(ID_EBML_MAX_SIZE_LENGTH, 8),
                string(ID_DOC_TYPE, docType),
                uint(ID_DOC_TYPE_VERSION, if (docType == "webm") 2 else 4),
                uint(ID_DOC_TYPE_READ_VERSION, 2),
            ),
        )
        file.write(idBytes(ID_SEGMENT))
        segmentSizeAt = file.filePointer
        file.write(UNKNOWN_SIZE)
        segmentDataStart = file.filePointer

        // Room for the seek index, which is only known at the end.
        seekHeadAt = file.filePointer
        file.write(void(SEEK_HEAD_SPACE))

        infoPosition = file.filePointer - segmentDataStart
        val info = master(
            ID_INFO,
            uint(ID_TIMESTAMP_SCALE, 1_000_000),
            string(ID_MUXING_APP, appName),
            string(ID_WRITING_APP, appName),
            float8(ID_DURATION, 0.0),
        )
        // The duration's 8 bytes are the last ones of the info element.
        durationAt = file.filePointer + info.size - 8
        file.write(info)

        tracksPosition = file.filePointer - segmentDataStart
        file.write(master(ID_TRACKS, *tracks.map(::trackEntry).toTypedArray()))
        headerWritten = true
    }

    private fun trackEntry(track: Track): ByteArray {
        val parts = ArrayList<ByteArray>()
        parts += uint(ID_TRACK_NUMBER, track.number.toLong())
        parts += uint(ID_TRACK_UID, Random.nextLong(1, Long.MAX_VALUE))
        parts += uint(ID_TRACK_TYPE, if (track.isVideo) 1 else 2)
        parts += uint(ID_FLAG_LACING, 0)
        parts += string(ID_LANGUAGE, "und")
        parts += string(ID_CODEC_ID, track.codecId)
        track.codecPrivate?.let { parts += element(ID_CODEC_PRIVATE, it) }
        if (track.codecDelayNs > 0) parts += uint(ID_CODEC_DELAY, track.codecDelayNs)
        if (track.seekPreRollNs > 0) parts += uint(ID_SEEK_PRE_ROLL, track.seekPreRollNs)
        if (track.isVideo) {
            parts += master(
                ID_VIDEO,
                uint(ID_PIXEL_WIDTH, track.width.toLong()),
                uint(ID_PIXEL_HEIGHT, track.height.toLong()),
            )
        } else {
            parts += master(
                ID_AUDIO,
                float8(ID_SAMPLING_FREQUENCY, track.sampleRate.toDouble()),
                uint(ID_CHANNELS, track.channels.toLong()),
            )
        }
        return master(ID_TRACK_ENTRY, *parts.toTypedArray())
    }

    override fun close() {
        if (closed) return
        try {
            flushCluster()
            if (!headerWritten) writeHeader()
            var cuesPosition = -1L
            if (cues.isNotEmpty()) {
                cuesPosition = file.filePointer - segmentDataStart
                val points = cues.map { (timeMs, position) ->
                    master(
                        ID_CUE_POINT,
                        uint(ID_CUE_TIME, timeMs),
                        master(
                            ID_CUE_TRACK_POSITIONS,
                            uint(ID_CUE_TRACK, (tracks.firstOrNull { it.isVideo } ?: tracks.first()).number.toLong()),
                            uint(ID_CUE_CLUSTER_POSITION, position),
                        ),
                    )
                }
                file.write(master(ID_CUES, *points.toTypedArray()))
            }
            val end = file.filePointer

            file.seek(segmentSizeAt)
            file.write(fixedSize(end - segmentDataStart))

            file.seek(durationAt)
            file.writeDouble(if (endUs > baseUs) (endUs - baseUs) / 1000.0 else 0.0)

            val seeks = ArrayList<ByteArray>()
            seeks += seek(ID_INFO, infoPosition)
            seeks += seek(ID_TRACKS, tracksPosition)
            if (cuesPosition >= 0) seeks += seek(ID_CUES, cuesPosition)
            val seekHead = master(ID_SEEK_HEAD, *seeks.toTypedArray())
            file.seek(seekHeadAt)
            file.write(seekHead)
            file.write(void(SEEK_HEAD_SPACE - seekHead.size))
            file.setLength(end)
        } finally {
            closed = true
            file.close()
        }
    }

    companion object {
        private const val CLUSTER_MAX_US = 5_000_000L
        private const val CLUSTER_MAX_BYTES = 8L shl 20
        private const val SEEK_HEAD_SPACE = 100

        // Element IDs from the Matroska specification.
        const val ID_EBML = 0x1A45DFA3L
        const val ID_EBML_VERSION = 0x4286L
        const val ID_EBML_READ_VERSION = 0x42F7L
        const val ID_EBML_MAX_ID_LENGTH = 0x42F2L
        const val ID_EBML_MAX_SIZE_LENGTH = 0x42F3L
        const val ID_DOC_TYPE = 0x4282L
        const val ID_DOC_TYPE_VERSION = 0x4287L
        const val ID_DOC_TYPE_READ_VERSION = 0x4285L
        const val ID_SEGMENT = 0x18538067L
        const val ID_SEEK_HEAD = 0x114D9B74L
        const val ID_SEEK = 0x4DBBL
        const val ID_SEEK_ID = 0x53ABL
        const val ID_SEEK_POSITION = 0x53ACL
        const val ID_INFO = 0x1549A966L
        const val ID_TIMESTAMP_SCALE = 0x2AD7B1L
        const val ID_MUXING_APP = 0x4D80L
        const val ID_WRITING_APP = 0x5741L
        const val ID_DURATION = 0x4489L
        const val ID_TRACKS = 0x1654AE6BL
        const val ID_TRACK_ENTRY = 0xAEL
        const val ID_TRACK_NUMBER = 0xD7L
        const val ID_TRACK_UID = 0x73C5L
        const val ID_TRACK_TYPE = 0x83L
        const val ID_FLAG_LACING = 0x9CL
        const val ID_LANGUAGE = 0x22B59CL
        const val ID_CODEC_ID = 0x86L
        const val ID_CODEC_PRIVATE = 0x63A2L
        const val ID_CODEC_DELAY = 0x56AAL
        const val ID_SEEK_PRE_ROLL = 0x56BBL
        const val ID_VIDEO = 0xE0L
        const val ID_PIXEL_WIDTH = 0xB0L
        const val ID_PIXEL_HEIGHT = 0xBAL
        const val ID_AUDIO = 0xE1L
        const val ID_SAMPLING_FREQUENCY = 0xB5L
        const val ID_CHANNELS = 0x9FL
        const val ID_CLUSTER = 0x1F43B675L
        const val ID_TIMESTAMP = 0xE7L
        const val ID_SIMPLE_BLOCK = 0xA3L
        const val ID_CUES = 0x1C53BB6BL
        const val ID_CUE_POINT = 0xBBL
        const val ID_CUE_TIME = 0xB3L
        const val ID_CUE_TRACK_POSITIONS = 0xB7L
        const val ID_CUE_TRACK = 0xF7L
        const val ID_CUE_CLUSTER_POSITION = 0xF1L
        const val ID_VOID = 0xECL

        /** "Size unknown" in 8 bytes; replaced by the real size when the file is closed. */
        private val UNKNOWN_SIZE = byteArrayOf(0x01, -1, -1, -1, -1, -1, -1, -1)

        internal fun idBytes(id: Long): ByteArray {
            val length = when {
                id <= 0xFF -> 1
                id <= 0xFFFF -> 2
                id <= 0xFFFFFF -> 3
                else -> 4
            }
            return ByteArray(length) { i -> (id shr (8 * (length - 1 - i))).toByte() }
        }

        /** An element size in the shortest form that holds it. */
        internal fun sizeBytes(size: Long): ByteArray {
            var length = 1
            // All bits set means "unknown", so the largest value of each length is skipped.
            while (length < 8 && size >= (1L shl (7 * length)) - 1) length++
            val marked = size or (1L shl (7 * length))
            return ByteArray(length) { i -> (marked shr (8 * (length - 1 - i))).toByte() }
        }

        /** An element size in 8 bytes, so it can be patched in place. */
        internal fun fixedSize(size: Long): ByteArray {
            val marked = size or (1L shl 56)
            return ByteArray(8) { i -> (marked shr (8 * (7 - i))).toByte() }
        }

        internal fun element(id: Long, payload: ByteArray): ByteArray =
            idBytes(id) + sizeBytes(payload.size.toLong()) + payload

        internal fun master(id: Long, vararg children: ByteArray): ByteArray {
            val body = ByteArrayOutputStream()
            children.forEach { body.write(it) }
            return element(id, body.toByteArray())
        }

        internal fun uint(id: Long, value: Long): ByteArray {
            var length = 1
            while (length < 8 && (value ushr (8 * length)) != 0L) length++
            return element(id, ByteArray(length) { i -> (value shr (8 * (length - 1 - i))).toByte() })
        }

        internal fun float8(id: Long, value: Double): ByteArray {
            val bits = java.lang.Double.doubleToLongBits(value)
            return element(id, ByteArray(8) { i -> (bits shr (8 * (7 - i))).toByte() })
        }

        internal fun string(id: Long, value: String): ByteArray = element(id, value.toByteArray(Charsets.UTF_8))

        private fun seek(id: Long, position: Long): ByteArray =
            master(ID_SEEK, element(ID_SEEK_ID, idBytes(id)), uint(ID_SEEK_POSITION, position))

        /** A filler element of exactly [totalBytes] bytes (2 to 128). */
        internal fun void(totalBytes: Int): ByteArray {
            require(totalBytes in 2..128)
            return idBytes(ID_VOID) + sizeBytes((totalBytes - 2).toLong()) + ByteArray(totalBytes - 2)
        }
    }
}
