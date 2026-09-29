package com.youhao.fueltrack.domain.calc

import com.youhao.fueltrack.domain.model.FuelRecord
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Amount ↔ litres ↔ pump-price coupling for the fill-up form.
 *
 * A direct port of `refuel-entry.ts`. The original mutated a plain object in place; this is
 * immutable so it can back a Compose `TextField` state holder. `null` represents the
 * original's empty-string (`''`) "blank field" value.
 */

enum class AmountField { LITERS, AMOUNT, PUMP_AMOUNT, PUMP_PRICE }

/** Which field the user last drove the pump amount from. */
enum class PumpSource { AMOUNT, UNIT_PRICE }

data class RefuelEntry(
    val liters: Double? = null,
    val amount: Double? = null,
    val pumpAmount: Double? = null,
    val pumpPrice: Double? = null,
    /** True once the user edited the pump amount / pump price directly. */
    val hasPumpOverride: Boolean = false,
    val lastPumpSource: PumpSource = PumpSource.AMOUNT,
)

/**
 * `Number(value.toFixed(2))` equivalent. Uses the exact binary value (as JavaScript's
 * `toFixed` does) rather than `BigDecimal.valueOf`, which would round 2.675 differently.
 */
internal fun round2(value: Double): Double =
    BigDecimal(value).setScale(2, RoundingMode.HALF_UP).toDouble()

fun createRefuelEntry(
    liters: Double? = null,
    amount: Double? = null,
    pumpAmount: Double? = null,
): RefuelEntry {
    return RefuelEntry(
        liters = liters,
        amount = amount,
        pumpAmount = pumpAmount,
        pumpPrice = if (liters != null && liters > 0 && pumpAmount != null) {
            round2(pumpAmount / liters)
        } else {
            null
        },
        hasPumpOverride = pumpAmount != amount,
        lastPumpSource = PumpSource.AMOUNT,
    )
}

/** Builds the form state for editing an existing record. */
fun createRefuelEntryFor(record: FuelRecord): RefuelEntry =
    createRefuelEntry(record.liters, record.amount, record.pumpAmount)

/** The pump amount actually used, falling back to the charged amount. */
fun effectivePumpAmount(entry: RefuelEntry): Double? = entry.pumpAmount ?: entry.amount

private fun RefuelEntry.withPumpPriceFromPumpAmount(): RefuelEntry {
    val liters = liters
    val pumpAmount = pumpAmount
    return if (liters != null && liters > 0 && pumpAmount != null) {
        copy(pumpPrice = round2(pumpAmount / liters))
    } else {
        this
    }
}

/** Re-derives the pump amount from the charged amount, clearing any manual override. */
fun resetPumpAmount(entry: RefuelEntry): RefuelEntry {
    val liters = entry.liters
    val amount = entry.amount
    return entry.copy(
        hasPumpOverride = false,
        lastPumpSource = PumpSource.AMOUNT,
        pumpAmount = amount,
        pumpPrice = if (liters != null && liters > 0 && amount != null) {
            round2(amount / liters)
        } else {
            null
        },
    )
}

fun updateRefuelAmount(entry: RefuelEntry, field: AmountField, value: Double?): RefuelEntry {
    val sanitized = if (value == null || !value.isFinite()) null else value
    val edited = when (field) {
        AmountField.LITERS -> entry.copy(liters = sanitized)
        AmountField.AMOUNT -> entry.copy(amount = sanitized)
        AmountField.PUMP_AMOUNT -> entry.copy(pumpAmount = sanitized)
        AmountField.PUMP_PRICE -> entry.copy(pumpPrice = sanitized)
    }

    return when (field) {
        AmountField.AMOUNT -> {
            if (!edited.hasPumpOverride) resetPumpAmount(edited) else edited
        }

        AmountField.PUMP_AMOUNT -> {
            var next = edited.copy(
                hasPumpOverride = edited.pumpAmount != null,
                lastPumpSource = PumpSource.AMOUNT,
            )
            val pumpAmount = next.pumpAmount
            if (pumpAmount == null) {
                next
            } else {
                val liters = next.liters
                val pumpPrice = next.pumpPrice
                next = when {
                    liters != null && liters > 0 -> next.withPumpPriceFromPumpAmount()
                    pumpPrice != null && pumpPrice > 0 -> next.copy(liters = round2(pumpAmount / pumpPrice))
                    else -> next
                }
                next
            }
        }

        AmountField.PUMP_PRICE -> {
            val pumpPrice = edited.pumpPrice
            if (pumpPrice == null) {
                edited.copy(lastPumpSource = PumpSource.AMOUNT)
            } else {
                var next = edited.copy(
                    hasPumpOverride = true,
                    lastPumpSource = PumpSource.UNIT_PRICE,
                )
                val liters = next.liters
                val pumpAmount = next.pumpAmount
                next = when {
                    liters != null && liters > 0 -> next.copy(pumpAmount = round2(liters * pumpPrice))
                    pumpPrice > 0 && pumpAmount != null -> next.copy(liters = round2(pumpAmount / pumpPrice))
                    else -> next
                }
                next
            }
        }

        AmountField.LITERS -> {
            if (!edited.hasPumpOverride) {
                resetPumpAmount(edited)
            } else {
                val liters = edited.liters
                val pumpPrice = edited.pumpPrice
                if (liters != null && liters > 0) {
                    if (edited.lastPumpSource == PumpSource.UNIT_PRICE && pumpPrice != null) {
                        edited.copy(pumpAmount = round2(liters * pumpPrice))
                    } else {
                        edited.withPumpPriceFromPumpAmount()
                    }
                } else {
                    edited
                }
            }
        }
    }
}
