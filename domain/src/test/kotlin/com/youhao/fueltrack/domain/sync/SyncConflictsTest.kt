package com.youhao.fueltrack.domain.sync

import com.google.common.truth.Truth.assertThat
import com.youhao.fueltrack.domain.model.FuelRecord
import com.youhao.fueltrack.domain.model.SyncPayloadV1
import com.youhao.fueltrack.domain.model.Vehicle
import kotlin.test.Test

/**
 * Conflict detection ported from `src/services/sync-conflicts.ts`. Only two-sided edits away
 * from the shared baseline count; one-sided edits are mergeable and must not raise a conflict.
 */
class SyncConflictsTest {

    private companion object {
        const val T0 = "2024-01-01T00:00:00.000Z"
        const val T2 = "2024-01-02T00:00:00.000Z"
        const val T3 = "2024-01-03T00:00:00.000Z"
        const val DETECTED_AT = "2024-06-01T00:00:00.000Z"
    }

    private fun vehicle(id: String = "v1", name: String = "原", updatedAt: String = T0) =
        Vehicle(
            id = id,
            name = name,
            plate = "",
            fuelType = "92",
            initialOdometer = 0.0,
            createdAt = T0,
            updatedAt = updatedAt,
        )

    private fun record(id: String = "r1", liters: Double = 40.0, updatedAt: String = T0) =
        FuelRecord(
            id = id,
            vehicleId = "v1",
            date = "2024-01-01",
            odometer = 1000.0,
            liters = liters,
            amount = liters * 7.5,
            pumpAmount = liters * 7.5,
            pricePerLiter = 7.5,
            isFull = true,
            station = "",
            note = "",
            createdAt = T0,
            updatedAt = updatedAt,
        )

    private fun payload(vehicles: List<Vehicle> = emptyList(), records: List<FuelRecord> = emptyList()) =
        SyncPayloadV1(exportedAt = T0, vehicles = vehicles, records = records)

    @Test
    fun hashIsStableLowercaseHexOfSixtyFourChars() {
        val first = hashEntity(vehicle())
        val second = hashEntity(vehicle())

        assertThat(first).isEqualTo(second)
        assertThat(first).hasLength(64)
        assertThat(first).matches("[0-9a-f]{64}")
        assertThat(hashEntity(vehicle(name = "别的"))).isNotEqualTo(first)
    }

    @Test
    fun buildSyncBaseIndexesEveryRowById() {
        val base = buildSyncBase(payload(vehicles = listOf(vehicle()), records = listOf(record())))

        assertThat(base.vehicles.keys).containsExactly("v1")
        assertThat(base.records.keys).containsExactly("r1")
        assertThat(base.vehicles.getValue("v1")).isEqualTo(hashEntity(vehicle()))
    }

    @Test
    fun detectsATwoSidedEdit() {
        val base = buildSyncBase(payload(vehicles = listOf(vehicle(name = "原"))))
        val local = payload(vehicles = listOf(vehicle(name = "本地", updatedAt = T2)))
        val remote = payload(vehicles = listOf(vehicle(name = "远端", updatedAt = T3)))

        val conflicts = detectSyncConflicts(local, remote, base, DETECTED_AT)

        assertThat(conflicts).hasSize(1)
        val conflict = conflicts.single()
        assertThat(conflict.id).isEqualTo("vehicle:v1")
        assertThat(conflict.entityId).isEqualTo("v1")
        assertThat(conflict.detectedAt).isEqualTo(DETECTED_AT)
    }

    @Test
    fun anUntouchedLocalRowIsNotAConflict() {
        val base = buildSyncBase(payload(vehicles = listOf(vehicle(name = "原"))))
        val local = payload(vehicles = listOf(vehicle(name = "原")))
        val remote = payload(vehicles = listOf(vehicle(name = "远端", updatedAt = T3)))

        assertThat(detectSyncConflicts(local, remote, base, DETECTED_AT)).isEmpty()
    }

    @Test
    fun anUntouchedRemoteRowIsNotAConflict() {
        val base = buildSyncBase(payload(vehicles = listOf(vehicle(name = "原"))))
        val local = payload(vehicles = listOf(vehicle(name = "本地", updatedAt = T2)))
        val remote = payload(vehicles = listOf(vehicle(name = "原")))

        assertThat(detectSyncConflicts(local, remote, base, DETECTED_AT)).isEmpty()
    }

    @Test
    fun bothSidesReachingTheSameValueIsNotAConflict() {
        val base = buildSyncBase(payload(vehicles = listOf(vehicle(name = "原"))))
        val same = payload(vehicles = listOf(vehicle(name = "一样", updatedAt = T3)))

        assertThat(detectSyncConflicts(same, same, base, DETECTED_AT)).isEmpty()
    }

    @Test
    fun aRowMissingFromTheBaselineIsNotAConflict() {
        val base = SyncBase.EMPTY
        val local = payload(vehicles = listOf(vehicle(name = "本地", updatedAt = T2)))
        val remote = payload(vehicles = listOf(vehicle(name = "远端", updatedAt = T3)))

        assertThat(detectSyncConflicts(local, remote, base, DETECTED_AT)).isEmpty()
    }

    @Test
    fun detectsRecordConflictsToo() {
        val base = buildSyncBase(payload(vehicles = listOf(vehicle()), records = listOf(record(liters = 40.0))))
        val local = payload(vehicles = listOf(vehicle()), records = listOf(record(liters = 45.0, updatedAt = T2)))
        val remote = payload(vehicles = listOf(vehicle()), records = listOf(record(liters = 50.0, updatedAt = T3)))

        val conflicts = detectSyncConflicts(local, remote, base, DETECTED_AT)

        assertThat(conflicts).hasSize(1)
        assertThat(conflicts.single().entityType.wireName).isEqualTo("record")
        assertThat(conflicts.single().id).isEqualTo("record:r1")
    }

    @Test
    fun withoutConflictsDropsOnlyTheConflictingRows() {
        val payload = payload(
            vehicles = listOf(vehicle("v1"), vehicle("v2")),
            records = listOf(record("r1"), record("r2")),
        )
        val conflict = DetectedConflict(
            id = "vehicle:v1",
            entityType = com.youhao.fueltrack.domain.model.SyncEntityType.VEHICLE,
            entityId = "v1",
            localValue = vehicle("v1"),
            remoteValue = vehicle("v1"),
            detectedAt = DETECTED_AT,
        )

        val filtered = withoutConflicts(payload, listOf(conflict))

        assertThat(filtered.vehicles.map { it.id }).containsExactly("v2")
        assertThat(filtered.records.map { it.id }).containsExactly("r1", "r2")
    }

    @Test
    fun withoutConflictsIsANoOpWithoutConflicts() {
        val payload = payload(vehicles = listOf(vehicle()), records = listOf(record()))

        assertThat(withoutConflicts(payload, emptyList())).isEqualTo(payload)
    }
}
