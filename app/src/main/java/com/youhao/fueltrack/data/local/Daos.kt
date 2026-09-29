package com.youhao.fueltrack.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface VehicleDao {

    @Query("SELECT * FROM vehicles ORDER BY createdAt")
    suspend fun all(): List<VehicleEntity>

    @Query("SELECT * FROM vehicles WHERE deletedAt IS NULL ORDER BY createdAt")
    suspend fun active(): List<VehicleEntity>

    @Query("SELECT * FROM vehicles WHERE id = :id LIMIT 1")
    suspend fun find(id: String): VehicleEntity?

    @Query("SELECT COUNT(*) FROM vehicles")
    suspend fun count(): Int

    /** Mirrors `INSERT ... ON CONFLICT(id) DO UPDATE` from the original adapter. */
    @Upsert
    suspend fun upsert(vehicle: VehicleEntity)

    @Upsert
    suspend fun upsertAll(vehicles: List<VehicleEntity>)

    @Query("UPDATE vehicles SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE id = :id")
    suspend fun markDeleted(id: String, deletedAt: String)

    @Query("SELECT COUNT(*) FROM vehicles WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun countTombstones(cutoff: String): Int

    @Query("DELETE FROM vehicles WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun purgeTombstones(cutoff: String): Int

    @Query("DELETE FROM vehicles")
    suspend fun deleteAll()
}

@Dao
interface FuelRecordDao {

    @Query("SELECT * FROM fuel_records ORDER BY date DESC, odometer DESC")
    suspend fun all(): List<FuelRecordEntity>

    @Query("SELECT * FROM fuel_records WHERE deletedAt IS NULL ORDER BY date DESC, odometer DESC")
    suspend fun active(): List<FuelRecordEntity>

    @Query("SELECT * FROM fuel_records WHERE id = :id LIMIT 1")
    suspend fun find(id: String): FuelRecordEntity?

    @Upsert
    suspend fun upsert(record: FuelRecordEntity)

    @Upsert
    suspend fun upsertAll(records: List<FuelRecordEntity>)

    @Query(
        "UPDATE fuel_records SET deletedAt = :deletedAt, updatedAt = :deletedAt " +
            "WHERE vehicleId = :vehicleId AND deletedAt IS NULL",
    )
    suspend fun markDeletedByVehicle(vehicleId: String, deletedAt: String)

    @Query("SELECT COUNT(*) FROM fuel_records WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun countTombstones(cutoff: String): Int

    @Query("DELETE FROM fuel_records WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun purgeTombstones(cutoff: String): Int

    @Query("DELETE FROM fuel_records")
    suspend fun deleteAll()
}

@Dao
interface SafetySnapshotDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(snapshot: SafetySnapshotEntity)

    @Query("SELECT payload FROM safety_snapshots WHERE id = :id LIMIT 1")
    suspend fun payload(id: String): String?

    @Query("SELECT * FROM safety_snapshots ORDER BY createdAt DESC")
    suspend fun all(): List<SafetySnapshotEntity>

    @Query("DELETE FROM safety_snapshots WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface SyncConflictDao {

    @Query("SELECT * FROM sync_conflicts ORDER BY detectedAt DESC")
    suspend fun all(): List<SyncConflictEntity>

    @Query("SELECT * FROM sync_conflicts WHERE id = :id LIMIT 1")
    suspend fun find(id: String): SyncConflictEntity?

    /** `INSERT OR IGNORE`: a conflict already under review keeps its original payloads. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(conflicts: List<SyncConflictEntity>)

    @Query("DELETE FROM sync_conflicts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM sync_conflicts")
    suspend fun count(): Int
}
