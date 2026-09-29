package com.youhao.fueltrack.domain.backup

import com.google.common.truth.Truth.assertThat
import com.youhao.fueltrack.domain.model.FuelRecord
import com.youhao.fueltrack.domain.model.SyncPayloadV1
import com.youhao.fueltrack.domain.model.Vehicle
import com.youhao.fueltrack.domain.sync.SyncJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertThrows
import org.junit.Test

private const val NOW = "2026-06-01T12:00:00.000Z"

private val CSV_HEADER =
    "车辆,日期,里程(km),加油量(L),表显金额(元),实付金额(元),优惠金额(元),表显单价(元/L),优惠后单价(元/L),满箱,加油站,备注"

private fun vehicle(id: String = "v1", name: String = "我的车") = Vehicle(
    id = id,
    name = name,
    plate = "沪A12345",
    fuelType = "92#",
    initialOdometer = 1000.0,
    createdAt = NOW,
    updatedAt = NOW,
    deletedAt = null,
)

private fun record(
    id: String = "r1",
    vehicleId: String = "v1",
    odometer: Double = 1500.0,
    liters: Double = 10.0,
    amount: Double = 70.0,
    pumpAmount: Double = 75.0,
    pricePerLiter: Double = 7.0,
    isFull: Boolean = true,
    station: String = "中石化",
    note: String = "备注",
    deletedAt: String? = null,
) = FuelRecord(
    id = id,
    vehicleId = vehicleId,
    date = "2026-06-01",
    odometer = odometer,
    liters = liters,
    amount = amount,
    pumpAmount = pumpAmount,
    pricePerLiter = pricePerLiter,
    isFull = isFull,
    station = station,
    note = note,
    createdAt = NOW,
    updatedAt = NOW,
    deletedAt = deletedAt,
)

class BackupTest {

    // ------------------------------------------------------------------
    // parseBackup
    // ------------------------------------------------------------------

    @Test
    fun `rejects a file past the size ceiling`() {
        val text = "a".repeat(MAX_BACKUP_CHARS + 1)

        val error = assertThrows(IllegalArgumentException::class.java) { parseBackup(text) }

        assertThat(error).hasMessageThat().isEqualTo("备份文件超过 20 MB，拒绝导入")
    }

    @Test
    fun `does not reject a file exactly at the size ceiling`() {
        // The guard is `>`, not `>=`, so a max-length file is handed to the reader and fails there
        // instead. Asserting on the absence of the size message keeps the boundary honest without
        // pinning down whatever the JSON reader happens to say about a wall of 'a'.
        val text = "a".repeat(MAX_BACKUP_CHARS)

        val error = assertThrows(IllegalArgumentException::class.java) { parseBackup(text) }

        assertThat(error).hasMessageThat().doesNotContain("20 MB")
    }

    @Test
    fun `rejects text that is not json`() {
        val error = assertThrows(IllegalArgumentException::class.java) { parseBackup("not json") }

        assertThat(error).hasMessageThat().isEqualTo("备份文件不是有效的 JSON")
    }

    @Test
    fun `propagates the validation message from the payload validator`() {
        val error = assertThrows(IllegalArgumentException::class.java) { parseBackup("""{"hello":1}""") }

        assertThat(error).isInstanceOf(com.youhao.fueltrack.domain.sync.SyncValidationException::class.java)
        assertThat(error).hasMessageThat().startsWith("备份文件校验失败")
    }

    @Test
    fun `round trips a payload written by the syncer`() {
        val payload = SyncPayloadV1(
            exportedAt = NOW,
            vehicles = listOf(vehicle()),
            records = listOf(record()),
        )

        val parsed = parseBackup(SyncJson.encodeToString(payload))

        assertThat(parsed).isEqualTo(payload)
    }

    @Test
    fun `keeps tombstones so a restore cannot resurrect deleted records`() {
        val deleted = record(id = "r2", deletedAt = NOW)
        val payload = SyncPayloadV1(
            exportedAt = NOW,
            vehicles = listOf(vehicle()),
            records = listOf(deleted),
        )

        val parsed = parseBackup(SyncJson.encodeToString(payload))

        assertThat(parsed.records).hasSize(1)
        assertThat(parsed.records.single().deletedAt).isEqualTo(NOW)
    }

    // ------------------------------------------------------------------
    // recordsToCsv
    // ------------------------------------------------------------------

    @Test
    fun `starts with a byte order mark and uses crlf line endings`() {
        val csv = recordsToCsv(listOf(record()), listOf(vehicle()))

        assertThat(csv).startsWith("\uFEFF")
        assertThat(csv).contains("\r\n")
        // A bare \n would corrupt a cell containing a newline; every newline here must be part of a
        // CRLF pair.
        assertThat(csv.replace("\r\n", "")).doesNotContain("\n")
    }

    @Test
    fun `writes the header and one row per live record`() {
        val csv = recordsToCsv(
            listOf(record(id = "r1"), record(id = "r2", odometer = 1600.0)),
            listOf(vehicle()),
        )

        val lines = csv.removePrefix("\uFEFF").split("\r\n")
        assertThat(lines).hasSize(3)
        assertThat(lines[0]).isEqualTo(CSV_HEADER)
        assertThat(lines[1]).startsWith("我的车,2026-06-01,1500,")
        assertThat(lines[2]).startsWith("我的车,2026-06-01,1600,")
    }

    @Test
    fun `omits deleted records entirely`() {
        val csv = recordsToCsv(
            listOf(record(id = "r1"), record(id = "r2", deletedAt = NOW)),
            listOf(vehicle()),
        )

        assertThat(csv.removePrefix("\uFEFF").split("\r\n")).hasSize(2)
    }

    @Test
    fun `renders whole numbers the way javascript stringifies them`() {
        // `String(1500)` is "1500" in JavaScript, not "1500.0" — the CSV has to stay identical to
        // what the web build exported for the same records.
        val csv = recordsToCsv(listOf(record(odometer = 1500.0, liters = 10.0)), listOf(vehicle()))

        assertThat(csv.removePrefix("\uFEFF").split("\r\n")[1])
            .isEqualTo("我的车,2026-06-01,1500,10,75,70,5.00,7.50,7.00,是,中石化,备注")
    }

    @Test
    fun `keeps the fractional part of a non-whole number`() {
        val csv = recordsToCsv(
            listOf(record(odometer = 1500.5, liters = 10.25, pumpAmount = 75.5, amount = 70.25)),
            listOf(vehicle()),
        )

        val cells = csv.removePrefix("\uFEFF").split("\r\n")[1].split(",")
        assertThat(cells[2]).isEqualTo("1500.5")
        assertThat(cells[3]).isEqualTo("10.25")
        assertThat(cells[4]).isEqualTo("75.5")
        assertThat(cells[5]).isEqualTo("70.25")
    }

    @Test
    fun `derives the discount and the pump unit price`() {
        val csv = recordsToCsv(
            listOf(record(liters = 10.0, amount = 70.0, pumpAmount = 75.0, pricePerLiter = 7.0)),
            listOf(vehicle()),
        )

        val cells = csv.removePrefix("\uFEFF").split("\r\n")[1].split(",")
        assertThat(cells[4]).isEqualTo("75")     // 表显金额, verbatim
        assertThat(cells[5]).isEqualTo("70")     // 实付金额, verbatim
        assertThat(cells[6]).isEqualTo("5.00")   // 优惠金额 = 75 - 70
        assertThat(cells[7]).isEqualTo("7.50")   // 表显单价 = 75 / 10
        assertThat(cells[8]).isEqualTo("7.00")   // 优惠后单价 = the stored pricePerLiter
    }

    @Test
    fun `clamps a negative discount to zero`() {
        // A pump reading below what was actually paid is nonsense, but the export must not print a
        // negative discount for it.
        val csv = recordsToCsv(
            listOf(record(liters = 10.0, amount = 70.0, pumpAmount = 60.0)),
            listOf(vehicle()),
        )

        val cells = csv.removePrefix("\uFEFF").split("\r\n")[1].split(",")
        assertThat(cells[6]).isEqualTo("0.00")
    }

    @Test
    fun `renders the full tank flag in chinese`() {
        val csv = recordsToCsv(
            listOf(record(id = "r1", isFull = true), record(id = "r2", isFull = false)),
            listOf(vehicle()),
        )

        val rows = csv.removePrefix("\uFEFF").split("\r\n")
        assertThat(rows[1].split(",")[9]).isEqualTo("是")
        assertThat(rows[2].split(",")[9]).isEqualTo("否")
    }

    @Test
    fun `quotes cells containing a separator and doubles embedded quotes`() {
        val csv = recordsToCsv(
            listOf(record(station = "中石化, 沪", note = """他说"加满"""")),
            listOf(vehicle()),
        )

        assertThat(csv).contains(""""中石化, 沪"""")
        assertThat(csv).contains(""""他说""加满"""""")
    }

    @Test
    fun `quotes cells containing a line break so the row stays one row`() {
        val csv = recordsToCsv(listOf(record(note = "第一行\n第二行")), listOf(vehicle()))

        // The quoted cell holds a lone \n; the row separator stays CRLF, so a naive split on CRLF
        // still yields exactly header + one record.
        assertThat(csv.removePrefix("\uFEFF").split("\r\n")).hasSize(2)
        assertThat(csv).contains("\"第一行\n第二行\"")
    }

    @Test
    fun `quotes the vehicle name when it contains a comma`() {
        val csv = recordsToCsv(listOf(record()), listOf(vehicle(name = "我的车, 二号")))

        assertThat(csv).contains(""""我的车, 二号",2026-06-01""")
    }

    @Test
    fun `falls back to the vehicle id when the vehicle is unknown`() {
        val csv = recordsToCsv(listOf(record(vehicleId = "ghost")), emptyList())

        assertThat(csv.removePrefix("\uFEFF").split("\r\n")[1]).startsWith("ghost,")
    }

    @Test
    fun `exports an empty history as just the header`() {
        val csv = recordsToCsv(emptyList(), listOf(vehicle()))

        // The trailing CRLF is part of the legacy template (`${header}\r\n${lines.join()}`), so an
        // empty history still ends with one.
        assertThat(csv).isEqualTo("\uFEFF$CSV_HEADER\r\n")
    }
}
