package io.github.madtreasures.videoeditor.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.SpeedParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.AudioEncoderSettings
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
import io.github.madtreasures.videoeditor.model.Container
import io.github.madtreasures.videoeditor.model.ExportPlan
import io.github.madtreasures.videoeditor.model.ExportSettings
import io.github.madtreasures.videoeditor.model.Project
import io.github.madtreasures.videoeditor.model.VideoCodec
import io.github.madtreasures.videoeditor.model.audioPlacements
import io.github.madtreasures.videoeditor.model.hasLaneAudio
import io.github.madtreasures.videoeditor.model.planExport
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.max
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

sealed interface ExportState {
    data object Idle : ExportState

    /** [progress] is 0..100, or null while it is not known yet. */
    data class Running(val progress: Int?) : ExportState

    data object Saving : ExportState

    /**
     * @param fallbackFrom the codec that was chosen but could not be used on this device, if the
     *   video was saved with another one instead.
     */
    data class Done(
        val uri: Uri,
        val location: String,
        val sizeBytes: Long,
        val mimeType: String,
        val videoCodec: VideoCodec? = null,
        val fallbackFrom: VideoCodec? = null,
    ) : ExportState

    data class Failed(val reason: String) : ExportState
}

const val EXPORT_FOLDER = "VideoEditor"
private const val TAG = "VideoEditorExport"

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

    /** Set by the activity. While false, the result of an export is announced as a notification. */
    var appVisible = false

    fun start(context: Context, project: Project, settings: ExportSettings) {
        if (isBusy || project.isEmpty) return
        val app = context.applicationContext
        _state.value = ExportState.Running(null)
        try {
            ContextCompat.startForegroundService(app, Intent(app, ExportService::class.java))
        } catch (e: Exception) {
            // Without the service the export still runs for as long as the app stays open.
        }
        val plan = planExport(project, settings, encodableCodecs)
        if (!plan.canExport) {
            _state.value = ExportState.Failed("Nothing to export")
            return
        }
        triedCodecs.clear()
        run(app, project, settings, plan, passthrough = plan.passthrough)
    }

    /** Video codecs that failed to start in the current export; see [nextCodec]. */
    private val triedCodecs = ArrayList<VideoCodec>()

    /**
     * The codec to try when the video encoder for the current one fails, or null: the next one
     * the file type takes and this device has, ending with the most widely supported.
     */
    private fun nextCodec(plan: ExportPlan): VideoCodec? = plan.container.videoCodecs
        .filter { it !in triedCodecs && it.mime in encodableCodecs }
        .minByOrNull { if (it == VideoCodec.H264) 0 else 1 + plan.container.videoCodecs.indexOf(it) }

    /**
     * True if the video encoder itself failed, however Media3 classified it: an encoder that
     * dies while starting surfaces as an init error, an encoding error, or (when MediaCodec has
     * already released it) as a failed state check.
     */
    private fun isVideoEncoderFailure(e: ExportException): Boolean {
        val codec = e.codecInfo
        return codec != null && codec.isVideo && !codec.isDecoder
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
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "export." + plan.container.extension)
        tempFile = file

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                stopTransformer(cancel = false)
                _state.value = ExportState.Saving
                scope.launch {
                    _state.value = try {
                        withContext(Dispatchers.IO) { patchFileType(file, plan.container) }
                        val size = file.length()
                        val (uri, location) = saveToGallery(app, file, plan.container)
                        ExportState.Done(
                            uri, location, size, plan.container.mimeType,
                            videoCodec = plan.videoCodec,
                            fallbackFrom = triedCodecs.firstOrNull()?.takeIf { it != plan.videoCodec },
                        )
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
                Log.w(TAG, "Export failed (passthrough=$passthrough)", exportException)
                val failedCodec = plan.videoCodec
                val fallback = if (!passthrough && failedCodec != null && isVideoEncoderFailure(exportException)) {
                    triedCodecs += failedCodec
                    nextCodec(plan)
                } else {
                    null
                }
                when {
                    passthrough -> {
                        // Copying the streams as they are did not work for this file; encode instead.
                        scope.launch {
                            if (_state.value is ExportState.Running) {
                                _state.value = ExportState.Running(null)
                                run(app, project, settings, plan, passthrough = false)
                            }
                        }
                    }
                    fallback != null -> {
                        // This device's encoder for the chosen codec does not work; use another
                        // one the file type takes.
                        Log.w(TAG, "${failedCodec?.label} encoder failed, trying ${fallback.label}")
                        val retry = planExport(project, settings.copy(videoCodec = fallback), encodableCodecs)
                        scope.launch {
                            if (_state.value is ExportState.Running) {
                                _state.value = ExportState.Running(null)
                                run(app, project, settings.copy(videoCodec = fallback), retry, passthrough = false)
                            }
                        }
                    }
                    else -> _state.value = ExportState.Failed(describe(exportException))
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

    /** Media3's error code plus the message of the underlying cause, for the error dialog. */
    private fun describe(exception: ExportException): String {
        var root: Throwable = exception
        while (true) root = root.cause?.takeIf { it !== root } ?: break
        val detail = (root.message ?: root.javaClass.simpleName)
            .substringBefore(" DebugTrace")
            .trim()
        return listOf(exception.errorCodeName, detail).filter { it.isNotEmpty() }.joinToString("\n")
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
        @Suppress("UNUSED_PARAMETER") settings: ExportSettings,
        passthrough: Boolean,
        listener: Transformer.Listener,
    ): Transformer {
        val builder = Transformer.Builder(app)
            .addListener(listener)
            .setMaxDelayBetweenMuxerSamplesMs(
                if (Util.isRunningOnEmulator()) STALL_TIMEOUT_EMULATOR_MS else STALL_TIMEOUT_MS,
            )
        muxerFactoryFor(plan.container)?.let(builder::setMuxerFactory)
        // Matroska files have no rotation flag: a portrait picture is stored as portrait.
        if (plan.portraitEncoding) builder.setPortraitEncodingEnabled(true)
        plan.videoCodec?.let { if (plan.hasVideo) builder.setVideoMimeType(it.mime) }
        if (plan.hasAudio) builder.setAudioMimeType(plan.container.audioCodec.mime)

        // Requested encoder settings make Media3 re-encode even a stream it could copy, so they
        // are only given for what is re-encoded anyway.
        val encoders = DefaultEncoderFactory.Builder(app)
        if (!passthrough && plan.hasVideo) {
            encoders.setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(plan.videoBitrate).build(),
            )
        }
        if (plan.hasAudio && !(passthrough && plan.audioCopied)) {
            encoders.setRequestedAudioEncoderSettings(
                AudioEncoderSettings.Builder().setBitrate(plan.audioBitrate).build(),
            )
        }
        builder.setEncoderFactory(encoders.build())
        return builder.build()
    }

    private fun buildComposition(project: Project, plan: ExportPlan, passthrough: Boolean): Composition {
        val videoEffects = buildList<Effect> {
            if (!plan.hasVideo) return@buildList
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
                val keepEvery = project.speed.toInt()
                if (keepEvery >= 2) {
                    // Faster playback would otherwise multiply the frame rate. This keeps every
                    // n-th frame; Media3's default frame dropper fails with a GL error when a
                    // sequence moves on to its next clip.
                    add(
                        FrameDropEffect.createSimpleFrameDropEffect(
                            /* expectedFrameRate= */ plan.frameRate * keepEvery,
                            /* targetFrameRate= */ plan.frameRate,
                        ),
                    )
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

        // The video's own sound and the added audio are separate sequences that Media3 mixes.
        val withOwnSound = plan.hasAudio && project.hasAudio
        val sequences = ArrayList<EditedMediaItemSequence>(2)
        when {
            plan.hasVideo && withOwnSound -> sequences += EditedMediaItemSequence.withAudioAndVideoFrom(items)
            plan.hasVideo -> sequences += EditedMediaItemSequence.withVideoFrom(items)
            withOwnSound -> sequences += EditedMediaItemSequence.withAudioFrom(items)
        }
        if (plan.hasAudio && project.hasLaneAudio) sequences += audioLane(project)

        // Re-encoded video is written as standard dynamic range so it plays everywhere.
        val hdrMode = if (plan.hasVideo && !passthrough && Build.VERSION.SDK_INT >= 29) {
            Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
        } else {
            Composition.HDR_MODE_KEEP_HDR
        }
        return Composition.Builder(sequences).setHdrMode(hdrMode).build()
    }

    /**
     * The added audio as one sequence on the output's time axis: silence until a clip begins,
     * then the part of the file that is heard. Added audio keeps its own speed.
     */
    private fun audioLane(project: Project): EditedMediaItemSequence {
        val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
        var cursorUs = 0L
        for (placement in project.audioPlacements()) {
            val clip = placement.clip
            val sourceEnd = placement.sourceEnd(project.speed)
            val lengthUs = (sourceEnd - clip.srcStartMs) * 1000
            if (lengthUs <= 0) continue
            val startUs = (placement.startMs * 1000.0 / project.speed).toLong()
            if (startUs > cursorUs) builder.addGap(startUs - cursorUs)
            val clipping = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(clip.srcStartMs)
                .apply { if (sourceEnd < clip.source.durationMs) setEndPositionMs(sourceEnd) }
                .build()
            val item = EditedMediaItem.Builder(
                MediaItem.Builder().setUri(clip.source.uri).setClippingConfiguration(clipping).build(),
            )
            if (clip.volume != 1f) {
                item.setEffects(Effects(listOf(volume(clip.volume)), emptyList()))
            }
            builder.addItem(item.build())
            cursorUs = max(cursorUs, startUs) + lengthUs
        }
        return builder.build()
    }

    /** Scales every channel by [gain], for files with up to eight channels. */
    private fun volume(gain: Float): AudioProcessor = ChannelMixingAudioProcessor().apply {
        for (channels in 1..8) {
            putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(channels, channels).scaleBy(gain))
        }
    }

    private class ConstantSpeed(private val speed: Float) : SpeedProvider {
        override fun getSpeed(timeUs: Long): Float = speed
        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
    }

    private suspend fun saveToGallery(app: Context, file: File, container: Container): Pair<Uri, String> =
        withContext(Dispatchers.IO) {
            val audio = container.audioOnly
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val name = (if (audio) "AUD_" else "VID_") + stamp + "." + container.extension
            val folder = (if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) +
                "/" + EXPORT_FOLDER
            val location = "$folder/$name"
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = app.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, container.mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val collection = if (audio) {
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }
                val uri = resolver.insert(collection, values)
                    ?: throw IOException("MediaStore insert failed")
                try {
                    val out = resolver.openOutputStream(uri)
                        ?: throw IOException("MediaStore open failed")
                    out.use { target -> file.inputStream().use { it.copyTo(target, 1 shl 16) } }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (e: Exception) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw e
                }
                uri to location
            } else {
                @Suppress("DEPRECATION")
                val base = Environment.getExternalStoragePublicDirectory(
                    if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES,
                )
                val dir = File(base, EXPORT_FOLDER).apply { mkdirs() }
                val target = File(dir, name)
                file.copyTo(target, overwrite = true)
                val uri = suspendCancellableCoroutine<Uri?> { continuation ->
                    MediaScannerConnection.scanFile(
                        app, arrayOf(target.absolutePath), arrayOf(container.mimeType),
                    ) { _, scanned -> continuation.resume(scanned) }
                } ?: throw IOException("Media scan failed")
                uri to location
            }
        }
}
