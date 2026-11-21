package io.github.madtreasures.videoeditor.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.SpeedParameters
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import io.github.madtreasures.videoeditor.model.ExportPlan
import io.github.madtreasures.videoeditor.model.ExportSettings
import io.github.madtreasures.videoeditor.model.Project
import io.github.madtreasures.videoeditor.model.planExport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

sealed interface ExportState {
    data object Idle : ExportState

    /** [progress] is 0..100, or null while it is not known yet. */
    data class Running(val progress: Int?) : ExportState

    data object Saving : ExportState

    data class Done(val uri: Uri, val location: String, val sizeBytes: Long) : ExportState

    data class Failed(val reason: String) : ExportState
}

const val EXPORT_FOLDER = "VideoEditor"

/**
 * How long the exporter may go without writing anything before it gives up. Media3's default of
 * ten seconds is tight for a slow phone that has to decode a long stretch before the first
 * frame of a cut; emulators decode and encode in software and need far longer.
 */
private const val STALL_TIMEOUT_MS = 30_000L
private const val STALL_TIMEOUT_EMULATOR_MS = 15 * 60_000L

/**
 * Runs one export at a time. Lives for the whole process, so an export keeps going when the
 * editor screen is closed; [ExportService] keeps the process alive meanwhile.
 * Everything here is called on the main thread.
 */
@OptIn(UnstableApi::class)
object ExportController {
    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var transformer: Transformer? = null
    private var progressJob: Job? = null
    private var tempFile: File? = null

    val isBusy: Boolean
        get() = _state.value is ExportState.Running || _state.value is ExportState.Saving

    fun start(context: Context, project: Project, settings: ExportSettings) {
        if (isBusy || project.isEmpty) return
        val app = context.applicationContext
        _state.value = ExportState.Running(null)
        try {
            ContextCompat.startForegroundService(app, Intent(app, ExportService::class.java))
        } catch (e: Exception) {
            // Without the service the export still runs for as long as the app stays open.
        }
        val plan = planExport(project, settings)
        run(app, project, settings, plan, passthrough = plan.passthrough)
    }

    fun cancel() {
        if (_state.value !is ExportState.Running) return
        stopTransformer(cancel = true)
        tempFile?.delete()
        _state.value = ExportState.Idle
    }

    /** Acknowledges a finished or failed export. */
    fun reset() {
        if (!isBusy) _state.value = ExportState.Idle
    }

    private fun run(
        app: Context,
        project: Project,
        settings: ExportSettings,
        plan: ExportPlan,
        passthrough: Boolean,
    ) {
        val dir = File(app.cacheDir, "export").apply { mkdirs() }
        val file = File(dir, "export.mp4").apply { delete() }
        tempFile = file

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                stopTransformer(cancel = false)
                _state.value = ExportState.Saving
                scope.launch {
                    _state.value = try {
                        val size = file.length()
                        val (uri, location) = saveToGallery(app, file)
                        ExportState.Done(uri, location, size)
                    } catch (e: Exception) {
                        ExportState.Failed(e.message ?: e.javaClass.simpleName)
                    } finally {
                        file.delete()
                    }
                }
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException,
            ) {
                stopTransformer(cancel = false)
                file.delete()
                if (passthrough) {
                    // Copying the streams as they are did not work for this file; encode instead.
                    scope.launch {
                        if (_state.value is ExportState.Running) {
                            run(app, project, settings, plan, passthrough = false)
                        }
                    }
                } else {
                    val detail = (exportException.cause?.message ?: exportException.message)
                        ?.substringBefore(" DebugTrace")
                    _state.value = ExportState.Failed(
                        listOfNotNull(exportException.errorCodeName, detail).joinToString("\n"),
                    )
                }
            }
        }

        try {
            val created = buildTransformer(app, plan, settings, passthrough, listener)
            transformer = created
            created.start(buildComposition(project, plan, passthrough), file.absolutePath)
        } catch (e: Exception) {
            stopTransformer(cancel = true)
            file.delete()
            _state.value = ExportState.Failed(e.message ?: e.javaClass.simpleName)
            return
        }

        progressJob = scope.launch {
            val holder = ProgressHolder()
            while (true) {
                val current = transformer ?: break
                if (current.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE &&
                    _state.value is ExportState.Running
                ) {
                    _state.value = ExportState.Running(holder.progress.coerceIn(0, 100))
                }
                delay(250)
            }
        }
    }

    private fun stopTransformer(cancel: Boolean) {
        progressJob?.cancel()
        progressJob = null
        val current = transformer
        transformer = null
        if (cancel) runCatching { current?.cancel() }
    }

    private fun buildTransformer(
        app: Context,
        plan: ExportPlan,
        settings: ExportSettings,
        passthrough: Boolean,
        listener: Transformer.Listener,
    ): Transformer {
        val builder = Transformer.Builder(app)
            .addListener(listener)
            .setMaxDelayBetweenMuxerSamplesMs(
                if (Util.isRunningOnEmulator()) STALL_TIMEOUT_EMULATOR_MS else STALL_TIMEOUT_MS,
            )
        if (!passthrough) {
            val encoderSettings = VideoEncoderSettings.Builder().setBitrate(plan.videoBitrate).build()
            builder
                .setVideoMimeType(if (settings.hevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(app)
                        .setRequestedVideoEncoderSettings(encoderSettings)
                        .build(),
                )
        }
        return builder.build()
    }

    private fun buildComposition(project: Project, plan: ExportPlan, passthrough: Boolean): Composition {
        val videoEffects = buildList<Effect> {
            if (project.rotation != 0) {
                // The project stores clockwise degrees, Media3 rotates counterclockwise.
                add(
                    ScaleAndRotateTransformation.Builder()
                        .setRotationDegrees(((360 - project.rotation) % 360).toFloat())
                        .build(),
                )
            }
            if (!passthrough) {
                if (plan.needsScaling) {
                    add(
                        Presentation.createForWidthAndHeight(
                            plan.width, plan.height, Presentation.LAYOUT_SCALE_TO_FIT,
                        ),
                    )
                }
                if (project.speed > 1f) {
                    // Faster playback would otherwise multiply the frame rate.
                    add(FrameDropEffect.createDefaultFrameDropEffect(plan.frameRate))
                }
            }
        }
        val effects = Effects(emptyList(), videoEffects)

        val items = project.clips.map { clip ->
            val clipping = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(clip.startMs)
                .apply { if (clip.endMs < clip.source.durationMs) setEndPositionMs(clip.endMs) }
                .build()
            val mediaItem = MediaItem.Builder()
                .setUri(clip.source.uri)
                .setClippingConfiguration(clipping)
                .build()
            EditedMediaItem.Builder(mediaItem)
                .setEffects(effects)
                .apply {
                    if (project.speed != 1f) {
                        setSpeed(SpeedParameters(ConstantSpeed(project.speed), /* shouldMaintainPitch= */ true))
                    }
                }
                .build()
        }

        val sequence = if (project.hasAudio) {
            EditedMediaItemSequence.withAudioAndVideoFrom(items)
        } else {
            EditedMediaItemSequence.withVideoFrom(items)
        }
        // Re-encoded video is written as standard dynamic range so it plays everywhere.
        val hdrMode = if (!passthrough && Build.VERSION.SDK_INT >= 29) {
            Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
        } else {
            Composition.HDR_MODE_KEEP_HDR
        }
        return Composition.Builder(sequence).setHdrMode(hdrMode).build()
    }

    private class ConstantSpeed(private val speed: Float) : SpeedProvider {
        override fun getSpeed(timeUs: Long): Float = speed
        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
    }

    private suspend fun saveToGallery(app: Context, file: File): Pair<Uri, String> =
        withContext(Dispatchers.IO) {
            val name = "VID_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
            val location = "${Environment.DIRECTORY_MOVIES}/$EXPORT_FOLDER/$name"
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = app.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_MOVIES}/$EXPORT_FOLDER",
                    )
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = resolver.insert(collection, values)
                    ?: throw IOException("MediaStore insert failed")
                try {
                    val out = resolver.openOutputStream(uri)
                        ?: throw IOException("MediaStore open failed")
                    out.use { target -> file.inputStream().use { it.copyTo(target, 1 shl 16) } }
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (e: Exception) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw e
                }
                uri to location
            } else {
                @Suppress("DEPRECATION")
                val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                val dir = File(movies, EXPORT_FOLDER).apply { mkdirs() }
                val target = File(dir, name)
                file.copyTo(target, overwrite = true)
                val uri = suspendCancellableCoroutine<Uri?> { continuation ->
                    MediaScannerConnection.scanFile(
                        app, arrayOf(target.absolutePath), arrayOf("video/mp4"),
                    ) { _, scanned -> continuation.resume(scanned) }
                } ?: throw IOException("Media scan failed")
                uri to location
            }
        }
}
