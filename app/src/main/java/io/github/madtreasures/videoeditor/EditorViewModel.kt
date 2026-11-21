package io.github.madtreasures.videoeditor

import android.app.Application
import android.net.Uri
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
import io.github.madtreasures.videoeditor.media.probeVideo
import io.github.madtreasures.videoeditor.model.Clip
import io.github.madtreasures.videoeditor.model.Edge
import io.github.madtreasures.videoeditor.model.ExportSettings
import io.github.madtreasures.videoeditor.model.Project
import io.github.madtreasures.videoeditor.model.VideoSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

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

    /** The clip the tools act on: the one under the playhead. */
    val currentClipIndex: Int by derivedStateOf {
        activeTrim?.first ?: project.locate(positionMs).index
    }

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    val exportState: StateFlow<ExportState> = ExportController.state

    val thumbnails = ThumbnailLoader(app, viewModelScope)

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

    private val undoStack = ArrayDeque<Project>()
    private val redoStack = ArrayDeque<Project>()
    private var nextClipId = 1L

    /** The clip list the player's playlist was last built from. */
    private var syncedClips: List<Clip>? = null
    private var projectBeforeTrim: Project? = null
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
     */
    fun trimStart(index: Int, edge: Edge) {
        val clip = project.clips.getOrNull(index) ?: return
        if (activeTrim != null) return
        scrubEnd()
        player.pause()
        projectBeforeTrim = project
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
        player.seekTo(trimPreviewMs(updated.clips[index], edge))
    }

    fun trimEnd() {
        val (index, edge) = activeTrim ?: return
        val before = projectBeforeTrim
        activeTrim = null
        projectBeforeTrim = null
        player.setScrubbingModeEnabled(false)
        if (before != null && before != project) pushUndo(before)
        val clipStart = project.clipStartMs(index)
        positionMs = when (edge) {
            Edge.START -> clipStart
            Edge.END -> max(clipStart, clipStart + project.clips[index].durationMs - FRAME_MS)
        }
        syncPlayer()
    }

    private fun trimPreviewMs(clip: Clip, edge: Edge): Long = when (edge) {
        Edge.START -> clip.startMs
        Edge.END -> max(clip.startMs, clip.endMs - FRAME_MS)
    }

    fun initZoom(timelineWidthDp: Float) {
        if (zoom != null || project.isEmpty) return
        val seconds = project.durationMs / 1000f
        zoom = (timelineWidthDp * 0.85f / seconds).coerceIn(minZoom(), INITIAL_MAX_ZOOM_DP_PER_SECOND)
    }

    fun zoomBy(factor: Float) {
        val current = zoom ?: return
        zoom = (current * factor).coerceIn(minZoom(), MAX_ZOOM_DP_PER_SECOND)
    }

    /** Never let the whole project shrink below about a third of a phone screen. */
    private fun minZoom(): Float =
        min(120f / max(0.1f, project.durationMs / 1000f), INITIAL_MAX_ZOOM_DP_PER_SECOND)

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
        // Free the preview's decoder; the exporter needs the hardware codecs.
        player.stop()
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
        thumbnails.close()
    }
}
