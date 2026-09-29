package com.youhao.fueltrack.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room schema mirroring the SQLite schema the Capacitor build shipped as `DATABASE_VERSION = 5`
 * (`src/services/database-native.ts`). The database file is deliberately *not* named
 * `fuel-track`, so an in-place upgrade from the old WebView build starts from a clean database
 * instead of tripping Room's schema-identity check.
 */

@Entity(
    tableName = "vehicles",
    indices = [Index(value = ["deletedAt"])],
)
data class VehicleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val plate: String,
    val fuelType: String,
    val initialOdometer: Double,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String?,
)

@Entity(
    tableName = "fuel_records",
    foreignKeys = [
        ForeignKey(
            entity = VehicleEntity::class,
            parentColumns = ["id"],
            childColumns = ["vehicleId"],
        ),
    ],
    indices = [
        Index(value = ["vehicleId", "date"]),
        Index(value = ["deletedAt"]),
    ],
)
data class FuelRecordEntity(
    @PrimaryKey val id: String,
    val vehicleId: String,
    val date: String,
    val odometer: Double,
    val liters: Double,
    val amount: Double,
    val pumpAmount: Double,
    val pricePerLiter: Double,
    val isFull: Boolean,
    val station: String,
    val note: String,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String?,
)

@Entity(tableName = "safety_snapshots")
data class SafetySnapshotEntity(
    @PrimaryKey val id: String,
    val createdAt: String,
    val payload: String,
)

/** `entityType` holds the wire name (`vehicle` / `record`); payloads are stored as JSON text. */
@Entity(
    tableName = "sync_conflicts",
    indices = [Index(value = ["detectedAt"])],
)
data class SyncConflictEntity(
    @PrimaryKey val id: String,
    val entityType: String,
    val entityId: String,
    val localJson: String,
    val remoteJson: String,
    val detectedAt: String,
)
