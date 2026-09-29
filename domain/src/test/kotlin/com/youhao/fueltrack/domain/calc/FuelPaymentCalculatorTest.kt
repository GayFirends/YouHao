package com.youhao.fueltrack.domain.calc

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class FuelPaymentCalculatorTest {
    private fun result(input: FuelPaymentInput): FuelPaymentResult =
        (calculateFuelPayment(input) as FuelPaymentCalculation.Success).result

    @Test
    fun allTargetsSupportEveryDiscountAndStackedCoupons() {
        val cases = listOf(
            Triple(FuelDiscountType.NONE, 0.0, 320.0),
            Triple(FuelDiscountType.FIXED, 20.0, 300.0),
            Triple(FuelDiscountType.PER_LITER, 0.5, 290.0),
            Triple(FuelDiscountType.RATE, 9.5, 294.0),
        )
        for ((discount, value, paid) in cases) {
            for (target in FuelCalculationTarget.entries) {
                val actual = result(FuelPaymentInput(target, 40.0, 8.0, paid, discount, value, coupon = 10.0))
                assertThat(actual.liters).isEqualTo(40.0)
                assertThat(actual.pumpPrice).isEqualTo(8.0)
                assertThat(actual.amount).isEqualTo(paid)
                assertThat(actual.pumpAmount).isEqualTo(320.0)
                assertThat(actual.discountAmount).isWithin(1e-9).of(320 - paid)
                assertThat(actual.paidPrice).isWithin(1e-9).of(paid / 40)
            }
        }
    }

    @Test
    fun moneyUsesDecimalHalfUpRounding() {
        assertThat(result(FuelPaymentInput(liters = 1.0, pumpPrice = 1.005)).amount).isEqualTo(1.01)
        assertThat(result(FuelPaymentInput(liters = 1.0, pumpPrice = 2.675)).amount).isEqualTo(2.68)
        val discounted = result(FuelPaymentInput(liters = 1.0, pumpPrice = 8.05,
            discountType = FuelDiscountType.RATE, discountValue = 9.0))
        assertThat(discounted.amount).isEqualTo(7.25)
        assertThat(discounted.pumpAmount).isEqualTo(8.05)
        assertThat(discounted.discountAmount).isEqualTo(0.8)
    }

    @Test
    fun reverseLitersKeepsReceiptAmountsAndFourDecimals() {
        val actual = result(FuelPaymentInput(FuelCalculationTarget.LITERS, pumpPrice = 7.85, amount = 300.0,
            discountType = FuelDiscountType.FIXED, discountValue = 20.0))
        assertThat(actual.liters).isEqualTo(40.7643)
        assertThat(actual.amount).isEqualTo(300.0)
        assertThat(actual.pumpAmount).isEqualTo(320.0)
        assertThat(actual.discountAmount).isEqualTo(20.0)
    }

    @Test
    fun reversePriceKeepsReceiptAmountsAndFourDecimals() {
        val actual = result(FuelPaymentInput(FuelCalculationTarget.UNIT_PRICE, liters = 37.0, amount = 300.0,
            discountType = FuelDiscountType.FIXED, discountValue = 20.0))
        assertThat(actual.pumpPrice).isEqualTo(8.6486)
        assertThat(actual.amount).isEqualTo(300.0)
        assertThat(actual.pumpAmount).isEqualTo(320.0)
    }

    @Test
    fun reverseDiscountedPriceKeepsOriginalPaidAmount() {
        val actual = result(FuelPaymentInput(FuelCalculationTarget.UNIT_PRICE, liters = 26.83, amount = 199.99,
            discountType = FuelDiscountType.RATE, discountValue = 9.5, coupon = 10.0))
        assertThat(actual.amount).isEqualTo(199.99)
        assertThat(actual.pumpPrice).isWithin(0.00005).of((199.99 + 10) / 0.95 / 26.83)
        assertThat(actual.pumpAmount).isEqualTo(221.04)
    }

    @Test
    fun staleTargetValuesAreIgnored() {
        assertThat(result(FuelPaymentInput(liters = 40.0, pumpPrice = 8.0, amount = Double.NaN)).amount).isEqualTo(320.0)
        assertThat(result(FuelPaymentInput(FuelCalculationTarget.UNIT_PRICE, 40.0, -1.0, 320.0)).pumpPrice).isEqualTo(8.0)
        assertThat(result(FuelPaymentInput(FuelCalculationTarget.LITERS, 0.0, 8.0, 320.0)).liters).isEqualTo(40.0)
    }

    @Test
    fun missingInputsDoNotProduceAResult() {
        assertThat(calculateFuelPayment(FuelPaymentInput())).isEqualTo(FuelPaymentCalculation.Incomplete)
        assertThat(calculateFuelPayment(FuelPaymentInput(liters = 40.0))).isEqualTo(FuelPaymentCalculation.Incomplete)
        assertThat(calculateFuelPayment(FuelPaymentInput(liters = 40.0, pumpPrice = 8.0,
            discountType = FuelDiscountType.FIXED))).isEqualTo(FuelPaymentCalculation.Incomplete)
    }

    @Test
    fun emptyOptionalCouponMeansZero() {
        assertThat(result(FuelPaymentInput(liters = 40.0, pumpPrice = 8.0,
            discountType = FuelDiscountType.PER_LITER, discountValue = 0.5)).amount).isEqualTo(300.0)
    }

    @Test
    fun fullDiscountCanProduceZeroPayment() {
        assertThat(result(FuelPaymentInput(liters = 40.0, pumpPrice = 8.0,
            discountType = FuelDiscountType.FIXED, discountValue = 320.0)).amount).isEqualTo(0.0)
        assertThat(result(FuelPaymentInput(liters = 40.0, pumpPrice = 8.0,
            discountType = FuelDiscountType.PER_LITER, discountValue = 8.0)).amount).isEqualTo(0.0)
    }

    @Test
    fun zeroPaymentCanStillReverseWithAFixedDiscount() {
        assertThat(result(FuelPaymentInput(FuelCalculationTarget.LITERS, pumpPrice = 8.0, amount = 0.0,
            discountType = FuelDiscountType.FIXED, discountValue = 100.0)).liters).isEqualTo(12.5)
    }

    @Test
    fun zeroDiscountedPriceCannotReverseLiters() {
        assertThat(calculateFuelPayment(FuelPaymentInput(FuelCalculationTarget.LITERS, pumpPrice = 8.0, amount = 0.0,
            discountType = FuelDiscountType.PER_LITER, discountValue = 8.0))).isInstanceOf(FuelPaymentCalculation.Invalid::class.java)
    }

    @Test
    fun excessDiscountIsRejectedInsteadOfClamped() {
        for (input in listOf(
            FuelPaymentInput(liters = 40.0, pumpPrice = 8.0, discountType = FuelDiscountType.FIXED, discountValue = 321.0),
            FuelPaymentInput(liters = 40.0, pumpPrice = 8.0, discountType = FuelDiscountType.PER_LITER, discountValue = 8.01),
            FuelPaymentInput(liters = 40.0, pumpPrice = 8.0, discountType = FuelDiscountType.RATE, discountValue = 9.5, coupon = 305.0),
        )) assertThat(calculateFuelPayment(input)).isInstanceOf(FuelPaymentCalculation.Invalid::class.java)
    }

    @Test
    fun invalidRatesAreRejected() {
        for (rate in listOf(0.0, -1.0, 10.1, Double.NaN)) {
            assertThat(calculateFuelPayment(FuelPaymentInput(liters = 40.0, pumpPrice = 8.0,
                discountType = FuelDiscountType.RATE, discountValue = rate))).isInstanceOf(FuelPaymentCalculation.Invalid::class.java)
        }
    }

    @Test
    fun invalidPrimaryNumbersAreRejected() {
        for (value in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e100)) {
            assertThat(calculateFuelPayment(FuelPaymentInput(liters = value, pumpPrice = 8.0))).isInstanceOf(FuelPaymentCalculation.Invalid::class.java)
            assertThat(calculateFuelPayment(FuelPaymentInput(liters = 40.0, pumpPrice = value))).isInstanceOf(FuelPaymentCalculation.Invalid::class.java)
        }
    }

    @Test
    fun invalidCouponAndPaidAmountAreRejected() {
        assertThat(calculateFuelPayment(FuelPaymentInput(liters = 40.0, pumpPrice = 8.0,
            discountType = FuelDiscountType.RATE, discountValue = 9.5, coupon = Double.NaN))).isInstanceOf(FuelPaymentCalculation.Invalid::class.java)
        assertThat(calculateFuelPayment(FuelPaymentInput(FuelCalculationTarget.LITERS, pumpPrice = 8.0, amount = -1.0)))
            .isInstanceOf(FuelPaymentCalculation.Invalid::class.java)
    }

    @Test
    fun oversizedAndUnrepresentableResultsAreRejected() {
        assertThat(calculateFuelPayment(FuelPaymentInput(liters = 1e9, pumpPrice = 1e9))).isInstanceOf(FuelPaymentCalculation.Invalid::class.java)
        assertThat(calculateFuelPayment(FuelPaymentInput(FuelCalculationTarget.LITERS, pumpPrice = 1e9, amount = 0.01)))
            .isInstanceOf(FuelPaymentCalculation.Invalid::class.java)
    }
}
