package io.github.madtreasures.videoeditor.export

import java.io.ByteArrayOutputStream

/**
 * Small conversions between the forms in which codecs hand out their data and the forms
 * Matroska stores. Plain Kotlin, so it can be tested without a device.
 */
object CodecData {

    /** Splits an H.264 stream with start codes (00 00 01 or 00 00 00 01) into NAL units. */
    fun nalUnits(data: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>() // index of the first byte after each start code
        var i = 0
        while (i + 2 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                starts += i + 3
                i += 3
            } else {
                i++
            }
        }
        if (starts.isEmpty()) return if (data.isEmpty()) emptyList() else listOf(data)
        return starts.mapIndexed { n, start ->
            var end = if (n + 1 < starts.size) starts[n + 1] - 3 else data.size
            // A four-byte start code leaves a zero byte at the end of the previous unit.
            while (n + 1 < starts.size && end > start && data[end - 1].toInt() == 0) end--
            data.copyOfRange(start, end)
        }.filter { it.isNotEmpty() }
    }

    fun hasStartCode(data: ByteArray): Boolean =
        (data.size > 3 && data[0].toInt() == 0 && data[1].toInt() == 0 &&
            (data[2].toInt() == 1 || (data[2].toInt() == 0 && data[3].toInt() == 1)))

    /**
     * Turns a sample with start codes into the form Matroska and MP4 store: each NAL unit
     * preceded by its length in four bytes. A sample that is already in that form is kept.
     */
    fun toLengthPrefixed(data: ByteArray): ByteArray {
        if (!hasStartCode(data)) return data
        val out = ByteArrayOutputStream(data.size + 16)
        for (nal in nalUnits(data)) {
            out.write(nal.size ushr 24)
            out.write(nal.size ushr 16)
            out.write(nal.size ushr 8)
            out.write(nal.size)
            out.write(nal)
        }
        return out.toByteArray()
    }

    /**
     * Builds the H.264 decoder configuration (an "avcC" record) from the parameter sets found in
     * [nalUnits], or returns null if there is no sequence and picture parameter set among them.
     */
    fun avcConfig(nalUnits: List<ByteArray>): ByteArray? {
        val sps = nalUnits.filter { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 7 }
        val pps = nalUnits.filter { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 8 }
        if (sps.isEmpty() || pps.isEmpty() || sps[0].size < 4) return null
        val out = ByteArrayOutputStream()
        out.write(1) // configuration version
        out.write(sps[0][1].toInt()) // profile
        out.write(sps[0][2].toInt()) // profile compatibility
        out.write(sps[0][3].toInt()) // level
        out.write(0xFF) // 4-byte NAL unit lengths
        out.write(0xE0 or sps.size)
        for (unit in sps) {
            out.write(unit.size ushr 8)
            out.write(unit.size)
            out.write(unit)
        }
        out.write(pps.size)
        for (unit in pps) {
            out.write(unit.size ushr 8)
            out.write(unit.size)
            out.write(unit)
        }
        return out.toByteArray()
    }

    private val AAC_SAMPLE_RATES = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350,
    )

    /** The two-byte AAC-LC decoder configuration, for when an encoder did not supply one. */
    fun aacConfig(sampleRate: Int, channels: Int): ByteArray {
        val index = AAC_SAMPLE_RATES.indexOf(sampleRate).takeIf { it >= 0 } ?: 4
        val value = (2 shl 11) or (index shl 7) or (channels.coerceIn(1, 7) shl 3)
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    private val OPUS_HEAD = "OpusHead".toByteArray(Charsets.US_ASCII)

    fun isOpusHead(data: ByteArray): Boolean =
        data.size >= 19 && data.copyOfRange(0, 8).contentEquals(OPUS_HEAD)

    /** The Opus identification header, for when an encoder did not supply one. */
    fun opusHead(channels: Int, preSkip: Int, inputSampleRate: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(OPUS_HEAD)
        out.write(1)
        out.write(channels)
        out.write(preSkip and 0xFF)
        out.write(preSkip ushr 8)
        for (shift in intArrayOf(0, 8, 16, 24)) out.write(inputSampleRate ushr shift)
        out.write(0) // output gain
        out.write(0)
        out.write(0) // channel mapping family
        return out.toByteArray()
    }

    /** Samples Opus decoders drop at the start, from an identification header. */
    fun opusPreSkip(head: ByteArray): Int =
        if (isOpusHead(head)) (head[10].toInt() and 0xFF) or ((head[11].toInt() and 0xFF) shl 8) else 0
}
