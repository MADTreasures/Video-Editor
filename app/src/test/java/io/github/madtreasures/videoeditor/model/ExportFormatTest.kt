package io.github.madtreasures.videoeditor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportFormatTest {
    private var nextId = 1L
    private val newId = { nextId++ }

    private fun video(
        videoMime: String = "video/avc",
        audioMime: String = "audio/mp4a-latm",
        rotation: Int = 0,
    ) = VideoSource(
        "content://v", "v.mp4", 10_000, 1920, 1080, 30f, audioMime.isNotEmpty(), 8_000_000,
        videoMime = videoMime, audioMime = audioMime, rotationDegrees = rotation,
    )

    private fun project(source: VideoSource = video()) = Project().append(listOf(source), newId)

    private fun plan(p: Project, container: Container, codec: VideoCodec = container.videoCodecs.firstOrNull() ?: VideoCodec.H264, encodable: Set<String> = ALL_CODECS) =
        planExport(p, ExportSettings(container = container, videoCodec = codec), encodable)

    @Test
    fun copyingIntoAnotherContainer_worksWhenTheCodecFits() {
        val p = project()
        for (container in listOf(Container.MP4, Container.MOV, Container.MKV, Container.THREE_GP)) {
            val plan = plan(p, container, VideoCodec.H264)
            assertTrue(container.name, plan.passthrough)
            assertTrue(container.name, plan.audioCopied)
        }
        // WebM holds no H.264: the picture is re-encoded as VP9, the sound as Opus.
        val webm = plan(p, Container.WEBM)
        assertFalse(webm.passthrough)
        assertEquals(VideoCodec.VP9, webm.videoCodec)
    }

    @Test
    fun anotherCodec_meansReencoding() {
        assertFalse(plan(project(), Container.MP4, VideoCodec.H265).passthrough)
        assertTrue(plan(project(video(videoMime = "video/hevc")), Container.MP4, VideoCodec.H265).passthrough)
    }

    @Test
    fun codecTheContainerDoesNotTake_fallsBackToItsFirst() {
        assertEquals(VideoCodec.H264, plan(project(), Container.THREE_GP, VideoCodec.VP9).videoCodec)
    }

    @Test
    fun matroska_hasNoRotationFlag_soTurnedPicturesAreReencodedUpright() {
        val rotatedSource = project(video(rotation = 90))
        assertFalse(plan(rotatedSource, Container.MKV).passthrough)
        assertTrue(plan(rotatedSource, Container.MP4).passthrough)
        assertFalse(plan(project().rotatedClockwise(), Container.MKV).passthrough)
        assertTrue(plan(project(), Container.MKV).portraitEncoding)
        assertFalse(plan(project(), Container.MP4).portraitEncoding)
    }

    @Test
    fun otherAudioCodec_copiesThePictureButConvertsTheSound() {
        val plan = plan(project(video(audioMime = "audio/opus")), Container.MKV)
        assertTrue(plan.passthrough)
        assertFalse(plan.audioCopied)
        assertTrue(plan.hasAudio)
    }

    @Test
    fun audioOnly_formats() {
        val m4a = plan(project(), Container.M4A)
        assertFalse(m4a.hasVideo)
        assertNull(m4a.videoCodec)
        assertTrue(m4a.passthrough) // AAC copied straight out of the MP4
        val ogg = plan(project(), Container.OGG)
        assertFalse(ogg.passthrough)
        assertTrue(ogg.hasAudio)
        assertEquals(160_000, ogg.audioBitrate)
        // 10 s at 192 kbit/s is 240 kB.
        assertEquals(240_000, m4a.estimatedBytes)
    }

    @Test
    fun audioOnly_withoutAnySound_cannotBeExported_unlessAudioWasAdded() {
        val silent = project(video(audioMime = ""))
        assertFalse(plan(silent, Container.M4A).canExport)
        assertFalse(plan(project().copy(muted = true), Container.OGG).canExport)
        val withMusic = silent.addAudio(AudioSource("content://m", "m.mp3", 60_000), 0, newId)
        val plan = plan(withMusic, Container.M4A)
        assertTrue(plan.canExport)
        assertFalse(plan.passthrough)
    }

    @Test
    fun addedAudio_preventsCopying_becauseTheSoundIsMixed() {
        val p = project().addAudio(AudioSource("content://m", "m.mp3", 60_000), 0, newId)
        val plan = plan(p, Container.MP4)
        assertFalse(plan.passthrough)
        assertTrue(plan.hasAudio)
    }

    @Test
    fun soundThatCannotBeEncoded_isDropped_andSaidSo() {
        // A device without an Opus encoder (Android 9 and older) cannot give WebM sound.
        val noOpus = ALL_CODECS - "audio/opus"
        val webm = plan(project(), Container.WEBM, encodable = noOpus)
        assertFalse(webm.hasAudio)
        assertTrue(webm.audioDropped)
        assertTrue(webm.canExport)
        assertFalse(plan(project(), Container.OGG, encodable = noOpus).canExport)
    }
}
