package com.youhao.fueltrack.domain.backup

import com.youhao.fueltrack.domain.model.FuelRecord
import com.youhao.fueltrack.domain.model.SyncPayloadV1
import com.youhao.fueltrack.domain.model.Vehicle
import com.youhao.fueltrack.domain.sync.SyncJson
import com.youhao.fueltrack.domain.sync.SyncPayloadValidator
import com.youhao.fueltrack.domain.sync.SyncValidationException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Backup import/export, ported from `src/services/backup.ts`.
 *
 * The only piece that stays behind on Android is actually handing the text to the user — that needs
 * a `FileProvider`/`Share` intent and lives in the app module.
 */

/**
 * The legacy check is on `String.length`, not on encoded bytes: `text.length > 20 * 1024 * 1024`.
 * Kept as-is so an oversized file is rejected at exactly the same point.
 */
const val MAX_BACKUP_CHARS: Int = 20 * 1024 * 1024

/**
 * Parses a backup file. Errors are `IllegalArgumentException` with the same Chinese wording the
 * legacy service used, so the UI can show them verbatim.
 */
fun parseBackup(text: String): SyncPayloadV1 {
    if (text.length > MAX_BACKUP_CHARS) {
        throw IllegalArgumentException("备份文件超过 20 MB，拒绝导入")
    }
    val root = try {
        SyncJson.parseToJsonElement(text)
    } catch (_: Exception) {
        throw IllegalArgumentException("备份文件不是有效的 JSON")
    }
    return try {
        SyncPayloadValidator.validate(root)
    } catch (error: SyncValidationException) {
        // `validate` already produced a user-facing "备份文件校验失败：…" message.
        throw error
    } catch (_: Exception) {
        throw IllegalArgumentException("备份文件格式不受支持")
    }
}

/** Column order matches the legacy CSV export exactly, including the two derived unit prices. */
private val CSV_COLUMNS = listOf(
    "车辆", "日期", "里程(km)", "加油量(L)", "表显金额(元)", "实付金额(元)", "优惠金额(元)",
    "表显单价(元/L)", "优惠后单价(元/L)", "满箱", "加油站", "备注",
)

/**
 * Renders the live records as CSV: UTF-8 BOM, CRLF line endings, deleted records omitted.
 *
 * `优惠后单价` is stored `pricePerLiter` rather than recomputed, which is what the legacy export
 * did — `amount / liters` would usually agree, but the stored value is the record of truth.
 */
fun recordsToCsv(records: List<FuelRecord>, vehicles: List<Vehicle>): String {
    val vehicleNames = vehicles.associate { it.id to it.name }
    val lines = records.filter { it.deletedAt == null }.map { record ->
        listOf(
            vehicleNames[record.vehicleId] ?: record.vehicleId,
            record.date,
            jsNumber(record.odometer),
            jsNumber(record.liters),
            jsNumber(record.pumpAmount),
            jsNumber(record.amount),
            fixed2(max(record.pumpAmount - record.amount, 0.0)),
            fixed2(if (record.liters != 0.0) record.pumpAmount / record.liters else 0.0),
            fixed2(record.pricePerLiter),
            if (record.isFull) "是" else "否",
            record.station,
            record.note,
        ).joinToString(",") { csvCell(it) }
    }
    return "\uFEFF" + CSV_COLUMNS.joinToString(",") + "\r\n" + lines.joinToString("\r\n")
}

/** `Number.toFixed(2)` equivalent. `Locale.ROOT` keeps the decimal point a dot. */
private fun fixed2(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

/**
 * `String(number)` for the four columns the legacy export wrote undecorated.
 *
 * JavaScript renders a whole number as `1500`, never `1500.0`, and `Double.toString` would emit the
 * latter — so a port that used `toString` would produce a CSV that no longer matches the web build's
 * output for the same data. Non-finite values cannot reach here (both the sync validator and the
 * database reject them) but are folded to `0` rather than printed as Kotlin's `NaN`/`Infinity`,
 * which are not valid JSON literals either. Beyond 10^15 the two languages diverge again
 * (`1.0E20` here, `100000000000000000000` in JavaScript); no odometer, litre or yuan column
 * realistically reaches that.
 */
private fun jsNumber(value: Double): String = when {
    !value.isFinite() -> "0"
    value == 0.0 -> "0"
    value % 1.0 == 0.0 && abs(value) < 1e15 -> value.toLong().toString()
    else -> value.toString()
}

private val NEEDS_QUOTING = Regex("[\",\r\n]")

private fun csvCell(value: String): String =
    if (NEEDS_QUOTING.containsMatchIn(value)) "\"${value.replace("\"", "\"\"")}\"" else value
