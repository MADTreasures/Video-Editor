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
}

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
