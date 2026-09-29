package com.youhao.fueltrack.ui.records

import com.google.common.truth.Truth.assertThat
import com.youhao.fueltrack.domain.calc.FuelCalculationTarget
import com.youhao.fueltrack.domain.calc.FuelDiscountType
import com.youhao.fueltrack.domain.calc.FuelPaymentCalculation
import com.youhao.fueltrack.domain.model.FuelRecord
import org.junit.Test

class FuelAmountFormTest {
    private fun record(liters: Double = 37.0, amount: Double = 300.0, pumpAmount: Double = 320.0) = FuelRecord(
        id = "r1", vehicleId = "v1", date = "2026-09-29", odometer = 12345.0,
        liters = liters, amount = amount, pumpAmount = pumpAmount, pricePerLiter = amount / liters,
        isFull = true, station = "", note = "", createdAt = "2026-09-29T00:00:00Z", updatedAt = "2026-09-29T00:00:00Z",
    )

    @Test
    fun typingTwoInputsImmediatelyUpdatesTheRecordAmount() {
        val form = FuelAmountForm().edit(FuelAmountInput.LITERS, "40").edit(FuelAmountInput.UNIT_PRICE, "8")
        assertThat(form.amountText).isEqualTo("320.00")
        assertThat(form.result!!.amount).isEqualTo(320.0)
        val discounted = form.switchDiscount(FuelDiscountType.PER_LITER)
            .edit(FuelAmountInput.DISCOUNT, "0.5").edit(FuelAmountInput.COUPON, "10")
        assertThat(discounted.amountText).isEqualTo("290.00")
        assertThat(discounted.result!!.pumpAmount).isEqualTo(320.0)
    }

    @Test
    fun clearingOrInvalidatingInputClearsTheResultUsedForSaving() {
        val form = FuelAmountForm(liters = "40", pumpPrice = "8")
        for (text in listOf("", "-1", "1..2", "NaN", "Infinity", "1e3")) {
            val changed = form.edit(FuelAmountInput.UNIT_PRICE, text)
            assertThat(changed.result).isNull()
            assertThat(changed.amountText).isEmpty()
        }
    }

    @Test
    fun switchingTargetsUsesTheDisplayedValues() {
        val original = FuelAmountForm(liters = "40", pumpPrice = "8", amount = "999",
            discountType = FuelDiscountType.PER_LITER, discount = "0.5", coupon = "10")
        val liters = original.switchTarget(FuelCalculationTarget.LITERS)
        assertThat(liters.amount).isEqualTo("290.00")
        assertThat(liters.litersText).isEqualTo("40")
        assertThat(liters.edit(FuelAmountInput.AMOUNT, "365").litersText).isEqualTo("50")
        val price = original.switchTarget(FuelCalculationTarget.UNIT_PRICE)
        assertThat(price.edit(FuelAmountInput.AMOUNT, "310").priceText).isEqualTo("8.5")
    }

    @Test
    fun incompleteModeSwitchDoesNotRestoreStaleComputedText() {
        val form = FuelAmountForm(liters = "40", pumpPrice = "8", amount = "999")
            .edit(FuelAmountInput.UNIT_PRICE, "").switchTarget(FuelCalculationTarget.LITERS)
        assertThat(form.amountText).isEmpty()
        assertThat(form.litersText).isEmpty()
        assertThat(form.result).isNull()
    }

    @Test
    fun excessDiscountDisablesSavingAndCorrectionRecovers() {
        val form = FuelAmountForm(liters = "40", pumpPrice = "8").switchDiscount(FuelDiscountType.FIXED)
        assertThat(form.result).isNull()
        val excessive = form.edit(FuelAmountInput.DISCOUNT, "321")
        assertThat(excessive.calculation).isInstanceOf(FuelPaymentCalculation.Invalid::class.java)
        assertThat(excessive.result).isNull()
        assertThat(excessive.edit(FuelAmountInput.DISCOUNT, "20").result!!.amount).isEqualTo(300.0)
    }

    @Test
    fun changingDiscountTypeClearsIncompatibleUnitsAndCoupon() {
        val form = FuelAmountForm(liters = "40", pumpPrice = "8", discountType = FuelDiscountType.PER_LITER,
            discount = "0.5", coupon = "10")
        assertThat(form.switchDiscount(FuelDiscountType.NONE).amountText).isEqualTo("320.00")
        val rate = form.switchDiscount(FuelDiscountType.RATE)
        assertThat(rate.discount).isEmpty()
        assertThat(rate.coupon).isEmpty()
        assertThat(rate.result).isNull()
        assertThat(rate.edit(FuelAmountInput.DISCOUNT, "9.5").amountText).isEqualTo("304.00")
    }

    @Test
    fun invalidOptionalCouponIsNotTreatedAsZero() {
        val form = FuelAmountForm(liters = "40", pumpPrice = "8", discountType = FuelDiscountType.RATE, discount = "9.5")
        assertThat(form.edit(FuelAmountInput.COUPON, "oops").result).isNull()
        assertThat(form.edit(FuelAmountInput.COUPON, "").result!!.amount).isEqualTo(304.0)
    }

    @Test
    fun typingAndModeSwitchesPreserveRawKnownInputs() {
        val form = FuelAmountForm(liters = "40.", pumpPrice = "8.00")
        assertThat(form.litersText).isEqualTo("40.")
        assertThat(form.priceText).isEqualTo("8.00")
        assertThat(form.switchTarget(FuelCalculationTarget.UNIT_PRICE).litersText).isEqualTo("40.")
        assertThat(form.switchTarget(FuelCalculationTarget.LITERS).priceText).isEqualTo("8.00")
    }

    @Test
    fun editingAnAutomaticFieldDoesNotOverrideTheResult() {
        val form = FuelAmountForm(liters = "40", pumpPrice = "8")
        assertThat(form.edit(FuelAmountInput.AMOUNT, "1")).isEqualTo(form)
    }

    @Test
    fun existingRecordKeepsReceiptValuesWhenOnlyOtherDetailsChange() {
        val original = record()
        val state = RecordEditorUiState(amounts = FuelAmountForm.fromRecord(original), note = "原备注")
        val edited = state.copy(note = "新备注", odometerText = "9999")
        val form = edited.amounts
        assertThat(form.target).isEqualTo(FuelCalculationTarget.UNIT_PRICE)
        assertThat(form.discountType).isEqualTo(FuelDiscountType.FIXED)
        assertThat(form.discount).isEqualTo("20")
        assertThat(form.priceText).isEqualTo("8.6486")
        assertThat(form.result!!.liters).isEqualTo(original.liters)
        assertThat(form.result.amount).isEqualTo(original.amount)
        assertThat(form.result.pumpAmount).isEqualTo(original.pumpAmount)
        assertThat(form.edit(FuelAmountInput.AMOUNT, "280").result!!.pumpAmount).isEqualTo(300.0)
    }

    @Test
    fun repeatedModeChangesDoNotAccumulateRoundingInReceiptAmounts() {
        val original = record(liters = 26.8321, amount = 199.99, pumpAmount = 221.04)
        var form = FuelAmountForm.fromRecord(original)
        repeat(10) {
            FuelCalculationTarget.entries.forEach { form = form.switchTarget(it) }
        }
        assertThat(form.result!!.liters).isEqualTo(original.liters)
        assertThat(form.result.amount).isEqualTo(original.amount)
        assertThat(form.result.pumpAmount).isEqualTo(original.pumpAmount)
    }
}
