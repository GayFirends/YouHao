package com.youhao.fueltrack.ui.records

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youhao.fueltrack.data.FuelTrackStore
import com.youhao.fueltrack.domain.calc.AmountField
import com.youhao.fueltrack.domain.calc.RecordDraft
import com.youhao.fueltrack.domain.calc.RefuelEntry
import com.youhao.fueltrack.domain.calc.createRefuelEntry
import com.youhao.fueltrack.domain.calc.createRefuelEntryFor
import com.youhao.fueltrack.domain.calc.effectivePumpAmount
import com.youhao.fueltrack.domain.calc.fuelRecordWarnings
import com.youhao.fueltrack.domain.calc.updateRefuelAmount
import com.youhao.fueltrack.domain.error.AppErrorCode
import com.youhao.fueltrack.domain.error.userErrorMessage
import com.youhao.fueltrack.domain.model.FuelRecord
import com.youhao.fueltrack.domain.time.LocalDateKeys
import com.youhao.fueltrack.domain.time.Timestamps
import com.youhao.fueltrack.ui.toDoubleOrNullField
import com.youhao.fueltrack.ui.toFieldText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class RecordEditorUiState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val date: String = "",
    val odometerText: String = "",
    /** The four coupled amount inputs, kept as raw text so partial typing is never reformatted. */
    val litersText: String = "",
    val amountText: String = "",
    val pumpAmountText: String = "",
    val pumpPriceText: String = "",
    val entry: RefuelEntry = RefuelEntry(),
    val isFull: Boolean = true,
    val station: String = "",
    val note: String = "",
    val warnings: List<String> = emptyList(),
    val error: String? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val deleted: Boolean = false,
) {
    /** The pump amount that will be stored: the shown amount, or the charged amount when blank. */
    val pumpAmountOrNull: Double? get() = effectivePumpAmount(entry)
}

/**
 * Add / edit form for a single fill-up.
 *
 * The amount fields are coupled exactly like the legacy form (`updateRefuelAmount`): typing litres
 * or the shown amount re-derives the pump price, and a manual pump override wins until it is reset.
 * Because Compose text fields are string-backed, only the field the user is actually typing into is
 * left alone — the other three are rewritten from the resulting [RefuelEntry].
 */
class RecordEditorViewModel(
    private val store: FuelTrackStore,
    private val recordId: String?,
) : ViewModel() {

    private val _state = MutableStateFlow(RecordEditorUiState())
    val state: StateFlow<RecordEditorUiState> = _state.asStateFlow()

    private var existing: FuelRecord? = null
    private var vehicleId: String? = null
    private var history: List<FuelRecord> = emptyList()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val vehicles = store.vehicles()
                existing = recordId?.let { store.getRecord(it) }
                val record = existing
                val vehicle = record?.let { saved -> vehicles.firstOrNull { it.id == saved.vehicleId } }
                    ?: vehicles.firstOrNull()
                vehicleId = record?.vehicleId ?: vehicle?.id
                history = store.records().filter { it.vehicleId == vehicleId }

                val entry = record?.let { createRefuelEntryFor(it) } ?: createRefuelEntry()
                val odometer = record?.odometer
                    ?: history.filter { it.deletedAt == null }.maxOfOrNull { it.odometer }
                    ?: vehicle?.initialOdometer
                    ?: 0.0

                _state.value = RecordEditorUiState(
                    loading = false,
                    isNew = record == null,
                    date = record?.date ?: LocalDateKeys.localDateKey(),
                    odometerText = odometer.toFieldText(),
                    litersText = entry.liters.toFieldText(),
                    amountText = entry.amount.toFieldText(),
                    pumpAmountText = entry.pumpAmount.toFieldText(),
                    pumpPriceText = entry.pumpPrice.toFieldText(),
                    entry = entry,
                    isFull = record?.isFull ?: true,
                    station = record?.station.orEmpty(),
                    note = record?.note.orEmpty(),
                ).recompute()
            } catch (error: Throwable) {
                _state.update {
                    it.copy(loading = false, error = userErrorMessage(error))
                }
            }
        }
    }

    fun onDateChange(value: String) = update { it.copy(date = value) }

    fun onOdometerChange(value: String) = update { it.copy(odometerText = value) }

    fun onStationChange(value: String) = update { it.copy(station = value) }

    fun onNoteChange(value: String) = update { it.copy(note = value) }

    fun onFullChange(value: Boolean) = update { it.copy(isFull = value) }

    fun onLitersChange(value: String) = editAmount(AmountField.LITERS, value) { it.copy(litersText = value) }

    fun onAmountChange(value: String) = editAmount(AmountField.AMOUNT, value) { it.copy(amountText = value) }

    fun onPumpAmountChange(value: String) =
        editAmount(AmountField.PUMP_AMOUNT, value) { it.copy(pumpAmountText = value) }

    fun onPumpPriceChange(value: String) =
        editAmount(AmountField.PUMP_PRICE, value) { it.copy(pumpPriceText = value) }

    fun clearError() = update { it.copy(error = null) }

    fun save() {
        val current = _state.value
        val odometer = current.odometerText.toDoubleOrNullField()
        val liters = current.entry.liters
        val amount = current.entry.amount
        val target = vehicleId

        val problem = when {
            target == null -> "还没有车辆，无法保存记录"
            odometer == null -> "请填写里程"
            liters == null || liters <= 0.0 -> "请填写加油量"
            amount == null || amount < 0.0 -> "请填写实付金额"
            else -> null
        }
        if (problem != null) {
            _state.update { it.copy(error = problem) }
            return
        }

        val now = Timestamps.nowIso()
        val previous = existing
        val pumpAmount = current.pumpAmountOrNull ?: amount!!
        val saved = FuelRecord(
            id = previous?.id ?: UUID.randomUUID().toString(),
            vehicleId = target!!,
            date = current.date,
            odometer = odometer!!,
            liters = liters!!,
            amount = amount!!,
            pumpAmount = pumpAmount,
            // `pricePerLiter` is the discounted unit price — that is what the CSV export and the
            // legacy records mean by it (see `recordsToCsv` in backup.ts).
            pricePerLiter = if (liters > 0.0) amount / liters else 0.0,
            isFull = current.isFull,
            station = current.station.trim(),
            note = current.note.trim(),
            createdAt = previous?.createdAt ?: now,
            updatedAt = now,
            deletedAt = null,
        )

        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            try {
                store.saveRecord(saved)
                _state.update { it.copy(saving = false, saved = true) }
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        saving = false,
                        error = "记录保存失败。" + userErrorMessage(error, AppErrorCode.DATABASE_WRITE_FAILED),
                    )
                }
            }
        }
    }

    /** Soft delete, matching `deleteVehicle`: the row is kept as a tombstone for the sync merge. */
    fun delete() {
        val previous = existing ?: return
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            try {
                val now = Timestamps.nowIso()
                store.saveRecord(previous.copy(deletedAt = now, updatedAt = now))
                _state.update { it.copy(saving = false, deleted = true) }
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        saving = false,
                        error = "记录删除失败。" + userErrorMessage(error, AppErrorCode.DATABASE_WRITE_FAILED),
                    )
                }
            }
        }
    }

    private fun update(transform: (RecordEditorUiState) -> RecordEditorUiState) {
        _state.update { transform(it).recompute() }
    }

    private fun editAmount(
        field: AmountField,
        text: String,
        assignText: (RecordEditorUiState) -> RecordEditorUiState,
    ) {
        _state.update { current ->
            val entry = updateRefuelAmount(current.entry, field, text.toDoubleOrNullField())
            assignText(current).copy(entry = entry).withDerivedAmounts(entry, field).recompute()
        }
    }

    /** Rewrites every amount field except the one being typed into. */
    private fun RecordEditorUiState.withDerivedAmounts(
        entry: RefuelEntry,
        edited: AmountField,
    ): RecordEditorUiState = copy(
        litersText = if (edited == AmountField.LITERS) litersText else entry.liters.toFieldText(),
        amountText = if (edited == AmountField.AMOUNT) amountText else entry.amount.toFieldText(),
        pumpAmountText = if (edited == AmountField.PUMP_AMOUNT) pumpAmountText else entry.pumpAmount.toFieldText(),
        pumpPriceText = if (edited == AmountField.PUMP_PRICE) pumpPriceText else entry.pumpPrice.toFieldText(),
    )

    private fun RecordEditorUiState.recompute(): RecordEditorUiState {
        val draft = RecordDraft(
            id = existing?.id,
            date = date,
            odometer = odometerText.toDoubleOrNullField() ?: 0.0,
            liters = entry.liters ?: 0.0,
            amount = entry.amount ?: 0.0,
            isFull = isFull,
        )
        return copy(warnings = fuelRecordWarnings(draft, history))
    }
}
