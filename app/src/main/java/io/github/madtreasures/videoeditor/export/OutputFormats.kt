package io.github.madtreasures.videoeditor.export

import android.media.MediaCodecList
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.Muxer
import androidx.media3.muxer.MuxerException
import androidx.media3.muxer.OggMuxer
import androidx.media3.muxer.SeekableMuxerOutput
import androidx.media3.muxer.WebmMuxer
import com.google.common.collect.ImmutableList
import io.github.madtreasures.videoeditor.model.ALL_CODECS
import io.github.madtreasures.videoeditor.model.Container
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** The codecs this device can encode, as MIME types. */
val encodableCodecs: Set<String> by lazy {
    runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder }
            .flatMap { it.supportedTypes.map(String::lowercase) }
            .toSet()
            .intersect(ALL_CODECS)
    }.getOrDefault(setOf(MimeTypes.VIDEO_H264, MimeTypes.AUDIO_AAC))
}

/**
 * The muxer that writes [container], or null for the MP4 family (MP4, MOV, 3GP, M4A), which
 * Media3 writes by default; [patchFileType] then gives the file its proper type label.
 */
@OptIn(UnstableApi::class)
fun muxerFactoryFor(container: Container): Muxer.Factory? = when (container) {
    Container.MKV -> MatroskaMuxer.Factory()
    Container.WEBM -> WebmFactory
    Container.OGG -> OggFactory
    else -> null
}

@OptIn(UnstableApi::class)
private object WebmFactory : Muxer.Factory {
    override fun create(path: String): Muxer = try {
        WebmMuxer.Builder(SeekableMuxerOutput.of(path)).build()
    } catch (e: FileNotFoundException) {
        throw MuxerException("Cannot open $path", e)
    }

    override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> = when (trackType) {
        C.TRACK_TYPE_VIDEO -> ImmutableList.of(MimeTypes.VIDEO_VP9, MimeTypes.VIDEO_VP8)
        C.TRACK_TYPE_AUDIO -> ImmutableList.of(MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_VORBIS)
        else -> ImmutableList.of()
    }
}

@OptIn(UnstableApi::class)
private object OggFactory : Muxer.Factory {
    override fun create(path: String): Muxer = try {
        OggMuxer.Builder(FileOutputStream(path).channel).build()
    } catch (e: FileNotFoundException) {
        throw MuxerException("Cannot open $path", e)
    }

    override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> = when (trackType) {
        C.TRACK_TYPE_AUDIO -> ImmutableList.of(MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_VORBIS)
        else -> ImmutableList.of()
    }
}

/**
 * MP4, MOV, 3GP and M4A share one structure and differ in the brands of the leading "ftyp" box,
 * which tell players what kind of file it is. Media3 writes MP4 brands; this rewrites them in
 * place, keeping the box size, so nothing else in the file moves.
 */
fun patchFileType(file: java.io.File, container: Container) {
    val brands = when (container) {
        Container.MOV -> listOf("qt  ")
        Container.THREE_GP -> listOf("3gp6", "isom", "iso2")
        Container.M4A -> listOf("M4A ", "isom", "mp42")
        else -> return
    }
    RandomAccessFile(file, "rw").use { raf ->
        val size = raf.readInt()
        val type = ByteArray(4).also { raf.readFully(it) }
        if (String(type, Charsets.US_ASCII) != "ftyp" || size < 16 || size > 64) return
        raf.seek(8)
        raf.write(brands[0].toByteArray(Charsets.US_ASCII))
        raf.writeInt(if (container == Container.MOV) 0x200 else 0)
        val compatibleCount = (size - 16) / 4
        for (i in 0 until compatibleCount) {
            raf.write(brands[i % brands.size].toByteArray(Charsets.US_ASCII))
        }
    }
}
