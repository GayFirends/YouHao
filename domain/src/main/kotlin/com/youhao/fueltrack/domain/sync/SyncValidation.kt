package com.youhao.fueltrack.domain.sync

import com.youhao.fueltrack.domain.model.FuelRecord
import com.youhao.fueltrack.domain.model.SyncPayloadV1
import com.youhao.fueltrack.domain.model.Vehicle
import com.youhao.fueltrack.domain.time.Timestamps
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Raised for any rejected backup, mirroring `备份文件校验失败：…`. */
class SyncValidationException(message: String) : IllegalArgumentException("备份文件校验失败：$message")

/**
 * Strict validator for incoming `SyncPayloadV1` documents, ported from `sync-validation.ts`.
 *
 * This is the gate that protects the database from a hand-edited or corrupted WebDAV file,
 * so it stays intentionally paranoid: unknown shapes, duplicate ids, dangling `vehicleId`
 * references and non-representable dates are all hard failures.
 */
object SyncPayloadValidator {

    private const val MAX_ITEMS = 100_000
    private val DATE_RE = Regex("""^\d{4}-\d{2}-\d{2}$""")

    private fun fail(message: String): Nothing = throw SyncValidationException(message)

    fun validate(root: JsonElement): SyncPayloadV1 {
        val payload = root as? JsonObject ?: fail("根节点必须是对象")

        // `as?` rather than `jsonPrimitive`, which throws for a non-primitive node.
        val versionElement = payload["version"] as? JsonPrimitive
        val version = versionElement?.takeIf { !it.isString }?.doubleOrNull
        if (version != 1.0) fail("仅支持版本 1")

        val exportedAt = timestamp(payload["exportedAt"], "exportedAt")

        val vehiclesElement = payload["vehicles"]
        val recordsElement = payload["records"]
        if (vehiclesElement !is kotlinx.serialization.json.JsonArray || recordsElement !is kotlinx.serialization.json.JsonArray) {
            fail("vehicles 和 records 必须是数组")
        }
        if (vehiclesElement.size > MAX_ITEMS || recordsElement.size > MAX_ITEMS) {
            fail("记录数量超过上限")
        }

        val vehicles = vehiclesElement.mapIndexed { index, element -> validateVehicle(element, index) }
        val vehicleIds = mutableSetOf<String>()
        for (vehicle in vehicles) {
            if (!vehicleIds.add(vehicle.id)) fail("车辆 ID 重复：${vehicle.id}")
        }

        val records = recordsElement.mapIndexed { index, element -> validateRecord(element, index) }
        val recordIds = mutableSetOf<String>()
        for (record in records) {
            if (!recordIds.add(record.id)) fail("记录 ID 重复：${record.id}")
            if (!vehicleIds.contains(record.vehicleId)) fail("记录 ${record.id} 引用了不存在的车辆")
        }

        return SyncPayloadV1(
            version = 1,
            exportedAt = exportedAt,
            vehicles = vehicles,
            records = records,
        )
    }

    private fun validateVehicle(element: JsonElement, index: Int): Vehicle {
        val item = element as? JsonObject ?: fail("vehicles[$index] 不是对象")
        return Vehicle(
            id = stringField(item["id"], "vehicles[$index].id", allowEmpty = false),
            name = stringField(item["name"], "vehicles[$index].name", allowEmpty = false),
            plate = stringField(item["plate"], "vehicles[$index].plate"),
            fuelType = stringField(item["fuelType"], "vehicles[$index].fuelType", allowEmpty = false),
            initialOdometer = numberField(item["initialOdometer"], "vehicles[$index].initialOdometer", 0.0),
            createdAt = timestamp(item["createdAt"], "vehicles[$index].createdAt"),
            updatedAt = timestamp(item["updatedAt"], "vehicles[$index].updatedAt"),
            deletedAt = nullableTimestamp(item["deletedAt"], "vehicles[$index].deletedAt"),
        )
    }

    private fun validateRecord(element: JsonElement, index: Int): FuelRecord {
        val item = element as? JsonObject ?: fail("records[$index] 不是对象")
        // Reject `"true"`: the TypeScript validator used `typeof value === 'boolean'`.
        val isFullElement = item["isFull"] as? JsonPrimitive
        val isFull = isFullElement?.takeIf { !it.isString }?.booleanOrNull
            ?: fail("records[$index].isFull 必须是布尔值")
        val amount = numberField(item["amount"], "records[$index].amount", 0.0)
        val pumpAmountElement = item["pumpAmount"]
        return FuelRecord(
            id = stringField(item["id"], "records[$index].id", allowEmpty = false),
            vehicleId = stringField(item["vehicleId"], "records[$index].vehicleId", allowEmpty = false),
            date = dateOnly(item["date"], "records[$index].date"),
            odometer = numberField(item["odometer"], "records[$index].odometer", 0.0),
            liters = numberField(item["liters"], "records[$index].liters", Double.MIN_VALUE),
            amount = amount,
            // `pumpAmount` was added after v1 shipped; older files omit it.
            pumpAmount = if (pumpAmountElement == null || pumpAmountElement is JsonNull) {
                amount
            } else {
                numberField(pumpAmountElement, "records[$index].pumpAmount", 0.0)
            },
            pricePerLiter = numberField(item["pricePerLiter"], "records[$index].pricePerLiter", 0.0),
            isFull = isFull,
            station = stringField(item["station"], "records[$index].station"),
            note = stringField(item["note"], "records[$index].note"),
            createdAt = timestamp(item["createdAt"], "records[$index].createdAt"),
            updatedAt = timestamp(item["updatedAt"], "records[$index].updatedAt"),
            deletedAt = nullableTimestamp(item["deletedAt"], "records[$index].deletedAt"),
        )
    }

    private fun stringField(value: JsonElement?, field: String, allowEmpty: Boolean = true): String {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: fail("$field 必须是非空文本")
        if (!allowEmpty && text.isBlank()) fail("$field 必须是非空文本")
        return text
    }

    private fun numberField(value: JsonElement?, field: String, minimum: Double): Double {
        val primitive = value as? JsonPrimitive
        val number = if (primitive != null && !primitive.isString) primitive.doubleOrNull else null
        if (number == null || !number.isFinite() || number < minimum) {
            fail("$field 必须是大于等于 $minimum 的数字")
        }
        return number
    }

    private fun timestamp(value: JsonElement?, field: String): String {
        val text = stringField(value, field, allowEmpty = false)
        val instant = Timestamps.parseOrNull(text) ?: fail("$field 不是有效时间")
        return Timestamps.format(instant)
    }

    private fun nullableTimestamp(value: JsonElement?, field: String): String? =
        if (value == null || value is JsonNull) null else timestamp(value, field)

    private fun dateOnly(value: JsonElement?, field: String): String {
        val text = stringField(value, field, allowEmpty = false)
        if (!DATE_RE.matches(text)) fail("$field 不是有效日期")
        try {
            LocalDate.parse(text)
        } catch (_: DateTimeParseException) {
            fail("$field 不是有效日期")
        }
        return text
    }
}
