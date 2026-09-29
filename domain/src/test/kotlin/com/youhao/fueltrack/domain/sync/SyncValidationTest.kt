package com.youhao.fueltrack.domain.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The validator is the only gate between a hand-edited / corrupted WebDAV file and the
 * database, so it is deliberately strict. Ported from `src/services/sync-validation.ts`.
 */
class SyncValidationTest {

    private companion object {
        const val VEHICLE = """{"id":"v1","name":"车","plate":"","fuelType":"92",""" +
            """"initialOdometer":1000,"createdAt":"2024-01-01T00:00:00.000Z",""" +
            """"updatedAt":"2024-01-01T00:00:00.000Z"}"""

        const val RECORD = """{"id":"r1","vehicleId":"v1","date":"2024-01-01","odometer":1000,""" +
            """"liters":40,"amount":300,"pumpAmount":320,"pricePerLiter":8,"isFull":true,""" +
            """"station":"","note":"","createdAt":"2024-01-01T00:00:00.000Z",""" +
            """"updatedAt":"2024-01-01T00:00:00.000Z"}"""
    }

    private fun document(
        version: Int = 1,
        exportedAt: String = "2024-01-01T00:00:00.000Z",
        vehicles: String = "[$VEHICLE]",
        records: String = "[$RECORD]",
    ): JsonElement = Json.parseToJsonElement(
        """{"version":$version,"exportedAt":"$exportedAt","vehicles":$vehicles,"records":$records}"""
    )

    private fun failureFor(root: JsonElement): String {
        val failure = assertFailsWith<SyncValidationException> { SyncPayloadValidator.validate(root) }
        return failure.message.orEmpty()
    }

    @Test
    fun acceptsAWellFormedDocument() {
        val payload = SyncPayloadValidator.validate(document())

        assertThat(payload.version).isEqualTo(1)
        assertThat(payload.vehicles.map { it.id }).containsExactly("v1")
        assertThat(payload.records.map { it.id }).containsExactly("r1")
        assertThat(payload.records.single().odometer).isWithin(1e-9).of(1000.0)
        assertThat(payload.records.single().pumpAmount).isWithin(1e-9).of(320.0)
    }

    @Test
    fun normalisesOffsetsToUtcMilliseconds() {
        val payload = SyncPayloadValidator.validate(document(exportedAt = "2024-01-01T08:00:00+08:00"))

        assertThat(payload.exportedAt).isEqualTo("2024-01-01T00:00:00.000Z")
    }

    @Test
    fun acceptsAnEmptyButWellFormedDocument() {
        val payload = SyncPayloadValidator.validate(document(vehicles = "[]", records = "[]"))

        assertThat(payload.vehicles).isEmpty()
        assertThat(payload.records).isEmpty()
    }

    @Test
    fun rejectsOtherVersions() {
        assertThat(failureFor(document(version = 2))).isEqualTo("备份文件校验失败：仅支持版本 1")
    }

    @Test
    fun rejectsAVersionSentAsAString() {
        val root = Json.parseToJsonElement(
            """{"version":"1","exportedAt":"2024-01-01T00:00:00.000Z","vehicles":[],"records":[]}"""
        )

        assertThat(failureFor(root)).isEqualTo("备份文件校验失败：仅支持版本 1")
    }

    @Test
    fun rejectsAVersionOfTheWrongShapeWithoutCrashing() {
        val root = Json.parseToJsonElement(
            """{"version":{"major":1},"exportedAt":"2024-01-01T00:00:00.000Z","vehicles":[],"records":[]}"""
        )

        assertThat(failureFor(root)).isEqualTo("备份文件校验失败：仅支持版本 1")
    }

    @Test
    fun rejectsANonObjectRoot() {
        assertThat(failureFor(Json.parseToJsonElement("[]"))).isEqualTo("备份文件校验失败：根节点必须是对象")
    }

    @Test
    fun rejectsMissingArrays() {
        val root = Json.parseToJsonElement(
            """{"version":1,"exportedAt":"2024-01-01T00:00:00.000Z","records":[]}"""
        )

        assertThat(failureFor(root)).isEqualTo("备份文件校验失败：vehicles 和 records 必须是数组")
    }

    @Test
    fun rejectsAnInvalidExportedAt() {
        assertThat(failureFor(document(exportedAt = "yesterday")))
            .isEqualTo("备份文件校验失败：exportedAt 不是有效时间")
    }

    @Test
    fun rejectsDuplicateVehicleIds() {
        assertThat(failureFor(document(vehicles = "[$VEHICLE,$VEHICLE]")))
            .isEqualTo("备份文件校验失败：车辆 ID 重复：v1")
    }

    @Test
    fun rejectsARecordPointingAtAnUnknownVehicle() {
        val orphan = RECORD.replace(""""vehicleId":"v1"""", """"vehicleId":"missing"""")

        assertThat(failureFor(document(records = "[$orphan]")))
            .isEqualTo("备份文件校验失败：记录 r1 引用了不存在的车辆")
    }

    @Test
    fun fallsBackToAmountWhenPumpAmountIsAbsent() {
        // pumpAmount was added after v1 shipped, so older files omit it entirely.
        val legacy = RECORD.replace(""""pumpAmount":320,""", "")

        val payload = SyncPayloadValidator.validate(document(records = "[$legacy]"))

        assertThat(payload.records.single().pumpAmount).isWithin(1e-9).of(300.0)
    }

    @Test
    fun treatsAnExplicitNullPumpAmountAsAbsent() {
        val legacy = RECORD.replace(""""pumpAmount":320""", """"pumpAmount":null""")

        val payload = SyncPayloadValidator.validate(document(records = "[$legacy]"))

        assertThat(payload.records.single().pumpAmount).isWithin(1e-9).of(300.0)
    }

    @Test
    fun rejectsNegativeLitres() {
        val negative = RECORD.replace(""""liters":40""", """"liters":-1""")

        assertThat(failureFor(document(records = "[$negative]")))
            .startsWith("备份文件校验失败：records[0].liters")
    }

    @Test
    fun rejectsNumbersSentAsStrings() {
        val quoted = RECORD.replace(""""odometer":1000""", """"odometer":"1000"""")

        assertThat(failureFor(document(records = "[$quoted]")))
            .isEqualTo("备份文件校验失败：records[0].odometer 必须是大于等于 0.0 的数字")
    }

    @Test
    fun rejectsImpossibleCalendarDates() {
        assertThat(failureFor(document(records = "[" + RECORD.replace(""""date":"2024-01-01"""", """"date":"2024-13-01"""") + "]")))
            .isEqualTo("备份文件校验失败：records[0].date 不是有效日期")
        assertThat(failureFor(document(records = "[" + RECORD.replace(""""date":"2024-01-01"""", """"date":"2024-02-30"""") + "]")))
            .isEqualTo("备份文件校验失败：records[0].date 不是有效日期")
    }

    @Test
    fun rejectsBlankRequiredText() {
        val unnamed = VEHICLE.replace(""""name":"车"""", """"name":"   """")

        assertThat(failureFor(document(vehicles = "[$unnamed]")))
            .isEqualTo("备份文件校验失败：vehicles[0].name 必须是非空文本")
    }

    @Test
    fun rejectsANonBooleanIsFull() {
        val texty = RECORD.replace(""""isFull":true""", """"isFull":"true"""")

        assertThat(failureFor(document(records = "[$texty]")))
            .isEqualTo("备份文件校验失败：records[0].isFull 必须是布尔值")
    }

    @Test
    fun everyRejectionCarriesTheBackupFilePrefix() {
        assertThat(failureFor(document(version = 9))).startsWith("备份文件校验失败：")
    }
}
