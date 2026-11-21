package io.github.madtreasures.videoeditor.ui

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
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

private val RulerHeight = 22.dp
private val TrackTop = RulerHeight + 6.dp
private val TrackHeight = 56.dp
private val HandleWidth = 14.dp
private val HandleTouchRadius = 20.dp
val TimelineHeight = TrackTop + TrackHeight + 14.dp

private const val THUMB_QUANTUM_MS = 250f
private val RulerSteps = longArrayOf(
    100, 200, 500, 1_000, 2_000, 5_000, 10_000, 15_000, 30_000, 60_000,
    120_000, 300_000, 600_000, 900_000, 1_800_000, 3_600_000,
)

/** Where the view was when a trim handle was grabbed, to keep the far edge still while dragging. */
private class TrimSession(val index: Int, val edge: Edge, val startMs: Long, val viewTimeMs: Float)

/**
 * The clips laid out along a time axis that scrolls under a fixed playhead in the middle.
 *
 * - Dragging scrolls through the video, a flick keeps it rolling, a tap jumps there.
 * - Two fingers zoom.
 * - The clip under the playhead shows two handles; dragging one trims that end of the clip.
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

    var trim by remember { mutableStateOf<TrimSession?>(null) }
    var widthPx by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var momentum by remember { mutableStateOf<Job?>(null) }
    val decay = rememberSplineBasedDecay<Float>()

    // Project time shown under the playhead. While trimming the start of a clip the view moves
    // along with the handle, so the rest of the clip stays put under the finger.
    fun viewTimeMs(): Float {
        val session = trim ?: return currentPosition().toFloat()
        val clip = currentProject.clips.getOrNull(session.index) ?: return session.viewTimeMs
        return when (session.edge) {
            Edge.START -> session.viewTimeMs - (clip.startMs - session.startMs)
            Edge.END -> session.viewTimeMs
        }
    }

    LaunchedEffect(thumbnails) {
        snapshotFlow {
            visibleThumbs(currentProject, viewTimeMs(), currentPxPerMs, widthPx.toFloat(), tileWidthPx)
        }.distinctUntilChanged().collect { thumbnails.request(it) }
    }

    val textMeasurer = rememberTextMeasurer()
    val rulerStyle = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
    val badgeStyle = TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    val tickColor = MaterialTheme.colorScheme.outlineVariant
    val placeholderColor = MaterialTheme.colorScheme.surfaceVariant
    val gripColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val description = stringResource(R.string.timeline_description)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(TimelineHeight)
            .semantics { contentDescription = description }
            .onSizeChanged { widthPx = it.width }
            .pointerInput(Unit) {
                val handleWidth = HandleWidth.toPx()
                val handleRadius = HandleTouchRadius.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    momentum?.cancel()
                    val startProject = currentProject
                    if (startProject.isEmpty) return@awaitEachGesture
                    val startPxPerMs = currentPxPerMs
                    val centerX = size.width / 2f
                    val startTime = currentPosition().toFloat()

                    // Did the finger land on one of the current clip's handles?
                    val index = currentIndexState.coerceIn(0, startProject.clips.lastIndex)
                    val clip = startProject.clips[index]
                    val clipLeft = centerX + (startProject.clipStartMs(index) - startTime) * startPxPerMs
                    val clipRight = clipLeft + clip.durationMs * startPxPerMs
                    val toStart = abs(down.position.x - (clipLeft - handleWidth / 2))
                    val toEnd = abs(down.position.x - (clipRight + handleWidth / 2))
                    val onTrack = down.position.y >= TrackTop.toPx() - 8.dp.toPx() &&
                        down.position.y <= (TrackTop + TrackHeight).toPx() + 8.dp.toPx()
                    val edge = when {
                        !onTrack -> null
                        toStart <= handleRadius && toStart <= toEnd -> Edge.START
                        toEnd <= handleRadius -> Edge.END
                        else -> null
                    }

                    if (edge != null) {
                        val initial = if (edge == Edge.START) clip.startMs else clip.endMs
                        trim = TrimSession(index, edge, clip.startMs, startTime)
                        currentOnTrimStart(index, edge)
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val dragged = change.position.x - down.position.x
                                change.consume()
                                currentOnTrim(index, edge, (initial + dragged / startPxPerMs).roundToLong())
                            }
                        } finally {
                            trim = null
                            currentOnTrimEnd()
                        }
                        return@awaitEachGesture
                    }

                    var time = startTime
                    var travelled = 0f
                    var dragging = false
                    var zoomed = false
                    val velocity = VelocityTracker()
                    velocity.addPointerInputChange(down)
                    var lastX = down.position.x
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size > 1) {
                            zoomed = true
                            val factor = event.calculateZoom()
                            if (factor != 1f) currentOnZoom(factor)
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        if (zoomed) continue
                        val change = pressed.first()
                        val dx = change.positionChange().x
                        lastX = change.position.x
                        travelled += dx
                        velocity.addPointerInputChange(change)
                        if (!dragging && abs(travelled) > viewConfiguration.touchSlop) dragging = true
                        if (dragging) {
                            change.consume()
                            time = (time - dx / currentPxPerMs)
                                .coerceIn(0f, currentProject.durationMs.toFloat())
                            currentOnScrub(time.roundToLong())
                        }
                    }

                    when {
                        zoomed -> currentOnScrubEnd()
                        dragging -> {
                            val speed = velocity.calculateVelocity().x
                            if (abs(speed) < 300f) {
                                currentOnScrubEnd()
                            } else {
                                momentum = scope.launch {
                                    var last = 0f
                                    try {
                                        AnimationState(0f, -speed).animateDecay(decay) {
                                            val duration = currentProject.durationMs.toFloat()
                                            time = (time + (value - last) / currentPxPerMs)
                                                .coerceIn(0f, duration)
                                            last = value
                                            currentOnScrub(time.roundToLong())
                                            if (time <= 0f || time >= duration) cancelAnimation()
                                        }
                                    } finally {
                                        currentOnScrubEnd()
                                    }
                                }
                            }
                        }
                        else -> {
                            // A tap: glide the playhead to the tapped moment.
                            val target = (startTime + (lastX - centerX) / startPxPerMs)
                                .coerceIn(0f, startProject.durationMs.toFloat())
                            momentum = scope.launch {
                                try {
                                    animate(startTime, target, animationSpec = tween(180)) { value, _ ->
                                        currentOnScrub(value.roundToLong())
                                    }
                                } finally {
                                    currentOnScrubEnd()
                                }
                            }
                        }
                    }
                }
            },
    ) {
        val centerX = size.width / 2f
        val viewTime = viewTimeMs()
        val trackTop = TrackTop.toPx()
        val trackHeight = TrackHeight.toPx()
        val trimming = trim != null

        drawRuler(textMeasurer, rulerStyle, tickColor, project.durationMs, viewTime, pxPerMs)

        // Clips with their thumbnails.
        val gap = 1.dp.toPx()
        val corner = CornerRadius(6.dp.toPx())
        var clipStartTime = 0L
        project.clips.forEach { clip ->
            val left = centerX + (clipStartTime - viewTime) * pxPerMs
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
            val left = centerX + (project.clipStartMs(index) - viewTime) * pxPerMs
            val right = left + clip.durationMs * pxPerMs
            val handle = HandleWidth.toPx()
            val border = 2.5.dp.toPx()
            val outer = Path().apply {
                addRoundRect(
                    RoundRect(
                        Rect(left - handle, trackTop - border, right + handle, trackTop + trackHeight + border),
                        CornerRadius(8.dp.toPx()),
                    ),
                )
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
            val gripHeight = 18.dp.toPx()
            val gripWidth = 2.5.dp.toPx()
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

        // Playhead.
        if (!trimming) {
            val top = RulerHeight.toPx() - 4.dp.toPx()
            val bottom = trackTop + trackHeight + 8.dp.toPx()
            drawLine(Color.Black.copy(alpha = 0.5f), Offset(centerX, top), Offset(centerX, bottom), 5.dp.toPx())
            drawLine(Color.White, Offset(centerX, top), Offset(centerX, bottom), 2.dp.toPx())
            drawCircle(Color.White, 4.dp.toPx(), Offset(centerX, top))
        }
    }
}

private fun DrawScope.drawRuler(
    textMeasurer: TextMeasurer,
    style: TextStyle,
    tickColor: Color,
    durationMs: Long,
    viewTimeMs: Float,
    pxPerMs: Float,
) {
    if (pxPerMs <= 0f) return
    val centerX = size.width / 2f
    val minSpacing = 72.dp.toPx()
    val step = RulerSteps.firstOrNull { it * pxPerMs >= minSpacing } ?: RulerSteps.last()
    val firstVisible = max(0f, viewTimeMs - centerX / pxPerMs)
    val lastVisible = min(durationMs.toFloat(), viewTimeMs + centerX / pxPerMs)
    var mark = ceil(firstVisible / step).toLong() * step
    val tickTop = RulerHeight.toPx() - 5.dp.toPx()
    while (mark <= lastVisible) {
        val x = centerX + (mark - viewTimeMs) * pxPerMs
        drawLine(tickColor, Offset(x, tickTop), Offset(x, RulerHeight.toPx()), 1.dp.toPx())
        val label = textMeasurer.measure(formatRulerTime(mark, step), style)
        drawText(label, topLeft = Offset(x - label.size.width / 2f, 2.dp.toPx()))
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
    viewTimeMs: Float,
    pxPerMs: Float,
    viewWidthPx: Float,
    tileWidthPx: Float,
): List<ThumbKey> {
    if (viewWidthPx <= 0f) return emptyList()
    val keys = ArrayList<ThumbKey>()
    val centerX = viewWidthPx / 2f
    var clipStartTime = 0L
    for (clip in project.clips) {
        val left = centerX + (clipStartTime - viewTimeMs) * pxPerMs
        val right = left + clip.durationMs * pxPerMs
        clipStartTime += clip.durationMs
        if (right < 0f || left > viewWidthPx) continue
        forEachVisibleTile(clip, left, pxPerMs, viewWidthPx, tileWidthPx) { key, _ -> keys += key }
    }
    return keys.distinct()
}
