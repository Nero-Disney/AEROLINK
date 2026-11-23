package com.example.vision.runtime.frame

import android.graphics.Bitmap
import com.example.vision.domain.contracts.FrameBuffer
import com.example.vision.domain.contracts.FrameInput
import com.example.vision.domain.contracts.FrameLease
import com.example.vision.domain.contracts.PixelFormat
import java.nio.ByteBuffer

class BitmapFrameBuffer(
    private val bitmap: Bitmap,
    override val format: PixelFormat = PixelFormat.RGBA_8888
) : FrameBuffer {

    override fun width(): Int = bitmap.width
    override fun height(): Int = bitmap.height

    override fun acquire(): FrameLease = object : FrameLease {
        private var buffer: ByteBuffer? = null

        override fun asBitmap(): Bitmap = bitmap

        override fun asByteBuffer(): ByteBuffer {
            val existing = buffer
            if (existing != null) {
                existing.rewind()
                return existing
            }
            val alloc = ByteBuffer.allocateDirect(bitmap.byteCount)
            bitmap.copyPixelsToBuffer(alloc)
            alloc.rewind()
            buffer = alloc
            return alloc
        }

        override fun close() {
            buffer = null
        }
    }

    companion object {
        fun wrap(
            bitmap: Bitmap,
            sequence: Long,
            receivedAtMs: Long,
            capturedAtMs: Long? = null,
            rotationDegrees: Int = 0,
            cameraProfileId: String = "default_640x480"
        ): FrameInput {
            return FrameInput(
                sequence = sequence,
                capturedAtNs = capturedAtMs?.let { it * 1_000_000L },
                receivedAtNs = receivedAtMs * 1_000_000L,
                width = bitmap.width,
                height = bitmap.height,
                rotationDegrees = rotationDegrees,
                cameraProfileId = cameraProfileId,
                frame = BitmapFrameBuffer(bitmap)
            )
        }
    }
}
