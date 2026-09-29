package com.youhao.fueltrack.domain.calc

import com.youhao.fueltrack.domain.model.FuelRecord
import com.youhao.fueltrack.domain.time.LocalDateKeys
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.max

/** A full-to-full stretch between two full tanks. */
data class ConsumptionInterval(
    val record: FuelRecord,
    val previousFullRecord: FuelRecord,
    val distance: Double,
    val liters: Double,
    /** Litres per 100 km. */
    val consumption: Double,
)

/**
 * Intervals above this are treated as data-entry mistakes rather than real consumption,
 * matching the original `consumption < 50` guard in `fuel-calculations.ts`.
 */
const val MAX_PLAUSIBLE_CONSUMPTION = 50.0

/**
 * Walks the records in odometer order and emits one interval per full tank, summing the
 * litres of every partial fill-up in between.
 */
fun calculateConsumptionIntervals(records: List<FuelRecord>): List<ConsumptionInterval> {
    val ordered = records
        .filter { it.deletedAt == null }
        .sortedWith(
            compareBy<FuelRecord> { it.odometer }
                .thenBy { it.date }
                .thenBy { it.createdAt }
        )

    val intervals = mutableListOf<ConsumptionInterval>()
    var previousFullRecord: FuelRecord? = null
    var accumulatedLiters = 0.0

    for (record in ordered) {
        val previous = previousFullRecord
        if (previous == null) {
            // Litres burnt before the first full tank cannot be attributed to an interval.
            if (record.isFull) previousFullRecord = record
            continue
        }

        accumulatedLiters += record.liters
        if (!record.isFull) continue

        val distance = record.odometer - previous.odometer
        val consumption = if (distance > 0) accumulatedLiters / distance * 100 else 0.0
        if (distance > 0 && consumption > 0 && consumption < MAX_PLAUSIBLE_CONSUMPTION) {
            intervals += ConsumptionInterval(
                record = record,
                previousFullRecord = previous,
                distance = distance,
                liters = accumulatedLiters,
                consumption = consumption,
            )
        }
        previousFullRecord = record
        accumulatedLiters = 0.0
    }

    return intervals
}

/** Distance-weighted average over [intervals]; 0 when there is no distance. */
fun calculateAverageConsumption(intervals: List<ConsumptionInterval>): Double {
    var liters = 0.0
    var distance = 0.0
    for (interval in intervals) {
        liters += interval.liters
        distance += interval.distance
    }
    return if (distance > 0) liters / distance * 100 else 0.0
}

data class FuelPriceSummary(
    /** What the pump displayed, per litre. */
    val pumpPricePerLiter: Double,
    /** What was actually paid, per litre. */
    val discountedPricePerLiter: Double,
    val discountAmount: Double,
)

/** Splits a fill-up into pump price, discounted price and the saving. */
fun fuelPriceSummary(liters: Double, amount: Double, pumpAmount: Double): FuelPriceSummary {
    val validLiters = if (liters.isFinite() && liters > 0) liters else 0.0
    val paid = if (amount.isFinite() && amount > 0) amount else 0.0
    val displayed = if (pumpAmount.isFinite() && pumpAmount > 0) pumpAmount else 0.0
    return FuelPriceSummary(
        pumpPricePerLiter = if (validLiters != 0.0) displayed / validLiters else 0.0,
        discountedPricePerLiter = if (validLiters != 0.0) paid / validLiters else 0.0,
        discountAmount = max(displayed - paid, 0.0),
    )
}

/** The editable fields of a fill-up, independent of storage. */
data class RecordDraft(
    val id: String? = null,
    val date: String,
    val odometer: Double,
    val liters: Double,
    val amount: Double,
    val isFull: Boolean,
)

const val MAX_PLAUSIBLE_LITERS = 150.0
const val MIN_PLAUSIBLE_UNIT_PRICE = 2.0
const val MAX_PLAUSIBLE_UNIT_PRICE = 20.0

/**
 * Soft, non-blocking warnings shown while entering a fill-up. Never rejects input —
 * matching `fuelRecordWarnings` in `fuel-calculations.ts`.
 */
fun fuelRecordWarnings(
    draft: RecordDraft,
    records: List<FuelRecord>,
    today: String = LocalDateKeys.localDateKey(),
): List<String> {
    val warnings = mutableListOf<String>()
    val others = records.filter { it.deletedAt == null && it.id != draft.id }

    val before = others
        .filter { it.date <= draft.date }
        .sortedWith(compareByDescending<FuelRecord> { it.date }.thenByDescending { it.odometer })
        .firstOrNull()
    val after = others
        .filter { it.date > draft.date }
        .sortedWith(compareBy<FuelRecord> { it.date }.thenBy { it.odometer })
        .firstOrNull()
    val unitPrice = if (draft.liters > 0) draft.amount / draft.liters else 0.0

    if (draft.date > today) warnings += "加油日期晚于今天"
    if (before != null && draft.odometer < before.odometer) {
        warnings += "里程低于此前记录的 ${formatOdometer(before.odometer)} km"
    }
    if (after != null && draft.odometer > after.odometer) {
        warnings += "里程高于之后记录的 ${formatOdometer(after.odometer)} km"
    }
    if (draft.isFull && others.any { it.isFull && it.odometer == draft.odometer }) {
        warnings += "同一里程已经存在满箱记录"
    }
    if (draft.liters > MAX_PLAUSIBLE_LITERS) {
        warnings += "加油量超过 ${MAX_PLAUSIBLE_LITERS.toInt()} L"
    }
    if (unitPrice > 0 && (unitPrice < MIN_PLAUSIBLE_UNIT_PRICE || unitPrice > MAX_PLAUSIBLE_UNIT_PRICE)) {
        warnings += "计算单价为 ¥%.2f/L".format(unitPrice)
    }
    return warnings
}

/** Matches `Number.toLocaleString()` for the odometer values shown in warnings. */
internal fun formatOdometer(value: Double): String {
    val format = NumberFormat.getNumberInstance(Locale.getDefault())
    format.maximumFractionDigits = if (value % 1.0 == 0.0) 0 else 3
    return format.format(value)
}
