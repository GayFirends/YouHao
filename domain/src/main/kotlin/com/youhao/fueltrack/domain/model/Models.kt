package com.youhao.fueltrack.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Domain model for 油迹 Fuel Track.
 *
 * Field names and nullability mirror `src/types/index.ts` from the original Vue/Capacitor
 * implementation so that sync payloads stay wire-compatible.
 */

/** Anything that participates in the WebDAV merge, keyed by id + updatedAt. */
interface SyncEntity {
    val id: String
    val updatedAt: String
    val deletedAt: String?
}

@Serializable
data class Vehicle(
    override val id: String,
    val name: String,
    val plate: String,
    val fuelType: String,
    val initialOdometer: Double,
    val createdAt: String,
    override val updatedAt: String,
    override val deletedAt: String? = null,
) : SyncEntity

@Serializable
data class FuelRecord(
    override val id: String,
    val vehicleId: String,
    /** Local calendar date, `yyyy-MM-dd`. */
    val date: String,
    val odometer: Double,
    val liters: Double,
    /** Amount actually charged after any discount. */
    val amount: Double,
    /** Amount shown on the pump, before discount. */
    val pumpAmount: Double,
    val pricePerLiter: Double,
    val isFull: Boolean,
    val station: String,
    val note: String,
    val createdAt: String,
    override val updatedAt: String,
    override val deletedAt: String? = null,
) : SyncEntity

@Serializable
data class WebDavConfig(
    val url: String,
    val username: String,
    val password: String,
    val fileName: String,
    val encryptionEnabled: Boolean? = null,
    val encryptionPassphrase: String? = null,
    val rememberEncryptionPassphrase: Boolean? = null,
    /**
     * Whether the WebDAV password may be kept in the keystore. Off by default, because it is the
     * only thing that would ever put the password into persistent storage — and the only way a
     * background sync can authenticate once the process has been killed.
     */
    val rememberPassword: Boolean? = null,
)

// ---------------------------------------------------------------------------
// Sync wire format
// ---------------------------------------------------------------------------

@Serializable
data class SyncPayloadV1(
    override val version: Int = 1,
    val exportedAt: String,
    val vehicles: List<Vehicle> = emptyList(),
    val records: List<FuelRecord> = emptyList(),
) : SyncDocument

/** Backwards-compatible name used by v1 backups and the local database. */
typealias SyncPayload = SyncPayloadV1

@Serializable
data class EncryptedSyncEnvelopeV2(
    override val version: Int = 2,
    val encrypted: Boolean = true,
    val createdAt: String,
    val crypto: CryptoParams,
    val compression: String = "gzip",
    val ciphertext: String,
) : SyncDocument

@Serializable
data class CryptoParams(
    val algorithm: String = "AES-GCM",
    val kdf: String = "PBKDF2-SHA-256",
    val iterations: Int,
    /** Base64. */
    val salt: String,
    /** Base64. */
    val iv: String,
)

/** A sync file is either a plaintext v1 payload or a v2 encrypted envelope. */
sealed interface SyncDocument {
    val version: Int
}

// ---------------------------------------------------------------------------
// Queries and projections
// ---------------------------------------------------------------------------

@Serializable
data class RecordCursor(
    val date: String,
    val odometer: Double,
    val createdAt: String,
    val id: String,
)

data class RecordListQuery(
    val vehicleId: String? = null,
    val includeDeleted: Boolean = false,
    val search: String? = null,
    /** `yyyy-MM`. */
    val month: String? = null,
    val limit: Int? = null,
    val cursor: RecordCursor? = null,
)

data class RecordPage(
    val items: List<FuelRecord>,
    val nextCursor: RecordCursor? = null,
)

data class VehicleSummary(
    val vehicleId: String,
    val recordCount: Int,
    val totalPaid: Double,
    val currentOdometer: Double? = null,
)

@Serializable
data class SafetySnapshot(
    val id: String,
    val createdAt: String,
)

@Serializable
data class SchemaInfo(
    val version: Int,
    val backend: String,
)

// ---------------------------------------------------------------------------
// Sync bookkeeping
// ---------------------------------------------------------------------------

@Serializable
data class SyncConflict(
    val id: String,
    @SerialName("entityType") val entityType: SyncEntityType,
    val entityId: String,
    val detectedAt: String,
)

@Serializable
enum class SyncEntityType {
    @SerialName("vehicle") VEHICLE,
    @SerialName("record") RECORD,
}

@Serializable
data class SyncDeviceState(
    val deviceId: String,
    val lastSeenAt: String,
    val acknowledgedThrough: String,
)

@Serializable
data class SyncMetadata(
    val version: Int = 1,
    val devices: Map<String, SyncDeviceState> = emptyMap(),
)

enum class ViewName {
    OVERVIEW, RECORDS, VEHICLES, SETTINGS
}

object StorageBackends {
    const val ANDROID_NATIVE_SQLITE = "android-native-sqlite"
}
