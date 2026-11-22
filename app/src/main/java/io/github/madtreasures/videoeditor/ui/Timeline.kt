package io.github.madtreasures.videoeditor.ui

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.madtreasures.videoeditor.R
import io.github.madtreasures.videoeditor.media.ThumbKey
import io.github.madtreasures.videoeditor.media.ThumbnailLoader
import io.github.madtreasures.videoeditor.model.Clip
import io.github.madtreasures.videoeditor.model.Edge
import io.github.madtreasures.videoeditor.model.Project
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Free space beside the clips, so the handles of the first and last clip can be reached. */
val TimelineSidePadding = 32.dp

// From top to bottom: time labels, the lane with the playhead's knob, the clips.
private val LabelsHeight = 16.dp
private val KnobLaneHeight = 22.dp
private val TrackTop = LabelsHeight + KnobLaneHeight
private val TrackHeight = 56.dp
val TimelineHeight = TrackTop + TrackHeight + 18.dp

private val HandleWidth = 16.dp
private val HandleReachOut = 18.dp
private val HandleReachIn = 10.dp
private val PlayheadReach = 24.dp
private val TrackSlack = 6.dp
private val KnobRadius = 8.dp

private const val THUMB_QUANTUM_MS = 250f
private val RulerSteps = longArrayOf(
    100, 200, 500, 1_000, 2_000, 5_000, 10_000, 15_000, 30_000, 60_000,
    120_000, 300_000, 600_000, 900_000, 1_800_000, 3_600_000,
)

/** The trim in progress: which edge, and where clip and view were when it was grabbed. */
private class TrimSession(val index: Int, val edge: Edge, val startMs: Long, val viewStartMs: Float)

/**
 * The clips laid out along a time axis that stands still, with a playhead that moves over it.
 *
 * Three things can be dragged, each with a touch area of its own:
 * - the playhead, by its knob above the clips or anywhere along its line, and by dragging on
 *   the time scale: only the playhead moves;
 * - the two handles of the clip under the playhead: only that edge of the clip moves, the
 *   other edge and the pictures stay where they are;
 * - the strip itself, which scrolls if the project is longer than the view.
 *
 * A tap puts the playhead there, two fingers zoom.
 */
@Composable
fun Timeline(
    project: Project,
    currentIndex: Int,
    positionMs: () -> Long,
    dpPerSecond: Float,
    thumbnails: ThumbnailLoader,
    onZoom: (Float) -> Unit,
    onScrub: (Long) -> Unit,
    onScrubEnd: () -> Unit,
    onTrimStart: (Int, Edge) -> Unit,
    onTrim: (Int, Edge, Long) -> Unit,
    onTrimEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val pxPerMs = with(density) { dpPerSecond.dp.toPx() } / 1000f
    val tileWidthPx = with(density) { TrackHeight.toPx() }
    val sidePadding = with(density) { TimelineSidePadding.toPx() }

    val currentProject by rememberUpdatedState(project)
    val currentPxPerMs by rememberUpdatedState(pxPerMs)
    val currentIndexState by rememberUpdatedState(currentIndex)
    val currentPosition by rememberUpdatedState(positionMs)
    val currentOnZoom by rememberUpdatedState(onZoom)
    val currentOnScrub by rememberUpdatedState(onScrub)
    val currentOnScrubEnd by rememberUpdatedState(onScrubEnd)
    val currentOnTrimStart by rememberUpdatedState(onTrimStart)
    val currentOnTrim by rememberUpdatedState(onTrim)
    val currentOnTrimEnd by rememberUpdatedState(onTrimEnd)

    var widthPx by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Float>()

    // The view is stored as "this moment of the project is shown at this x". A change of zoom
    // then scales the strip around that point without anything else having to be adjusted.
    var anchorTimeMs by remember { mutableFloatStateOf(0f) }
    var anchorPx by remember { mutableFloatStateOf(sidePadding) }

    var trim by remember { mutableStateOf<TrimSession?>(null) }
    /** What the finger is holding right now, for highlighting it. */
    var held by remember { mutableStateOf<TimelineTarget?>(null) }
    var touching by remember { mutableStateOf(false) }
    /** True while the view glides back after a trim left it outside its scroll range. */
    var settling by remember { mutableStateOf(false) }
    var motion by remember { mutableStateOf<Job?>(null) }

    fun range() = scrollRange(currentProject.durationMs, widthPx.toFloat(), currentPxPerMs, sidePadding)

    /** View start as stored, plus the shift that keeps a clip's end in place while its start is trimmed. */
    fun rawViewStartMs(): Float {
        val session = trim
        if (session != null && session.edge == Edge.START) {
            val clip = currentProject.clips.getOrNull(session.index)
            if (clip != null) return session.viewStartMs - (clip.startMs - session.startMs)
        }
        return anchorTimeMs - (anchorPx - sidePadding) / currentPxPerMs
    }

    /** View start as drawn: kept inside the scroll range, except while a trim or its glide is running. */
    fun viewStartMs(): Float {
        val raw = rawViewStartMs()
        return if (trim != null || settling || widthPx == 0) raw else raw.coerceIn(range())
    }

    fun currentView() = TimelineView(viewStartMs(), currentPxPerMs, sidePadding)

    /** Stores [viewStart], pinned at screen position [atPx]. */
    fun setView(viewStart: Float, atPx: Float = sidePadding) {
        anchorPx = atPx
        anchorTimeMs = viewStart + (atPx - sidePadding) / currentPxPerMs
    }

    /** Pins the view at the playhead if it is in view, else at the middle: that is what zooming keeps still. */
    fun pinForZoom() {
        if (widthPx == 0) return
        val view = currentView()
        val playheadX = view.xOf(currentPosition().toFloat())
        val pin = if (playheadX in 0f..widthPx.toFloat()) playheadX else widthPx / 2f
        setView(view.viewStartMs, pin)
    }

    /** If the view was left outside its scroll range, glides it back in. */
    fun settle() {
        val from = rawViewStartMs()
        val to = from.coerceIn(range())
        if (abs(to - from) * currentPxPerMs < 1f) {
            pinForZoom()
            return
        }
        settling = true
        motion = scope.launch {
            try {
                animate(from, to, animationSpec = tween(200)) { value, _ -> setView(value) }
            } finally {
                settling = false
                pinForZoom()
            }
        }
    }

    // Follow the playhead when it leaves the view on its own: during playback, after an undo,
    // after a clip was deleted. While a finger is on the timeline the view is left alone.
    LaunchedEffect(Unit) {
        snapshotFlow { currentPosition() }.collect { position ->
            if (touching || trim != null || settling || widthPx == 0) return@collect
            val view = currentView()
            val x = view.xOf(position.toFloat())
            val scrollable = canScroll(currentProject.durationMs, widthPx.toFloat(), currentPxPerMs, sidePadding)
            if (scrollable && (x < sidePadding - 1f || x > widthPx - sidePadding + 1f)) {
                // Turn the page: the playhead continues near the left end of the view.
                val lead = visibleMs(widthPx.toFloat(), currentPxPerMs, sidePadding) * 0.1f
                setView((position - lead).coerceIn(range()))
            }
            pinForZoom()
        }
    }

    LaunchedEffect(thumbnails) {
        snapshotFlow {
            visibleThumbs(currentProject, currentView(), widthPx.toFloat(), tileWidthPx)
        }.distinctUntilChanged().collect { thumbnails.request(it) }
    }

    val textMeasurer = rememberTextMeasurer()
    val rulerStyle = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
    val badgeStyle = TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    val tickColor = MaterialTheme.colorScheme.outlineVariant
    val placeholderColor = MaterialTheme.colorScheme.surfaceVariant
    val gripColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val heldColor = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.timeline_description)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(TimelineHeight)
            .semantics { contentDescription = description }
            // Drags that start at the screen edge belong to the timeline, not to "back".
            .systemGestureExclusion()
            .onSizeChanged { widthPx = it.width }
            .pointerInput(Unit) {
                val metrics = TimelineMetrics(
                    sidePadding = sidePadding,
                    trackTop = TrackTop.toPx(),
                    trackBottom = (TrackTop + TrackHeight).toPx(),
                    handleWidth = HandleWidth.toPx(),
                    handleReachOut = HandleReachOut.toPx(),
                    handleReachIn = HandleReachIn.toPx(),
                    playheadReach = PlayheadReach.toPx(),
                    trackSlack = TrackSlack.toPx(),
                )
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val caughtMotion = motion?.isActive == true
                    motion?.cancel()
                    settling = false
                    val startProject = currentProject
                    if (startProject.isEmpty || widthPx == 0) return@awaitEachGesture

                    // From here on the view is what is on screen right now.
                    val pxPerMs0 = currentPxPerMs
                    val view = TimelineView(viewStartMs(), pxPerMs0, sidePadding)
                    setView(view.viewStartMs)
                    val durationMs = startProject.durationMs.toFloat()
                    val index = currentIndexState.coerceIn(0, startProject.clips.lastIndex)
                    val clip = startProject.clips[index]
                    val clipLeft = view.xOf(startProject.clipStartMs(index).toFloat())
                    val clipRight = clipLeft + clip.durationMs * pxPerMs0
                    val playheadX = view.xOf(currentPosition().toFloat())
                    val target = hitTest(down.position.x, down.position.y, clipLeft, clipRight, playheadX, metrics)

                    touching = true
                    // What was touched lights up at once, so it is clear what the finger has.
                    held = target.takeIf {
                        it == TimelineTarget.PLAYHEAD ||
                            it == TimelineTarget.START_HANDLE ||
                            it == TimelineTarget.END_HANDLE
                    }
                    try {
                        // Nothing moves until the finger has clearly moved; a second finger
                        // before that turns the gesture into a zoom. From then on everything
                        // is measured from where the finger went down, so what it holds stays
                        // exactly under it.
                        val downX = down.position.x
                        var dragX = Float.NaN
                        var zoom = false
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } > 1) {
                                zoom = true
                                break
                            }
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) break
                            if (abs(change.position.x - downX) > viewConfiguration.touchSlop) {
                                dragX = change.position.x
                                change.consume()
                                break
                            }
                        }

                        when {
                            zoom -> {
                                held = null
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.count { it.pressed }
                                    if (pressed == 0) break
                                    if (pressed > 1) {
                                        // The spot between the fingers stays where it is.
                                        val between = event.calculateCentroid()
                                        if (between.isSpecified) setView(viewStartMs(), between.x)
                                        val factor = event.calculateZoom()
                                        if (factor != 1f) currentOnZoom(factor)
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                            }

                            dragX.isNaN() -> {
                                // A tap. On the strip or the time scale it puts the playhead there.
                                val tapped = target == TimelineTarget.STRIP || target == TimelineTarget.RULER
                                if (tapped && !caughtMotion) {
                                    val time = view.timeAt(down.position.x).coerceIn(0f, durationMs)
                                    currentOnScrub(time.roundToLong())
                                    currentOnScrubEnd()
                                }
                            }

                            target == TimelineTarget.PLAYHEAD || target == TimelineTarget.RULER -> {
                                // The playhead keeps its distance to the finger if it was taken
                                // by itself, and comes under the finger from the time scale.
                                val grab = if (target == TimelineTarget.PLAYHEAD) downX - playheadX else 0f
                                held = TimelineTarget.PLAYHEAD
                                try {
                                    followPointer(down.id, dragX) { x ->
                                        val time = view.timeAt(x - grab).coerceIn(0f, durationMs)
                                        currentOnScrub(time.roundToLong())
                                    }
                                } finally {
                                    currentOnScrubEnd()
                                }
                            }

                            target == TimelineTarget.START_HANDLE || target == TimelineTarget.END_HANDLE -> {
                                val edge = if (target == TimelineTarget.START_HANDLE) Edge.START else Edge.END
                                val grab = downX - (if (edge == Edge.START) clipLeft else clipRight)
                                trim = TrimSession(index, edge, clip.startMs, view.viewStartMs)
                                currentOnTrimStart(index, edge)
                                try {
                                    followPointer(down.id, dragX) { x ->
                                        // The edge that is not held keeps its place on screen,
                                        // so the held one is measured from it.
                                        val value = when (edge) {
                                            Edge.START -> clip.endMs - (clipRight - (x - grab)) / pxPerMs0
                                            Edge.END -> clip.startMs + ((x - grab) - clipLeft) / pxPerMs0
                                        }
                                        currentOnTrim(index, edge, value.roundToLong())
                                    }
                                } finally {
                                    val reached = rawViewStartMs()
                                    trim = null
                                    setView(reached)
                                    currentOnTrimEnd()
                                    settle()
                                }
                            }

                            else -> {
                                // The strip: scrolls, if there is more of it than fits.
                                if (canScroll(startProject.durationMs, widthPx.toFloat(), pxPerMs0, sidePadding)) {
                                    val velocity = VelocityTracker()
                                    var viewStart = view.viewStartMs
                                    followPointer(down.id, dragX, velocity) { x ->
                                        viewStart = (view.viewStartMs - (x - downX) / pxPerMs0).coerceIn(range())
                                        setView(viewStart)
                                    }
                                    val speed = velocity.calculateVelocity().x
                                    if (abs(speed) > 300f) {
                                        motion = scope.launch {
                                            var last = 0f
                                            AnimationState(0f, -speed).animateDecay(decay) {
                                                val bounds = range()
                                                viewStart = (viewStart + (value - last) / currentPxPerMs).coerceIn(bounds)
                                                last = value
                                                setView(viewStart)
                                                if (viewStart <= bounds.start || viewStart >= bounds.endInclusive) {
                                                    cancelAnimation()
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    followPointer(down.id, dragX) { }
                                }
                            }
                        }
                    } finally {
                        held = null
                        touching = false
                        if (trim == null && !settling && motion?.isActive != true) pinForZoom()
                    }
                }
            },
    ) {
        val view = TimelineView(viewStartMs(), pxPerMs, sidePadding)
        val trackTop = TrackTop.toPx()
        val trackHeight = TrackHeight.toPx()

        drawRuler(textMeasurer, rulerStyle, tickColor, project.durationMs, view)

        // Clips with their thumbnails.
        val gap = 1.dp.toPx()
        val corner = CornerRadius(6.dp.toPx())
        var clipStartTime = 0L
        project.clips.forEach { clip ->
            val left = view.xOf(clipStartTime.toFloat())
            val right = left + clip.durationMs * pxPerMs
            clipStartTime += clip.durationMs
            if (right < 0f || left > size.width) return@forEach
            val body = Rect(left + gap, trackTop, max(left + gap + 1f, right - gap), trackTop + trackHeight)
            clipPath(Path().apply { addRoundRect(RoundRect(body, corner)) }) {
                drawRect(placeholderColor, body.topLeft, body.size)
                forEachVisibleTile(clip, left, pxPerMs, size.width, tileWidthPx) { key, tileLeft ->
                    val image = thumbnails.cache[key] ?: return@forEachVisibleTile
                    drawImage(
                        image = image,
                        dstOffset = IntOffset(tileLeft.roundToInt(), trackTop.roundToInt()),
                        dstSize = IntSize(ceil(tileWidthPx).toInt() + 1, trackHeight.roundToInt()),
                        filterQuality = FilterQuality.Low,
                    )
                }
            }
        }

        // The current clip: white frame whose thick sides are the trim handles.
        val index = (trim?.index ?: currentIndex).coerceIn(0, max(0, project.clips.lastIndex))
        project.clips.getOrNull(index)?.let { clip ->
            val left = view.xOf(project.clipStartMs(index).toFloat())
            val right = left + clip.durationMs * pxPerMs
            val handle = HandleWidth.toPx()
            val border = 2.5.dp.toPx()
            val top = trackTop - border
            val bottom = trackTop + trackHeight + border
            val frameCorner = CornerRadius(8.dp.toPx())
            val outer = Path().apply {
                addRoundRect(RoundRect(Rect(left - handle, top, right + handle, bottom), frameCorner))
            }
            val inner = Path().apply {
                addRoundRect(
                    RoundRect(
                        Rect(left, trackTop, max(left + 1f, right), trackTop + trackHeight),
                        CornerRadius(2.dp.toPx()),
                    ),
                )
            }
            drawPath(Path.combine(PathOperation.Difference, outer, inner), Color.White)

            // The handle in the finger's grip lights up.
            val heldLeft = when (held) {
                TimelineTarget.START_HANDLE -> left - handle
                TimelineTarget.END_HANDLE -> right
                else -> null
            }
            if (heldLeft != null) {
                val lit = Path().apply { addRect(Rect(heldLeft, top, heldLeft + handle, bottom)) }
                drawPath(Path.combine(PathOperation.Intersect, outer, lit), heldColor)
            }

            val gripHeight = 20.dp.toPx()
            val gripWidth = 3.dp.toPx()
            val gripTop = trackTop + (trackHeight - gripHeight) / 2
            for (x in floatArrayOf(left - handle / 2, right + handle / 2)) {
                drawRoundRect(
                    color = gripColor,
                    topLeft = Offset(x - gripWidth / 2, gripTop),
                    size = Size(gripWidth, gripHeight),
                    cornerRadius = CornerRadius(gripWidth / 2),
                )
            }

            // Length of the current clip, inside its top left corner.
            val label = textMeasurer.measure(formatTime(clip.durationMs), badgeStyle)
            val pad = 4.dp.toPx()
            val badgeLeft = max(left, 0f) + pad
            if (badgeLeft + label.size.width + 3 * pad < right) {
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.6f),
                    topLeft = Offset(badgeLeft, trackTop + pad),
                    size = Size(label.size.width + 2 * pad, label.size.height + pad),
                    cornerRadius = CornerRadius(pad),
                )
                drawText(label, topLeft = Offset(badgeLeft + pad, trackTop + pad * 1.5f))
            }
        }

        // Playhead: a line across the clips with a knob above them to take it by.
        val playheadX = view.xOf(positionMs().toFloat())
        if (playheadX > -KnobRadius.toPx() && playheadX < size.width + KnobRadius.toPx()) {
            val knobY = LabelsHeight.toPx() + KnobLaneHeight.toPx() / 2
            val bottom = trackTop + trackHeight + 10.dp.toPx()
            val holding = held == TimelineTarget.PLAYHEAD
            val radius = KnobRadius.toPx() * (if (holding) 1.25f else 1f)
            val shade = Color.Black.copy(alpha = 0.55f)
            drawLine(shade, Offset(playheadX, knobY), Offset(playheadX, bottom), 5.dp.toPx())
            drawLine(Color.White, Offset(playheadX, knobY), Offset(playheadX, bottom), 2.5.dp.toPx())
            drawCircle(shade, radius + 1.5.dp.toPx(), Offset(playheadX, knobY))
            drawCircle(if (holding) heldColor else Color.White, radius, Offset(playheadX, knobY))
        }
    }
}

/**
 * Reports the x position of the pointer [id] until it lifts, starting with [firstX], the
 * position at which it passed the touch slop.
 */
private suspend fun AwaitPointerEventScope.followPointer(
    id: PointerId,
    firstX: Float,
    velocity: VelocityTracker? = null,
    onMove: (x: Float) -> Unit,
) {
    onMove(firstX)
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == id } ?: return
        if (!change.pressed) return
        velocity?.addPointerInputChange(change)
        change.consume()
        onMove(change.position.x)
    }
}

private fun DrawScope.drawRuler(
    textMeasurer: TextMeasurer,
    style: TextStyle,
    tickColor: Color,
    durationMs: Long,
    view: TimelineView,
) {
    if (view.pxPerMs <= 0f) return
    val minSpacing = 72.dp.toPx()
    val step = RulerSteps.firstOrNull { it * view.pxPerMs >= minSpacing } ?: RulerSteps.last()
    val firstVisible = max(0f, view.timeAt(0f))
    val lastVisible = min(durationMs.toFloat(), view.timeAt(size.width))
    var mark = ceil(firstVisible / step).toLong() * step
    val tickTop = LabelsHeight.toPx()
    val tickBottom = tickTop + 5.dp.toPx()
    while (mark <= lastVisible) {
        val x = view.xOf(mark.toFloat())
        drawLine(tickColor, Offset(x, tickTop), Offset(x, tickBottom), 1.dp.toPx())
        val label = textMeasurer.measure(formatRulerTime(mark, step), style)
        drawText(label, topLeft = Offset(x - label.size.width / 2f, 1.dp.toPx()))
        mark += step
    }
}

/**
 * Calls [block] for every thumbnail tile of [clip] that is on screen. Tiles sit on a fixed grid
 * in the source file's time, so trimming or splitting a clip never changes their pictures.
 */
private inline fun forEachVisibleTile(
    clip: Clip,
    clipLeftPx: Float,
    pxPerMs: Float,
    viewWidthPx: Float,
    tileWidthPx: Float,
    block: (key: ThumbKey, tileLeftPx: Float) -> Unit,
) {
    if (pxPerMs <= 0f || tileWidthPx <= 0f) return
    val tileMs = tileWidthPx / pxPerMs
    // Frames are requested on a grid that only changes when the zoom doubles or halves.
    var quantum = THUMB_QUANTUM_MS
    while (quantum * 2 <= tileMs) quantum *= 2
    val visibleFrom = clip.startMs + max(0f, -clipLeftPx) / pxPerMs
    val visibleTo = min(clip.endMs.toFloat(), clip.startMs + (viewWidthPx - clipLeftPx) / pxPerMs)
    if (visibleTo <= visibleFrom) return
    var tile = floor(visibleFrom / tileMs).toLong()
    while (tile * tileMs < visibleTo) {
        val tileTime = tile * tileMs
        val frameTime = (floor(tileTime / quantum) * quantum).toLong()
            .coerceAtMost(max(0, clip.source.durationMs - 1))
        block(ThumbKey(clip.source.uri, frameTime), clipLeftPx + (tileTime - clip.startMs) * pxPerMs)
        tile++
    }
}

private fun visibleThumbs(
    project: Project,
    view: TimelineView,
    viewWidthPx: Float,
    tileWidthPx: Float,
): List<ThumbKey> {
    if (viewWidthPx <= 0f) return emptyList()
    val keys = ArrayList<ThumbKey>()
    var clipStartTime = 0L
    for (clip in project.clips) {
        val left = view.xOf(clipStartTime.toFloat())
        val right = left + clip.durationMs * view.pxPerMs
        clipStartTime += clip.durationMs
        if (right < 0f || left > viewWidthPx) continue
        forEachVisibleTile(clip, left, view.pxPerMs, viewWidthPx, tileWidthPx) { key, _ -> keys += key }
    }
    return keys.distinct()
}
