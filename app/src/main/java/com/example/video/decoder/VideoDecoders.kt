package com.example.video.decoder

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer

class MjpegFrameDecoder {
    private val options = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inMutable = false
    }

    fun decode(jpegBytes: ByteArray, length: Int = jpegBytes.size): Bitmap? {
        if (length < 4) return null
        return try {
            BitmapFactory.decodeByteArray(jpegBytes, 0, length, options)
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * Pont MediaCodec matériel (H.264 / H.265) pour les familles RTSP et Lewei TCP.
 */
class HardwareVideoCodecBridge(
    private val mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC
) {
    private var codec: MediaCodec? = null

    fun start(width: Int, height: Int, surface: Surface) {
        stop()
        val format = MediaFormat.createVideoFormat(mimeType, width, height)
        codec = MediaCodec.createDecoderByType(mimeType).apply {
            configure(format, surface, null, 0)
            start()
        }
    }

    fun queueNalUnit(data: ByteArray, length: Int, presentationTimeUs: Long) {
        val activeCodec = codec ?: return
        val inputIndex = activeCodec.dequeueInputBuffer(5_000)
        if (inputIndex >= 0) {
            val buffer: ByteBuffer? = activeCodec.getInputBuffer(inputIndex)
            buffer?.clear()
            buffer?.put(data, 0, length)
            activeCodec.queueInputBuffer(inputIndex, 0, length, presentationTimeUs, 0)
        }
        val info = MediaCodec.BufferInfo()
        var outIndex = activeCodec.dequeueOutputBuffer(info, 0)
        while (outIndex >= 0) {
            activeCodec.releaseOutputBuffer(outIndex, true)
            outIndex = activeCodec.dequeueOutputBuffer(info, 0)
        }
    }

    fun stop() {
        runCatching {
            codec?.stop()
            codec?.release()
        }
        codec = null
    }
}
