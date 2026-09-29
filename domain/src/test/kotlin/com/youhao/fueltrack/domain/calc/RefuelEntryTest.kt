package com.youhao.fueltrack.domain.calc

import com.google.common.truth.Truth.assertThat
import com.youhao.fueltrack.domain.model.FuelRecord
import kotlin.test.Test

/**
 * The litres ↔ amount ↔ pump-price coupling ported from `src/services/refuel-entry.ts`.
 * The original mutated a plain object; this port is immutable, so every case asserts on
 * the returned value.
 */
class RefuelEntryTest {

    private fun entry(
        liters: Double? = 40.0,
        amount: Double? = 300.0,
        pumpAmount: Double? = 300.0,
        pumpPrice: Double? = null,
        hasPumpOverride: Boolean = false,
        lastPumpSource: PumpSource = PumpSource.AMOUNT,
    ) = RefuelEntry(
        liters = liters,
        amount = amount,
        pumpAmount = pumpAmount,
        pumpPrice = pumpPrice ?: if (liters != null && liters > 0 && pumpAmount != null) round2(pumpAmount / liters) else null,
        hasPumpOverride = hasPumpOverride,
        lastPumpSource = lastPumpSource,
    )

    @Test
    fun emptyEntryHasNoDerivedValues() {
        val created = createRefuelEntry()

        assertThat(created.liters).isNull()
        assertThat(created.amount).isNull()
        assertThat(created.pumpAmount).isNull()
        assertThat(created.pumpPrice).isNull()
        assertThat(created.hasPumpOverride).isFalse()
    }

    @Test
    fun matchingPumpAndChargedAmountIsNotAnOverride() {
        val created = createRefuelEntry(liters = 40.0, amount = 300.0, pumpAmount = 300.0)

        assertThat(created.pumpPrice).isWithin(1e-9).of(7.5)
        assertThat(created.hasPumpOverride).isFalse()
    }

    @Test
    fun differingPumpAmountCountsAsAnOverride() {
        val created = createRefuelEntry(liters = 40.0, amount = 300.0, pumpAmount = 320.0)

        assertThat(created.pumpPrice).isWithin(1e-9).of(8.0)
        assertThat(created.hasPumpOverride).isTrue()
    }

    @Test
    fun effectivePumpAmountFallsBackToAmount() {
        assertThat(effectivePumpAmount(entry(pumpAmount = null))).isWithin(1e-9).of(300.0)
        assertThat(effectivePumpAmount(entry(pumpAmount = 320.0))).isWithin(1e-9).of(320.0)
    }

    @Test
    fun editingTheChargedAmountRedrivesThePumpAmountWhileUnoverridden() {
        val updated = updateRefuelAmount(entry(), AmountField.AMOUNT, 360.0)

        assertThat(updated.amount).isWithin(1e-9).of(360.0)
        assertThat(updated.pumpAmount).isWithin(1e-9).of(360.0)
        assertThat(updated.pumpPrice).isWithin(1e-9).of(9.0)
        assertThat(updated.hasPumpOverride).isFalse()
    }

    @Test
    fun editingTheChargedAmountKeepsAManualPumpOverride() {
        val overridden = entry(pumpAmount = 320.0, hasPumpOverride = true)

        val updated = updateRefuelAmount(overridden, AmountField.AMOUNT, 360.0)

        assertThat(updated.pumpAmount).isWithin(1e-9).of(320.0)
        assertThat(updated.hasPumpOverride).isTrue()
    }

    @Test
    fun editingThePumpAmountSetsTheOverrideAndRedrivesThePrice() {
        val updated = updateRefuelAmount(entry(), AmountField.PUMP_AMOUNT, 320.0)

        assertThat(updated.pumpAmount).isWithin(1e-9).of(320.0)
        assertThat(updated.pumpPrice).isWithin(1e-9).of(8.0)
        assertThat(updated.hasPumpOverride).isTrue()
    }

    @Test
    fun editingThePumpPriceRedrivesThePumpAmount() {
        val updated = updateRefuelAmount(entry(), AmountField.PUMP_PRICE, 8.5)

        assertThat(updated.pumpAmount).isWithin(1e-9).of(340.0)
        assertThat(updated.hasPumpOverride).isTrue()
        assertThat(updated.lastPumpSource).isEqualTo(PumpSource.UNIT_PRICE)
    }

    @Test
    fun editingLitresRecomputesThePumpAmountFromTheLastPumpSource() {
        val fromAmount = updateRefuelAmount(entry(), AmountField.LITERS, 50.0)
        // Not overridden: the pump amount follows the charged amount.
        assertThat(fromAmount.pumpAmount).isWithin(1e-9).of(300.0)

        val fromUnitPrice = updateRefuelAmount(
            entry(pumpAmount = 340.0, pumpPrice = 8.5, hasPumpOverride = true, lastPumpSource = PumpSource.UNIT_PRICE),
            AmountField.LITERS,
            50.0,
        )
        assertThat(fromUnitPrice.pumpAmount).isWithin(1e-9).of(round2(50.0 * 8.5))
    }

    @Test
    fun resetPumpAmountClearsTheOverride() {
        val reset = resetPumpAmount(entry(pumpAmount = 320.0, hasPumpOverride = true))

        assertThat(reset.pumpAmount).isWithin(1e-9).of(300.0)
        assertThat(reset.pumpPrice).isWithin(1e-9).of(7.5)
        assertThat(reset.hasPumpOverride).isFalse()
    }

    @Test
    fun nonFiniteInputIsTreatedAsBlank() {
        val updated = updateRefuelAmount(entry(), AmountField.LITERS, Double.NaN)

        assertThat(updated.liters).isNull()
    }

    @Test
    fun buildsFormStateFromAnExistingRecord() {
        val record = FuelRecord(
            id = "r1",
            vehicleId = "v1",
            date = "2024-01-01",
            odometer = 1000.0,
            liters = 40.0,
            amount = 300.0,
            pumpAmount = 320.0,
            pricePerLiter = 8.0,
            isFull = true,
            station = "",
            note = "",
            createdAt = "2024-01-01T00:00:00.000Z",
            updatedAt = "2024-01-01T00:00:00.000Z",
        )

        val form = createRefuelEntryFor(record)

        assertThat(form.liters).isWithin(1e-9).of(40.0)
        assertThat(form.amount).isWithin(1e-9).of(300.0)
        assertThat(form.pumpAmount).isWithin(1e-9).of(320.0)
        assertThat(form.pumpPrice).isWithin(1e-9).of(8.0)
        assertThat(form.hasPumpOverride).isTrue()
    }

    @Test
    fun round2MatchesJavaScriptToFixed() {
        // (2.675).toFixed(2) === "2.67" because the double is 2.67499999999999982...
        assertThat(round2(2.675)).isWithin(1e-12).of(2.67)
        assertThat(round2(7.5)).isWithin(1e-12).of(7.5)
        assertThat(round2(1.005)).isWithin(1e-12).of(1.0)
    }
}
