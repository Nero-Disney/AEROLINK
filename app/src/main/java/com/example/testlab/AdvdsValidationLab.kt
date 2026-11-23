package com.example.testlab

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.example.core.models.NormalizedBox
import com.example.vision.adapters.geometry.FrameQualityEngine
import com.example.vision.domain.contracts.CoordinateTransform
import com.example.vision.domain.contracts.DetectorRequirements
import com.example.vision.domain.contracts.InferenceConfig
import com.example.vision.domain.contracts.VisionFramework
import com.example.vision.runtime.frame.BitmapFrameBuffer
import com.example.vision.runtime.provider.VisionRuntimeConfig
import com.example.vision.runtime.provider.VisionRuntimeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

data class ContractCheckResult(
    val name: String,
    val passed: Boolean,
    val detailFr: String
)

data class ValidationLabReport(
    val frameworkName: String,
    val providerId: String,
    val checks: List<ContractCheckResult>,
    val allPassed: Boolean,
    val medianLatencyMs: Long,
    val p95LatencyMs: Long,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Laboratoire de validation contractuelle ADVDS (Section 20.1 & 22) :
 * vérifie chaque provider sur la restitution de coordonnées [0..1],
 * l'horodatage, la résistance aux trames noires/obstruées et les latences P50/P95.
 */
class AdvdsValidationLab(
    private val runtimeManager: VisionRuntimeManager
) {

    suspend fun runProviderContractSuite(
        preferredFramework: VisionFramework,
        gpuSupported: Boolean
    ): ValidationLabReport = withContext(Dispatchers.Default) {
        val checks = mutableListOf<ContractCheckResult>()

        // 1. Test contrat CoordinateTransform (Letterbox 1280x720 -> 416x416 -> Source [0..1])
        val ct = CoordinateTransform(
            sourceWidth = 1280,
            sourceHeight = 720,
            modelWidth = 416,
            modelHeight = 416,
            scaleX = 416f / 1280f,
            scaleY = 416f / 1280f,
            padLeftPx = 0f,
            padTopPx = (416f - 720f * (416f / 1280f)) / 2f
        )
        val mapped = ct.mapModelBoxToSource(NormalizedBox(0.25f, 0.25f, 0.75f, 0.75f))
        val coordOk = mapped.left in 0.24f..0.26f && mapped.right in 0.74f..0.76f && mapped.top < mapped.bottom
        checks.add(
            ContractCheckResult(
                name = "Mapping géométrique Letterbox -> Source [0..1]",
                passed = coordOk,
                detailFr = if (coordOk) "Inversion exacte (${"%.2f".format(mapped.left)}..${"%.2f".format(mapped.right)})" else "Erreur projection"
            )
        )

        // 2. Test FrameQualityEngine sur image noire (caméra recouverte)
        val darkBmp = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val qDark = FrameQualityEngine().assess(darkBmp)
        darkBmp.recycle()
        checks.add(
            ContractCheckResult(
                name = "Rejet caméra recouverte (trame noire)",
                passed = qDark.isObstructed,
                detailFr = qDark.reasonFr ?: "Non détecté"
            )
        )

        // 3. Résolution du provider demandé & test d'inférence contractuel
        val device = runtimeManager.currentDeviceProfile(gpuSupported)
        val (detector, report) = runtimeManager.resolveObjectDetector(
            device = device,
            requirements = DetectorRequirements(allowFallback = true),
            runtimeConfig = VisionRuntimeConfig(preferredObjectFramework = preferredFramework, allowFallback = true),
            inferenceConfig = InferenceConfig()
        )

        if (detector == null) {
            checks.add(ContractCheckResult("Résolution Provider", false, report.warningFr ?: "Indisponible"))
            return@withContext ValidationLabReport(
                frameworkName = preferredFramework.displayName,
                providerId = "None",
                checks = checks,
                allPassed = false,
                medianLatencyMs = 0L,
                p95LatencyMs = 0L
            )
        }

        val testBmp = createSyntheticScene(640, 360)
        val latencies = mutableListOf<Long>()
        var timestampPreserved = true
        var coordsNormalized = true

        try {
            repeat(8) { pass ->
                val seq = 9000L + pass
                val frame = BitmapFrameBuffer.wrap(testBmp, seq, 1_700_000_000_000L + pass * 33L)
                val t0 = System.nanoTime()
                val batch = detector.detect(frame)
                val dt = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                latencies.add(dt)
                if (batch.frameSequence != seq) timestampPreserved = false
                if (batch.detections.any { d ->
                        d.box.left < 0f || d.box.right > 1f || d.box.top < 0f || d.box.bottom > 1f
                    }
                ) {
                    coordsNormalized = false
                }
            }
        } finally {
            testBmp.recycle()
            detector.close()
        }

        checks.add(
            ContractCheckResult(
                name = "Horodatage & séquence préservés",
                passed = timestampPreserved,
                detailFr = if (timestampPreserved) "frameSequence conservé sur 8 passes" else "Dérive de séquence"
            )
        )
        checks.add(
            ContractCheckResult(
                name = "Bornes normalisées [0..1]",
                passed = coordsNormalized,
                detailFr = if (coordsNormalized) "Toutes les boîtes dans [0..1]" else "Dépassement détecté"
            )
        )

        val sorted = latencies.sorted()
        val p50 = sorted[sorted.size / 2]
        val p95 = sorted[((sorted.size - 1) * 0.95).toInt().coerceIn(0, sorted.lastIndex)]
        val latencyOk = abs(p50) < 400L
        checks.add(
            ContractCheckResult(
                name = "Budget latence S9 (P50 < 400 ms)",
                passed = latencyOk,
                detailFr = "P50=${p50} ms • P95=${p95} ms"
            )
        )

        ValidationLabReport(
            frameworkName = report.selectedFramework?.displayName ?: preferredFramework.displayName,
            providerId = report.selectedProviderId ?: detector.providerId,
            checks = checks,
            allPassed = checks.all { it.passed },
            medianLatencyMs = p50,
            p95LatencyMs = p95
        )
    }

    private fun createSyntheticScene(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(30, 46, 66))
        val p = Paint().apply { color = Color.rgb(210, 170, 130); isAntiAlias = true }
        c.drawCircle(w * 0.5f, h * 0.35f, 44f, p)
        c.drawRect(w * 0.42f, h * 0.46f, w * 0.58f, h * 0.85f, p)
        return bmp
    }
}
