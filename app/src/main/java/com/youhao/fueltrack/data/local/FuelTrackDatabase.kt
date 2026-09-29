package com.youhao.fueltrack.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        VehicleEntity::class,
        FuelRecordEntity::class,
        SafetySnapshotEntity::class,
        SyncConflictEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class FuelTrackDatabase : RoomDatabase() {

    abstract fun vehicles(): VehicleDao
    abstract fun records(): FuelRecordDao
    abstract fun snapshots(): SafetySnapshotDao
    abstract fun conflicts(): SyncConflictDao

    companion object {
        /**
         * Deliberately not `fuel-track`: the Capacitor build created a database file by that
         * name with a schema Room did not author. A fresh filename keeps an in-place upgrade
         * from the old WebView build from failing Room's schema-identity check.
         */
        const val NAME = "fueltrack.db"

        fun create(context: Context): FuelTrackDatabase =
            Room.databaseBuilder(context.applicationContext, FuelTrackDatabase::class.java, NAME)
                // Pre-1.0 there is no data to migrate and the schema is still moving; wiping
                // beats shipping half-written migrations. Also required for a clean downgrade
                // path, since `dropAllTables` respects foreign-key ordering.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
