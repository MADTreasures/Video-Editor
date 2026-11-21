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

data class ExportSettings(
    val resolution: Resolution = Resolution.ORIGINAL,
    val quality: Quality = Quality.HIGH,
    val hevc: Boolean = false,
)

/** What the exporter will actually do for a project and a set of settings. */
data class ExportPlan(
    /** Output frame size, rotation and scaling applied. */
    val width: Int,
    val height: Int,
    /**
     * True if the video can be copied into the new file without re-encoding (no quality loss,
     * very fast). Only possible for one clip that starts at the beginning of its file.
     */
    val passthrough: Boolean,
    /** True if every frame has to be fitted into the output frame size. */
    val needsScaling: Boolean,
    val videoBitrate: Int,
    val frameRate: Float,
    /** Rough size of the result in bytes; meaningless when [passthrough] is true. */
    val estimatedBytes: Long,
)

private const val AUDIO_BITRATE = 128_000
private const val HEVC_BITRATE_FACTOR = 0.65f

private fun even(value: Float): Int = max(2, (value / 2f).roundToInt() * 2)

fun planExport(project: Project, settings: ExportSettings): ExportPlan {
    val first = project.clips.first().source
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
    val codecFactor = if (settings.hevc) HEVC_BITRATE_FACTOR else 1f
    val byPixels = (width.toLong() * height * frameRate * settings.quality.bitsPerPixel * codecFactor)
        .toLong()
    val sourceBitrate = project.clips.maxOf { it.source.bitrate }
    val bySource = if (sourceBitrate > 0) (sourceBitrate * settings.quality.ofSource).toLong() else Long.MAX_VALUE
    val videoBitrate = minOf(byPixels, bySource).coerceIn(200_000L, 80_000_000L).toInt()

    val passthrough = project.clips.size == 1 &&
        project.clips[0].startMs == 0L &&
        project.speed == 1f &&
        !downscale &&
        settings.quality == Quality.HIGH &&
        !settings.hevc

    val audioBitrate = if (project.hasAudio) AUDIO_BITRATE else 0
    val estimatedBytes = (videoBitrate.toLong() + audioBitrate) * project.outputDurationMs / 8000L

    return ExportPlan(
        width = width,
        height = height,
        passthrough = passthrough,
        needsScaling = downscale || mixedSizes,
        videoBitrate = videoBitrate,
        frameRate = frameRate,
        estimatedBytes = estimatedBytes,
    )
}
