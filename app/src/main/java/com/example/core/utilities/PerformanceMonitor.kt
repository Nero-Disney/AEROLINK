package com.example.core.utilities

import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.util.ArrayDeque

class PerformanceMonitor(private val context: Context) {
    private val frameTimestamps = ArrayDeque<Long>(64)
    private val inferenceTimestamps = ArrayDeque<Long>(32)
    private val latencySamplesMs = ArrayDeque<Long>(128)
    private var bytesReceivedWindow = 0L
    private var windowStartMs = System.currentTimeMillis()
    private var lastKbps = 0

    @Synchronized
    fun recordVideoFrame(bytesSize: Int, latencyMs: Long) {
        val now = System.currentTimeMillis()
        frameTimestamps.addLast(now)
        while (frameTimestamps.size > 60) frameTimestamps.removeFirst()

        if (latencyMs >= 0) {
            latencySamplesMs.addLast(latencyMs)
            while (latencySamplesMs.size > 120) latencySamplesMs.removeFirst()
        }

        bytesReceivedWindow += bytesSize
        val elapsed = now - windowStartMs
        if (elapsed >= 1000L) {
            lastKbps = ((bytesReceivedWindow * 8L) / elapsed).toInt()
            bytesReceivedWindow = 0L
            windowStartMs = now
        }
    }

    @Synchronized
    fun recordInference() {
        val now = System.currentTimeMillis()
        inferenceTimestamps.addLast(now)
        while (inferenceTimestamps.size > 30) inferenceTimestamps.removeFirst()
    }

    @Synchronized
    fun videoFps(): Float {
        if (frameTimestamps.size < 2) return 0f
        val now = System.currentTimeMillis()
        val recent = frameTimestamps.filter { now - it <= 2000L }
        if (recent.size < 2) return 0f
        val spanMs = (recent.last() - recent.first()).coerceAtLeast(1L)
        return (recent.size - 1) * 1000f / spanMs
    }

    @Synchronized
    fun inferenceFps(): Float {
        if (inferenceTimestamps.size < 2) return 0f
        val now = System.currentTimeMillis()
        val recent = inferenceTimestamps.filter { now - it <= 3000L }
        if (recent.size < 2) return 0f
        val spanMs = (recent.last() - recent.first()).coerceAtLeast(1L)
        return (recent.size - 1) * 1000f / spanMs
    }

    @Synchronized
    fun medianLatencyMs(): Long {
        if (latencySamplesMs.isEmpty()) return 0L
        val sorted = latencySamplesMs.sorted()
        return sorted[sorted.size / 2]
    }

    @Synchronized
    fun p95LatencyMs(): Long {
        if (latencySamplesMs.isEmpty()) return 0L
        val sorted = latencySamplesMs.sorted()
        val idx = ((sorted.size - 1) * 0.95).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }

    @Synchronized
    fun rxKbps(): Int = lastKbps

    fun usedMemoryMb(): Int {
        val runtime = Runtime.getRuntime()
        return ((runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)).toInt()
    }

    fun thermalStatusLabel(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "N/A (<API 29)"
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return "Inconnu"
        return when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "Nominal"
            PowerManager.THERMAL_STATUS_LIGHT -> "Tiède (Light)"
            PowerManager.THERMAL_STATUS_MODERATE -> "Modéré"
            PowerManager.THERMAL_STATUS_SEVERE -> "Élevé (Throttling)"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Critique"
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Urgence thermique"
            else -> "Nominal"
        }
    }

    @Synchronized
    fun reset() {
        frameTimestamps.clear()
        inferenceTimestamps.clear()
        latencySamplesMs.clear()
        bytesReceivedWindow = 0L
        lastKbps = 0
        windowStartMs = System.currentTimeMillis()
    }
}
