package com.example.connectivity.diagnostics

import android.content.Context
import android.os.Build
import com.example.core.models.TelemetrySnapshot
import com.example.storage.db.DiagnosticLogDao
import com.example.storage.db.DiagnosticLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogSubsystem { NETWORK, VIDEO, AI, FLIGHT }
enum class LogSeverity { INFO, WARN, ERROR }

class DiagnosticLogger(
    private val context: Context,
    private val dao: DiagnosticLogDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val recentLogs: Flow<List<DiagnosticLogEntity>> = dao.observeRecent()

    fun log(
        subsystem: LogSubsystem,
        severity: LogSeverity,
        message: String,
        metadata: Map<String, Any?> = emptyMap()
    ) {
        val json = JSONObject().apply {
            metadata.forEach { (k, v) -> put(k, v?.toString() ?: "null") }
        }.toString()

        scope.launch {
            dao.insert(
                DiagnosticLogEntity(
                    subsystem = subsystem.name,
                    severity = severity.name,
                    message = message,
                    metadataJson = json,
                    timestampMs = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun clear() {
        dao.clearAll()
    }

    suspend fun buildStatsJsonAndReportFile(telemetry: TelemetrySnapshot): File {
        val logs = dao.getRecentSnapshot()
        val root = JSONObject().apply {
            put("app", "AeroLink")
            put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("android_sdk", Build.VERSION.SDK_INT)
            put("generated_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
            put("stats", JSONObject().apply {
                put("ssid", telemetry.ssid)
                put("connected", telemetry.isConnected)
                put("streaming", telemetry.isVideoStreaming)
                put("video_fps", telemetry.videoFps)
                put("latency_ms", telemetry.latencyMs)
                put("median_latency_ms", telemetry.medianLatencyMs)
                put("p95_latency_ms", telemetry.p95LatencyMs)
                put("dropped_frames", telemetry.droppedFrames)
                put("total_frames", telemetry.totalFrames)
                put("acked_packets", telemetry.ackedPackets)
                put("rx_kbps", telemetry.rxKbps)
                put("inference_fps", telemetry.inferenceFps)
                put("inference_ms", telemetry.inferenceLatencyMs)
                put("memory_mb", telemetry.memoryMb)
                put("thermal", telemetry.thermalStatusLabel)
            })
            val arr = JSONArray()
            logs.forEach { entry ->
                arr.put(JSONObject().apply {
                    put("ts", entry.timestampMs)
                    put("subsystem", entry.subsystem)
                    put("severity", entry.severity)
                    put("message", entry.message)
                    put("meta", entry.metadataJson)
                })
            }
            put("logs", arr)
        }

        val dir = File(context.filesDir, "diagnostics").apply { mkdirs() }
        val file = File(dir, "aerolink_diagnostics_${System.currentTimeMillis()}.json")
        file.writeText(root.toString(2))
        return file
    }
}
