package io.github.madtreasures.videoeditor

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.madtreasures.videoeditor.export.ExportController
import io.github.madtreasures.videoeditor.export.ExportState
import io.github.madtreasures.videoeditor.media.ThumbnailLoader
import io.github.madtreasures.videoeditor.media.Waveforms
import io.github.madtreasures.videoeditor.media.probeAudio
import io.github.madtreasures.videoeditor.media.probeVideo
import io.github.madtreasures.videoeditor.model.AudioClip
import io.github.madtreasures.videoeditor.model.Clip
import io.github.madtreasures.videoeditor.model.Edge
import io.github.madtreasures.videoeditor.model.ExportSettings
import io.github.madtreasures.videoeditor.model.Project
import io.github.madtreasures.videoeditor.model.VideoSource
import io.github.madtreasures.videoeditor.model.addAudio
import io.github.madtreasures.videoeditor.model.audioAt
import io.github.madtreasures.videoeditor.model.canSplitAudioAt
import io.github.madtreasures.videoeditor.model.moveAudio
import io.github.madtreasures.videoeditor.model.removeAudio
import io.github.madtreasures.videoeditor.model.setAudioVolume
import io.github.madtreasures.videoeditor.model.splitAudio
import io.github.madtreasures.videoeditor.model.trimAudio
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * A short notice for the user. With [offerFileBrowser] it comes with a button that opens the
 * file browser, as another way to get at a video that could not be opened.
 */
data class UiMessage(
    @StringRes val text: Int,
    val arg: String? = null,
    val offerFileBrowser: Boolean = false,
)

private const val MAX_UNDO = 100
private const val MAX_ZOOM_DP_PER_SECOND = 400f
private const val INITIAL_MAX_ZOOM_DP_PER_SECOND = 120f

/** Roughly one frame; used to show the last frame that is still inside a clip. */
private const val FRAME_MS = 34L

@OptIn(UnstableApi::class)
class EditorViewModel(app: Application) : AndroidViewModel(app) {

    var project by mutableStateOf(Project())
        private set

    /** Playhead, in project time. */
    var positionMs by mutableLongStateOf(0L)
        private set

    var isPlaying by mutableStateOf(false)
        private set

    var isLoading by mutableStateOf(false)
        private set

    var canUndo by mutableStateOf(false)
        private set

    var canRedo by mutableStateOf(false)
        private set

    /** Timeline scale in dp per second of video; null until the timeline has been laid out. */
    var zoom by mutableStateOf<Float?>(null)
        private set

    /** Clip and edge whose trim handle is being dragged right now. */
    private var activeTrim by mutableStateOf<Pair<Int, Edge>?>(null)

    /** The audio clip on the lane the tools act on, if one is selected. */
    var selectedAudioId by mutableStateOf<Long?>(null)
        private set

    val selectedAudio: AudioClip? by derivedStateOf {
        selectedAudioId?.let { id -> project.audio.firstOrNull { it.id == id } }
    }

    /** The clip the tools act on: the one under the playhead. */
    val currentClipIndex: Int by derivedStateOf {
        activeTrim?.first ?: project.locate(positionMs).index
    }

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    val exportState: StateFlow<ExportState> = ExportController.state

    val thumbnails = ThumbnailLoader(app, viewModelScope)

    val waveforms = Waveforms(app, viewModelScope)

    val player: ExoPlayer = ExoPlayer.Builder(app)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus= */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    /**
     * Plays the audio lane during preview, kept in step with [player]. It does not take audio
     * focus itself; it simply stops whenever the video stops.
     */
    private val lanePlayer: ExoPlayer = ExoPlayer.Builder(app)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus= */ false,
        )
        .build()

    /** The lane clip [lanePlayer] has loaded, with the part of its file. */
    private var laneLoaded: Triple<Long, Long, Long>? = null
    private var laneLastSeekAt = 0L

    /** The project as it was when a drag on the audio lane began, for undo. */
    private var projectBeforeLiveEdit: Project? = null

    private val undoStack = ArrayDeque<Project>()
    private val redoStack = ArrayDeque<Project>()
    private var nextClipId = 1L

    /** The clip list the player's playlist was last built from. */
    private var syncedClips: List<Clip>? = null
    private var projectBeforeTrim: Project? = null

    /** The moment of the trimmed clip's file that the playhead showed when the trim began. */
    private var playheadSourceMs = 0L

    /** Room the timeline has for the clips, in dp; see [initZoom]. */
    private var fitWidthDp = 300f
    private var scrubbing = false

    private val playerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = updatePlaying()

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && activeTrim == null && player.playWhenReady) {
                player.pause()
                positionMs = project.durationMs
            }
            updatePlaying()
        }

        override fun onPlayerError(error: PlaybackException) {
            _messages.tryEmit(UiMessage(R.string.msg_playback_error, error.errorCodeName))
        }
    }

    init {
        player.addListener(playerListener)
    }

    private fun updatePlaying() {
        isPlaying = player.playWhenReady &&
            player.playbackState != Player.STATE_ENDED &&
            player.playbackState != Player.STATE_IDLE
        syncLane()
    }

    // ---- Opening videos ----

    fun addVideos(uris: List<Uri>) {
        if (uris.isEmpty() || isLoading) return
        viewModelScope.launch {
            isLoading = true
            val sources = ArrayList<VideoSource>()
            var failed = false
            for (uri in uris) {
                val source = probeVideo(getApplication(), uri)
                if (source != null) sources += source else failed = true
            }
            isLoading = false
            if (failed) _messages.tryEmit(UiMessage(R.string.msg_open_failed, offerFileBrowser = true))
            if (sources.isEmpty()) return@launch

            val appended = project.append(sources) { nextClipId++ }
            if (project.isEmpty) {
                undoStack.clear()
                redoStack.clear()
                updateUndoState()
                zoom = null
                positionMs = 0
                project = appended
                syncPlayer()
            } else {
                // Jump to the first new clip.
                positionMs = project.durationMs
                commit(appended)
            }
        }
    }

    // ---- Audio lane ----

    /** Puts audio files on the lane at the playhead, one after another. */
    fun addAudio(uris: List<Uri>) {
        if (uris.isEmpty() || isLoading || project.isEmpty) return
        viewModelScope.launch {
            isLoading = true
            player.pause()
            var updated = project
            var at = positionMs
            var failed = false
            var noRoom = false
            var lastAdded: Long? = null
            for (uri in uris) {
                val source = probeAudio(getApplication(), uri)
                if (source == null) {
                    failed = true
                    continue
                }
                val next = updated.addAudio(source, at) { nextClipId++ }
                if (next === updated) {
                    noRoom = true
                    continue
                }
                val added = next.audio.first { clip -> updated.audio.none { it.id == clip.id } }
                waveforms.request(source.uri)
                lastAdded = added.id
                at = added.endMs(next.speed)
                updated = next
            }
            isLoading = false
            if (failed) _messages.tryEmit(UiMessage(R.string.msg_audio_failed))
            if (noRoom) _messages.tryEmit(UiMessage(R.string.msg_audio_no_room))
            if (updated !== project) {
                commit(updated)
                selectedAudioId = lastAdded
            }
        }
    }

    fun selectAudio(id: Long?) {
        selectedAudioId = id?.takeIf { wanted -> project.audio.any { it.id == wanted } }
    }

    /** Starts a drag on the audio lane: the changes it makes are undone as one step. */
    fun audioEditStart(id: Long) {
        if (project.audio.none { it.id == id }) return
        scrubEnd()
        player.pause()
        selectedAudioId = id
        projectBeforeLiveEdit = project
    }

    fun audioMoveTo(id: Long, startMs: Long) {
        if (projectBeforeLiveEdit == null) return
        project = project.moveAudio(id, startMs)
    }

    fun audioTrimTo(id: Long, edge: Edge, valueMs: Long) {
        if (projectBeforeLiveEdit == null) return
        project = project.trimAudio(id, edge, valueMs)
    }

    fun audioEditEnd() {
        val before = projectBeforeLiveEdit ?: return
        projectBeforeLiveEdit = null
        if (before != project) pushUndo(before)
    }

    /** Changes the volume while the slider moves; [commitAudioVolume] makes it one undo step. */
    fun previewAudioVolume(id: Long, volume: Float) {
        if (projectBeforeLiveEdit == null) projectBeforeLiveEdit = project
        project = project.setAudioVolume(id, volume)
        syncLane()
    }

    fun commitAudioVolume() = audioEditEnd()

    val canSplitAudio: Boolean by derivedStateOf {
        selectedAudio?.let { project.canSplitAudioAt(it.id, positionMs) } == true
    }

    fun splitSelectedAudio() {
        val id = selectedAudioId ?: return
        val updated = project.splitAudio(id, positionMs) { nextClipId++ }
        if (updated === project) return
        commit(updated)
        // The second part starts at the playhead; it stays selected.
        selectedAudioId = updated.audio.firstOrNull { it.startMs == positionMs }?.id
    }

    fun deleteSelectedAudio() {
        val id = selectedAudioId ?: return
        selectedAudioId = null
        commit(project.removeAudio(id))
    }

    fun closeProject() {
        player.stop()
        player.clearMediaItems()
        syncedClips = null
        activeTrim = null
        projectBeforeTrim = null
        scrubbing = false
        undoStack.clear()
        redoStack.clear()
        updateUndoState()
        project = Project()
        positionMs = 0
        zoom = null
        selectedAudioId = null
        projectBeforeLiveEdit = null
        lanePlayer.stop()
        lanePlayer.clearMediaItems()
        laneLoaded = null
        thumbnails.retain(emptySet())
    }

    // ---- Playback ----

    fun togglePlay() {
        if (project.isEmpty || activeTrim != null) return
        if (isPlaying) {
            player.pause()
            return
        }
        scrubEnd()
        if (positionMs >= project.durationMs - 2 * FRAME_MS) {
            positionMs = 0
            seekPlayer(0)
        }
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    fun pause() {
        player.pause()
    }

    /** Called once per display frame while playing, to move the playhead with the video. */
    fun onFrame() {
        if (!isPlaying || activeTrim != null) return
        val index = player.currentMediaItemIndex
        val clip = project.clips.getOrNull(index) ?: return
        positionMs = project.clipStartMs(index) + player.currentPosition.coerceIn(0, clip.durationMs)
        syncLane()
    }

    /**
     * Makes [lanePlayer] play what the audio lane holds at the playhead, or be quiet. Called on
     * every frame while playing and whenever playback starts or stops.
     */
    private fun syncLane() {
        val placement = if (isPlaying && activeTrim == null) project.audioAt(positionMs) else null
        if (placement == null) {
            if (lanePlayer.playWhenReady) lanePlayer.pause()
            return
        }
        val clip = placement.clip
        val wanted = placement.sourceAt(positionMs, project.speed) - clip.srcStartMs
        val spec = Triple(clip.id, clip.srcStartMs, clip.srcEndMs)
        if (laneLoaded != spec) {
            val clipping = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(clip.srcStartMs)
                .apply { if (clip.srcEndMs < clip.source.durationMs) setEndPositionMs(clip.srcEndMs) }
                .build()
            lanePlayer.setMediaItem(
                MediaItem.Builder().setUri(clip.source.uri).setClippingConfiguration(clipping).build(),
                wanted,
            )
            lanePlayer.prepare()
            laneLoaded = spec
            laneLastSeekAt = SystemClock.elapsedRealtime()
        } else {
            val now = SystemClock.elapsedRealtime()
            // Correct drift, but not so often that the sound stutters.
            if (abs(lanePlayer.currentPosition - wanted) > 200 && now - laneLastSeekAt > 1000) {
                lanePlayer.seekTo(wanted)
                laneLastSeekAt = now
            }
        }
        lanePlayer.volume = clip.volume.coerceIn(0f, 1f)
        if (!lanePlayer.playWhenReady) lanePlayer.play()
    }

    private fun seekPlayer(timeMs: Long) {
        if (project.isEmpty) return
        val (index, offset) = project.locate(timeMs)
        // Stay just inside the clip so the player shows its last frame instead of ending.
        val last = max(0, project.clips[index].durationMs - 1)
        player.seekTo(index, min(offset, last))
    }

    // ---- Timeline gestures ----

    fun scrubTo(timeMs: Long) {
        if (project.isEmpty || activeTrim != null) return
        if (!scrubbing) {
            scrubbing = true
            player.pause()
            player.setScrubbingModeEnabled(true)
        }
        positionMs = timeMs.coerceIn(0, project.durationMs)
        seekPlayer(positionMs)
    }

    fun scrubEnd() {
        if (!scrubbing) return
        scrubbing = false
        player.setScrubbingModeEnabled(false)
    }

    /**
     * While a trim handle is dragged the player shows the clip's whole source file, so the frame
     * under the handle can be shown even where it lies outside the clip's current range.
     *
     * The playhead is not part of the trim: it stays on the frame it was on. Only if that frame
     * is cut away does it move, to the nearest frame the clip still has.
     */
    fun trimStart(index: Int, edge: Edge) {
        val clip = project.clips.getOrNull(index) ?: return
        if (activeTrim != null) return
        scrubEnd()
        player.pause()
        projectBeforeTrim = project
        playheadSourceMs = clip.startMs + (positionMs - project.clipStartMs(index)).coerceIn(0, clip.durationMs)
        activeTrim = index to edge
        syncedClips = null
        player.setMediaItem(MediaItem.fromUri(clip.source.uri), trimPreviewMs(clip, edge))
        player.prepare()
        player.setScrubbingModeEnabled(true)
    }

    fun trimTo(index: Int, edge: Edge, valueMs: Long) {
        if (activeTrim != index to edge) return
        val updated = project.trim(index, edge, valueMs)
        if (updated === project) return
        project = updated
        positionMs = updated.timeOfSource(index, playheadSourceMs)
        player.seekTo(trimPreviewMs(updated.clips[index], edge))
    }

    fun trimEnd() {
        val (index, _) = activeTrim ?: return
        val before = projectBeforeTrim
        activeTrim = null
        projectBeforeTrim = null
        player.setScrubbingModeEnabled(false)
        if (before != null && before != project) pushUndo(before)
        positionMs = project.timeOfSource(index, playheadSourceMs)
        // Back from the frame under the handle to the frame under the playhead.
        syncPlayer()
    }

    private fun trimPreviewMs(clip: Clip, edge: Edge): Long = when (edge) {
        Edge.START -> clip.startMs
        Edge.END -> max(clip.startMs, clip.endMs - FRAME_MS)
    }

    /**
     * Tells the view model how many dp the clips have to spread out in. The first time for a
     * project, the zoom is chosen so that all of it fits.
     */
    fun initZoom(usableWidthDp: Float) {
        fitWidthDp = usableWidthDp
        if (zoom != null || project.isEmpty) return
        zoom = min(fitZoom(), INITIAL_MAX_ZOOM_DP_PER_SECOND)
    }

    fun zoomBy(factor: Float) {
        val current = zoom ?: return
        // Zooming out stops where the whole project fits. A project that was shortened since
        // may already be smaller than that; then it is left as it is.
        val lowest = min(current, min(fitZoom(), INITIAL_MAX_ZOOM_DP_PER_SECOND))
        zoom = (current * factor).coerceIn(lowest, max(lowest, MAX_ZOOM_DP_PER_SECOND))
    }

    /** The zoom at which the project is exactly as wide as the timeline has room for. */
    private fun fitZoom(): Float = fitWidthDp / max(0.1f, project.durationMs / 1000f)

    // ---- Edits ----

    val canSplit: Boolean by derivedStateOf { project.canSplitAt(positionMs) }

    fun split() {
        // The playhead stays where it is, which is now the start of the second part.
        commit(project.splitAt(positionMs) { nextClipId++ })
    }

    fun deleteCurrentClip() {
        if (project.clips.size <= 1) return
        val index = currentClipIndex
        positionMs = project.clipStartMs(index)
        commit(project.removeClip(index))
    }

    fun moveCurrentClip(delta: Int) {
        val index = currentClipIndex
        val moved = project.moveClip(index, delta)
        if (moved === project) return
        val offset = positionMs - project.clipStartMs(index)
        positionMs = moved.clipStartMs(index + delta) + offset
        commit(moved)
    }

    fun rotate() = commit(project.rotatedClockwise())

    fun toggleMute() = commit(project.copy(muted = !project.muted))

    fun setSpeed(speed: Float) = commit(project.copy(speed = speed))

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(project)
        restore(previous)
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(project)
        restore(next)
    }

    private fun commit(updated: Project) {
        if (updated == project) return
        pushUndo(project)
        project = updated
        positionMs = positionMs.coerceIn(0, updated.durationMs)
        syncPlayer()
    }

    private fun pushUndo(previous: Project) {
        undoStack.addLast(previous)
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
        updateUndoState()
    }

    private fun restore(snapshot: Project) {
        project = snapshot
        positionMs = positionMs.coerceIn(0, snapshot.durationMs)
        updateUndoState()
        syncPlayer()
    }

    private fun updateUndoState() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    /** Makes the player's playlist, speed and volume match the project. */
    private fun syncPlayer() {
        val current = project
        if (current.isEmpty) {
            player.stop()
            player.clearMediaItems()
            syncedClips = null
            return
        }
        if (syncedClips != current.clips) {
            val (index, offset) = current.locate(positionMs)
            val last = max(0, current.clips[index].durationMs - 1)
            player.setMediaItems(current.clips.map(::toMediaItem), index, min(offset, last))
            syncedClips = current.clips
            if (!ExportController.isBusy) player.prepare()
        }
        player.setPlaybackSpeed(current.speed)
        player.volume = if (current.muted) 0f else 1f
        current.audio.forEach { waveforms.request(it.source.uri) }
        syncLane()
    }

    private fun toMediaItem(clip: Clip): MediaItem {
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(clip.startMs)
            .apply { if (clip.endMs < clip.source.durationMs) setEndPositionMs(clip.endMs) }
            .build()
        return MediaItem.Builder()
            .setUri(clip.source.uri)
            .setMediaId(clip.id.toString())
            .setClippingConfiguration(clipping)
            .build()
    }

    // ---- Export ----

    fun startExport(settings: ExportSettings) {
        if (project.isEmpty) return
        scrubEnd()
        // Free the preview's decoders; the exporter needs the hardware codecs.
        player.stop()
        lanePlayer.stop()
        laneLoaded = null
        ExportController.start(getApplication(), project, settings)
    }

    fun cancelExport() {
        ExportController.cancel()
        resumePreview()
    }

    fun dismissExport() {
        ExportController.reset()
        resumePreview()
    }

    private fun resumePreview() {
        if (!project.isEmpty && player.playbackState == Player.STATE_IDLE) player.prepare()
    }

    override fun onCleared() {
        player.removeListener(playerListener)
        player.release()
        lanePlayer.release()
        thumbnails.close()
    }
}
