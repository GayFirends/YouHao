package com.youhao.fueltrack.domain.query

import com.google.common.truth.Truth.assertThat
import com.youhao.fueltrack.domain.model.FuelRecord
import com.youhao.fueltrack.domain.model.RecordCursor
import com.youhao.fueltrack.domain.model.RecordListQuery
import kotlin.test.Test

class RecordQueryTest {

    private var nextId = 0

    private fun record(
        date: String = "2024-05-01",
        odometer: Double = 1000.0,
        createdAt: String = "2024-05-01T10:00:00.000Z",
        id: String = "r${nextId++}",
        vehicleId: String = "v1",
        amount: Double = 300.0,
        station: String = "",
        note: String = "",
        deletedAt: String? = null,
    ) = FuelRecord(
        id = id,
        vehicleId = vehicleId,
        date = date,
        odometer = odometer,
        liters = 40.0,
        amount = amount,
        pumpAmount = amount,
        pricePerLiter = 7.5,
        isFull = true,
        station = station,
        note = note,
        createdAt = createdAt,
        updatedAt = createdAt,
        deletedAt = deletedAt,
    )

    // -----------------------------------------------------------------------
    // compareRecords
    // -----------------------------------------------------------------------

    @Test
    fun ordersByDateDescendingFirst() {
        val older = record(date = "2024-05-01", id = "a")
        val newer = record(date = "2024-06-01", id = "b")

        assertThat(compareRecords(newer, older)).isLessThan(0)
        assertThat(compareRecords(older, newer)).isGreaterThan(0)
    }

    @Test
    fun breaksDateTiesByOdometerThenCreatedAtThenId() {
        val base = record(date = "2024-05-01", odometer = 1000.0, createdAt = "2024-05-01T10:00:00.000Z", id = "a")

        assertThat(compareRecords(base.copy(odometer = 1200.0, id = "z"), base)).isLessThan(0)
        assertThat(compareRecords(base.copy(createdAt = "2024-05-01T11:00:00.000Z", id = "z"), base)).isLessThan(0)
        assertThat(compareRecords(base.copy(id = "b"), base)).isLessThan(0)
        assertThat(compareRecords(base, base)).isEqualTo(0)
    }

    // -----------------------------------------------------------------------
    // listRecordPage — filtering
    // -----------------------------------------------------------------------

    @Test
    fun hidesDeletedRecordsUnlessRequested() {
        val live = record(id = "live")
        val gone = record(id = "gone", deletedAt = "2024-06-01T00:00:00.000Z")
        val all = listOf(live, gone)

        assertThat(listRecordPage(all, RecordListQuery()).items.map { it.id }).containsExactly("live")
        assertThat(listRecordPage(all, RecordListQuery(includeDeleted = true)).items.map { it.id })
            .containsExactly("live", "gone")
    }

    @Test
    fun filtersByVehicleAndMonth() {
        val all = listOf(
            record(id = "may-v1", vehicleId = "v1", date = "2024-05-10"),
            record(id = "jun-v1", vehicleId = "v1", date = "2024-06-10"),
            record(id = "may-v2", vehicleId = "v2", date = "2024-05-20"),
        )

        assertThat(listRecordPage(all, RecordListQuery(vehicleId = "v1")).items.map { it.id })
            .containsExactly("jun-v1", "may-v1")
        assertThat(listRecordPage(all, RecordListQuery(month = "2024-05")).items.map { it.id })
            .containsExactly("may-v2", "may-v1")
        assertThat(listRecordPage(all, RecordListQuery(vehicleId = "v2", month = "2024-06")).items).isEmpty()
    }

    @Test
    fun searchesStationNoteAndDateCaseInsensitively() {
        val all = listOf(
            record(id = "station", station = "Shell 加油站"),
            record(id = "note", note = "第一次长途"),
            record(id = "date", date = "2024-07-04"),
            record(id = "other", station = "中石化"),
        )

        assertThat(listRecordPage(all, RecordListQuery(search = "shell")).items.map { it.id })
            .containsExactly("station")
        assertThat(listRecordPage(all, RecordListQuery(search = "长途")).items.map { it.id })
            .containsExactly("note")
        assertThat(listRecordPage(all, RecordListQuery(search = "2024-07")).items.map { it.id })
            .containsExactly("date")
        // Blank searches are treated as "no filter", not "match nothing".
        assertThat(listRecordPage(all, RecordListQuery(search = "   ")).items).hasSize(4)
    }

    // -----------------------------------------------------------------------
    // listRecordPage — paging
    // -----------------------------------------------------------------------

    @Test
    fun walksEveryRecordExactlyOnceAcrossPages() {
        val all = (1..7).map { record(id = "r$it", date = "2024-05-0$it", odometer = it * 100.0) }

        val first = listRecordPage(all, RecordListQuery(limit = 3))
        val second = listRecordPage(all, RecordListQuery(limit = 3, cursor = first.nextCursor))
        val third = listRecordPage(all, RecordListQuery(limit = 3, cursor = second.nextCursor))

        assertThat(first.items.map { it.id }).containsExactly("r7", "r6", "r5").inOrder()
        assertThat(second.items.map { it.id }).containsExactly("r4", "r3", "r2").inOrder()
        assertThat(third.items.map { it.id }).containsExactly("r1").inOrder()

        assertThat(first.nextCursor).isNotNull()
        assertThat(second.nextCursor).isNotNull()
        // The last page is short, so there is nothing left to fetch.
        assertThat(third.nextCursor).isNull()
    }

    @Test
    fun reportsNoCursorWhenThePageExactlyDrainsTheResult() {
        val all = (1..3).map { record(id = "r$it", date = "2024-05-0$it", odometer = it * 100.0) }

        val page = listRecordPage(all, RecordListQuery(limit = 3))

        assertThat(page.items).hasSize(3)
        assertThat(page.nextCursor).isNull()
    }

    @Test
    fun cursorResumesThroughRecordsSharingADateAndOdometer() {
        // Same date and odometer, so the tie is broken by createdAt then id — the cursor must
        // not skip or repeat any of them.
        val all = listOf(
            record(id = "a", date = "2024-05-01", odometer = 1000.0, createdAt = "2024-05-01T10:00:00.000Z"),
            record(id = "b", date = "2024-05-01", odometer = 1000.0, createdAt = "2024-05-01T10:00:00.000Z"),
            record(id = "c", date = "2024-05-01", odometer = 1000.0, createdAt = "2024-05-01T09:00:00.000Z"),
            record(id = "d", date = "2024-05-01", odometer = 900.0, createdAt = "2024-05-01T10:00:00.000Z"),
        )

        val first = listRecordPage(all, RecordListQuery(limit = 2))
        val second = listRecordPage(all, RecordListQuery(limit = 2, cursor = first.nextCursor))

        assertThat(first.items.map { it.id }).containsExactly("b", "a").inOrder()
        assertThat(second.items.map { it.id }).containsExactly("c", "d").inOrder()
    }

    @Test
    fun clampsThePageSize() {
        val all = (1..300).map { record(id = "r$it", date = "2024-05-01", odometer = it.toDouble()) }

        assertThat(listRecordPage(all, RecordListQuery(limit = 500)).items).hasSize(MAX_PAGE_SIZE)
        assertThat(listRecordPage(all, RecordListQuery(limit = 0)).items).hasSize(1)
        assertThat(listRecordPage(all, RecordListQuery(limit = -5)).items).hasSize(1)
        assertThat(listRecordPage(all, RecordListQuery()).items).hasSize(DEFAULT_PAGE_SIZE)
    }

    // -----------------------------------------------------------------------
    // summarizeVehicle
    // -----------------------------------------------------------------------

    @Test
    fun summarizesOnlyTheActiveRecordsOfOneVehicle() {
        val all = listOf(
            record(id = "a", vehicleId = "v1", odometer = 1000.0, amount = 300.0),
            record(id = "b", vehicleId = "v1", odometer = 1500.0, amount = 250.5),
            record(id = "c", vehicleId = "v1", odometer = 9999.0, amount = 100.0, deletedAt = "2024-06-01T00:00:00.000Z"),
            record(id = "d", vehicleId = "v2", odometer = 8888.0, amount = 77.0),
        )

        val summary = summarizeVehicle(all, "v1")

        assertThat(summary.vehicleId).isEqualTo("v1")
        assertThat(summary.recordCount).isEqualTo(2)
        assertThat(summary.totalPaid).isEqualTo(550.5)
        assertThat(summary.currentOdometer).isEqualTo(1500.0)
    }

    @Test
    fun summarizesAnEmptyVehicleAsNullOdometer() {
        val summary = summarizeVehicle(emptyList(), "v1")

        assertThat(summary.recordCount).isEqualTo(0)
        assertThat(summary.totalPaid).isEqualTo(0.0)
        assertThat(summary.currentOdometer).isNull()
    }
}
