package com.youhao.fueltrack.ui.records

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youhao.fueltrack.data.FuelTrackStore
import com.youhao.fueltrack.domain.calc.FuelPaymentCalculation
import com.youhao.fueltrack.domain.calc.RecordDraft
import com.youhao.fueltrack.domain.calc.fuelRecordWarnings
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
    val vehicleName: String = "",
    val date: String = "",
    val odometerText: String = "",
    val amounts: FuelAmountForm = FuelAmountForm(),
    val isFull: Boolean = true,
    val station: String = "",
    val note: String = "",
    val warnings: List<String> = emptyList(),
    val error: String? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val deleted: Boolean = false,
)

/**
 * Add / edit form for a single fill-up.
 *
 * Amounts are computed directly from the visible form. An incomplete or invalid calculation
 * cannot save a stale result; editing other record details preserves the receipt amounts.
 */
class RecordEditorViewModel(
    private val store: FuelTrackStore,
    private val recordId: String?,
    private val requestedVehicleId: String? = null,
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
                    ?: requestedVehicleId?.let { id -> vehicles.firstOrNull { it.id == id } }
                    ?: vehicles.firstOrNull()
                vehicleId = record?.vehicleId ?: vehicle?.id
                history = store.records().filter { it.vehicleId == vehicleId }

                val odometer = record?.odometer
                    ?: history.filter { it.deletedAt == null }.maxOfOrNull { it.odometer }
                    ?: vehicle?.initialOdometer
                    ?: 0.0

                _state.value = RecordEditorUiState(
                    loading = false,
                    isNew = record == null,
                    vehicleName = vehicle?.name.orEmpty(),
                    date = record?.date ?: LocalDateKeys.localDateKey(),
                    odometerText = odometer.toFieldText(),
                    amounts = record?.let(FuelAmountForm::fromRecord) ?: FuelAmountForm(),
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

    fun onAmountsChange(value: FuelAmountForm) = update { it.copy(amounts = value, error = null) }

    fun clearError() = update { it.copy(error = null) }

    fun save() {
        val current = _state.value
        val odometer = current.odometerText.toDoubleOrNullField()
        val payment = current.amounts.result
        val target = vehicleId

        val problem = when {
            target == null -> "还没有车辆，无法保存记录"
            odometer == null || !odometer.isFinite() || odometer < 0 -> "请填写有效里程"
            payment == null -> (current.amounts.calculation as? FuelPaymentCalculation.Invalid)?.message
                ?: "请补全加油数据与优惠，完成自动计算后再保存"
            else -> null
        }
        if (problem != null) {
            _state.update { it.copy(error = problem) }
            return
        }

        val now = Timestamps.nowIso()
        val previous = existing
        val amounts = payment!!
        val saved = FuelRecord(
            id = previous?.id ?: UUID.randomUUID().toString(),
            vehicleId = target!!,
            date = current.date,
            odometer = odometer!!,
            liters = amounts.liters,
            amount = amounts.amount,
            pumpAmount = amounts.pumpAmount,
            // `pricePerLiter` is the discounted unit price — that is what the CSV export and the
            // legacy records mean by it (see `recordsToCsv` in backup.ts).
            pricePerLiter = amounts.paidPrice,
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

    private fun RecordEditorUiState.recompute(): RecordEditorUiState {
        val payment = amounts.result ?: return copy(warnings = emptyList())
        val draft = RecordDraft(
            id = existing?.id,
            date = date,
            odometer = odometerText.toDoubleOrNullField() ?: 0.0,
            liters = payment.liters,
            amount = payment.amount,
            isFull = isFull,
        )
        return copy(warnings = fuelRecordWarnings(draft, history))
    }
}
