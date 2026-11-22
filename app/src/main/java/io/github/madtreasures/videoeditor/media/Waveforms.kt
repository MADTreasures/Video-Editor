package io.github.madtreasures.videoeditor.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Length of time one waveform value stands for. */
const val WAVEFORM_BUCKET_MS = 50L

private const val TAG = "VideoEditorWaveform"

/**
 * Loudness outlines of the added audio files, for drawing on the audio lane: one value from 0 to
 * 1 per [WAVEFORM_BUCKET_MS]. Each file is decoded once, in the background.
 */
class Waveforms(private val context: Context, private val scope: CoroutineScope) {
    /** Read while drawing; filling it triggers a redraw. */
    val cache = mutableStateMapOf<String, FloatArray>()

    private val started = HashSet<String>()
    private val oneAtATime = Semaphore(1)

    fun request(uri: String) {
        if (!started.add(uri)) return
        scope.launch(Dispatchers.Default) {
            oneAtATime.withPermit {
                val peaks = try {
                    decode(Uri.parse(uri))
                } catch (e: Exception) {
                    Log.w(TAG, "Could not read the waveform of $uri", e)
                    null
                }
                if (peaks != null) launch(Dispatchers.Main) { cache[uri] = peaks }
            }
        }
    }

    private fun decode(uri: Uri): FloatArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs <= 0) return null
            val peaks = FloatArray((durationUs / 1000 / WAVEFORM_BUCKET_MS + 1).toInt())

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val out = codec.outputFormat
                    sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = out.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        out.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (index >= 0) {
                    val buffer = codec.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder())
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val bytesPerFrame = (if (floatPcm) 4 else 2) * channels
                    val frames = info.size / bytesPerFrame
                    // Every 8th frame is plenty for a picture of the loudness.
                    var frame = 0
                    while (frame < frames) {
                        val at = info.offset + frame * bytesPerFrame
                        val value = if (floatPcm) abs(buffer.getFloat(at)) else abs(buffer.getShort(at) / 32768f)
                        val timeUs = info.presentationTimeUs + frame * 1_000_000L / sampleRate
                        val bucket = (timeUs / 1000 / WAVEFORM_BUCKET_MS).toInt()
                        if (bucket in peaks.indices) peaks[bucket] = max(peaks[bucket], value)
                        frame += 8
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            // A square root makes quiet passages visible next to loud ones.
            for (i in peaks.indices) peaks[i] = sqrt(peaks[i].coerceIn(0f, 1f))
            return peaks
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
