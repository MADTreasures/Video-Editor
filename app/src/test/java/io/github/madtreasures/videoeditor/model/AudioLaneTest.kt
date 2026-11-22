package io.github.madtreasures.videoeditor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLaneTest {
    private var nextId = 1L
    private val newId = { nextId++ }

    private fun video(durationMs: Long) =
        VideoSource("content://v", "v.mp4", durationMs, 1920, 1080, 30f, true, videoMime = "video/avc")

    private fun song(durationMs: Long, name: String = "song.mp3") = AudioSource("content://$name", name, durationMs)

    /** A 20 s video. */
    private fun project() = Project().append(listOf(video(20_000)), newId)

    @Test
    fun addAudio_atPlayhead_isCutToTheVideoEnd() {
        val p = project().addAudio(song(60_000), atMs = 5000, newId)
        val clip = p.audio.single()
        assertEquals(5000, clip.startMs)
        assertEquals(0, clip.srcStartMs)
        assertEquals(15_000, clip.srcEndMs)
        assertEquals(listOf(5000L to 20_000L), p.audioPlacements().map { it.startMs to it.endMs })
    }

    @Test
    fun addAudio_shortFile_keepsItsLength_andTheNextOneGoesAfterIt() {
        val p = project()
            .addAudio(song(4000, "a.mp3"), atMs = 2000, newId)
            .addAudio(song(30_000, "b.mp3"), atMs = 3000, newId)
        // The second lands where the first ends and fills the rest of the video.
        assertEquals(listOf(2000L to 6000L, 6000L to 20_000L), p.audioPlacements().map { it.startMs to it.endMs })
    }

    @Test
    fun addAudio_noRoomAtTheEnd_triesTheStart() {
        val p = project().addAudio(song(5000), atMs = 20_000, newId)
        assertEquals(0, p.audio.single().startMs)
        val full = project().addAudio(song(60_000), atMs = 0, newId)
        assertSame(full, full.addAudio(song(5000), atMs = 10_000, newId))
    }

    @Test
    fun speed_stretchesTheLaneButNotTheSound() {
        // At 2x, the 20 s of video become 10 s; 10 s of music cover them all.
        val p = project().copy(speed = 2f).addAudio(song(60_000), atMs = 0, newId)
        val clip = p.audio.single()
        assertEquals(10_000, clip.srcEndMs)
        val placement = p.audioPlacements().single()
        assertEquals(20_000, placement.endMs)
        assertEquals(5000, placement.sourceAt(10_000, p.speed))
    }

    @Test
    fun moveAudio_staysBetweenItsNeighboursAndInsideTheVideo() {
        val p = project()
            .addAudio(song(3000, "a.mp3"), atMs = 0, newId)
            .addAudio(song(3000, "b.mp3"), atMs = 10_000, newId)
        val a = p.audio[0].id
        val b = p.audio[1].id
        assertEquals(4000, p.moveAudio(a, 4000).audio.first { it.id == a }.startMs)
        // Not into b, which starts at 10 s.
        assertEquals(7000, p.moveAudio(a, 9000).audio.first { it.id == a }.startMs)
        assertEquals(3000, p.moveAudio(b, 1000).audio.first { it.id == b }.startMs)
        assertEquals(0, p.moveAudio(a, -500).audio.first { it.id == a }.startMs)
    }

    @Test
    fun trimAudio_startKeepsTheSoundInPlace_endFollowsTheFinger() {
        val p = project().addAudio(song(10_000), atMs = 4000, newId)
        val id = p.audio.single().id
        val startTrimmed = p.trimAudio(id, Edge.START, 6000).audio.single()
        assertEquals(6000, startTrimmed.startMs)
        assertEquals(2000, startTrimmed.srcStartMs)
        assertEquals(10_000, startTrimmed.srcEndMs)
        // Dragging the start back out uncovers the beginning again, but not past the file's start.
        val back = p.trimAudio(id, Edge.START, 6000).trimAudio(id, Edge.START, 1000).audio.single()
        assertEquals(4000, back.startMs)
        assertEquals(0, back.srcStartMs)

        val endTrimmed = p.trimAudio(id, Edge.END, 9000).audio.single()
        assertEquals(5000, endTrimmed.srcEndMs)
        // Never longer than the file.
        assertEquals(10_000, p.trimAudio(id, Edge.END, 19_000).trimAudio(id, Edge.END, 30_000).audio.single().srcEndMs)
        // Never shorter than the minimum.
        assertEquals(MIN_AUDIO_MS, p.trimAudio(id, Edge.END, 0).audio.single().srcEndMs)
    }

    @Test
    fun splitAndRemoveAudio() {
        val p = project().addAudio(song(10_000), atMs = 0, newId)
        val id = p.audio.single().id
        val split = p.splitAudio(id, 4000, newId)
        assertEquals(listOf(0L to 4000L, 4000L to 10_000L), split.audio.map { it.srcStartMs to it.srcEndMs })
        assertEquals(listOf(0L, 4000L), split.audio.map { it.startMs })
        assertTrue(split.removeAudio(split.audio[0].id).audio.single().startMs == 4000L)
        assertSame(p, p.splitAudio(id, 50, newId))
    }

    @Test
    fun audioAt_findsWhatPlaysAtAMoment() {
        val p = project().addAudio(song(3000), atMs = 5000, newId)
        assertNull(p.audioAt(4999))
        assertEquals(p.audio.single().id, p.audioAt(5000)?.clip?.id)
        assertEquals(1000L, p.audioAt(6000)?.sourceAt(6000, p.speed))
        assertNull(p.audioAt(8000))
    }

    @Test
    fun placements_cutClipsThatBeginAfterTheVideoWasShortened() {
        val p = project().addAudio(song(60_000), atMs = 5000, newId).trim(0, Edge.END, 8000)
        assertEquals(listOf(5000L to 8000L), p.audioPlacements().map { it.startMs to it.endMs })
        val gone = project().addAudio(song(3000), atMs = 15_000, newId).trim(0, Edge.END, 10_000)
        assertTrue(gone.audioPlacements().isEmpty())
    }

    @Test
    fun setAudioVolume_isKeptInRange() {
        val p = project().addAudio(song(3000), atMs = 0, newId)
        val id = p.audio.single().id
        assertEquals(0.5f, p.setAudioVolume(id, 0.5f).audio.single().volume, 0f)
        assertEquals(0f, p.setAudioVolume(id, -1f).audio.single().volume, 0f)
    }
}
