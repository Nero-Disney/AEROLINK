package com.example.tracking

import android.graphics.Bitmap
import android.graphics.Color
import com.example.core.models.OpticalFlowEstimate
import kotlin.math.abs

/**
 * Estimateur de flux optique léger (inspiré Lucas-Kanade par gradients spatiaux/temporels)
 * fonctionnant sur grille réduite 48x27 pour estimer la dérive caméra sans surcharger le S9.
 */
class OpticalFlowEstimator {
    private val gridW = 48
    private val gridH = 27
    private var prevLuma: IntArray? = null

    @Synchronized
    fun estimate(bitmap: Bitmap): OpticalFlowEstimate {
        val scaled = Bitmap.createScaledBitmap(bitmap, gridW, gridH, false)
        val curr = IntArray(gridW * gridH)
        val pixels = IntArray(gridW * gridH)
        scaled.getPixels(pixels, 0, gridW, 0, 0, gridW, gridH)
        if (scaled != bitmap) scaled.recycle()

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            curr[i] = (r * 30 + g * 59 + b * 11) / 100
        }

        val prev = prevLuma
        prevLuma = curr
        if (prev == null) {
            return OpticalFlowEstimate(advisoryLabel = "Initialisation référence")
        }

        var sumDx = 0f
        var sumDy = 0f
        var validFeatures = 0

        // Calcul du gradient Lucas-Kanade simplifié sur points à fort contraste
        for (y in 2 until gridH - 2 step 2) {
            for (x in 2 until gridW - 2 step 2) {
                val idx = y * gridW + x
                val ix = (curr[idx + 1] - curr[idx - 1]) * 0.5f
                val iy = (curr[idx + gridW] - curr[idx - gridW]) * 0.5f
                val gradSq = ix * ix + iy * iy
                if (gradSq < 180f) continue // Ignore les zones uniformes

                val it = (curr[idx] - prev[idx]).toFloat()
                // Résolution scalaire le long du gradient normalisé
                val vx = -(it * ix) / (gradSq + 1e-2f)
                val vy = -(it * iy) / (gradSq + 1e-2f)
                if (abs(vx) < 3.5f && abs(vy) < 3.5f) {
                    sumDx += vx
                    sumDy += vy
                    validFeatures++
                }
            }
        }

        if (validFeatures < 6) {
            return OpticalFlowEstimate(
                featureCount = validFeatures,
                confidence = 0.2f,
                advisoryLabel = "Texture sol insuffisante"
            )
        }

        val avgDx = (sumDx / validFeatures) / gridW
        val avgDy = (sumDy / validFeatures) / gridH
        val mag = kotlin.math.hypot(avgDx.toDouble(), avgDy.toDouble()).toFloat()
        val conf = (validFeatures / 40f).coerceIn(0.25f, 0.98f)
        val label = when {
            mag < 0.008f -> "Stationnaire"
            abs(avgDx) > abs(avgDy) && avgDx > 0 -> "Dérive Droite"
            abs(avgDx) > abs(avgDy) && avgDx < 0 -> "Dérive Gauche"
            avgDy > 0 -> "Dérive Arrière"
            else -> "Dérive Avant"
        }

        return OpticalFlowEstimate(
            driftX = avgDx,
            driftY = avgDy,
            rotationDeg = (avgDx * 45f).coerceIn(-15f, 15f),
            confidence = conf,
            featureCount = validFeatures,
            advisoryLabel = label
        )
    }

    @Synchronized
    fun reset() {
        prevLuma = null
    }
}
