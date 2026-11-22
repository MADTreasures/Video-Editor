package io.github.madtreasures.videoeditor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectTest {
    private var nextId = 1L
    private val newId = { nextId++ }

    private fun source(
        durationMs: Long,
        width: Int = 1920,
        height: Int = 1080,
        audio: Boolean = true,
        bitrate: Int = 0,
    ) = VideoSource(
        "content://test/$durationMs", "v$durationMs.mp4", durationMs, width, height, 30f, audio, bitrate,
        videoMime = "video/avc",
        audioMime = if (audio) "audio/mp4a-latm" else "",
    )

    private fun project(vararg durations: Long) =
        Project().append(durations.map { source(it) }, newId)

    @Test
    fun locate_mapsProjectTimeToClipAndOffset() {
        val p = project(1000, 2000, 500)
        assertEquals(3500, p.durationMs)
        assertEquals(ClipPosition(0, 0), p.locate(0))
        assertEquals(ClipPosition(0, 999), p.locate(999))
        // A time exactly on a cut belongs to the clip that starts there.
        assertEquals(ClipPosition(1, 0), p.locate(1000))
        assertEquals(ClipPosition(2, 0), p.locate(3000))
        // The very end belongs to the last clip.
        assertEquals(ClipPosition(2, 500), p.locate(3500))
        assertEquals(ClipPosition(2, 500), p.locate(99_999))
        assertEquals(ClipPosition(0, 0), p.locate(-5))
    }

    @Test
    fun clipStartMs_sumsPrecedingClips() {
        val p = project(1000, 2000, 500)
        assertEquals(0, p.clipStartMs(0))
        assertEquals(1000, p.clipStartMs(1))
        assertEquals(3000, p.clipStartMs(2))
    }

    @Test
    fun splitAt_cutsClipInTwoAndKeepsTotalDuration() {
        val p = project(1000, 2000).splitAt(1500, newId)
        assertEquals(3, p.clips.size)
        assertEquals(3000, p.durationMs)
        assertEquals(0L to 500L, p.clips[1].startMs to p.clips[1].endMs)
        assertEquals(500L to 2000L, p.clips[2].startMs to p.clips[2].endMs)
        assertEquals(3, p.clips.map { it.id }.toSet().size)
    }

    @Test
    fun splitAt_tooCloseToAnEdge_doesNothing() {
        val p = project(1000, 2000)
        assertFalse(p.canSplitAt(0))
        assertFalse(p.canSplitAt(1000))
        assertFalse(p.canSplitAt(1000 + MIN_CLIP_MS - 1))
        assertFalse(p.canSplitAt(3000))
        assertTrue(p.canSplitAt(1000 + MIN_CLIP_MS))
        assertSame(p, p.splitAt(1000, newId))
    }

    @Test
    fun splitThenRemoveMiddle_cutsOutASection() {
        val p = project(10_000).splitAt(3000, newId).splitAt(7000, newId).removeClip(1)
        assertEquals(2, p.clips.size)
        assertEquals(6000, p.durationMs)
        assertEquals(3000, p.clips[0].endMs)
        assertEquals(7000, p.clips[1].startMs)
    }

    @Test
    fun trim_clampsToSourceAndMinimumLength() {
        val p = project(5000)
        assertEquals(1200, p.trim(0, Edge.START, 1200).clips[0].startMs)
        assertEquals(0, p.trim(0, Edge.START, -300).clips[0].startMs)
        assertEquals(5000 - MIN_CLIP_MS, p.trim(0, Edge.START, 9000).clips[0].startMs)
        assertEquals(4000, p.trim(0, Edge.END, 4000).clips[0].endMs)
        assertEquals(5000, p.trim(0, Edge.END, 9000).clips[0].endMs)
        assertEquals(MIN_CLIP_MS, p.trim(0, Edge.END, 0).clips[0].endMs)
        // The end of a clip that was trimmed can be dragged back out to the source's end.
        val shortened = p.trim(0, Edge.END, 2000)
        assertEquals(5000, shortened.trim(0, Edge.END, 99_999).clips[0].endMs)
        assertSame(p, p.trim(0, Edge.END, 5000))
    }

    @Test
    fun moveClip_swapsNeighbours() {
        val p = project(1000, 2000, 500)
        assertEquals(listOf(2000L, 1000L, 500L), p.moveClip(0, 1).clips.map { it.durationMs })
        assertEquals(listOf(1000L, 500L, 2000L), p.moveClip(2, -1).clips.map { it.durationMs })
        assertSame(p, p.moveClip(0, -1))
        assertSame(p, p.moveClip(2, 1))
    }

    @Test
    fun speedAndRotation_affectOutput() {
        val p = project(4000)
        assertEquals(2000, p.copy(speed = 2f).outputDurationMs)
        assertEquals(8000, p.copy(speed = 0.5f).outputDurationMs)
        assertEquals(16f / 9f, p.canvasAspectRatio, 0.001f)
        assertEquals(9f / 16f, p.rotatedClockwise().canvasAspectRatio, 0.001f)
        assertEquals(0, p.rotatedClockwise().rotatedClockwise().rotatedClockwise().rotatedClockwise().rotation)
    }

    @Test
    fun hasAudio_respectsMuteAndSources() {
        assertTrue(project(1000).hasAudio)
        assertFalse(project(1000).copy(muted = true).hasAudio)
        assertFalse(Project().append(listOf(source(1000, audio = false)), newId).hasAudio)
    }

    @Test
    fun planExport_untouchedClip_isPassthrough() {
        val plan = planExport(project(5000), ExportSettings())
        assertTrue(plan.passthrough)
        assertFalse(plan.needsScaling)
        assertEquals(1920 to 1080, plan.width to plan.height)
    }

    @Test
    fun planExport_endTrimAndRotation_stayPassthrough() {
        val p = project(5000).trim(0, Edge.END, 3000).rotatedClockwise()
        val plan = planExport(p, ExportSettings())
        assertTrue(plan.passthrough)
        assertEquals(1080 to 1920, plan.width to plan.height)
    }

    @Test
    fun planExport_anythingThatNeedsNewFrames_isNotPassthrough() {
        val base = project(5000)
        assertFalse(planExport(base.trim(0, Edge.START, 1000), ExportSettings()).passthrough)
        assertFalse(planExport(base.splitAt(2000, newId), ExportSettings()).passthrough)
        assertFalse(planExport(base.copy(speed = 2f), ExportSettings()).passthrough)
        assertFalse(planExport(base, ExportSettings(resolution = Resolution.P720)).passthrough)
        assertFalse(planExport(base, ExportSettings(quality = Quality.MEDIUM)).passthrough)
        assertFalse(planExport(base, ExportSettings(videoCodec = VideoCodec.H265)).passthrough)
    }

    @Test
    fun planExport_downscalesByShortSideAndNeverUpscales() {
        val landscape = planExport(project(5000), ExportSettings(resolution = Resolution.P720))
        assertEquals(1280 to 720, landscape.width to landscape.height)
        assertTrue(landscape.needsScaling)

        val portrait = planExport(project(5000).rotatedClockwise(), ExportSettings(resolution = Resolution.P480))
        assertEquals(480 to 854, portrait.width to portrait.height)

        val small = Project().append(listOf(source(5000, width = 640, height = 360)), newId)
        val kept = planExport(small, ExportSettings(resolution = Resolution.P1080))
        assertEquals(640 to 360, kept.width to kept.height)
        assertFalse(kept.needsScaling)
    }

    @Test
    fun planExport_mixedSourceSizes_needScalingToFirstClip() {
        val p = Project().append(listOf(source(1000), source(2000, width = 1080, height = 1920)), newId)
        val plan = planExport(p, ExportSettings())
        assertTrue(plan.needsScaling)
        assertEquals(1920 to 1080, plan.width to plan.height)
    }

    @Test
    fun planExport_bitrateScalesWithQualityAndCodec() {
        val p = project(10_000).splitAt(5000, newId)
        val high = planExport(p, ExportSettings())
        val low = planExport(p, ExportSettings(quality = Quality.LOW))
        val hevc = planExport(p, ExportSettings(videoCodec = VideoCodec.H265))
        assertEquals((1920L * 1080 * 30 * 0.14f).toLong().toInt(), high.videoBitrate)
        assertTrue(low.videoBitrate < high.videoBitrate)
        assertTrue(hevc.videoBitrate < high.videoBitrate)
        // 10 s at ~8.7 Mbit/s video + 128 kbit/s audio is about 11 MB.
        assertEquals(11.0, high.estimatedBytes / 1_000_000.0, 0.5)
    }

    @Test
    fun planExport_neverSpendsMoreBitsThanTheSourceHas() {
        // A 1080p file at only 2 Mbit/s, cut in two so it has to be re-encoded.
        val p = Project().append(listOf(source(10_000, bitrate = 2_000_000)), newId).splitAt(5000, newId)
        val high = planExport(p, ExportSettings())
        val medium = planExport(p, ExportSettings(quality = Quality.MEDIUM))
        val low = planExport(p, ExportSettings(quality = Quality.LOW))
        assertEquals(2_300_000, high.videoBitrate)
        assertEquals(1_200_000, medium.videoBitrate)
        assertEquals(700_000, low.videoBitrate)
    }

    @Test
    fun timeOfSource_followsAFrameThroughATrim() {
        // Two clips; the playhead shows second 4 of the second clip's file.
        val p = project(2000, 10_000)
        assertEquals(6000, p.timeOfSource(1, 4000))

        // Trimming that clip's start moves everything in it earlier on the project's time axis.
        val startTrimmed = p.trim(1, Edge.START, 3000)
        assertEquals(3000, startTrimmed.timeOfSource(1, 4000))
        // Trimming its end leaves the frame where it was.
        assertEquals(6000, p.trim(1, Edge.END, 5000).timeOfSource(1, 4000))
    }

    @Test
    fun timeOfSource_frameThatWasCutAway_goesToTheNearestOneLeft() {
        val p = project(2000, 10_000)
        // Start trimmed past the frame: the clip's new first frame.
        assertEquals(2000, p.trim(1, Edge.START, 6000).timeOfSource(1, 4000))
        // End trimmed before the frame: the clip's new last millisecond, still inside the clip.
        val endTrimmed = p.trim(1, Edge.END, 3000)
        assertEquals(4999, endTrimmed.timeOfSource(1, 4000))
        assertEquals(1, endTrimmed.locate(endTrimmed.timeOfSource(1, 4000)).index)
    }
}
