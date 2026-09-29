package com.youhao.fueltrack.ui.records

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youhao.fueltrack.data.FuelTrackStore
import com.youhao.fueltrack.domain.calc.calculateConsumptionIntervals
import com.youhao.fueltrack.domain.error.userErrorMessage
import com.youhao.fueltrack.domain.model.FuelRecord
import com.youhao.fueltrack.domain.model.RecordCursor
import com.youhao.fueltrack.domain.model.RecordListQuery
import com.youhao.fueltrack.domain.model.Vehicle
import com.youhao.fueltrack.domain.time.LocalDateKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RecordsUiState(
    val loading: Boolean = true,
    val vehicles: List<Vehicle> = emptyList(),
    val selectedVehicleId: String? = null,
    val search: String = "",
    val month: String = "",
    val records: List<FuelRecord> = emptyList(),
    /** Per-record consumption, derived from the vehicle's full history rather than the current page. */
    val consumptionByRecordId: Map<String, Double> = emptyMap(),
    val hasMore: Boolean = false,
    val error: String? = null,
)

/**
 * The record list. Paging is delegated to `listRecordPage` in the domain module, which keeps the
 * cursor semantics identical to the legacy app — including the fact that it sorts and filters in
 * memory. The vehicle's whole history is therefore loaded anyway to derive per-record consumption.
 */
class RecordsViewModel(private val store: FuelTrackStore) : ViewModel() {

    private val _state = MutableStateFlow(RecordsUiState())
    val state: StateFlow<RecordsUiState> = _state.asStateFlow()

    private var cursor: RecordCursor? = null

    init {
        reload()
    }

    fun selectVehicle(vehicleId: String) {
        if (_state.value.selectedVehicleId == vehicleId) return
        _state.update { it.copy(selectedVehicleId = vehicleId) }
        reload()
    }

    fun updateSearch(text: String) {
        _state.update { it.copy(search = text) }
        reload()
    }

    fun updateMonth(text: String) {
        _state.update { it.copy(month = text) }
        reload()
    }

    fun useCurrentMonth() = updateMonth(LocalDateKeys.localMonthKey())

    fun clearFilters() {
        _state.update { it.copy(search = "", month = "") }
        reload()
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun reload() {
        viewModelScope.launch {
            try {
                val vehicles = store.vehicles()
                val previous = _state.value.selectedVehicleId
                val selected = vehicles.firstOrNull { it.id == previous } ?: vehicles.firstOrNull()
                _state.update { it.copy(vehicles = vehicles, selectedVehicleId = selected?.id) }

                val page = store.listRecords(query(cursor = null))
                cursor = page.nextCursor

                val history = selected
                    ?.let { vehicle -> store.records().filter { it.vehicleId == vehicle.id } }
                    .orEmpty()
                val consumptionByRecord = calculateConsumptionIntervals(history)
                    .associate { interval -> interval.record.id to interval.consumption }

                _state.update {
                    it.copy(
                        loading = false,
                        records = page.items,
                        consumptionByRecordId = consumptionByRecord,
                        hasMore = page.nextCursor != null,
                        error = null,
                    )
                }
            } catch (error: Throwable) {
                _state.update {
                    it.copy(loading = false, error = userErrorMessage(error))
                }
            }
        }
    }

    /** Appends the next page. A null cursor means the last page was already reached. */
    fun loadMore() {
        val next = cursor ?: return
        viewModelScope.launch {
            try {
                val page = store.listRecords(query(cursor = next))
                cursor = page.nextCursor
                _state.update {
                    it.copy(
                        records = it.records + page.items,
                        hasMore = page.nextCursor != null,
                        error = null,
                    )
                }
            } catch (error: Throwable) {
                _state.update { it.copy(error = userErrorMessage(error)) }
            }
        }
    }

    /** Leaving `limit` null keeps the domain default (50) so the page size has one owner. */
    private fun query(cursor: RecordCursor?) = RecordListQuery(
        vehicleId = _state.value.selectedVehicleId,
        search = _state.value.search.trim().takeIf { it.isNotEmpty() },
        month = _state.value.month.trim().takeIf { it.isNotEmpty() },
        cursor = cursor,
    )
}
