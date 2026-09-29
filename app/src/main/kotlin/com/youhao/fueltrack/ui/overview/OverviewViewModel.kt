package com.youhao.fueltrack.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youhao.fueltrack.data.FuelTrackStore
import com.youhao.fueltrack.data.prefs.SettingsRepository
import com.youhao.fueltrack.domain.calc.ConsumptionInterval
import com.youhao.fueltrack.domain.calc.calculateAverageConsumption
import com.youhao.fueltrack.domain.calc.calculateConsumptionIntervals
import com.youhao.fueltrack.domain.error.LastError
import com.youhao.fueltrack.domain.model.FuelRecord
import com.youhao.fueltrack.domain.model.Vehicle
import com.youhao.fueltrack.domain.model.VehicleSummary
import com.youhao.fueltrack.domain.time.LocalDateKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OverviewUiState(
    val loading: Boolean = true,
    val vehicles: List<Vehicle> = emptyList(),
    val selectedVehicleId: String? = null,
    val selectedVehicleName: String? = null,
    val selectedVehicleInitialOdometer: Double? = null,
    val summary: VehicleSummary? = null,
    val averageConsumption: Double = 0.0,
    /** Newest first, capped at [RECENT_LIMIT]. */
    val intervals: List<ConsumptionInterval> = emptyList(),
    /** Newest first, capped at [RECENT_LIMIT]. */
    val recentRecords: List<FuelRecord> = emptyList(),
    /** 当月油费与次数，对应原 Vue 概览卡的「本月油费 / 本月 N 次」。 */
    val monthCost: Double = 0.0,
    val monthRecordCount: Int = 0,
    /** 所有满箱区间的里程之和，对应「基于 N 个满箱区间 · X km」。 */
    val measuredDistance: Double = 0.0,
    val intervalCount: Int = 0,
    val lastError: LastError? = null,
    val conflictCount: Int = 0,
)

/**
 * Dashboard for the currently selected vehicle.
 *
 * State is rebuilt on demand rather than observed from Room: every DAO call is a one-shot `suspend`
 * function and the whole dataset is a few hundred rows, which matches how the ported query layer
 * already pages records in memory.
 */
class OverviewViewModel(
    private val store: FuelTrackStore,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(OverviewUiState())
    val state: StateFlow<OverviewUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun selectVehicle(vehicleId: String) {
        if (_state.value.selectedVehicleId == vehicleId) return
        _state.update { it.copy(selectedVehicleId = vehicleId) }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val vehicles = store.vehicles()
            val previous = _state.value.selectedVehicleId
            val selected = vehicles.firstOrNull { it.id == previous } ?: vehicles.firstOrNull()
            val records = selected
                ?.let { vehicle -> store.records().filter { it.vehicleId == vehicle.id } }
                .orEmpty()
            val intervals = calculateConsumptionIntervals(records)
            val monthKey = LocalDateKeys.localMonthKey()
            val monthRecords = records.filter { it.date.startsWith(monthKey) }
            _state.value = OverviewUiState(
                loading = false,
                vehicles = vehicles,
                selectedVehicleId = selected?.id,
                selectedVehicleName = selected?.name,
                selectedVehicleInitialOdometer = selected?.initialOdometer,
                summary = selected?.let { store.getVehicleSummary(it.id) },
                averageConsumption = calculateAverageConsumption(intervals),
                intervals = intervals.takeLast(RECENT_LIMIT).reversed(),
                recentRecords = records
                    .sortedWith(compareByDescending<FuelRecord> { it.date }.thenByDescending { it.odometer })
                    .take(RECENT_LIMIT),
                monthCost = monthRecords.sumOf { it.amount },
                monthRecordCount = monthRecords.size,
                measuredDistance = intervals.sumOf { it.distance },
                intervalCount = intervals.size,
                lastError = settings.lastError(),
                conflictCount = store.conflictCount(),
            )
        }
    }

    /** Clears the persisted error banner. The stored code is all that was kept, so there is nothing to re-read. */
    fun clearLastError() {
        viewModelScope.launch {
            settings.clearLastError()
            _state.update { it.copy(lastError = null) }
        }
    }

    companion object {
        const val RECENT_LIMIT = 5
    }
}
