package com.youhao.fueltrack.domain.sync

import com.google.common.truth.Truth.assertThat
import com.youhao.fueltrack.domain.model.FuelRecord
import com.youhao.fueltrack.domain.model.SyncPayloadV1
import com.youhao.fueltrack.domain.model.Vehicle
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Last-writer-wins merge ported from `src/services/conflict-resolution.ts` and `webdav.ts`.
 * Both devices must reach the same answer without another round trip.
 */
class MergeTest {

    private companion object {
        const val T0 = "2024-01-01T00:00:00.000Z"
        const val T1 = "2024-01-02T00:00:00.000Z"
        const val T2 = "2024-01-03T00:00:00.000Z"
    }

    private fun vehicle(
        id: String,
        updatedAt: String,
        name: String = "车",
        deletedAt: String? = null,
    ) = Vehicle(
        id = id,
        name = name,
        plate = "",
        fuelType = "92",
        initialOdometer = 0.0,
        createdAt = T0,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

    private fun record(
        id: String,
        vehicleId: String = "v1",
        updatedAt: String,
        liters: Double = 40.0,
        deletedAt: String? = null,
    ) = FuelRecord(
        id = id,
        vehicleId = vehicleId,
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
        deletedAt = deletedAt,
    )

    private fun payload(vehicles: List<Vehicle> = emptyList(), records: List<FuelRecord> = emptyList()) =
        SyncPayloadV1(exportedAt = T0, vehicles = vehicles, records = records)

    @Test
    fun acceptsRemoteWhenNothingIsStoredLocally() {
        assertThat(shouldAcceptRemote(null, vehicle("v1", T1))).isTrue()
    }

    @Test
    fun laterUpdateWins() {
        assertThat(shouldAcceptRemote(vehicle("v1", T0), vehicle("v1", T1))).isTrue()
        assertThat(shouldAcceptRemote(vehicle("v1", T2), vehicle("v1", T1))).isFalse()
    }

    @Test
    fun identicalInstantKeepsTheLocalValue() {
        assertThat(shouldAcceptRemote(vehicle("v1", T1), vehicle("v1", T1))).isFalse()
    }

    @Test
    fun deletionBeatsAnEditAtTheSameInstant() {
        // Restoring an older copy must not resurrect a row another device removed.
        assertThat(shouldAcceptRemote(vehicle("v1", T1), vehicle("v1", T1, deletedAt = T1))).isTrue()
        assertThat(shouldAcceptRemote(vehicle("v1", T1, deletedAt = T1), vehicle("v1", T1))).isFalse()
    }

    @Test
    fun canonicalJsonBreaksTiesDeterministically() {
        val local = vehicle("v1", T1, name = "A")
        val remote = vehicle("v1", T1, name = "B")

        // Both sides must agree on exactly one winner, and it is the greater canonical JSON.
        assertThat(shouldAcceptRemote(local, remote)).isTrue()
        assertThat(shouldAcceptRemote(remote, local)).isFalse()
        assertThat(CanonicalJson.of(remote) > CanonicalJson.of(local)).isTrue()
    }

    @Test
    fun rejectsUnusableTimestamps() {
        val failure = assertFailsWith<InvalidSyncTimestampException> {
            shouldAcceptRemote(null, vehicle("v1", "not-a-time"))
        }
        assertThat(failure).hasMessageThat().isEqualTo("记录更新时间无效，无法合并")
    }

    @Test
    fun rejectsUnusableRemoteTimestampsEvenWithNoLocalRow() {
        assertFailsWith<InvalidSyncTimestampException> {
            shouldAcceptRemote(vehicle("v1", T1), vehicle("v1", ""))
        }
    }

    @Test
    fun mergesRowByRowAndReportsWhatItTook() {
        val local = payload(
            vehicles = listOf(vehicle("v1", T1, name = "本地")),
            records = listOf(record("r1", updatedAt = T1)),
        )
        val remote = payload(
            vehicles = listOf(
                vehicle("v1", T0, name = "远端"), // stale, must lose
                vehicle("v2", T1),
            ),
            records = listOf(record("r1", updatedAt = T2, liters = 55.0)),
        )

        val result = mergePayloads(local, remote)

        assertThat(result.acceptedVehicleIds).containsExactly("v2")
        assertThat(result.acceptedRecordIds).containsExactly("r1")
        assertThat(result.payload.vehicles.map { it.id }).containsExactly("v1", "v2").inOrder()
        assertThat(result.payload.vehicles.single { it.id == "v1" }.name).isEqualTo("本地")
        assertThat(result.payload.records.single().liters).isWithin(1e-9).of(55.0)
    }

    @Test
    fun mergingIsIdempotent() {
        val local = payload(vehicles = listOf(vehicle("v1", T1)))
        val remote = payload(vehicles = listOf(vehicle("v1", T2), vehicle("v2", T1)))

        val once = mergePayloads(local, remote)
        val twice = mergePayloads(once.payload, remote)

        // The remote revisions are already in place, so the second pass is a no-op.
        assertThat(twice.acceptedVehicleIds).isEmpty()
        assertThat(twice.payload.vehicles.map { it.id }).containsExactly("v1", "v2").inOrder()
    }

    @Test
    fun mergingWithItselfChangesNothing() {
        val local = payload(
            vehicles = listOf(vehicle("v1", T1)),
            records = listOf(record("r1", updatedAt = T1)),
        )

        val result = mergePayloads(local, local)

        assertThat(result.acceptedVehicleIds).isEmpty()
        assertThat(result.acceptedRecordIds).isEmpty()
        assertThat(result.payload).isEqualTo(local)
    }
}
