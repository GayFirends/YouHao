package com.youhao.fueltrack.domain.calc

import com.google.common.truth.Truth.assertThat
import com.youhao.fueltrack.domain.model.FuelRecord
import kotlin.test.Test

/**
 * Locks in the full-to-full consumption rules ported from `src/services/fuel-calculations.ts`.
 */
class FuelCalculationsTest {

    private fun record(
        id: String,
        date: String,
        odometer: Double,
        liters: Double,
        isFull: Boolean = true,
        deletedAt: String? = null,
    ) = FuelRecord(
        id = id,
        vehicleId = "v1",
        date = date,
        odometer = odometer,
        liters = liters,
        amount = liters * 7.5,
        pumpAmount = liters * 7.5,
        pricePerLiter = 7.5,
        isFull = isFull,
        station = "",
        note = "",
        createdAt = "${date}T00:00:00.000Z",
        updatedAt = "${date}T00:00:00.000Z",
        deletedAt = deletedAt,
    )

    /** 1000 km → 1500 km burning 50 L (20 L partial + 30 L full) = 10 L/100 km. */
    private fun twoFullTanksWithPartial() = listOf(
        record("a", "2024-01-01", 1000.0, 50.0),
        record("b", "2024-02-01", 1300.0, 20.0, isFull = false),
        record("c", "2024-03-01", 1500.0, 30.0),
    )

    @Test
    fun sumsPartialFillsIntoTheFollowingFullTank() {
        val intervals = calculateConsumptionIntervals(twoFullTanksWithPartial())

        assertThat(intervals).hasSize(1)
        val interval = intervals.single()
        assertThat(interval.previousFullRecord.id).isEqualTo("a")
        assertThat(interval.record.id).isEqualTo("c")
        assertThat(interval.distance).isWithin(1e-9).of(500.0)
        assertThat(interval.liters).isWithin(1e-9).of(50.0)
        assertThat(interval.consumption).isWithin(1e-9).of(10.0)
    }

    @Test
    fun sortsByOdometerRegardlessOfInputOrder() {
        val shuffled = calculateConsumptionIntervals(twoFullTanksWithPartial().reversed())

        assertThat(shuffled).hasSize(1)
        assertThat(shuffled.single().distance).isWithin(1e-9).of(500.0)
        assertThat(shuffled.single().liters).isWithin(1e-9).of(50.0)
    }

    @Test
    fun ignoresLitresBurntBeforeTheFirstFullTank() {
        val intervals = calculateConsumptionIntervals(
            listOf(
                record("z", "2023-12-01", 900.0, 10.0, isFull = false),
                record("a", "2024-01-01", 1000.0, 50.0),
                record("c", "2024-03-01", 1500.0, 30.0),
            )
        )

        assertThat(intervals).hasSize(1)
        // The 10 L before the first full tank must not be attributed to the interval.
        assertThat(intervals.single().liters).isWithin(1e-9).of(30.0)
    }

    @Test
    fun skipsDeletedRecords() {
        val intervals = calculateConsumptionIntervals(
            listOf(
                record("a", "2024-01-01", 1000.0, 50.0),
                record("b", "2024-02-01", 1300.0, 20.0, isFull = false, deletedAt = "2024-02-02T00:00:00.000Z"),
                record("c", "2024-03-01", 1500.0, 30.0),
            )
        )

        assertThat(intervals).hasSize(1)
        assertThat(intervals.single().liters).isWithin(1e-9).of(30.0)
    }

    @Test
    fun rejectsZeroDistanceIntervals() {
        val intervals = calculateConsumptionIntervals(
            listOf(
                record("a", "2024-01-01", 1000.0, 50.0),
                record("b", "2024-02-01", 1000.0, 30.0),
            )
        )

        assertThat(intervals).isEmpty()
    }

    @Test
    fun rejectsImplausibleConsumption() {
        // 50 L over 1 km is a data-entry mistake, not 5000 L/100 km.
        val intervals = calculateConsumptionIntervals(
            listOf(
                record("a", "2024-01-01", 1000.0, 50.0),
                record("b", "2024-02-01", 1001.0, 50.0),
            )
        )

        assertThat(intervals).isEmpty()
    }

    @Test
    fun averagesDistanceWeightedNotPerInterval() {
        val intervals = calculateConsumptionIntervals(
            listOf(
                record("a", "2024-01-01", 1000.0, 50.0),
                record("b", "2024-02-01", 1500.0, 30.0), // 500 km, 30 L -> 6.0
                record("c", "2024-03-01", 2500.0, 40.0), // 1000 km, 40 L -> 4.0
            )
        )

        assertThat(intervals).hasSize(2)
        // (30 + 40) / (500 + 1000) * 100, not the naive mean of 6.0 and 4.0.
        assertThat(calculateAverageConsumption(intervals)).isWithin(1e-9).of(70.0 / 1500.0 * 100.0)
    }

    @Test
    fun averageIsZeroWithoutDistance() {
        assertThat(calculateAverageConsumption(emptyList())).isWithin(1e-9).of(0.0)
    }

    @Test
    fun splitsPumpPriceFromDiscountedPrice() {
        val summary = fuelPriceSummary(liters = 40.0, amount = 300.0, pumpAmount = 320.0)

        assertThat(summary.pumpPricePerLiter).isWithin(1e-9).of(8.0)
        assertThat(summary.discountedPricePerLiter).isWithin(1e-9).of(7.5)
        assertThat(summary.discountAmount).isWithin(1e-9).of(20.0)
    }

    @Test
    fun neverReportsANegativeDiscount() {
        val summary = fuelPriceSummary(liters = 40.0, amount = 320.0, pumpAmount = 300.0)

        assertThat(summary.discountAmount).isWithin(1e-9).of(0.0)
    }

    @Test
    fun priceSummaryIsZeroForMissingInput() {
        val summary = fuelPriceSummary(liters = 0.0, amount = 0.0, pumpAmount = 0.0)

        assertThat(summary.pumpPricePerLiter).isWithin(1e-9).of(0.0)
        assertThat(summary.discountedPricePerLiter).isWithin(1e-9).of(0.0)
        assertThat(summary.discountAmount).isWithin(1e-9).of(0.0)
    }

    @Test
    fun warningsAreSoftAndDescribeEverySuspiciousField() {
        val warnings = fuelRecordWarnings(
            draft = RecordDraft(
                date = "2099-01-01",
                odometer = 500.0,
                liters = 200.0,
                amount = 2100.0,
                isFull = true,
            ),
            records = listOf(record("x", "2024-01-01", 1000.0, 50.0)),
            today = "2024-06-01",
        )

        assertThat(warnings).contains("加油日期晚于今天")
        assertThat(warnings).contains("加油量超过 150 L")
        assertThat(warnings.any { it.startsWith("里程低于此前记录的") }).isTrue()
        // 2100 / 200 = 10.5 ¥/L, inside the plausible band, so no price warning.
        assertThat(warnings.any { it.startsWith("计算单价为") }).isFalse()
    }

    @Test
    fun warnsAboutImplausibleUnitPrice() {
        val warnings = fuelRecordWarnings(
            draft = RecordDraft(
                date = "2024-01-01",
                odometer = 1000.0,
                liters = 40.0,
                amount = 40.0, // ¥1.00 / L
                isFull = false,
            ),
            records = emptyList(),
            today = "2024-06-01",
        )

        assertThat(warnings).containsExactly("计算单价为 ¥1.00/L")
    }

    @Test
    fun cleanDraftProducesNoWarnings() {
        val warnings = fuelRecordWarnings(
            draft = RecordDraft(
                date = "2024-01-01",
                odometer = 1000.0,
                liters = 40.0,
                amount = 300.0,
                isFull = true,
            ),
            records = emptyList(),
            today = "2024-06-01",
        )

        assertThat(warnings).isEmpty()
    }
}
