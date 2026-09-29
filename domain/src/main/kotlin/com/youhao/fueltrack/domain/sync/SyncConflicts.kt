package com.youhao.fueltrack.domain.sync

import com.youhao.fueltrack.domain.model.SyncEntity
import com.youhao.fueltrack.domain.model.SyncEntityType
import com.youhao.fueltrack.domain.model.SyncPayloadV1
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * Per-device snapshot of the last successfully synced revision of every row, keyed by id.
 * Ported from the `fuel-track-sync-base-v1:<target>` localStorage entry in `sync-conflicts.ts`.
 *
 * The Android port keeps this in DataStore rather than the WebView's localStorage.
 */
@Serializable
data class SyncBase(
    val vehicles: Map<String, String> = emptyMap(),
    val records: Map<String, String> = emptyMap(),
) {
    companion object {
        val EMPTY = SyncBase()
    }
}

/** A detected two-sided edit. Carries both revisions so the UI can offer a choice. */
data class DetectedConflict(
    val id: String,
    val entityType: SyncEntityType,
    val entityId: String,
    val localValue: SyncEntity,
    val remoteValue: SyncEntity,
    val detectedAt: String,
)

/** SHA-256 of the canonical JSON, hex-encoded — same digest the WebCrypto version produced. */
fun hashEntity(entity: SyncEntity): String =
    MessageDigest.getInstance("SHA-256")
        .digest(CanonicalJson.of(entity).toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }

/** Records the current revisions as the new conflict-detection baseline. */
fun buildSyncBase(payload: SyncPayloadV1): SyncBase = SyncBase(
    vehicles = payload.vehicles.associate { it.id to hashEntity(it) },
    records = payload.records.associate { it.id to hashEntity(it) },
)

/**
 * Finds rows both sides changed away from the last shared baseline in different directions.
 * Those are the only cases the automatic merge must not silently decide.
 */
fun detectSyncConflicts(
    local: SyncPayloadV1,
    remote: SyncPayloadV1,
    base: SyncBase,
    detectedAt: String,
): List<DetectedConflict> {
    val conflicts = mutableListOf<DetectedConflict>()

    fun compare(
        entityType: SyncEntityType,
        localItems: List<SyncEntity>,
        remoteItems: List<SyncEntity>,
        baseHashes: Map<String, String>,
    ) {
        val localById = localItems.associateBy { it.id }
        for (remoteValue in remoteItems) {
            val localValue = localById[remoteValue.id] ?: continue
            val baseHash = baseHashes[remoteValue.id] ?: continue
            val localHash = hashEntity(localValue)
            val remoteHash = hashEntity(remoteValue)
            if (localHash != baseHash && remoteHash != baseHash && localHash != remoteHash) {
                conflicts += DetectedConflict(
                    id = "${entityType.wireName}:${remoteValue.id}",
                    entityType = entityType,
                    entityId = remoteValue.id,
                    localValue = localValue,
                    remoteValue = remoteValue,
                    detectedAt = detectedAt,
                )
            }
        }
    }

    compare(SyncEntityType.VEHICLE, local.vehicles, remote.vehicles, base.vehicles)
    compare(SyncEntityType.RECORD, local.records, remote.records, base.records)

    return conflicts
}

/** Drops every conflicting row so an upload cannot clobber the user's other-device edits. */
fun withoutConflicts(payload: SyncPayloadV1, conflicts: List<DetectedConflict>): SyncPayloadV1 {
    val vehicleIds = conflicts.filter { it.entityType == SyncEntityType.VEHICLE }
        .mapTo(mutableSetOf()) { it.entityId }
    val recordIds = conflicts.filter { it.entityType == SyncEntityType.RECORD }
        .mapTo(mutableSetOf()) { it.entityId }
    return payload.copy(
        vehicles = payload.vehicles.filterNot { it.id in vehicleIds },
        records = payload.records.filterNot { it.id in recordIds },
    )
}

/** `vehicle` / `record`, the strings the conflict id and the sync file use. */
val SyncEntityType.wireName: String
    get() = when (this) {
        SyncEntityType.VEHICLE -> "vehicle"
        SyncEntityType.RECORD -> "record"
    }
