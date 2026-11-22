package io.github.madtreasures.videoeditor.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

class MatroskaWriterTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** A minimal EBML reader: (id, payload start, payload size) for each element in a range. */
    private class Element(val id: Long, val start: Int, val size: Int)

    private fun readVint(data: ByteArray, at: Int, keepMarker: Boolean): Pair<Long, Int> {
        val first = data[at].toInt() and 0xFF
        var length = 1
        while (length <= 8 && first and (0x80 ushr (length - 1)) == 0) length++
        var value = (if (keepMarker) first else first and (0xFF ushr length)).toLong()
        for (i in 1 until length) value = (value shl 8) or (data[at + i].toLong() and 0xFF)
        return value to length
    }

    private fun children(data: ByteArray, start: Int, end: Int): List<Element> {
        val result = ArrayList<Element>()
        var at = start
        while (at < end) {
            val (id, idLength) = readVint(data, at, keepMarker = true)
            val (size, sizeLength) = readVint(data, at + idLength, keepMarker = false)
            val payload = at + idLength + sizeLength
            result += Element(id, payload, size.toInt())
            at = payload + size.toInt()
        }
        assertEquals("elements must end exactly at their parent's end", end, at)
        return result
    }

    private fun uintOf(data: ByteArray, e: Element): Long =
        (0 until e.size).fold(0L) { acc, i -> (acc shl 8) or (data[e.start + i].toLong() and 0xFF) }

    @Test
    fun writesAFileWithConsistentSizes_seekIndex_clustersAndCues() {
        val file = folder.newFile("test.mkv")
        val writer = MatroskaWriter(RandomAccessFile(file, "rw"))
        val video = writer.addVideoTrack("V_VP9", null, 640, 360)
        val audio = writer.addAudioTrack("A_AAC", byteArrayOf(0x12, 0x10), 44_100, 2)
        // 12 s: 30 fps video with a key frame every 2 s, interleaved with ~23 ms audio frames.
        var audioUs = 0L
        for (frame in 0 until 360) {
            val timeUs = 1_000_000L + frame * 33_333L // starts at 1 s; the file must start at 0
            while (audioUs <= timeUs - 1_000_000L) {
                writer.writeSample(audio, 1_000_000L + audioUs, true, ByteArray(200))
                audioUs += 23_220
            }
            writer.writeSample(video, timeUs, frame % 60 == 0, ByteArray(1000) { frame.toByte() })
        }
        writer.close()

        val data = file.readBytes()
        val top = children(data, 0, data.size)
        assertEquals(MatroskaWriter.ID_EBML, top[0].id)
        assertEquals(MatroskaWriter.ID_SEGMENT, top[1].id)
        val docType = children(data, top[0].start, top[0].start + top[0].size).first { it.id == MatroskaWriter.ID_DOC_TYPE }
        assertEquals("matroska", String(data, docType.start, docType.size))

        val segment = top[1]
        val inSegment = children(data, segment.start, segment.start + segment.size)
        val clusters = inSegment.filter { it.id == MatroskaWriter.ID_CLUSTER }
        assertEquals(6, clusters.size)

        // Every seek entry points at the element it names.
        val seekHead = inSegment.first { it.id == MatroskaWriter.ID_SEEK_HEAD }
        val seeks = children(data, seekHead.start, seekHead.start + seekHead.size)
        assertEquals(3, seeks.size)
        for (seek in seeks) {
            val (idElement, positionElement) = children(data, seek.start, seek.start + seek.size)
            val target = segment.start + uintOf(data, positionElement).toInt()
            val (idAtTarget, _) = readVint(data, target, keepMarker = true)
            assertEquals(uintOf(data, idElement), idAtTarget)
        }

        // Every cue points at a cluster, at the time of its key frame.
        val cues = inSegment.first { it.id == MatroskaWriter.ID_CUES }
        val points = children(data, cues.start, cues.start + cues.size)
        assertEquals(6, points.size)
        points.forEachIndexed { i, point ->
            val (time, positions) = children(data, point.start, point.start + point.size)
            assertEquals(i * 2000L, uintOf(data, time))
            val position = children(data, positions.start, positions.start + positions.size)
                .first { it.id == MatroskaWriter.ID_CUE_CLUSTER_POSITION }
            val (idAtTarget, _) = readVint(data, segment.start + uintOf(data, position).toInt(), keepMarker = true)
            assertEquals(MatroskaWriter.ID_CLUSTER, idAtTarget)
        }

        // The first cluster starts at 0 and holds the first key frame, right after the sound
        // that arrived just before it.
        val first = children(data, clusters[0].start, clusters[0].start + clusters[0].size)
        assertEquals(0L, uintOf(data, first[0]))
        val blocks = first.filter { it.id == MatroskaWriter.ID_SIMPLE_BLOCK }
        assertEquals(0x80 or audio.number, data[blocks[0].start].toInt() and 0xFF)
        val block = blocks[1]
        assertEquals(0x80 or video.number, data[block.start].toInt() and 0xFF)
        assertEquals(0x80, data[block.start + 3].toInt() and 0x80)

        // About 12 s long.
        val info = inSegment.first { it.id == MatroskaWriter.ID_INFO }
        val duration = children(data, info.start, info.start + info.size).first { it.id == MatroskaWriter.ID_DURATION }
        val ms = java.nio.ByteBuffer.wrap(data, duration.start, 8).double
        assertEquals(12_000.0, ms, 50.0)
    }

    @Test
    fun h264_startCodesBecomeLengthPrefixes_andParameterSetsAConfig() {
        val sps = byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x11, 0x22)
        val pps = byteArrayOf(0x68, 0x33)
        val idr = byteArrayOf(0x65, 0x01, 0x02, 0x03)
        val annexB = byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 1) + pps + byteArrayOf(0, 0, 0, 1) + idr

        val units = CodecData.nalUnits(annexB)
        assertEquals(3, units.size)
        assertArrayEquals(sps, units[0])
        assertArrayEquals(pps, units[1])
        assertArrayEquals(idr, units[2])

        val prefixed = CodecData.toLengthPrefixed(annexB)
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 6) + sps + byteArrayOf(0, 0, 0, 2) + pps + byteArrayOf(0, 0, 0, 4) + idr,
            prefixed,
        )
        // Already length-prefixed data is left alone.
        assertArrayEquals(prefixed, CodecData.toLengthPrefixed(prefixed))

        val config = CodecData.avcConfig(units)!!
        assertArrayEquals(
            byteArrayOf(1, 0x64, 0x00, 0x1F, 0xFF.toByte(), 0xE1.toByte(), 0, 6) + sps + byteArrayOf(1, 0, 2) + pps,
            config,
        )
        assertEquals(null, CodecData.avcConfig(listOf(idr)))
    }

    @Test
    fun audioConfigs() {
        // AAC-LC, 44.1 kHz, stereo: the well-known 0x12 0x10.
        assertArrayEquals(byteArrayOf(0x12, 0x10), CodecData.aacConfig(44_100, 2))
        val head = CodecData.opusHead(channels = 2, preSkip = 312, inputSampleRate = 48_000)
        assertTrue(CodecData.isOpusHead(head))
        assertEquals(19, head.size)
        assertEquals(312, CodecData.opusPreSkip(head))
    }

    @Test
    fun vintSizes() {
        assertArrayEquals(byteArrayOf(0x81.toByte()), MatroskaWriter.sizeBytes(1))
        // 127 would read as "unknown" in one byte, so it takes two.
        assertArrayEquals(byteArrayOf(0x40, 0x7F), MatroskaWriter.sizeBytes(127))
        assertArrayEquals(byteArrayOf(0x01, 0, 0, 0, 0, 0, 0x30, 0x39), MatroskaWriter.fixedSize(12345))
    }

    @Test
    fun webm_isMarkedAsWebm_andOpusGetsItsDelayAndPreRoll() {
        val file = folder.newFile("test.webm")
        val writer = MatroskaWriter(RandomAccessFile(file, "rw"), docType = "webm")
        val head = CodecData.opusHead(channels = 2, preSkip = 312, inputSampleRate = 48_000)
        val audio = writer.addAudioTrack("A_OPUS", head, 48_000, 2, codecDelayNs = 6_500_000, seekPreRollNs = 80_000_000)
        for (i in 0 until 50) writer.writeSample(audio, i * 20_000L, true, ByteArray(100))
        writer.close()

        val data = file.readBytes()
        val top = children(data, 0, data.size)
        val header = children(data, top[0].start, top[0].start + top[0].size)
        val docType = header.first { it.id == MatroskaWriter.ID_DOC_TYPE }
        assertEquals("webm", String(data, docType.start, docType.size))
        assertEquals(2L, uintOf(data, header.first { it.id == MatroskaWriter.ID_DOC_TYPE_VERSION }))

        val segment = children(data, top[1].start, top[1].start + top[1].size)
        val tracks = segment.first { it.id == MatroskaWriter.ID_TRACKS }
        val entry = children(data, tracks.start, tracks.start + tracks.size).single()
        val fields = children(data, entry.start, entry.start + entry.size)
        assertEquals(6_500_000L, uintOf(data, fields.first { it.id == MatroskaWriter.ID_CODEC_DELAY }))
        assertEquals(80_000_000L, uintOf(data, fields.first { it.id == MatroskaWriter.ID_SEEK_PRE_ROLL }))
        val private = fields.first { it.id == MatroskaWriter.ID_CODEC_PRIVATE }
        assertArrayEquals(head, data.copyOfRange(private.start, private.start + private.size))
        // Without a picture, every cluster is listed for seeking; 1 s of sound fits in one.
        val cues = segment.first { it.id == MatroskaWriter.ID_CUES }
        assertEquals(1, children(data, cues.start, cues.start + cues.size).size)
    }
}
