package com.youhao.fueltrack.data

import com.youhao.fueltrack.domain.model.FuelRecord
import com.youhao.fueltrack.domain.model.SyncEntity
import com.youhao.fueltrack.domain.model.SyncEntityType
import com.youhao.fueltrack.domain.model.Vehicle

/** One side of a stored conflict, decoded back into the concrete entity it came from. */
sealed interface ConflictValue {
    val entity: SyncEntity

    data class VehicleValue(val vehicle: Vehicle) : ConflictValue {
        override val entity: SyncEntity get() = vehicle
    }

    data class RecordValue(val record: FuelRecord) : ConflictValue {
        override val entity: SyncEntity get() = record
    }
}

/** A stored conflict ready for the resolution screen. */
data class ConflictDetail(
    val id: String,
    val entityType: SyncEntityType,
    val entityId: String,
    val detectedAt: String,
    val local: ConflictValue,
    val remote: ConflictValue,
)

enum class ConflictChoice { LOCAL, REMOTE }

internal fun ConflictChoice.wireName(): String = when (this) {
    ConflictChoice.LOCAL -> "local"
    ConflictChoice.REMOTE -> "remote"
}
