package io.github.madtreasures.videoeditor.model

/** Shortest clip the editor will leave behind when trimming or splitting. */
const val MIN_CLIP_MS = 100L

/** A video file the user picked. Width and height are display dimensions (rotation applied). */
data class VideoSource(
    val uri: String,
    val name: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val frameRate: Float,
    val hasAudio: Boolean,
    /** Average bits per second of the whole file, or 0 if unknown. */
    val bitrate: Int = 0,
    /** Codec of the video track, as a MIME type such as "video/avc"; empty if unknown. */
    val videoMime: String = "",
    /** Codec of the audio track; empty if unknown or there is none. */
    val audioMime: String = "",
    /** Rotation the file asks players to apply; [width] and [height] already include it. */
    val rotationDegrees: Int = 0,
    /** Number of sound channels; 0 if unknown or there is no sound. */
    val audioChannels: Int = 0,
) {
    val aspectRatio: Float get() = if (height > 0) width.toFloat() / height else 16f / 9f
}

/** A section of a [VideoSource], from [startMs] (inclusive) to [endMs] (exclusive) in source time. */
data class Clip(
    val id: Long,
    val source: VideoSource,
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = endMs - startMs
}

data class ClipPosition(val index: Int, val offsetMs: Long)

enum class Edge { START, END }

/**
 * The whole edit: an ordered list of clips plus settings that apply to the full video.
 * Immutable, so undo/redo is a stack of snapshots.
 */
data class Project(
    val clips: List<Clip> = emptyList(),
    /** Clockwise rotation in degrees: 0, 90, 180 or 270. */
    val rotation: Int = 0,
    val muted: Boolean = false,
    val speed: Float = 1f,
    /** Audio files placed on the lane under the video; see AudioLane.kt. */
    val audio: List<AudioClip> = emptyList(),
) {
    val durationMs: Long = clips.sumOf { it.durationMs }

    val isEmpty: Boolean get() = clips.isEmpty()

    /** Length of the exported video, with the speed setting applied. */
    val outputDurationMs: Long get() = (durationMs / speed).toLong()

    /** True if the video's own sound is part of the result. */
    val hasAudio: Boolean get() = !muted && clips.any { it.source.hasAudio }

    /** Aspect ratio of the output frame: the first clip's, with the rotation applied. */
    val canvasAspectRatio: Float
        get() {
            val first = clips.firstOrNull()?.source?.aspectRatio ?: (16f / 9f)
            return if (rotation % 180 == 0) first else 1f / first
        }

    /** Project time at which the clip at [index] begins. */
    fun clipStartMs(index: Int): Long {
        var t = 0L
        for (i in 0 until index.coerceAtMost(clips.size)) t += clips[i].durationMs
        return t
    }

    /**
     * Maps a project time to a clip and an offset inside it. A time exactly on a cut belongs to
     * the clip that starts there; the very end of the project belongs to the last clip.
     */
    fun locate(timeMs: Long): ClipPosition {
        if (clips.isEmpty()) return ClipPosition(0, 0)
        var t = timeMs.coerceIn(0, durationMs)
        for (i in clips.indices) {
            val d = clips[i].durationMs
            if (t < d || i == clips.lastIndex) return ClipPosition(i, t.coerceAtMost(d))
            t -= d
        }
        return ClipPosition(clips.lastIndex, clips.last().durationMs)
    }

    /**
     * Project time at which the clip at [index] shows the moment [sourceMs] of its file. A
     * moment the clip does not contain is replaced by the nearest one it does, so the result
     * always lies inside that clip.
     */
    fun timeOfSource(index: Int, sourceMs: Long): Long {
        val clip = clips.getOrNull(index) ?: return 0
        val lastInside = (clip.endMs - 1).coerceAtLeast(clip.startMs)
        return clipStartMs(index) + (sourceMs.coerceIn(clip.startMs, lastInside) - clip.startMs)
    }

    fun canSplitAt(timeMs: Long): Boolean {
        if (clips.isEmpty()) return false
        val (index, offset) = locate(timeMs)
        return offset >= MIN_CLIP_MS && clips[index].durationMs - offset >= MIN_CLIP_MS
    }

    /** Cuts the clip under [timeMs] in two. Returns this project unchanged if too close to an edge. */
    fun splitAt(timeMs: Long, newId: () -> Long): Project {
        if (!canSplitAt(timeMs)) return this
        val (index, offset) = locate(timeMs)
        val clip = clips[index]
        val cut = clip.startMs + offset
        val parts = listOf(
            clip.copy(id = newId(), endMs = cut),
            clip.copy(id = newId(), startMs = cut),
        )
        return copy(clips = clips.subList(0, index) + parts + clips.subList(index + 1, clips.size))
    }

    fun removeClip(index: Int): Project {
        if (index !in clips.indices) return this
        return copy(clips = clips.filterIndexed { i, _ -> i != index })
    }

    /** Moves one edge of a clip to [valueMs] in source time, clamped to what the source allows. */
    fun trim(index: Int, edge: Edge, valueMs: Long): Project {
        val clip = clips.getOrNull(index) ?: return this
        val trimmed = when (edge) {
            Edge.START -> clip.copy(
                startMs = valueMs.coerceIn(0, (clip.endMs - MIN_CLIP_MS).coerceAtLeast(0)),
            )
            Edge.END -> clip.copy(
                endMs = valueMs.coerceIn(
                    (clip.startMs + MIN_CLIP_MS).coerceAtMost(clip.source.durationMs),
                    clip.source.durationMs,
                ),
            )
        }
        if (trimmed == clip) return this
        return copy(clips = clips.toMutableList().also { it[index] = trimmed })
    }

    /** Swaps the clip at [index] with its neighbour ([delta] = -1 or +1). */
    fun moveClip(index: Int, delta: Int): Project {
        val target = index + delta
        if (index !in clips.indices || target !in clips.indices) return this
        val list = clips.toMutableList()
        val moved = list.removeAt(index)
        list.add(target, moved)
        return copy(clips = list)
    }

    fun append(sources: List<VideoSource>, newId: () -> Long): Project {
        if (sources.isEmpty()) return this
        return copy(clips = clips + sources.map { Clip(newId(), it, 0, it.durationMs) })
    }

    fun rotatedClockwise(): Project = copy(rotation = (rotation + 90) % 360)
}
