package io.github.madtreasures.videoeditor.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineGeometryTest {
    // A 720 px wide timeline: track from y = 70 to 180, clip from x = 200 to 500.
    private val metrics = TimelineMetrics(
        sidePadding = 56f,
        trackTop = 70f,
        trackBottom = 180f,
        handleWidth = 32f,
        handleReachOut = 36f,
        handleReachIn = 20f,
        playheadReach = 48f,
        trackSlack = 12f,
    )
    private val clipLeft = 200f
    private val clipRight = 500f
    private val onTrack = 120f
    private val onRuler = 30f
    private val belowTrack = 200f

    private fun hit(x: Float, y: Float, playheadX: Float = 350f) =
        hitTest(x, y, clipLeft, clipRight, playheadX, metrics)

    @Test
    fun handles_areHitOnAndAroundThemButOnlyOnTheTrack() {
        assertEquals(TimelineTarget.START_HANDLE, hit(184f, onTrack)) // middle of the handle
        assertEquals(TimelineTarget.START_HANDLE, hit(135f, onTrack)) // reach beyond it
        assertEquals(TimelineTarget.START_HANDLE, hit(219f, onTrack)) // reach into the clip
        assertEquals(TimelineTarget.STRIP, hit(130f, onTrack))
        assertEquals(TimelineTarget.STRIP, hit(222f, onTrack))

        assertEquals(TimelineTarget.END_HANDLE, hit(516f, onTrack))
        assertEquals(TimelineTarget.END_HANDLE, hit(565f, onTrack))
        assertEquals(TimelineTarget.END_HANDLE, hit(481f, onTrack))
        assertEquals(TimelineTarget.STRIP, hit(570f, onTrack))

        // Above and below the track there are no handles.
        assertEquals(TimelineTarget.RULER, hit(184f, onRuler))
        assertEquals(TimelineTarget.STRIP, hit(184f, belowTrack))
    }

    @Test
    fun playhead_isHitAlongItsWholeLength() {
        assertEquals(TimelineTarget.PLAYHEAD, hit(350f, onRuler))
        assertEquals(TimelineTarget.PLAYHEAD, hit(350f + 48f, onRuler))
        assertEquals(TimelineTarget.RULER, hit(350f + 49f, onRuler))
        assertEquals(TimelineTarget.PLAYHEAD, hit(330f, onTrack))
        assertEquals(TimelineTarget.PLAYHEAD, hit(370f, belowTrack))
    }

    @Test
    fun playheadOnAClipEdge_handleWinsOnTheTrack_playheadAboveIt() {
        // After a split, or at the very start, the playhead stands exactly on the start edge.
        assertEquals(TimelineTarget.START_HANDLE, hit(195f, onTrack, playheadX = clipLeft))
        assertEquals(TimelineTarget.PLAYHEAD, hit(195f, onRuler, playheadX = clipLeft))
        assertEquals(TimelineTarget.PLAYHEAD, hit(195f, belowTrack, playheadX = clipLeft))
        // Inside the clip, past the handle's reach, the playhead can be taken on the track too.
        assertEquals(TimelineTarget.PLAYHEAD, hit(225f, onTrack, playheadX = clipLeft))
    }

    @Test
    fun veryShortClip_nearerHandleWins() {
        // A clip only 30 px wide: the inner reaches of both handles overlap.
        val left = 300f
        val right = 330f
        fun short(x: Float) = hitTest(x, onTrack, left, right, playheadX = 600f, metrics)
        assertEquals(TimelineTarget.START_HANDLE, short(312f))
        assertEquals(TimelineTarget.END_HANDLE, short(318f))
        assertEquals(TimelineTarget.START_HANDLE, short(270f))
        assertEquals(TimelineTarget.END_HANDLE, short(360f))
    }

    @Test
    fun view_mapsTimeToScreenAndBack() {
        val view = TimelineView(viewStartMs = 2000f, pxPerMs = 0.1f, sidePadding = 56f)
        assertEquals(56f, view.xOf(2000f), 0.001f)
        assertEquals(156f, view.xOf(3000f), 0.001f)
        assertEquals(3000f, view.timeAt(156f), 0.01f)
        assertEquals(1440f, view.timeAt(0f), 0.01f)
    }

    @Test
    fun scrollRange_longProjectScrollsFromFirstToLastMoment() {
        // 608 px of room at 0.1 px/ms show 6080 ms.
        assertEquals(6080f, visibleMs(720f, 0.1f, 56f), 0.01f)
        val range = scrollRange(durationMs = 10_000, widthPx = 720f, pxPerMs = 0.1f, sidePadding = 56f)
        assertEquals(0f, range.start, 0.01f)
        assertEquals(3920f, range.endInclusive, 0.01f)
        assertTrue(canScroll(10_000, 720f, 0.1f, 56f))
    }

    @Test
    fun scrollRange_shortProjectMayRestAnywhereInsideTheView() {
        val range = scrollRange(durationMs = 4000, widthPx = 720f, pxPerMs = 0.1f, sidePadding = 56f)
        // At -2080 its end touches the right padding, at 0 its start touches the left one.
        assertEquals(-2080f, range.start, 0.01f)
        assertEquals(0f, range.endInclusive, 0.01f)
        assertFalse(canScroll(4000, 720f, 0.1f, 56f))
        // Exactly filling the view is not scrollable either.
        assertFalse(canScroll(6080, 720f, 0.1f, 56f))
    }

    private val withLane = metrics.copy(laneTop = 200f, laneBottom = 260f)
    private val onLane = 230f
    private val lane = listOf(LaneBox(1, 100f, 300f), LaneBox(2, 400f, 600f))

    private fun hitLane(x: Float, selected: Long? = null, playheadX: Float = 350f) =
        hitTimeline(x, onLane, clipLeft, clipRight, playheadX, withLane, lane, selected)

    @Test
    fun audioLane_clipsAreHitAndOnlyTheSelectedOneHasHandles() {
        assertEquals(TimelineHit(TimelineTarget.AUDIO_CLIP, 1), hitLane(150f))
        assertEquals(TimelineHit(TimelineTarget.AUDIO_CLIP, 2), hitLane(500f))
        // Unselected: the area beside a clip is empty lane, not a handle.
        assertEquals(TimelineHit(TimelineTarget.AUDIO_EMPTY), hitLane(80f))
        // Selected: handles around its edges, the body still moves it.
        assertEquals(TimelineHit(TimelineTarget.AUDIO_START_HANDLE, 1), hitLane(80f, selected = 1))
        assertEquals(TimelineHit(TimelineTarget.AUDIO_START_HANDLE, 1), hitLane(115f, selected = 1))
        assertEquals(TimelineHit(TimelineTarget.AUDIO_END_HANDLE, 1), hitLane(320f, selected = 1))
        assertEquals(TimelineHit(TimelineTarget.AUDIO_CLIP, 1), hitLane(200f, selected = 1))
    }

    @Test
    fun audioLane_playheadBetweenClips_andVideoTrackUnaffected() {
        assertEquals(TimelineHit(TimelineTarget.PLAYHEAD), hitLane(360f))
        assertEquals(TimelineHit(TimelineTarget.AUDIO_EMPTY), hitLane(700f))
        // The video track above works as before.
        assertEquals(
            TimelineHit(TimelineTarget.START_HANDLE),
            hitTimeline(184f, onTrack, clipLeft, clipRight, 350f, withLane, lane, 1),
        )
    }

    @Test
    fun snap_pullsOnlyWithinReach() {
        assertEquals(5000L, snap(5080, listOf(0, 5000, 9000), reachMs = 100f))
        assertEquals(5150L, snap(5150, listOf(0, 5000, 9000), reachMs = 100f))
        assertEquals(42L, snap(42, emptyList(), reachMs = 100f))
    }
}
