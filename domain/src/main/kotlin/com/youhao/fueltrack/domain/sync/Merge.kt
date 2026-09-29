package com.youhao.fueltrack.domain.sync

import com.youhao.fueltrack.domain.model.SyncEntity
import com.youhao.fueltrack.domain.model.SyncPayloadV1
import com.youhao.fueltrack.domain.time.Timestamps

/** Thrown when a row carries an unusable `updatedAt`, matching `记录更新时间无效，无法合并`. */
class InvalidSyncTimestampException(message: String = "记录更新时间无效，无法合并") : IllegalArgumentException(message)

private fun requireTimestamp(value: String): Long =
    Timestamps.parseOrNull(value)?.toEpochMilli() ?: throw InvalidSyncTimestampException()

/**
 * Last-writer-wins with two deterministic tie-breakers, ported from `conflict-resolution.ts`.
 *
 * 1. Later `updatedAt` wins.
 * 2. At the same instant a deletion beats an edit, so restoring an older copy cannot
 *    resurrect a row another device removed.
 * 3. Otherwise the canonical JSON with the greater ordering wins, so both devices
 *    independently reach the same answer without another round trip.
 */
fun shouldAcceptRemote(local: SyncEntity?, remote: SyncEntity): Boolean {
    val remoteTime = requireTimestamp(remote.updatedAt)
    if (local == null) return true

    val localTime = requireTimestamp(local.updatedAt)
    if (remoteTime != localTime) return remoteTime > localTime

    val remoteDeleted = remote.deletedAt != null
    val localDeleted = local.deletedAt != null
    if (remoteDeleted != localDeleted) return remoteDeleted

    return CanonicalJson.of(remote) > CanonicalJson.of(local)
}

/**
 * Merges [remote] into [local] record by record. Returns the merged payload together with
 * every id that was taken from the remote side, so callers can report what changed.
 */
data class MergeResult(
    val payload: SyncPayloadV1,
    val acceptedVehicleIds: Set<String>,
    val acceptedRecordIds: Set<String>,
)

fun mergePayloads(local: SyncPayloadV1, remote: SyncPayloadV1): MergeResult {
    val acceptedVehicles = mutableSetOf<String>()
    val acceptedRecords = mutableSetOf<String>()

    val vehiclesById = local.vehicles.associateByTo(LinkedHashMap()) { it.id }
    for (remoteVehicle in remote.vehicles) {
        if (shouldAcceptRemote(vehiclesById[remoteVehicle.id], remoteVehicle)) {
            vehiclesById[remoteVehicle.id] = remoteVehicle
            acceptedVehicles += remoteVehicle.id
        }
    }

    val recordsById = local.records.associateByTo(LinkedHashMap()) { it.id }
    for (remoteRecord in remote.records) {
        if (shouldAcceptRemote(recordsById[remoteRecord.id], remoteRecord)) {
            recordsById[remoteRecord.id] = remoteRecord
            acceptedRecords += remoteRecord.id
        }
    }

    return MergeResult(
        payload = local.copy(
            vehicles = vehiclesById.values.toList(),
            records = recordsById.values.toList(),
        ),
        acceptedVehicleIds = acceptedVehicles,
        acceptedRecordIds = acceptedRecords,
    )
}
