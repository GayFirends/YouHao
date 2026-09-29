package com.youhao.fueltrack.domain.query

import com.youhao.fueltrack.domain.model.FuelRecord
import com.youhao.fueltrack.domain.model.RecordCursor
import com.youhao.fueltrack.domain.model.RecordListQuery
import com.youhao.fueltrack.domain.model.RecordPage
import com.youhao.fueltrack.domain.model.VehicleSummary

/**
 * Port of `src/services/record-query.ts`.
 *
 * The original Android build already paged in memory (`listRecordPage(await records(true), query)`),
 * so paging here stays a pure function over the full record list. That keeps cursor semantics
 * identical to the shipped app and makes them unit-testable without a database.
 */
const val DEFAULT_PAGE_SIZE = 50
const val MAX_PAGE_SIZE = 200

/** Newest first: date DESC, odometer DESC, createdAt DESC, id DESC. */
fun compareRecords(left: FuelRecord, right: FuelRecord): Int {
    right.date.compareTo(left.date).let { if (it != 0) return it }
    right.odometer.compareTo(left.odometer).let { if (it != 0) return it }
    right.createdAt.compareTo(left.createdAt).let { if (it != 0) return it }
    return right.id.compareTo(left.id)
}

/** A record comes after the cursor when it sorts strictly lower than the cursor position. */
private fun afterCursor(record: FuelRecord, cursor: RecordCursor): Boolean {
    val position = record.copy(
        date = cursor.date,
        odometer = cursor.odometer,
        createdAt = cursor.createdAt,
        id = cursor.id,
    )
    return compareRecords(record, position) > 0
}

fun listRecordPage(records: List<FuelRecord>, query: RecordListQuery): RecordPage {
    val search = query.search?.trim()?.lowercase().orEmpty()
    val limit = minOf(MAX_PAGE_SIZE, maxOf(1, query.limit ?: DEFAULT_PAGE_SIZE))
    val filtered = records
        .filter { query.includeDeleted || it.deletedAt == null }
        .filter { query.vehicleId == null || it.vehicleId == query.vehicleId }
        .filter { query.month == null || it.date.startsWith(query.month) }
        .filter { search.isEmpty() || "${it.station} ${it.note} ${it.date}".lowercase().contains(search) }
        .filter { query.cursor == null || afterCursor(it, query.cursor) }
        .sortedWith { left, right -> compareRecords(left, right) }

    val items = filtered.take(limit)
    val last = items.lastOrNull()
    return RecordPage(
        items = items,
        // Only the last record can have more rows behind it, and only if the page was full.
        nextCursor = if (filtered.size > limit && last != null) {
            RecordCursor(date = last.date, odometer = last.odometer, createdAt = last.createdAt, id = last.id)
        } else {
            null
        },
    )
}

fun summarizeVehicle(records: List<FuelRecord>, vehicleId: String): VehicleSummary {
    val active = records.filter { it.vehicleId == vehicleId && it.deletedAt == null }
    return VehicleSummary(
        vehicleId = vehicleId,
        recordCount = active.size,
        totalPaid = active.sumOf { it.amount },
        currentOdometer = active.maxOfOrNull { it.odometer },
    )
}
