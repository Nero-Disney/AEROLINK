package com.example.vision.mediapipe

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.sqrt

data class FrameQualityReport(
    val meanLuma: Float,
    val stdDevLuma: Float,
    val meanEdgeContrast: Float,
    val isObstructed: Boolean,
    val reasonFr: String? = null
)

/**
 * Évalue en < 0,2 ms si une trame est exploitable pour la vision par ordinateur
 * ou si la caméra est recouverte / trop sombre / totalement uniforme (flou complet).
 */
object FrameQualityAnalyzer {

    private const val SAMPLE_COLS = 24
    private const val SAMPLE_ROWS = 16

    fun assess(bitmap: Bitmap): FrameQualityReport {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 8 || h < 8 || bitmap.isRecycled) {
            return FrameQualityReport(0f, 0f, 0f, true, "Image invalide")
        }

        val grid = FloatArray(SAMPLE_COLS * SAMPLE_ROWS)
        val rowPixels = IntArray(w)
        var sum = 0f

        for (ry in 0 until SAMPLE_ROWS) {
            val py = ((ry + 0.5f) * h / SAMPLE_ROWS).toInt().coerceIn(0, h - 1)
            bitmap.getPixels(rowPixels, 0, w, 0, py, w, 1)
            for (rx in 0 until SAMPLE_COLS) {
                val px = ((rx + 0.5f) * w / SAMPLE_COLS).toInt().coerceIn(0, w - 1)
                val c = rowPixels[px]
                val luma = 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
                grid[ry * SAMPLE_COLS + rx] = luma
                sum += luma
            }
        }

        val count = (SAMPLE_COLS * SAMPLE_ROWS).toFloat()
        val mean = sum / count

        var varSum = 0f
        var edgeSum = 0f
        var edgeCount = 0

        for (ry in 0 until SAMPLE_ROWS) {
            for (rx in 0 until SAMPLE_COLS) {
                val v = grid[ry * SAMPLE_COLS + rx]
                val d = v - mean
                varSum += d * d
                if (rx + 1 < SAMPLE_COLS) {
                    edgeSum += abs(v - grid[ry * SAMPLE_COLS + rx + 1])
                    edgeCount++
                }
                if (ry + 1 < SAMPLE_ROWS) {
                    edgeSum += abs(v - grid[(ry + 1) * SAMPLE_COLS + rx])
                    edgeCount++
                }
            }
        }

        val stdDev = sqrt(varSum / count)
        val meanEdge = if (edgeCount > 0) edgeSum / edgeCount else 0f

        // Cas 1 : lentille couverte / obscurité totale (luma moyenne très faible ET contraste faible)
        val tooDark = mean < 15f && stdDev < 14f
        // Cas 2 : lentille recouverte par doigt/tissu éclairé (surface uniforme sans bords nets)
        val flatObstruction = stdDev < 9.5f && meanEdge < 4.5f

        val obstructed = tooDark || flatObstruction
        val reason = when {
            tooDark -> "Caméra obstruée ou scène trop sombre"
            flatObstruction -> "Objectif recouvert ou contraste nul"
            else -> null
        }

        return FrameQualityReport(
            meanLuma = mean,
            stdDevLuma = stdDev,
            meanEdgeContrast = meanEdge,
            isObstructed = obstructed,
            reasonFr = reason
        )
    }
}
