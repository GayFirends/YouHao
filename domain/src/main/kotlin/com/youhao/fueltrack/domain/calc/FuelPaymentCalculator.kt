package com.youhao.fueltrack.domain.calc

import java.math.BigDecimal
import java.math.RoundingMode

enum class FuelCalculationTarget { AMOUNT, UNIT_PRICE, LITERS }

/** RATE uses the Chinese convention: 9.5 means paying 95% of the original price. */
enum class FuelDiscountType { NONE, FIXED, PER_LITER, RATE }

data class FuelPaymentInput(
    val target: FuelCalculationTarget = FuelCalculationTarget.AMOUNT,
    val liters: Double? = null,
    val pumpPrice: Double? = null,
    val amount: Double? = null,
    val discountType: FuelDiscountType = FuelDiscountType.NONE,
    val discountValue: Double? = null,
    /** Applied after the per-litre reduction or percentage discount. */
    val coupon: Double? = null,
)

data class FuelPaymentResult(
    val liters: Double,
    val pumpPrice: Double,
    val amount: Double,
    val pumpAmount: Double,
) {
    val discountAmount: Double get() = BigDecimal.valueOf(pumpAmount)
        .subtract(BigDecimal.valueOf(amount)).toDouble()
    val paidPrice: Double get() = amount / liters
}

sealed interface FuelPaymentCalculation {
    data object Incomplete : FuelPaymentCalculation
    data class Invalid(val message: String) : FuelPaymentCalculation
    data class Success(val result: FuelPaymentResult) : FuelPaymentCalculation
}

private class PaymentInputProblem(val explanation: String?) : RuntimeException()

/**
 * Solves paid = litres * (pumpPrice * rate - perLitreDiscount) - fixedDiscount - coupon.
 * Exactly two primary inputs are used; any stale value for the chosen target is ignored.
 * Decimal arithmetic rounds money half up to cents; only reverse-calculated litres / prices
 * are rounded to four decimals. The original paid amount is preserved when reversing.
 */
fun calculateFuelPayment(input: FuelPaymentInput): FuelPaymentCalculation = try {
    fun number(value: Double?, label: String, positive: Boolean = false): BigDecimal {
        if (value == null) throw PaymentInputProblem(null)
        if (!value.isFinite() || value < 0 || (positive && value == 0.0)) {
            throw PaymentInputProblem("${label}须为${if (positive) "大于 0" else "不小于 0"}的有效数字")
        }
        if (value > 1_000_000_000) throw PaymentInputProblem("${label}过大，请核对")
        return BigDecimal.valueOf(value)
    }

    val zero = BigDecimal.ZERO
    val discount = if (input.discountType == FuelDiscountType.NONE) zero
        else number(input.discountValue, "优惠数值")
    val coupon = if (input.discountType == FuelDiscountType.PER_LITER || input.discountType == FuelDiscountType.RATE) {
        number(input.coupon ?: 0.0, "优惠券金额")
    } else zero
    val rate = if (input.discountType == FuelDiscountType.RATE) {
        if (discount <= zero || discount > BigDecimal.TEN) {
            throw PaymentInputProblem("折扣须大于 0 且不超过 10，例如 9.5 表示九五折")
        }
        discount.movePointLeft(1)
    } else BigDecimal.ONE
    val perLiter = if (input.discountType == FuelDiscountType.PER_LITER) discount else zero
    val fixed = if (input.discountType == FuelDiscountType.FIXED) discount else coupon

    var liters = if (input.target == FuelCalculationTarget.LITERS) zero else number(input.liters, "加油量", positive = true)
    var price = if (input.target == FuelCalculationTarget.UNIT_PRICE) zero else number(input.pumpPrice, "表显单价", positive = true)
    var amount = if (input.target == FuelCalculationTarget.AMOUNT) zero else number(input.amount, "实付金额").money()

    when (input.target) {
        FuelCalculationTarget.AMOUNT -> {
            val paidUnitPrice = price.multiply(rate).subtract(perLiter)
            if (paidUnitPrice < zero) throw PaymentInputProblem("每升优惠不能超过表显单价")
            amount = liters.multiply(paidUnitPrice).subtract(fixed)
            if (amount < zero) throw PaymentInputProblem("优惠超过应付金额，请核对优惠或优惠券")
        }
        FuelCalculationTarget.UNIT_PRICE -> {
            price = amount.add(fixed).divide(liters, 16, RoundingMode.HALF_UP)
                .add(perLiter).divide(rate, 16, RoundingMode.HALF_UP)
            if (price <= zero) throw PaymentInputProblem("无法算出有效单价，请核对金额和优惠")
        }
        FuelCalculationTarget.LITERS -> {
            val paidUnitPrice = price.multiply(rate).subtract(perLiter)
            if (paidUnitPrice <= zero) throw PaymentInputProblem("优惠后单价须大于 0，才能反算加油量")
            liters = amount.add(fixed).divide(paidUnitPrice, 16, RoundingMode.HALF_UP)
            if (liters <= zero) throw PaymentInputProblem("无法算出有效加油量，请核对金额和优惠")
        }
    }

    val pumpAmount = liters.multiply(price).money()
    val savedLiters = if (input.target == FuelCalculationTarget.LITERS) liters.setScale(4, RoundingMode.HALF_UP) else liters
    val shownPrice = if (input.target == FuelCalculationTarget.UNIT_PRICE) price.setScale(4, RoundingMode.HALF_UP) else price
    val max = BigDecimal("1000000000")
    if (savedLiters <= zero || shownPrice <= zero) {
        throw PaymentInputProblem("反算结果过小，请核对输入")
    }
    if (listOf(savedLiters, shownPrice, pumpAmount, amount).any { it > max }) {
        throw PaymentInputProblem("计算结果过大，请核对输入")
    }
    FuelPaymentCalculation.Success(
        FuelPaymentResult(savedLiters.toDouble(), shownPrice.toDouble(), amount.money().toDouble(), pumpAmount.toDouble()),
    )
} catch (problem: PaymentInputProblem) {
    problem.explanation?.let { FuelPaymentCalculation.Invalid(it) } ?: FuelPaymentCalculation.Incomplete
}

private fun BigDecimal.money(): BigDecimal = setScale(2, RoundingMode.HALF_UP)
