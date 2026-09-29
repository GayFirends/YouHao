package com.youhao.fueltrack.domain.sync

import com.google.common.truth.Truth.assertThat
import com.youhao.fueltrack.domain.model.FuelRecord
import com.youhao.fueltrack.domain.model.Vehicle
import kotlin.test.Test

/**
 * The canonical JSON is both the conflict-hash input and the final merge tie-break, so its
 * byte-for-byte shape is part of the on-disk compatibility contract with the old Web build.
 */
class CanonicalJsonTest {

    private fun vehicle(
        id: String = "v1",
        name: String = "A",
        plate: String = "",
        fuelType: String = "92",
        initialOdometer: Double = 0.0,
        createdAt: String = "2024-01-01T00:00:00.000Z",
        updatedAt: String = "2024-01-01T00:00:00.000Z",
        deletedAt: String? = null,
    ) = Vehicle(id, name, plate, fuelType, initialOdometer, createdAt, updatedAt, deletedAt)

    private fun record(
        odometer: Double = 1000.0,
        liters: Double = 40.5,
        amount: Double = 300.0,
        pumpAmount: Double = 310.0,
        pricePerLiter: Double = 7.5,
        deletedAt: String? = null,
    ) = FuelRecord(
        id = "r1",
        vehicleId = "v1",
        date = "2024-01-01",
        odometer = odometer,
        liters = liters,
        amount = amount,
        pumpAmount = pumpAmount,
        pricePerLiter = pricePerLiter,
        isFull = true,
        station = "",
        note = "",
        createdAt = "2024-01-01T00:00:00.000Z",
        updatedAt = "2024-01-01T00:00:00.000Z",
        deletedAt = deletedAt,
    )

    @Test
    fun vehicleKeysAreEmittedInAscendingOrder() {
        assertThat(CanonicalJson.of(vehicle())).isEqualTo(
            """{"createdAt":"2024-01-01T00:00:00.000Z","deletedAt":null,"fuelType":"92",""" +
                """"id":"v1","initialOdometer":0,"name":"A","plate":"",""" +
                """"updatedAt":"2024-01-01T00:00:00.000Z"}"""
        )
    }

    @Test
    fun recordKeysAreEmittedInAscendingOrder() {
        assertThat(CanonicalJson.of(record())).isEqualTo(
            """{"amount":300,"createdAt":"2024-01-01T00:00:00.000Z","date":"2024-01-01",""" +
                """"deletedAt":null,"id":"r1","isFull":true,"liters":40.5,"note":"",""" +
                """"odometer":1000,"pricePerLiter":7.5,"pumpAmount":310,"station":"",""" +
                """"updatedAt":"2024-01-01T00:00:00.000Z","vehicleId":"v1"}"""
        )
    }

    @Test
    fun integralNumbersRenderWithoutADecimalPoint() {
        // JavaScript String(1000) is "1000", not "1000.0".
        assertThat(CanonicalJson.number(1000.0)).isEqualTo("1000")
        assertThat(CanonicalJson.number(-40.0)).isEqualTo("-40")
        assertThat(CanonicalJson.number(0.0)).isEqualTo("0")
        assertThat(CanonicalJson.number(7.5)).isEqualTo("7.5")
    }

    @Test
    fun unrepresentableNumbersBecomeNull() {
        assertThat(CanonicalJson.number(Double.NaN)).isEqualTo("null")
        assertThat(CanonicalJson.number(Double.POSITIVE_INFINITY)).isEqualTo("null")
    }

    @Test
    fun timestampsAreNormalisedToUtcMilliseconds() {
        assertThat(CanonicalJson.normalizeTimestamp("2024-01-01T08:00:00+08:00"))
            .isEqualTo("2024-01-01T00:00:00.000Z")
        assertThat(CanonicalJson.normalizeTimestamp("2024-01-01T00:00:00Z"))
            .isEqualTo("2024-01-01T00:00:00.000Z")
    }

    @Test
    fun unparsableTimestampsAreLeftAlone() {
        assertThat(CanonicalJson.normalizeTimestamp("not-a-time")).isEqualTo("not-a-time")
    }

    @Test
    fun stringsAreEscapedLikeJsonStringify() {
        assertThat(CanonicalJson.quote("a\"b\\c\nd\te"))
            .isEqualTo("\"a\\\"b\\\\c\\nd\\te\"")
        assertThat(CanonicalJson.quote("控制\u0001符")).isEqualTo("\"控制\\u0001符\"")
    }

    @Test
    fun identicalEntitiesProduceIdenticalCanonicalJson() {
        assertThat(CanonicalJson.of(vehicle(name = "同名")))
            .isEqualTo(CanonicalJson.of(vehicle(name = "同名")))
    }

    @Test
    fun differentEntitiesProduceDifferentCanonicalJson() {
        assertThat(CanonicalJson.of(vehicle(name = "A")))
            .isNotEqualTo(CanonicalJson.of(vehicle(name = "B")))
    }

    @Test
    fun aDeletedRecordCarriesItsDeletionTimestamp() {
        val text = CanonicalJson.of(record(deletedAt = "2024-05-05T05:05:05.000Z"))

        assertThat(text).contains(""""deletedAt":"2024-05-05T05:05:05.000Z"""")
    }
}
