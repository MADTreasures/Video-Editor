package io.github.madtreasures.videoeditor.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ThumbKey(val uri: String, val timeMs: Long)

private const val THUMB_SIZE = 128
private const val MAX_CACHED = 400

/**
 * Loads small square frames for the timeline. The timeline says which frames are visible right
 * now; frames are decoded one at a time on a background thread and whatever scrolled out of
 * view in the meantime is skipped.
 */
class ThumbnailLoader(private val context: Context, private val scope: CoroutineScope) {
    /** Read by the timeline while drawing; filling it triggers a redraw. */
    val cache = mutableStateMapOf<ThumbKey, ImageBitmap>()

    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()

    // Only touched on the decoding thread.
    private val retrievers = HashMap<String, MediaMetadataRetriever?>()

    // Only touched on the main thread.
    private var wanted: List<ThumbKey> = emptyList()
    private val failed = HashSet<ThumbKey>()
    private var job: Job? = null

    fun request(keys: List<ThumbKey>) {
        wanted = keys
        if (job?.isActive == true) return
        job = scope.launch {
            while (true) {
                val next = wanted.firstOrNull { it !in cache && it !in failed } ?: break
                val bitmap = withContext(dispatcher) { load(next) }
                if (bitmap != null) cache[next] = bitmap.asImageBitmap() else failed += next
                evictIfNeeded()
            }
        }
    }

    private fun evictIfNeeded() {
        if (cache.size <= MAX_CACHED) return
        val keep = wanted.toSet()
        val surplus = cache.keys.filter { it !in keep }.take(cache.size - MAX_CACHED * 3 / 4)
        surplus.forEach { cache.remove(it) }
    }

    private fun load(key: ThumbKey): Bitmap? {
        val retriever = retrievers.getOrPut(key.uri) {
            try {
                MediaMetadataRetriever().apply { setDataSource(context, Uri.parse(key.uri)) }
            } catch (e: Exception) {
                null
            }
        } ?: return null
        return try {
            val timeUs = key.timeMs * 1000
            val frame = if (Build.VERSION.SDK_INT >= 27) {
                // Fit the short side to THUMB_SIZE; the long side is cropped below.
                retriever.getScaledFrameAtTime(
                    timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_SIZE * 3, THUMB_SIZE * 3,
                )
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } ?: return null
            squareThumb(frame)
        } catch (e: Exception) {
            null
        }
    }

    private fun squareThumb(frame: Bitmap): Bitmap {
        val side = min(frame.width, frame.height)
        val cropped = Bitmap.createBitmap(
            frame, (frame.width - side) / 2, (frame.height - side) / 2, side, side,
        )
        val scale = THUMB_SIZE.toFloat() / max(1, side)
        val result = if (scale < 1f) {
            val size = max(1, (side * scale).roundToInt())
            Bitmap.createScaledBitmap(cropped, size, size, true)
        } else {
            cropped
        }
        if (cropped !== result && cropped !== frame) cropped.recycle()
        if (frame !== result) frame.recycle()
        return result
    }

    /** Forgets everything about files that are no longer part of the project. */
    fun retain(uris: Set<String>) {
        cache.keys.filter { it.uri !in uris }.forEach { cache.remove(it) }
        failed.removeAll { it.uri !in uris }
        scope.launch(dispatcher) {
            val gone = retrievers.keys.filter { it !in uris }
            gone.forEach { uri -> runCatching { retrievers.remove(uri)?.release() } }
        }
    }

    fun close() {
        job?.cancel()
        executor.execute {
            retrievers.values.forEach { runCatching { it?.release() } }
            retrievers.clear()
        }
        executor.shutdown()
    }
}
