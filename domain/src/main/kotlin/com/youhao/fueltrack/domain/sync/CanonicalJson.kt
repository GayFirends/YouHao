package com.youhao.fueltrack.domain.sync

import com.youhao.fueltrack.domain.model.FuelRecord
import com.youhao.fueltrack.domain.model.SyncEntity
import com.youhao.fueltrack.domain.model.Vehicle
import com.youhao.fueltrack.domain.time.Timestamps

/**
 * Deterministic canonical JSON, reproducing the `JSON.stringify(Object.fromEntries(...sort...))`
 * trick used by `sync-conflicts.ts` and `conflict-resolution.ts`.
 *
 * Every field of the entity is emitted, keys sorted ascending by UTF-16 code unit, timestamp
 * fields normalised to `toISOString()` form, and numbers rendered as JavaScript `String(n)`
 * would. The result is used both as a stable content hash input and as the final tie-break
 * when two devices edited the same row at the same instant.
 */
object CanonicalJson {

    fun of(entity: SyncEntity): String = when (entity) {
        is Vehicle -> vehicle(entity)
        is FuelRecord -> record(entity)
        else -> error("Unsupported sync entity: ${entity::class}")
    }

    // Keys below are already in ascending code-unit order.

    fun vehicle(v: Vehicle): String = buildString {
        append('{')
        field("createdAt", normalizeTimestamp(v.createdAt))
        append(',')
        field("deletedAt", v.deletedAt?.let(::normalizeTimestamp))
        append(',')
        field("fuelType", v.fuelType)
        append(',')
        field("id", v.id)
        append(',')
        field("initialOdometer", v.initialOdometer)
        append(',')
        field("name", v.name)
        append(',')
        field("plate", v.plate)
        append(',')
        field("updatedAt", normalizeTimestamp(v.updatedAt))
        append('}')
    }

    fun record(r: FuelRecord): String = buildString {
        append('{')
        field("amount", r.amount)
        append(',')
        field("createdAt", normalizeTimestamp(r.createdAt))
        append(',')
        field("date", r.date)
        append(',')
        field("deletedAt", r.deletedAt?.let(::normalizeTimestamp))
        append(',')
        field("id", r.id)
        append(',')
        field("isFull", r.isFull)
        append(',')
        field("liters", r.liters)
        append(',')
        field("note", r.note)
        append(',')
        field("odometer", r.odometer)
        append(',')
        field("pricePerLiter", r.pricePerLiter)
        append(',')
        field("pumpAmount", r.pumpAmount)
        append(',')
        field("station", r.station)
        append(',')
        field("updatedAt", normalizeTimestamp(r.updatedAt))
        append(',')
        field("vehicleId", r.vehicleId)
        append('}')
    }

    /** `new Date(value).toISOString()`; leaves the raw text alone when it cannot be parsed. */
    fun normalizeTimestamp(value: String): String =
        Timestamps.parseOrNull(value)?.let(Timestamps::format) ?: value

    private fun StringBuilder.field(name: String, value: String?) {
        append(quote(name)).append(':')
        if (value == null) append("null") else append(quote(value))
    }

    private fun StringBuilder.field(name: String, value: Double) {
        append(quote(name)).append(':').append(number(value))
    }

    private fun StringBuilder.field(name: String, value: Boolean) {
        append(quote(name)).append(':').append(if (value) "true" else "false")
    }

    /** JavaScript `String(number)` for the magnitudes this domain produces. */
    internal fun number(value: Double): String = when {
        value.isNaN() -> "null"
        value.isInfinite() -> "null"
        value == 0.0 -> "0"
        value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e21 -> value.toLong().toString()
        else -> value.toString()
    }

    /** JSON string literal, matching the escaping `JSON.stringify` performs. */
    internal fun quote(text: String): String = buildString(text.length + 2) {
        append('"')
        for (ch in text) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (ch < ' ') {
                    append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    append(ch)
                }
            }
        }
        append('"')
    }
}
