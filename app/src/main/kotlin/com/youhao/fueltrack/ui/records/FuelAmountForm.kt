package com.youhao.fueltrack.ui.records

import com.youhao.fueltrack.domain.calc.FuelCalculationTarget
import com.youhao.fueltrack.domain.calc.FuelDiscountType
import com.youhao.fueltrack.domain.calc.FuelPaymentCalculation
import com.youhao.fueltrack.domain.calc.FuelPaymentInput
import com.youhao.fueltrack.domain.calc.FuelPaymentResult
import com.youhao.fueltrack.domain.calc.calculateFuelPayment
import com.youhao.fueltrack.domain.model.FuelRecord
import com.youhao.fueltrack.ui.toFieldText
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

enum class FuelAmountInput { LITERS, UNIT_PRICE, AMOUNT, DISCOUNT, COUPON }

/** The record's live amount inputs. Only the chosen result field is derived. */
data class FuelAmountForm(
    val target: FuelCalculationTarget = FuelCalculationTarget.AMOUNT,
    val liters: String = "",
    val pumpPrice: String = "",
    val amount: String = "",
    val discountType: FuelDiscountType = FuelDiscountType.NONE,
    val discount: String = "",
    val coupon: String = "",
    /** Preserve receipt precision on load / mode changes until a numeric input changes. */
    private val preservedResult: FuelPaymentResult? = null,
) {
    val calculation: FuelPaymentCalculation = preservedResult?.let(FuelPaymentCalculation::Success)
        ?: calculateFuelPayment(FuelPaymentInput(target, liters.numericInput(), pumpPrice.numericInput(),
            amount.numericInput(), discountType, discount.numericInput(), coupon.numericInput()))
    val result: FuelPaymentResult? = (calculation as? FuelPaymentCalculation.Success)?.result
    val litersText: String get() = if (target == FuelCalculationTarget.LITERS) result?.liters.toFieldText() else liters
    val priceText: String get() = if (target == FuelCalculationTarget.UNIT_PRICE) {
        result?.pumpPrice?.let { BigDecimal.valueOf(it).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() }.orEmpty()
    } else pumpPrice
    val amountText: String get() = if (target == FuelCalculationTarget.AMOUNT) {
        result?.let { String.format(Locale.ROOT, "%.2f", it.amount) }.orEmpty()
    } else amount

    fun edit(field: FuelAmountInput, value: String): FuelAmountForm = when (field) {
        FuelAmountInput.LITERS -> if (target == FuelCalculationTarget.LITERS) this else copy(liters = value, preservedResult = null)
        FuelAmountInput.UNIT_PRICE -> if (target == FuelCalculationTarget.UNIT_PRICE) this else copy(pumpPrice = value, preservedResult = null)
        FuelAmountInput.AMOUNT -> if (target == FuelCalculationTarget.AMOUNT) this else copy(amount = value, preservedResult = null)
        FuelAmountInput.DISCOUNT -> copy(discount = value, preservedResult = null)
        FuelAmountInput.COUPON -> copy(coupon = value, preservedResult = null)
    }

    fun switchTarget(next: FuelCalculationTarget): FuelAmountForm {
        if (next == target) return this
        return copy(
            target = next,
            // Promote only the old result to an input, preserving both raw inputs as typed.
            liters = litersText,
            pumpPrice = priceText,
            amount = amountText,
            preservedResult = result,
        )
    }

    fun switchDiscount(type: FuelDiscountType): FuelAmountForm = if (type == discountType) this
        else copy(discountType = type, discount = "", coupon = "", preservedResult = null)

    companion object {
        fun fromRecord(record: FuelRecord): FuelAmountForm {
            val difference = BigDecimal.valueOf(record.pumpAmount).subtract(BigDecimal.valueOf(record.amount))
            val price = if (record.liters > 0) record.pumpAmount / record.liters else 0.0
            return FuelAmountForm(
                target = FuelCalculationTarget.UNIT_PRICE,
                liters = record.liters.toFieldText(),
                amount = record.amount.toFieldText(),
                discountType = if (difference.signum() > 0) FuelDiscountType.FIXED else FuelDiscountType.NONE,
                discount = if (difference.signum() > 0) difference.stripTrailingZeros().toPlainString() else "",
                preservedResult = FuelPaymentResult(record.liters, price, record.amount, record.pumpAmount),
            )
        }
    }
}

/** Malformed text must not be treated as an empty optional coupon. */
private fun String.numericInput(): Double? = trim().let {
    when {
        it.isEmpty() -> null
        !it.matches(Regex("-?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")) -> Double.NaN
        else -> it.toDoubleOrNull() ?: Double.NaN
    }
}
