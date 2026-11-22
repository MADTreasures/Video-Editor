package io.github.madtreasures.videoeditor.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Shortest piece of audio the editor leaves behind when trimming or splitting, in file time. */
const val MIN_AUDIO_MS = 200L

/** An audio file the user added. */
data class AudioSource(
    val uri: String,
    val name: String,
    val durationMs: Long,
)

/**
 * A section of an audio file on the lane under the video.
 *
 * [startMs] is where it begins on the project's time axis, [srcStartMs] to [srcEndMs] is the
 * part of the file it plays. Added audio is never sped up: at project speed 2x one second of
 * music covers two seconds of the (unsped) time axis the timeline shows.
 */
data class AudioClip(
    val id: Long,
    val source: AudioSource,
    val startMs: Long,
    val srcStartMs: Long,
    val srcEndMs: Long,
    /** 0 is silent, 1 is as loud as the file. */
    val volume: Float = 1f,
) {
    val sourceLengthMs: Long get() = srcEndMs - srcStartMs

    fun lengthMs(speed: Float): Long = (sourceLengthMs * speed).roundToLong()

    fun endMs(speed: Float): Long = startMs + lengthMs(speed)
}

/**
 * Where an audio clip is actually heard, on the project's time axis: it is cut short where the
 * next clip begins or the video ends.
 */
data class AudioPlacement(val clip: AudioClip, val startMs: Long, val endMs: Long) {
    val lengthMs: Long get() = endMs - startMs

    /** The moment of the file heard at project time [timeMs]. */
    fun sourceAt(timeMs: Long, speed: Float): Long =
        clip.srcStartMs + ((timeMs - startMs) / speed).roundToLong()

    /** The end of the part of the file that is heard. */
    fun sourceEnd(speed: Float): Long = sourceAt(endMs, speed).coerceAtMost(clip.srcEndMs)
}

/** The audio clips as they are heard, in order; clips that begin after the video ends are left out. */
fun Project.audioPlacements(): List<AudioPlacement> {
    val sorted = audio.sortedBy { it.startMs }
    val result = ArrayList<AudioPlacement>(sorted.size)
    for ((i, clip) in sorted.withIndex()) {
        val nextStart = sorted.getOrNull(i + 1)?.startMs ?: Long.MAX_VALUE
        val end = minOf(clip.endMs(speed), nextStart, durationMs)
        if (end > clip.startMs) result += AudioPlacement(clip, clip.startMs, end)
    }
    return result
}

/** The audio heard at project time [timeMs], if any. */
fun Project.audioAt(timeMs: Long): AudioPlacement? =
    audioPlacements().firstOrNull { timeMs >= it.startMs && timeMs < it.endMs }

val Project.hasLaneAudio: Boolean get() = audioPlacements().isNotEmpty()

/**
 * Puts [source] on the audio lane at [atMs], or at the end of the clip already playing there.
 * It is cut to fit before the next clip and the end of the video. If there is no room there,
 * the start of the video is tried. Returns this project unchanged if there is no room at all.
 */
fun Project.addAudio(source: AudioSource, atMs: Long, newId: () -> Long): Project {
    if (isEmpty || source.durationMs < MIN_AUDIO_MS) return this
    val minLength = (MIN_AUDIO_MS * speed).roundToLong()
    for (wanted in listOf(atMs, 0L)) {
        var start = wanted.coerceIn(0, durationMs)
        audioAt(start)?.let { start = it.endMs }
        val nextStart = audio.filter { it.startMs > start }.minOfOrNull { it.startMs } ?: Long.MAX_VALUE
        val room = min(nextStart, durationMs) - start
        if (room < minLength) continue
        val lengthInFile = min(source.durationMs, (room / speed).toLong())
        val clip = AudioClip(newId(), source, start, 0, lengthInFile)
        return copy(audio = (audio + clip).sortedBy { it.startMs })
    }
    return this
}

private fun Project.neighbours(id: Long): Pair<Long, Long> {
    val clip = audio.first { it.id == id }
    val before = audio.filter { it.id != id && it.startMs <= clip.startMs }
    val after = audio.filter { it.id != id && it.startMs > clip.startMs }
    val previousEnd = before.maxOfOrNull { min(it.endMs(speed), clip.startMs) } ?: 0L
    val nextStart = after.minOfOrNull { it.startMs } ?: Long.MAX_VALUE
    return previousEnd to nextStart
}

private fun Project.replaceAudio(updated: AudioClip): Project {
    val old = audio.firstOrNull { it.id == updated.id } ?: return this
    if (old == updated) return this
    return copy(audio = audio.map { if (it.id == updated.id) updated else it }.sortedBy { it.startMs })
}

/** Moves an audio clip so that it begins at [startMs], as far as its neighbours allow. */
fun Project.moveAudio(id: Long, startMs: Long): Project {
    val clip = audio.firstOrNull { it.id == id } ?: return this
    val (previousEnd, nextStart) = neighbours(id)
    val length = clip.lengthMs(speed)
    val latest = min(nextStart - length, durationMs - (MIN_AUDIO_MS * speed).roundToLong())
    if (latest < previousEnd) return this
    return replaceAudio(clip.copy(startMs = startMs.coerceIn(previousEnd, latest)))
}

/**
 * Moves one edge of an audio clip to [valueMs] on the project's time axis. The sound stays where
 * it is in time: the start edge uncovers or hides the beginning of the file, the end edge its end.
 */
fun Project.trimAudio(id: Long, edge: Edge, valueMs: Long): Project {
    val clip = audio.firstOrNull { it.id == id } ?: return this
    val (previousEnd, nextStart) = neighbours(id)
    val minLength = (MIN_AUDIO_MS * speed).roundToLong()
    val end = clip.endMs(speed)
    return when (edge) {
        Edge.START -> {
            // As far left as the file has sound before the current start, and the neighbour allows.
            val earliest = max(previousEnd, clip.startMs - (clip.srcStartMs * speed).roundToLong())
            val newStart = valueMs.coerceIn(earliest, max(earliest, end - minLength))
            val newSrcStart = (clip.srcStartMs + ((newStart - clip.startMs) / speed).roundToLong())
                .coerceIn(0, clip.srcEndMs - MIN_AUDIO_MS)
            replaceAudio(clip.copy(startMs = newStart, srcStartMs = newSrcStart))
        }
        Edge.END -> {
            val latest = minOf(
                nextStart,
                clip.startMs + ((clip.source.durationMs - clip.srcStartMs) * speed).roundToLong(),
                max(durationMs, end),
            )
            val newEnd = valueMs.coerceIn(clip.startMs + minLength, max(clip.startMs + minLength, latest))
            val newSrcEnd = (clip.srcStartMs + ((newEnd - clip.startMs) / speed).roundToLong())
                .coerceIn(clip.srcStartMs + MIN_AUDIO_MS, clip.source.durationMs)
            replaceAudio(clip.copy(srcEndMs = newSrcEnd))
        }
    }
}

fun Project.removeAudio(id: Long): Project =
    if (audio.none { it.id == id }) this else copy(audio = audio.filter { it.id != id })

fun Project.setAudioVolume(id: Long, volume: Float): Project {
    val clip = audio.firstOrNull { it.id == id } ?: return this
    return replaceAudio(clip.copy(volume = volume.coerceIn(0f, 2f)))
}

fun Project.canSplitAudioAt(id: Long, timeMs: Long): Boolean {
    val placement = audioPlacements().firstOrNull { it.clip.id == id } ?: return false
    val minLength = (MIN_AUDIO_MS * speed).roundToLong()
    return timeMs - placement.startMs >= minLength && placement.endMs - timeMs >= minLength
}

/** Cuts an audio clip in two at [timeMs]. */
fun Project.splitAudio(id: Long, timeMs: Long, newId: () -> Long): Project {
    if (!canSplitAudioAt(id, timeMs)) return this
    val clip = audio.first { it.id == id }
    val cut = clip.srcStartMs + ((timeMs - clip.startMs) / speed).roundToLong()
    val first = clip.copy(id = newId(), srcEndMs = cut)
    val second = clip.copy(id = newId(), startMs = timeMs, srcStartMs = cut)
    return copy(audio = (audio.filter { it.id != id } + first + second).sortedBy { it.startMs })
}
