package com.example.video.recorder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import com.example.connectivity.diagnostics.DiagnosticLogger
import com.example.connectivity.diagnostics.LogSeverity
import com.example.connectivity.diagnostics.LogSubsystem
import com.example.core.models.VideoFrame
import com.example.storage.db.RecordingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LocalMediaRecorder(
    private val context: Context,
    private val logger: DiagnosticLogger
) {
    val capturesDir: File by lazy { File(context.filesDir, "captures").apply { mkdirs() } }
    val recordingsDir: File by lazy { File(context.filesDir, "recordings").apply { mkdirs() } }

    @Volatile
    var isRecordingVideo: Boolean = false
        private set

    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var recordingFile: File? = null
    private var recordingStartMs = 0L
    private var recordWidth = 640
    private var recordHeight = 480
    private var recordingSsid = ""
    private var recordingFamilyId = ""

    suspend fun captureSnapshot(
        frame: VideoFrame,
        ssid: String,
        familyId: String
    ): RecordingEntity? = withContext(Dispatchers.IO) {
        try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val file = File(capturesDir, "CAP_${stamp}.jpg")
            FileOutputStream(file).use { out ->
                frame.bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            logger.log(LogSubsystem.VIDEO, LogSeverity.INFO, "Capture image enregistrée: ${file.name}")
            RecordingEntity(
                title = "Capture $stamp",
                mediaType = "PHOTO",
                filePath = file.absolutePath,
                ssid = ssid,
                familyId = familyId,
                width = frame.width,
                height = frame.height,
                sizeBytes = file.length(),
                createdAtMs = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            logger.log(LogSubsystem.VIDEO, LogSeverity.ERROR, "Échec capture image: ${e.message}")
            null
        }
    }

    @Synchronized
    fun startVideoRecording(width: Int, height: Int, ssid: String, familyId: String): Boolean {
        if (isRecordingVideo) return true
        return try {
            val alignedW = (width.coerceAtLeast(320) / 16) * 16
            val alignedH = (height.coerceAtLeast(240) / 16) * 16
            recordWidth = alignedW
            recordHeight = alignedH
            recordingSsid = ssid
            recordingFamilyId = familyId
            recordingStartMs = System.currentTimeMillis()

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outFile = File(recordingsDir, "VID_${stamp}.mp4")
            recordingFile = outFile

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, alignedW, alignedH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 25)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
            encoder = codec

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            trackIndex = -1
            muxerStarted = false
            isRecordingVideo = true
            logger.log(LogSubsystem.VIDEO, LogSeverity.INFO, "Enregistrement vidéo démarré: ${outFile.name}")
            true
        } catch (e: Exception) {
            logger.log(LogSubsystem.VIDEO, LogSeverity.ERROR, "Impossible de démarrer l'enregistrement MP4: ${e.message}")
            cleanupRecording()
            false
        }
    }

    @Synchronized
    fun appendVideoFrame(frame: VideoFrame) {
        if (!isRecordingVideo) return
        val surface = inputSurface ?: return
        val codec = encoder ?: return
        try {
            val canvas: Canvas = surface.lockCanvas(null)
            try {
                canvas.drawBitmap(
                    frame.bitmap,
                    null,
                    Rect(0, 0, recordWidth, recordHeight),
                    null
                )
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
            drainEncoder(codec, endOfStream = false)
        } catch (e: Exception) {
            logger.log(LogSubsystem.VIDEO, LogSeverity.WARN, "Erreur encodage trame vidéo: ${e.message}")
        }
    }

    @Synchronized
    fun stopVideoRecording(): RecordingEntity? {
        if (!isRecordingVideo) return null
        val file = recordingFile
        val duration = (System.currentTimeMillis() - recordingStartMs).coerceAtLeast(0L)
        try {
            encoder?.let { codec ->
                codec.signalEndOfInputStream()
                drainEncoder(codec, endOfStream = true)
            }
        } catch (e: Exception) {
            logger.log(LogSubsystem.VIDEO, LogSeverity.WARN, "Fin encodage MP4: ${e.message}")
        } finally {
            cleanupRecording()
        }

        return if (file != null && file.exists() && file.length() > 0L) {
            logger.log(LogSubsystem.VIDEO, LogSeverity.INFO, "Vidéo sauvegardée: ${file.name} (${file.length() / 1024} Ko)")
            RecordingEntity(
                title = file.nameWithoutExtension,
                mediaType = "VIDEO",
                filePath = file.absolutePath,
                ssid = recordingSsid,
                familyId = recordingFamilyId,
                width = recordWidth,
                height = recordHeight,
                durationMs = duration,
                sizeBytes = file.length(),
                createdAtMs = System.currentTimeMillis()
            )
        } else {
            file?.delete()
            null
        }
    }

    private fun drainEncoder(codec: MediaCodec, endOfStream: Boolean) {
        val bufferInfo = MediaCodec.BufferInfo()
        val activeMuxer = muxer ?: return
        val timeoutUs = if (endOfStream) 10_000L else 0L
        while (true) {
            val outIdx = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                    break
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = activeMuxer.addTrack(codec.outputFormat)
                        activeMuxer.start()
                        muxerStarted = true
                    }
                }
                outIdx >= 0 -> {
                    val encodedData = codec.getOutputBuffer(outIdx)
                    if (encodedData != null && bufferInfo.size > 0 && muxerStarted &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        activeMuxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        }
    }

    private fun cleanupRecording() {
        isRecordingVideo = false
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        runCatching { if (muxerStarted) muxer?.stop() }
        runCatching { muxer?.release() }
        muxer = null
        muxerStarted = false
        trackIndex = -1
    }
}
