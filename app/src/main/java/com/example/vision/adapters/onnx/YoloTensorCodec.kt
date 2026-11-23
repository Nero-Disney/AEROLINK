package com.example.vision.adapters.onnx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.example.core.models.NormalizedBox
import com.example.vision.domain.contracts.CoordinateSpace
import com.example.vision.domain.contracts.CoordinateTransform
import com.example.vision.domain.contracts.ModelLabel
import com.example.vision.domain.contracts.VisionFramework
import com.example.vision.domain.entities.AdvdsTaxonomy
import com.example.vision.domain.observations.Detection
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

data class PreparedYoloTensor(
    val buffer: FloatBuffer,
    val nchw: Boolean,
    val width: Int,
    val height: Int,
    val transform: CoordinateTransform
)

/**
 * Préprocesseur Letterbox avec traçabilité géométrique exacte (CoordinateTransform)
 * entre la résolution source du drone et l'entrée modèle (ex: 416x416 ou 640x640).
 */
class YoloPreprocessor {

    fun prepare(
        bitmap: Bitmap,
        modelWidth: Int,
        modelHeight: Int,
        nchw: Boolean = true,
        rotationDegrees: Int = 0
    ): PreparedYoloTensor {
        val sw = bitmap.width.coerceAtLeast(1)
        val sh = bitmap.height.coerceAtLeast(1)
        val scale = min(modelWidth.toFloat() / sw, modelHeight.toFloat() / sh)
        val scaledW = (sw * scale).toInt().coerceIn(1, modelWidth)
        val scaledH = (sh * scale).toInt().coerceIn(1, modelHeight)
        val padLeft = (modelWidth - scaledW) / 2f
        val padTop = (modelHeight - scaledH) / 2f

        val letterboxed = Bitmap.createBitmap(modelWidth, modelHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(letterboxed)
        canvas.drawColor(Color.rgb(114, 114, 114))
        val scaled = Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)
        canvas.drawBitmap(scaled, padLeft, padTop, null)
        if (scaled !== bitmap) scaled.recycle()

        val pixelCount = modelWidth * modelHeight
        val pixels = IntArray(pixelCount)
        letterboxed.getPixels(pixels, 0, modelWidth, 0, 0, modelWidth, modelHeight)
        letterboxed.recycle()

        val floatBuffer = FloatBuffer.allocate(pixelCount * 3)
        if (nchw) {
            val gOffset = pixelCount
            val bOffset = pixelCount * 2
            val arr = FloatArray(pixelCount * 3)
            for (i in 0 until pixelCount) {
                val p = pixels[i]
                arr[i] = ((p shr 16) and 0xFF) / 255f
                arr[gOffset + i] = ((p shr 8) and 0xFF) / 255f
                arr[bOffset + i] = (p and 0xFF) / 255f
            }
            floatBuffer.put(arr)
        } else {
            val arr = FloatArray(pixelCount * 3)
            var outIdx = 0
            for (i in 0 until pixelCount) {
                val p = pixels[i]
                arr[outIdx++] = ((p shr 16) and 0xFF) / 255f
                arr[outIdx++] = ((p shr 8) and 0xFF) / 255f
                arr[outIdx++] = (p and 0xFF) / 255f
            }
            floatBuffer.put(arr)
        }
        floatBuffer.rewind()

        return PreparedYoloTensor(
            buffer = floatBuffer,
            nchw = nchw,
            width = modelWidth,
            height = modelHeight,
            transform = CoordinateTransform(
                sourceWidth = sw,
                sourceHeight = sh,
                modelWidth = modelWidth,
                modelHeight = modelHeight,
                scaleX = scale,
                scaleY = scale,
                padLeftPx = padLeft,
                padTopPx = padTop,
                rotationDegrees = rotationDegrees
            )
        )
    }
}

/**
 * Décodeur universel YOLO26 (end-to-end [N, 6]) et YOLOv8/YOLO26 brut ([4+C, N] ou [N, 4+C])
 * avec projection inverse exacte vers SOURCE_IMAGE et taxonomie ADVDS.
 */
class YoloOutputDecoder {

    /**
     * Décode une matrice [rows][cols] issue d'une sortie ONNX/LiteRT.
     * - Si cols == 6 : format end-to-end sans NMS (x1, y1, x2, y2, score, classId) en pixels modèle ou [0..1]
     * - Sinon si rows in 5..128 et cols > rows : format [4+C, N] (cx, cy, w, h, classScores...)
     * - Sinon : format [N, 4+C]
     */
    fun decodeMatrix(
        matrix: Array<FloatArray>,
        transform: CoordinateTransform,
        labels: List<ModelLabel>,
        providerId: String,
        framework: VisionFramework,
        confThreshold: Float = 0.35f,
        iouNmsThreshold: Float = 0.45f
    ): List<Detection> {
        if (matrix.isEmpty()) return emptyList()
        val rows = matrix.size
        val cols = matrix[0].size
        val mw = transform.modelWidth.coerceAtLeast(1).toFloat()
        val mh = transform.modelHeight.coerceAtLeast(1).toFloat()

        val rawCandidates = mutableListOf<Detection>()

        if (cols == 6) {
            // Format YOLO26 end-to-end (x1, y1, x2, y2, score, classId)
            for (r in matrix) {
                val score = r[4]
                if (score < confThreshold) continue
                val clsIdx = r[5].toInt().coerceAtLeast(0)
                val normNeeded = r[2] > 1.5f || r[3] > 1.5f
                val x1 = if (normNeeded) r[0] / mw else r[0]
                val y1 = if (normNeeded) r[1] / mh else r[1]
                val x2 = if (normNeeded) r[2] / mw else r[2]
                val y2 = if (normNeeded) r[3] / mh else r[3]
                if (x2 <= x1 || y2 <= y1) continue

                val srcBox = transform.mapModelBoxToSource(
                    NormalizedBox(x1.coerceIn(0f, 1f), y1.coerceIn(0f, 1f), x2.coerceIn(0f, 1f), y2.coerceIn(0f, 1f))
                )
                if (srcBox.width < 0.02f || srcBox.height < 0.02f) continue
                rawCandidates.add(buildDetection(clsIdx, score, srcBox, labels, providerId, framework))
            }
            return rawCandidates.sortedByDescending { it.rawConfidence }
        }

        val transposed = rows in 5..128 && cols > rows
        val anchorCount = if (transposed) cols else rows
        val featureDim = if (transposed) rows else cols
        val classCount = (featureDim - 4).coerceAtLeast(1)

        for (i in 0 until anchorCount) {
            val cx = if (transposed) matrix[0][i] else matrix[i][0]
            val cy = if (transposed) matrix[1][i] else matrix[i][1]
            val bw = if (transposed) matrix[2][i] else matrix[i][2]
            val bh = if (transposed) matrix[3][i] else matrix[i][3]

            var bestCls = 0
            var bestScore = -1f
            for (c in 0 until classCount) {
                val s = if (transposed) matrix[4 + c][i] else matrix[i][4 + c]
                if (s > bestScore) {
                    bestScore = s
                    bestCls = c
                }
            }
            if (bestScore < confThreshold) continue

            val normNeeded = cx > 1.5f || cy > 1.5f || bw > 1.5f || bh > 1.5f
            val nCx = if (normNeeded) cx / mw else cx
            val nCy = if (normNeeded) cy / mh else cy
            val nBw = if (normNeeded) bw / mw else bw
            val nBh = if (normNeeded) bh / mh else bh

            val modelBox = NormalizedBox(
                left = (nCx - nBw / 2f).coerceIn(0f, 1f),
                top = (nCy - nBh / 2f).coerceIn(0f, 1f),
                right = (nCx + nBw / 2f).coerceIn(0f, 1f),
                bottom = (nCy + nBh / 2f).coerceIn(0f, 1f)
            )
            val srcBox = transform.mapModelBoxToSource(modelBox)
            if (srcBox.width < 0.02f || srcBox.height < 0.02f) continue
            rawCandidates.add(buildDetection(bestCls, bestScore.coerceIn(0f, 1f), srcBox, labels, providerId, framework))
        }

        return applyNms(rawCandidates, iouNmsThreshold)
    }

    private fun buildDetection(
        clsIdx: Int,
        score: Float,
        box: NormalizedBox,
        labels: List<ModelLabel>,
        providerId: String,
        framework: VisionFramework
    ): Detection {
        val rawLabel = labels.firstOrNull { it.id == clsIdx.toString() }?.name
            ?: labels.getOrNull(clsIdx)?.name
            ?: "class_$clsIdx"
        val tax = AdvdsTaxonomy.classify(rawLabel, score)
        return Detection(
            classId = tax.classId,
            label = tax.displayLabelFr,
            family = tax.family,
            importance = tax.importance,
            box = box,
            rawConfidence = score,
            calibratedConfidence = score,
            providerId = providerId,
            framework = framework,
            coordinateSpace = CoordinateSpace.SOURCE_IMAGE,
            isOpenSetUnknown = tax.isOpenSetUnknown
        )
    }

    private fun applyNms(sortedInput: List<Detection>, iouThreshold: Float): List<Detection> {
        val sorted = sortedInput.sortedByDescending { it.rawConfidence }
        val kept = mutableListOf<Detection>()
        for (cand in sorted) {
            val suppressed = kept.any { k ->
                k.classId == cand.classId && iou(k.box, cand.box) > iouThreshold
            }
            if (!suppressed) kept.add(cand)
            if (kept.size >= 24) break
        }
        return kept
    }

    private fun iou(a: NormalizedBox, b: NormalizedBox): Float {
        val ix1 = max(a.left, b.left)
        val iy1 = max(a.top, b.top)
        val ix2 = min(a.right, b.right)
        val iy2 = min(a.bottom, b.bottom)
        val inter = (ix2 - ix1).coerceAtLeast(0f) * (iy2 - iy1).coerceAtLeast(0f)
        val union = a.area + b.area - inter
        return if (union > 1e-6f) inter / union else 0f
    }
}
