package com.example.vision.litert

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Build
import android.os.PowerManager
import com.example.core.models.DetectorType
import com.example.core.models.HardwareBackend
import com.example.storage.db.BenchmarkReportEntity
import com.example.vision.mediapipe.MlKitVisionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.gpu.CompatibilityList

data class InstalledModelSpec(
    val id: String,
    val name: String,
    val runtime: String,
    val version: String,
    val targetTask: String,
    val detectorType: DetectorType?,
    val gpuCompatible: Boolean
)

class LiteRtBenchmarkRunner(
    private val context: Context,
    private val visionEngine: MlKitVisionEngine
) {
    @Volatile
    private var cachedGpuSupport: Boolean? = null

    suspend fun probeGpuDelegateAvailable(): Boolean = withContext(Dispatchers.Default) {
        cachedGpuSupport?.let { return@withContext it }
        val supported = runCatching {
            withOffscreenEglContext {
                CompatibilityList().isDelegateSupportedOnThisDevice
            } ?: false
        }.getOrDefault(false)
        cachedGpuSupport = supported
        supported
    }

    private inline fun <T> withOffscreenEglContext(block: () -> T): T? {
        val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return null
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null

        var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        try {
            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
                numConfigs[0] <= 0 || configs[0] == null
            ) {
                return null
            }

            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) return null

            val surfAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreatePbufferSurface(display, configs[0], surfAttribs, 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) return null

            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) return null
            return block()
        } finally {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
            EGL14.eglTerminate(display)
        }
    }

    fun installedModels(gpuOk: Boolean = cachedGpuSupport ?: false): List<InstalledModelSpec> {
        return listOf(
            InstalledModelSpec(
                id = "mlkit_face",
                name = "ML Kit Face Detector + Landmarks",
                runtime = "ML Kit / MediaPipe Graph",
                version = "16.1.7",
                targetTask = "Visages & points faciaux (yeux, nez, bouche)",
                detectorType = DetectorType.FACE_LANDMARKS,
                gpuCompatible = gpuOk
            ),
            InstalledModelSpec(
                id = "mlkit_pose",
                name = "ML Kit Pose Landmarker (Stream)",
                runtime = "ML Kit / BlazePose LiteRT",
                version = "18.0.0-beta5",
                targetTask = "Corps humain & 33 points squelette",
                detectorType = DetectorType.PERSON_POSE,
                gpuCompatible = gpuOk
            ),
            InstalledModelSpec(
                id = "mlkit_object",
                name = "ML Kit Multi-Object Classifier",
                runtime = "ML Kit / EfficientDet LiteRT",
                version = "17.0.2",
                targetTask = "Personnes, objets courants & zones ROI",
                detectorType = DetectorType.OBJECTS_GENERAL,
                gpuCompatible = gpuOk
            ),
            InstalledModelSpec(
                id = "litert_runtime",
                name = "LiteRT CompiledModel Runtime",
                runtime = "Google AI Edge LiteRT",
                version = "1.0.1",
                targetTask = "Modèles .tflite personnalisés (CPU / GPU / NNAPI)",
                detectorType = null,
                gpuCompatible = gpuOk
            )
        )
    }

    /**
     * Exécute un banc de mesure réel sur l'appareil courant (ex: Galaxy S9 SM-G960U)
     * en mesurant les temps d'inférence médian, P95, FPS, RAM et statut thermique.
     */
    suspend fun runBenchmark(
        detectorType: DetectorType,
        backend: HardwareBackend,
        iterations: Int = 18
    ): BenchmarkReportEntity = withContext(Dispatchers.Default) {
        val testBitmap = createBenchmarkFrame(640, 360)
        val latencies = mutableListOf<Long>()

        // Warm-up (2 passes hors mesure)
        repeat(2) {
            visionEngine.analyze(testBitmap, detectorType)
        }

        val effectiveDetector = if (detectorType == DetectorType.NONE) {
            DetectorType.FACE_LANDMARKS
        } else {
            detectorType
        }

        repeat(iterations) {
            val t0 = System.nanoTime()
            visionEngine.analyze(testBitmap, effectiveDetector)
            val dtMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            latencies.add(dtMs)
        }
        testBitmap.recycle()

        val sorted = latencies.sorted()
        val avgMs = latencies.average().toFloat()
        val medianMs = sorted[sorted.size / 2]
        val p95Ms = sorted[((sorted.size - 1) * 0.95).toInt().coerceIn(0, sorted.lastIndex)]
        val fps = if (avgMs > 0f) (1000f / avgMs) else 0f

        val rt = Runtime.getRuntime()
        val memMb = ((rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)).toInt()
        val thermal = currentThermalLabel()

        BenchmarkReportEntity(
            modelName = effectiveDetector.labelFr,
            backend = backend.name,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            avgInferenceMs = avgMs,
            medianInferenceMs = medianMs,
            p95InferenceMs = p95Ms,
            inferenceFps = fps,
            memoryMb = memMb,
            thermalStatus = thermal,
            iterations = iterations
        )
    }

    private fun createBenchmarkFrame(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(24, 34, 48))
        val paint = Paint().apply {
            color = Color.rgb(210, 175, 145)
            isAntiAlias = true
        }
        // Silhouette géométrique pour stimuler le pipeline d'analyse sur un cliché réaliste
        canvas.drawCircle(w * 0.5f, h * 0.35f, 52f, paint)
        canvas.drawRect(w * 0.43f, h * 0.48f, w * 0.57f, h * 0.85f, paint)
        return bmp
    }

    private fun currentThermalLabel(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "Nominal"
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return "Inconnu"
        return when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "Nominal"
            PowerManager.THERMAL_STATUS_LIGHT -> "Tiède"
            PowerManager.THERMAL_STATUS_MODERATE -> "Modéré"
            PowerManager.THERMAL_STATUS_SEVERE -> "Élevé"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Critique"
            else -> "Nominal"
        }
    }
}
