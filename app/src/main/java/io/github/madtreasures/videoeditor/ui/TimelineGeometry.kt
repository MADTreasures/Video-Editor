package io.github.madtreasures.videoeditor.ui

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Where things are on the timeline and what a finger hits. Plain Kotlin, so it can be tested
// without a device.

/** The part of the timeline a finger went down on. */
enum class TimelineTarget {
    /** The handle at the start of the current clip. */
    START_HANDLE,

    /** The handle at the end of the current clip. */
    END_HANDLE,

    PLAYHEAD,

    /** The time scale above the clips, away from the playhead. */
    RULER,

    /** The clips themselves, away from handles and playhead. */
    STRIP,

    /** The handle at the start of the selected audio clip. */
    AUDIO_START_HANDLE,

    /** The handle at the end of the selected audio clip. */
    AUDIO_END_HANDLE,

    /** An audio clip on the lane, away from the selected clip's handles. */
    AUDIO_CLIP,

    /** The audio lane where it holds no clip. */
    AUDIO_EMPTY,
}

/** What a finger went down on; [audioId] names the audio clip for the audio targets. */
data class TimelineHit(val target: TimelineTarget, val audioId: Long? = null)

/** Where an audio clip is on screen. */
data class LaneBox(val id: Long, val left: Float, val right: Float)

/** Sizes of the timeline's parts and of their touch areas, in pixels. */
data class TimelineMetrics(
    /** Free space left and right of the clips when the view is scrolled to an end. */
    val sidePadding: Float,
    val trackTop: Float,
    val trackBottom: Float,
    /** Width of a trim handle. It sits outside the clip, against the clip's edge. */
    val handleWidth: Float,
    /** How far a handle's touch area reaches beyond the handle, away from the clip. */
    val handleReachOut: Float,
    /** How far a handle's touch area reaches into the clip. */
    val handleReachIn: Float,
    /** Half the width of the playhead's touch area. */
    val playheadReach: Float,
    /** How far above and below the track a touch still counts as on the track. */
    val trackSlack: Float,
    /** The audio lane under the video track. */
    val laneTop: Float = Float.NaN,
    val laneBottom: Float = Float.NaN,
)

/**
 * A view of the time axis: the moment [viewStartMs] of the project is shown at the left
 * padding edge, and every millisecond is [pxPerMs] pixels wide.
 */
class TimelineView(val viewStartMs: Float, val pxPerMs: Float, val sidePadding: Float) {
    fun xOf(timeMs: Float): Float = sidePadding + (timeMs - viewStartMs) * pxPerMs

    fun timeAt(x: Float): Float = viewStartMs + (x - sidePadding) / pxPerMs
}

/** Milliseconds of video that fit between the side paddings. */
fun visibleMs(widthPx: Float, pxPerMs: Float, sidePadding: Float): Float =
    max(0f, widthPx - 2 * sidePadding) / pxPerMs

/**
 * The view start values at which nothing is wasted: a project longer than the view can be
 * scrolled from its first to its last moment; a shorter one may rest anywhere inside the view,
 * so it does not have to jump back to the left after its start was trimmed.
 */
fun scrollRange(durationMs: Long, widthPx: Float, pxPerMs: Float, sidePadding: Float): ClosedFloatingPointRange<Float> {
    val spare = durationMs - visibleMs(widthPx, pxPerMs, sidePadding)
    return min(0f, spare)..max(0f, spare)
}

/** True if the project is longer than the view, so that dragging the strip scrolls it. */
fun canScroll(durationMs: Long, widthPx: Float, pxPerMs: Float, sidePadding: Float): Boolean =
    durationMs - visibleMs(widthPx, pxPerMs, sidePadding) > 0.5f / pxPerMs

/**
 * Decides what a finger at ([x], [y]) takes hold of.
 *
 * On the track, a trim handle wins over the playhead, because the playhead can always be taken
 * by its knob above the track, while a handle exists nowhere else. Between the two handles of a
 * very short clip the nearer one wins.
 *
 * @param clipLeft screen x of the current clip's start edge
 * @param clipRight screen x of the current clip's end edge
 */
fun hitTest(
    x: Float,
    y: Float,
    clipLeft: Float,
    clipRight: Float,
    playheadX: Float,
    metrics: TimelineMetrics,
): TimelineTarget = hitTimeline(x, y, clipLeft, clipRight, playheadX, metrics).target

/**
 * Like [hitTest], and also knows the audio lane: on the lane, the selected audio clip's handles
 * come first, then any audio clip (to select or move it), then the playhead.
 */
fun hitTimeline(
    x: Float,
    y: Float,
    clipLeft: Float,
    clipRight: Float,
    playheadX: Float,
    metrics: TimelineMetrics,
    lane: List<LaneBox> = emptyList(),
    selectedAudioId: Long? = null,
): TimelineHit {
    val laneSlack = metrics.trackSlack / 2
    if (!metrics.laneTop.isNaN() && y >= metrics.laneTop - laneSlack && y <= metrics.laneBottom + laneSlack) {
        lane.firstOrNull { it.id == selectedAudioId }?.let { box ->
            val onStart = x >= box.left - metrics.handleWidth - metrics.handleReachOut &&
                x <= box.left + metrics.handleReachIn
            val onEnd = x >= box.right - metrics.handleReachIn &&
                x <= box.right + metrics.handleWidth + metrics.handleReachOut
            if (onStart && onEnd) {
                val toStart = abs(x - (box.left - metrics.handleWidth / 2))
                val toEnd = abs(x - (box.right + metrics.handleWidth / 2))
                return TimelineHit(
                    if (toStart <= toEnd) TimelineTarget.AUDIO_START_HANDLE else TimelineTarget.AUDIO_END_HANDLE,
                    box.id,
                )
            }
            if (onStart) return TimelineHit(TimelineTarget.AUDIO_START_HANDLE, box.id)
            if (onEnd) return TimelineHit(TimelineTarget.AUDIO_END_HANDLE, box.id)
        }
        lane.firstOrNull { x >= it.left && x <= it.right }?.let {
            return TimelineHit(TimelineTarget.AUDIO_CLIP, it.id)
        }
        if (abs(x - playheadX) <= metrics.playheadReach) return TimelineHit(TimelineTarget.PLAYHEAD)
        return TimelineHit(TimelineTarget.AUDIO_EMPTY)
    }
    return TimelineHit(videoTarget(x, y, clipLeft, clipRight, playheadX, metrics))
}

private fun videoTarget(
    x: Float,
    y: Float,
    clipLeft: Float,
    clipRight: Float,
    playheadX: Float,
    metrics: TimelineMetrics,
): TimelineTarget {
    val aboveTrack = y < metrics.trackTop - metrics.trackSlack
    val belowTrack = y > metrics.trackBottom + metrics.trackSlack
    if (!aboveTrack && !belowTrack) {
        val onStart = x >= clipLeft - metrics.handleWidth - metrics.handleReachOut &&
            x <= clipLeft + metrics.handleReachIn
        val onEnd = x >= clipRight - metrics.handleReachIn &&
            x <= clipRight + metrics.handleWidth + metrics.handleReachOut
        if (onStart && onEnd) {
            val toStart = abs(x - (clipLeft - metrics.handleWidth / 2))
            val toEnd = abs(x - (clipRight + metrics.handleWidth / 2))
            return if (toStart <= toEnd) TimelineTarget.START_HANDLE else TimelineTarget.END_HANDLE
        }
        if (onStart) return TimelineTarget.START_HANDLE
        if (onEnd) return TimelineTarget.END_HANDLE
    }
    if (abs(x - playheadX) <= metrics.playheadReach) return TimelineTarget.PLAYHEAD
    return if (aboveTrack) TimelineTarget.RULER else TimelineTarget.STRIP
}

/**
 * Pulls [value] onto the nearest of [targets] if it is closer than [reachMs], so edges line up
 * with the playhead and other cuts without pixel-perfect dragging.
 */
fun snap(value: Long, targets: List<Long>, reachMs: Float): Long {
    val nearest = targets.minByOrNull { abs(it - value) } ?: return value
    return if (abs(nearest - value) <= reachMs) nearest else value
}
