package io.github.madtreasures.videoeditor.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Resolution(val shortSide: Int?) {
    ORIGINAL(null),
    P1080(1080),
    P720(720),
    P480(480),
}

/**
 * @param bitsPerPixel bits per pixel per frame for H.264; the basis of the target bitrate.
 * @param ofSource upper limit as a share of the source file's own bitrate. Spending more bits
 *   than the source has only makes the file bigger, not better.
 */
enum class Quality(val bitsPerPixel: Float, val ofSource: Float) {
    HIGH(0.14f, 1.15f),
    MEDIUM(0.07f, 0.6f),
    LOW(0.04f, 0.35f),
}

/**
 * @param bitrateFactor bitrate needed for the same quality, compared with H.264.
 */
enum class VideoCodec(val mime: String, val label: String, val bitrateFactor: Float) {
    H264("video/avc", "H.264", 1f),
    H265("video/hevc", "H.265", 0.65f),
    AV1("video/av01", "AV1", 0.55f),
    VP9("video/x-vnd.on2.vp9", "VP9", 0.7f),
    VP8("video/x-vnd.on2.vp8", "VP8", 1.1f),
}

enum class AudioCodec(val mime: String, val label: String) {
    AAC("audio/mp4a-latm", "AAC"),
    OPUS("audio/opus", "Opus"),
}

/**
 * A file type the editor can write.
 *
 * @param videoCodecs the codecs offered for the picture, best supported first; empty for files
 *   that only hold sound.
 * @param matroska true for Matroska based files (MKV, WebM). They store no rotation, so the
 *   picture is always encoded the right way up.
 */
enum class Container(
    val extension: String,
    val mimeType: String,
    val label: String,
    val videoCodecs: List<VideoCodec>,
    val audioCodec: AudioCodec,
    val matroska: Boolean = false,
) {
    MP4("mp4", "video/mp4", "MP4", listOf(VideoCodec.H264, VideoCodec.H265, VideoCodec.AV1), AudioCodec.AAC),
    MOV("mov", "video/quicktime", "MOV", listOf(VideoCodec.H264, VideoCodec.H265), AudioCodec.AAC),
    MKV("mkv", "video/x-matroska", "MKV", listOf(VideoCodec.H264, VideoCodec.VP9), AudioCodec.AAC, matroska = true),
    WEBM("webm", "video/webm", "WebM", listOf(VideoCodec.VP9, VideoCodec.VP8), AudioCodec.OPUS, matroska = true),
    THREE_GP("3gp", "video/3gpp", "3GP", listOf(VideoCodec.H264), AudioCodec.AAC),
    M4A("m4a", "audio/mp4", "M4A", emptyList(), AudioCodec.AAC),
    OGG("ogg", "audio/ogg", "OGG", emptyList(), AudioCodec.OPUS),
    ;

    val audioOnly: Boolean get() = videoCodecs.isEmpty()
}

data class ExportSettings(
    val container: Container = Container.MP4,
    /** Ignored for containers that only hold sound. */
    val videoCodec: VideoCodec = VideoCodec.H264,
    val resolution: Resolution = Resolution.ORIGINAL,
    val quality: Quality = Quality.HIGH,
)

/** What the exporter will actually do for a project and a set of settings. */
data class ExportPlan(
    val container: Container,
    /** Null if the file gets no picture. */
    val videoCodec: VideoCodec?,
    /** Output frame size, rotation and scaling applied; 0 without picture. */
    val width: Int,
    val height: Int,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    /**
     * True if the main stream (the picture, or the sound for audio files) is copied into the new
     * file as it is: no quality loss and very fast. Only possible for one clip that starts at
     * the beginning of its file and needs no change to its frames.
     */
    val passthrough: Boolean,
    /** True if the sound is copied as it is too. */
    val audioCopied: Boolean,
    /** True if there is sound to export but this device cannot encode it for this file type. */
    val audioDropped: Boolean,
    /** True if every frame has to be fitted into the output frame size. */
    val needsScaling: Boolean,
    /** True if a portrait picture must be stored as portrait (the file type has no rotation flag). */
    val portraitEncoding: Boolean,
    val videoBitrate: Int,
    val audioBitrate: Int,
    val frameRate: Float,
    /** Rough size of the result in bytes; meaningless when [passthrough] is true. */
    val estimatedBytes: Long,
) {
    /** False if the chosen file type would end up empty (sound only, but there is no sound). */
    val canExport: Boolean get() = hasVideo || hasAudio
}

/** Every codec; what a device can encode is narrowed down at run time. */
val ALL_CODECS: Set<String> = VideoCodec.entries.map { it.mime }.toSet() + AudioCodec.entries.map { it.mime }

private fun even(value: Float): Int = max(2, (value / 2f).roundToInt() * 2)

private fun audioBitrate(container: Container, quality: Quality): Int = when {
    container.audioOnly && container.audioCodec == AudioCodec.AAC ->
        when (quality) { Quality.HIGH -> 192_000; Quality.MEDIUM -> 128_000; Quality.LOW -> 96_000 }
    container.audioOnly ->
        when (quality) { Quality.HIGH -> 160_000; Quality.MEDIUM -> 96_000; Quality.LOW -> 64_000 }
    container.audioCodec == AudioCodec.OPUS -> 96_000
    container == Container.THREE_GP -> 96_000
    else -> 128_000
}

/**
 * @param encodable MIME types of the codecs this device can encode.
 */
fun planExport(
    project: Project,
    settings: ExportSettings,
    encodable: Set<String> = ALL_CODECS,
): ExportPlan {
    val container = settings.container
    val first = project.clips.first().source
    val single = project.clips.size == 1 && project.clips[0].startMs == 0L &&
        project.speed == 1f && project.audio.isEmpty()

    // Sound: the video's own, the added audio, or both mixed.
    val anySound = project.hasAudio || project.hasLaneAudio
    val audioCopyable = single && project.hasAudio && first.audioMime == container.audioCodec.mime
    val hasAudio = anySound && (container.audioCodec.mime in encodable || audioCopyable)
    val audioBitrate = if (hasAudio) audioBitrate(container, settings.quality) else 0

    if (container.audioOnly) {
        val passthrough = audioCopyable
        return ExportPlan(
            container = container,
            videoCodec = null,
            width = 0,
            height = 0,
            hasVideo = false,
            hasAudio = hasAudio,
            passthrough = passthrough,
            audioCopied = passthrough,
            audioDropped = anySound && !hasAudio,
            needsScaling = false,
            portraitEncoding = false,
            videoBitrate = 0,
            audioBitrate = audioBitrate,
            frameRate = 0f,
            estimatedBytes = audioBitrate.toLong() * project.outputDurationMs / 8000L,
        )
    }

    val codec = settings.videoCodec.takeIf { it in container.videoCodecs } ?: container.videoCodecs.first()
    val rotated = project.rotation % 180 != 0
    val canvasW = if (rotated) first.height else first.width
    val canvasH = if (rotated) first.width else first.height

    val shortSide = min(canvasW, canvasH)
    val target = settings.resolution.shortSide
    val downscale = target != null && shortSide > target
    val scale = if (downscale) target.toFloat() / shortSide else 1f
    val width = even(canvasW * scale)
    val height = even(canvasH * scale)

    val mixedSizes = project.clips.any {
        it.source.width != first.width || it.source.height != first.height
    }

    val frameRate = project.clips.maxOf { it.source.frameRate }.let {
        if (it.isNaN() || it <= 0f) 30f else it.coerceIn(1f, 60f)
    }
    val byPixels = (width.toLong() * height * frameRate * settings.quality.bitsPerPixel * codec.bitrateFactor)
        .toLong()
    val sourceBitrate = project.clips.maxOf { it.source.bitrate }
    val bySource = if (sourceBitrate > 0) (sourceBitrate * settings.quality.ofSource).toLong() else Long.MAX_VALUE
    val videoBitrate = minOf(byPixels, bySource).coerceIn(200_000L, 80_000_000L).toInt()

    // A Matroska file has no rotation flag, so a turned picture has to be re-encoded upright.
    val needsUpright = container.matroska && (project.rotation != 0 || first.rotationDegrees != 0)
    val passthrough = single &&
        !downscale &&
        settings.quality == Quality.HIGH &&
        first.videoMime == codec.mime &&
        !needsUpright

    val estimatedBytes = (videoBitrate.toLong() + audioBitrate) * project.outputDurationMs / 8000L

    return ExportPlan(
        container = container,
        videoCodec = codec,
        width = width,
        height = height,
        hasVideo = true,
        hasAudio = hasAudio,
        passthrough = passthrough,
        audioCopied = passthrough && audioCopyable,
        audioDropped = anySound && !hasAudio,
        needsScaling = downscale || mixedSizes,
        portraitEncoding = container.matroska,
        videoBitrate = videoBitrate,
        audioBitrate = audioBitrate,
        frameRate = frameRate,
        estimatedBytes = estimatedBytes,
    )
}
