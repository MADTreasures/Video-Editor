package io.github.madtreasures.videoeditor.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.inspector.MetadataRetriever
import io.github.madtreasures.videoeditor.model.VideoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private const val PROBE_TIMEOUT_MS = 20_000L
private const val TAG = "VideoEditorProbe"

/**
 * Reads what the editor needs to know about a video file. Returns null if the file has no
 * video track or cannot be read at all.
 */
@OptIn(UnstableApi::class)
suspend fun probeVideo(context: Context, uri: Uri): VideoSource? = withContext(Dispatchers.IO) {
    var video: Format? = null
    var hasAudio = false
    var durationMs = C.TIME_UNSET

    // Media3 reads every container the player and the exporter can handle.
    try {
        withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            MetadataRetriever.Builder(context, MediaItem.fromUri(uri)).build().use { retriever ->
                val groups = retriever.retrieveTrackGroups().await()
                for (g in 0 until groups.length) {
                    val group = groups[g]
                    for (i in 0 until group.length) {
                        val format = group.getFormat(i)
                        val mime = format.sampleMimeType
                        if (video == null && MimeTypes.isVideo(mime)) video = format
                        if (MimeTypes.isAudio(mime)) hasAudio = true
                    }
                }
                val durationUs = retriever.retrieveDurationUs().await()
                if (durationUs != C.TIME_UNSET) durationMs = durationUs / 1000
            }
        }
    } catch (e: Exception) {
        // Fall through to the platform retriever below.
        Log.w(TAG, "Media3 could not read $uri", e)
    }

    var width = video?.width ?: Format.NO_VALUE
    var height = video?.height ?: Format.NO_VALUE
    var rotation = video?.rotationDegrees ?: 0
    val pixelRatio = video?.pixelWidthHeightRatio?.takeIf { it > 0f } ?: 1f
    var frameRate = video?.frameRate ?: Format.NO_VALUE.toFloat()

    if (video == null || durationMs <= 0 || width <= 0 || height <= 0) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            fun meta(key: Int) = retriever.extractMetadata(key)
            if (meta(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") {
                Log.w(TAG, "No video track in $uri")
                return@withContext null
            }
            if (durationMs <= 0) {
                durationMs = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            }
            if (width <= 0 || height <= 0) {
                width = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                height = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                rotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            }
            if (video == null) {
                hasAudio = meta(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Platform retriever could not read $uri", e)
            return@withContext null
        } finally {
            runCatching { retriever.release() }
        }
    }

    if (durationMs <= 0 || width <= 0 || height <= 0) {
        Log.w(TAG, "No usable video in $uri: duration=$durationMs ms, size=${width}x$height")
        return@withContext null
    }

    val displayWidth = (width * pixelRatio).roundToInt()
    val sideways = rotation % 180 != 0
    val sizeBytes = fileSize(context, uri)
    VideoSource(
        uri = uri.toString(),
        name = displayName(context, uri),
        durationMs = durationMs,
        width = if (sideways) height else displayWidth,
        height = if (sideways) displayWidth else height,
        frameRate = if (frameRate > 0f) frameRate else 30f,
        hasAudio = hasAudio,
        bitrate = if (sizeBytes > 0) (sizeBytes * 8000 / durationMs).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0,
    )
}

private fun fileSize(context: Context, uri: Uri): Long =
    runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    }.getOrDefault(-1L)

private fun displayName(context: Context, uri: Uri): String {
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0)
            }
    }
    return uri.lastPathSegment ?: "Video"
}
