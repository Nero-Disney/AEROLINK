package com.example.storage.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        DroneProfileEntity::class,
        FlightSessionEntity::class,
        RecordingEntity::class,
        DiagnosticLogEntity::class,
        BenchmarkReportEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AeroLinkDatabase : RoomDatabase() {
    abstract fun droneProfileDao(): DroneProfileDao
    abstract fun flightSessionDao(): FlightSessionDao
    abstract fun recordingDao(): RecordingDao
    abstract fun diagnosticLogDao(): DiagnosticLogDao
    abstract fun benchmarkReportDao(): BenchmarkReportDao

    companion object {
        @Volatile
        private var INSTANCE: AeroLinkDatabase? = null

        fun getInstance(context: Context): AeroLinkDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AeroLinkDatabase::class.java,
                    "aerolink_drone_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
