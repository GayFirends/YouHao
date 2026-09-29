package com.youhao.fueltrack.data.sync

import com.youhao.fueltrack.domain.model.SyncPayloadV1
import com.youhao.fueltrack.domain.sync.DetectedConflict

/**
 * The slice of the data layer that the sync protocol touches. [com.youhao.fueltrack.data.FuelTrackStore]
 * implements it; tests supply a fake so the protocol can be exercised without Room.
 */
interface SyncStore {
    suspend fun exportData(): SyncPayloadV1

    /** Mirrors `mergeData`: applies every remote entity that wins against the local one. */
    suspend fun mergeData(remote: SyncPayloadV1)

    suspend fun saveConflicts(conflicts: List<DetectedConflict>)

    /** Deletes tombstones older than [cutoff]; returns how many rows went away. */
    suspend fun compactTombstones(cutoff: String): Int
}
