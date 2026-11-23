package com.example.storage.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "drone_profiles")
data class DroneProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val commercialName: String,
    val ssidPattern: String,
    val familyId: String,
    val gatewayIp: String,
    val videoPort: Int,
    val controlPort: Int,
    val videoTransportName: String,
    val sendRateHz: Int = 25,
    val invertPitch: Boolean = false,
    val invertRoll: Boolean = false,
    val invertYaw: Boolean = false,
    val supportsVideo: Boolean = true,
    val supportsFlightControl: Boolean = false,
    val supportsTakeOffLand: Boolean = false,
    val supportsEmergencyStop: Boolean = false,
    val supportsGyroCalibration: Boolean = false,
    val supportsSpeedModes: Boolean = false,
    val supportsHeadless: Boolean = false,
    val supportsFlip: Boolean = false,
    val supportsDualCamera: Boolean = false,
    val supportsCameraTilt: Boolean = false,
    val isProtocolVerified: Boolean = false,
    val lastConnectedMs: Long = 0L
)

@Entity(tableName = "flight_sessions")
data class FlightSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ssid: String,
    val familyId: String,
    val startedAtMs: Long,
    val durationSec: Long,
    val avgVideoFps: Float,
    val medianLatencyMs: Long,
    val p95LatencyMs: Long,
    val droppedFrames: Long,
    val capturesCount: Int
)

@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val mediaType: String, // "PHOTO" or "VIDEO"
    val filePath: String,
    val ssid: String,
    val familyId: String,
    val width: Int,
    val height: Int,
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
    val createdAtMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "diagnostic_logs")
data class DiagnosticLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subsystem: String, // NETWORK, VIDEO, AI, FLIGHT
    val severity: String,  // INFO, WARN, ERROR
    val message: String,
    val metadataJson: String = "{}",
    val timestampMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "benchmark_reports")
data class BenchmarkReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val modelName: String,
    val backend: String, // CPU, GPU, NNAPI
    val deviceModel: String,
    val avgInferenceMs: Float,
    val medianInferenceMs: Long,
    val p95InferenceMs: Long,
    val inferenceFps: Float,
    val memoryMb: Int,
    val thermalStatus: String,
    val iterations: Int,
    val createdAtMs: Long = System.currentTimeMillis()
)

@Dao
interface DroneProfileDao {
    @Query("SELECT * FROM drone_profiles ORDER BY lastConnectedMs DESC, id ASC")
    fun observeAll(): Flow<List<DroneProfileEntity>>

    @Query("SELECT * FROM drone_profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): DroneProfileEntity?

    @Query("SELECT COUNT(*) FROM drone_profiles")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: DroneProfileEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(profiles: List<DroneProfileEntity>)

    @Query("DELETE FROM drone_profiles WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface FlightSessionDao {
    @Query("SELECT * FROM flight_sessions ORDER BY startedAtMs DESC")
    fun observeAll(): Flow<List<FlightSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: FlightSessionEntity): Long

    @Query("DELETE FROM flight_sessions")
    suspend fun clearAll()
}

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY createdAtMs DESC")
    fun observeAll(): Flow<List<RecordingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recording: RecordingEntity): Long

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM recordings")
    suspend fun clearAll()
}

@Dao
interface DiagnosticLogDao {
    @Query("SELECT * FROM diagnostic_logs ORDER BY timestampMs DESC LIMIT 250")
    fun observeRecent(): Flow<List<DiagnosticLogEntity>>

    @Query("SELECT * FROM diagnostic_logs ORDER BY timestampMs DESC LIMIT 500")
    suspend fun getRecentSnapshot(): List<DiagnosticLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: DiagnosticLogEntity): Long

    @Query("DELETE FROM diagnostic_logs")
    suspend fun clearAll()
}

@Dao
interface BenchmarkReportDao {
    @Query("SELECT * FROM benchmark_reports ORDER BY createdAtMs DESC LIMIT 40")
    fun observeAll(): Flow<List<BenchmarkReportEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(report: BenchmarkReportEntity): Long

    @Query("DELETE FROM benchmark_reports")
    suspend fun clearAll()
}
