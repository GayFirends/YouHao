package com.youhao.fueltrack.data

import androidx.room.withTransaction
import com.youhao.fueltrack.domain.error.AppErrorCode
import com.youhao.fueltrack.domain.error.AppException
import com.youhao.fueltrack.data.local.FuelTrackDatabase
import com.youhao.fueltrack.data.local.SafetySnapshotEntity
import com.youhao.fueltrack.data.local.SyncConflictEntity
import com.youhao.fueltrack.data.local.VehicleEntity
import com.youhao.fueltrack.data.local.toDomain
import com.youhao.fueltrack.data.local.toEntity
import com.youhao.fueltrack.data.sync.SyncStore
import com.youhao.fueltrack.domain.model.FuelRecord
import com.youhao.fueltrack.domain.model.RecordListQuery
import com.youhao.fueltrack.domain.model.RecordPage
import com.youhao.fueltrack.domain.model.SafetySnapshot
import com.youhao.fueltrack.domain.model.SchemaInfo
import com.youhao.fueltrack.domain.model.StorageBackends
import com.youhao.fueltrack.domain.model.SyncEntity
import com.youhao.fueltrack.domain.model.SyncEntityType
import com.youhao.fueltrack.domain.model.SyncPayloadV1
import com.youhao.fueltrack.domain.model.Vehicle
import com.youhao.fueltrack.domain.model.VehicleSummary
import com.youhao.fueltrack.domain.query.listRecordPage
import com.youhao.fueltrack.domain.query.summarizeVehicle
import com.youhao.fueltrack.domain.sync.DetectedConflict
import com.youhao.fueltrack.domain.sync.SyncJson
import com.youhao.fueltrack.domain.sync.shouldAcceptRemote
import com.youhao.fueltrack.domain.sync.wireName
import com.youhao.fueltrack.domain.time.Timestamps
import java.util.UUID

/**
 * The single write path to local storage — the Room equivalent of the original
 * `DatabaseAdapter` interface plus the serialising `enqueue()` wrapper in `database.ts`.
 *
 * Room already funnels concurrent writers through SQLite, so the hand-rolled promise queue is
 * replaced by `withTransaction` wherever two statements must land together.
 */
class FuelTrackStore(private val db: FuelTrackDatabase) : SyncStore {

    private val vehicleDao = db.vehicles()
    private val recordDao = db.records()
    private val snapshotDao = db.snapshots()
    private val conflictDao = db.conflicts()

    // -----------------------------------------------------------------------
    // Reads
    // -----------------------------------------------------------------------

    suspend fun vehicles(includeDeleted: Boolean = false): List<Vehicle> =
        (if (includeDeleted) vehicleDao.all() else vehicleDao.active()).map { it.toDomain() }

    suspend fun records(includeDeleted: Boolean = false): List<FuelRecord> =
        (if (includeDeleted) recordDao.all() else recordDao.active()).map { it.toDomain() }

    /** Paging matches the shipped Android build: filter and slice the full list in memory. */
    suspend fun listRecords(query: RecordListQuery): RecordPage =
        listRecordPage(records(includeDeleted = true), query)

    suspend fun getRecord(id: String): FuelRecord? = recordDao.find(id)?.toDomain()

    suspend fun getVehicleSummary(vehicleId: String): VehicleSummary =
        summarizeVehicle(records(includeDeleted = true), vehicleId)

    suspend fun getSchemaInfo(): SchemaInfo = SchemaInfo(
        version = db.openHelper.writableDatabase.version,
        backend = StorageBackends.ANDROID_NATIVE_SQLITE,
    )

    // -----------------------------------------------------------------------
    // Writes
    // -----------------------------------------------------------------------

    suspend fun saveVehicle(vehicle: Vehicle) {
        vehicleDao.upsert(vehicle.toEntity())
    }

    suspend fun saveRecord(record: FuelRecord) {
        recordDao.upsert(record.toEntity())
    }

    suspend fun deleteVehicle(vehicleId: String, deletedAt: String) {
        db.withTransaction {
            vehicleDao.markDeleted(vehicleId, deletedAt)
            recordDao.markDeletedByVehicle(vehicleId, deletedAt)
        }
    }

    /** Seeds the first vehicle so a brand-new install is not an empty screen. */
    suspend fun ensureSeedVehicle() {
        if (vehicleDao.count() > 0) return
        val now = Timestamps.nowIso()
        vehicleDao.upsert(
            VehicleEntity(
                id = UUID.randomUUID().toString(),
                name = "我的车辆",
                plate = "",
                fuelType = "92#",
                initialOdometer = 0.0,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            ),
        )
    }

    // -----------------------------------------------------------------------
    // Sync support
    // -----------------------------------------------------------------------

    override suspend fun exportData(): SyncPayloadV1 = SyncPayloadV1(
        version = 1,
        exportedAt = Timestamps.nowIso(),
        vehicles = vehicles(includeDeleted = true),
        records = records(includeDeleted = true),
    )

    override suspend fun mergeData(remote: SyncPayloadV1) {
        db.withTransaction {
            val localVehicles = vehicleDao.all().associateBy { it.id }
            val localRecords = recordDao.all().associateBy { it.id }

            val acceptedVehicles = remote.vehicles.filter { candidate ->
                shouldAcceptRemote(localVehicles[candidate.id]?.toDomain(), candidate)
            }
            val acceptedRecords = remote.records.filter { candidate ->
                shouldAcceptRemote(localRecords[candidate.id]?.toDomain(), candidate)
            }

            if (acceptedVehicles.isNotEmpty()) vehicleDao.upsertAll(acceptedVehicles.map { it.toEntity() })
            if (acceptedRecords.isNotEmpty()) recordDao.upsertAll(acceptedRecords.map { it.toEntity() })
        }
    }

    suspend fun createSafetySnapshot(): SafetySnapshot {
        val id = UUID.randomUUID().toString()
        val createdAt = Timestamps.nowIso()
        val payload = SyncPayloadV1(
            version = 1,
            exportedAt = createdAt,
            vehicles = vehicles(includeDeleted = true),
            records = records(includeDeleted = true),
        )
        snapshotDao.insert(SafetySnapshotEntity(id = id, createdAt = createdAt, payload = SyncJson.encodeToString(payload)))
        return SafetySnapshot(id = id, createdAt = createdAt)
    }

    suspend fun restoreSafetySnapshot(id: String) {
        val text = snapshotDao.payload(id)
            ?: throw AppException(AppErrorCode.UNKNOWN, "安全快照不存在")
        val payload = SyncJson.decodeFromString<SyncPayloadV1>(text)
        db.withTransaction {
            // Records first: they hold the foreign key into vehicles.
            recordDao.deleteAll()
            vehicleDao.deleteAll()
            if (payload.vehicles.isNotEmpty()) vehicleDao.upsertAll(payload.vehicles.map { it.toEntity() })
            if (payload.records.isNotEmpty()) recordDao.upsertAll(payload.records.map { it.toEntity() })
        }
    }

    override suspend fun compactTombstones(cutoff: String): Int = db.withTransaction {
        val records = recordDao.purgeTombstones(cutoff)
        val vehicles = vehicleDao.purgeTombstones(cutoff)
        records + vehicles
    }

    // -----------------------------------------------------------------------
    // Conflicts
    // -----------------------------------------------------------------------

    suspend fun conflicts(): List<ConflictDetail> = conflictDao.all().map { it.toDetail() }

    override suspend fun saveConflicts(conflicts: List<DetectedConflict>) {
        if (conflicts.isEmpty()) return
        conflictDao.insertAll(conflicts.map { it.toEntity() })
    }

    suspend fun resolveConflict(id: String, choice: ConflictChoice, merged: SyncEntity? = null) {
        val row = conflictDao.find(id)
            ?: throw AppException(AppErrorCode.UNKNOWN, "同步冲突不存在或已处理")
        val entityType = row.entityType.toEntityType()
        val selected = merged
            ?: decodeValue(entityType, if (choice == ConflictChoice.LOCAL) row.localJson else row.remoteJson)
        val resolved = selected.withUpdatedAt(Timestamps.nowIso())

        db.withTransaction {
            when (resolved) {
                is Vehicle -> vehicleDao.upsert(resolved.toEntity())
                is FuelRecord -> recordDao.upsert(resolved.toEntity())
                else -> throw AppException(AppErrorCode.UNKNOWN, "同步冲突的数据类型不受支持")
            }
            conflictDao.delete(id)
        }
    }

    suspend fun conflictCount(): Int = conflictDao.count()

    // -----------------------------------------------------------------------
    // Mapping helpers
    // -----------------------------------------------------------------------

    private fun DetectedConflict.toEntity() = SyncConflictEntity(
        id = id,
        entityType = entityType.wireName,
        entityId = entityId,
        localJson = encodeValue(localValue),
        remoteJson = encodeValue(remoteValue),
        detectedAt = detectedAt,
    )

    private fun SyncConflictEntity.toDetail(): ConflictDetail {
        val type = entityType.toEntityType()
        return ConflictDetail(
            id = id,
            entityType = type,
            entityId = entityId,
            detectedAt = detectedAt,
            local = decodeValue(type, localJson).toConflictValue(),
            remote = decodeValue(type, remoteJson).toConflictValue(),
        )
    }

    private fun String.toEntityType(): SyncEntityType = when (this) {
        "vehicle" -> SyncEntityType.VEHICLE
        "record" -> SyncEntityType.RECORD
        else -> throw AppException(AppErrorCode.UNKNOWN, "同步冲突的实体类型无法识别：$this")
    }

    private fun encodeValue(entity: SyncEntity): String = when (entity) {
        is Vehicle -> SyncJson.encodeToString(Vehicle.serializer(), entity)
        is FuelRecord -> SyncJson.encodeToString(FuelRecord.serializer(), entity)
        else -> throw AppException(AppErrorCode.UNKNOWN, "同步冲突的数据类型不受支持")
    }

    private fun decodeValue(entityType: SyncEntityType, json: String): SyncEntity = when (entityType) {
        SyncEntityType.VEHICLE -> SyncJson.decodeFromString(Vehicle.serializer(), json)
        SyncEntityType.RECORD -> SyncJson.decodeFromString(FuelRecord.serializer(), json)
    }

    private fun SyncEntity.toConflictValue(): ConflictValue = when (this) {
        is Vehicle -> ConflictValue.VehicleValue(this)
        is FuelRecord -> ConflictValue.RecordValue(this)
        else -> throw AppException(AppErrorCode.UNKNOWN, "同步冲突的数据类型不受支持")
    }

    private fun SyncEntity.withUpdatedAt(value: String): SyncEntity = when (this) {
        is Vehicle -> copy(updatedAt = value)
        is FuelRecord -> copy(updatedAt = value)
        else -> this
    }
}
