package com.youhao.fueltrack.ui.vehicles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youhao.fueltrack.data.FuelTrackStore
import com.youhao.fueltrack.domain.error.AppErrorCode
import com.youhao.fueltrack.domain.error.userErrorMessage
import com.youhao.fueltrack.domain.model.Vehicle
import com.youhao.fueltrack.domain.model.VehicleSummary
import com.youhao.fueltrack.domain.time.Timestamps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Snapshot of the 车辆 screen.
 *
 * The per-vehicle [VehicleSummary] values live in a map rather than on the card state so that a
 * single refresh produces one coherent emission; the UI never renders a list whose summaries are
 * half-updated.
 */
data class VehiclesUiState(
    val loading: Boolean = true,
    val vehicles: List<Vehicle> = emptyList(),
    val summaries: Map<String, VehicleSummary> = emptyMap(),
    val error: String? = null,
)

/**
 * Drives the 车辆 screen.
 *
 * The Room DAOs behind [FuelTrackStore] are one-shot `suspend` functions, not `Flow` queries, so the
 * state is rebuilt by hand after every write: reload the vehicle list, then reload each summary, then
 * publish one new [VehiclesUiState]. That is also why every mutation ends in [refresh] instead of
 * patching the in-memory list — the store may normalise or tombstone rows on the way through.
 */
class VehiclesViewModel(private val store: FuelTrackStore) : ViewModel() {

    private val _state = MutableStateFlow(VehiclesUiState())

    val state: StateFlow<VehiclesUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** Reloads vehicles and their summaries. Safe to call at any time; the last write wins. */
    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val vehicles = store.vehicles()
                val summaries = mutableMapOf<String, VehicleSummary>()
                for (vehicle in vehicles) {
                    summaries[vehicle.id] = store.getVehicleSummary(vehicle.id)
                }
                _state.value = VehiclesUiState(loading = false, vehicles = vehicles, summaries = summaries)
            } catch (error: Throwable) {
                _state.value = _state.value.copy(loading = false, error = vehicleErrorMessage(error))
            }
        }
    }

    /**
     * Creates ([existing] == null) or updates a vehicle from the editor form.
     *
     * [initialOdometer] arrives already parsed: the form rejects blank and non-numeric input before
     * it gets here, so a `null` odometer never reaches the store.
     */
    fun saveVehicle(
        existing: Vehicle?,
        name: String,
        plate: String,
        fuelType: String,
        initialOdometer: Double,
    ) {
        viewModelScope.launch {
            val now = Timestamps.nowIso()
            val vehicle = existing?.copy(
                name = name,
                plate = plate,
                fuelType = fuelType,
                initialOdometer = initialOdometer,
                // id/createdAt/deletedAt are preserved by copy; only the edit timestamp moves, so the
                // WebDAV merge still sees this row as the newer one.
                updatedAt = now,
            ) ?: Vehicle(
                id = UUID.randomUUID().toString(),
                name = name,
                plate = plate,
                fuelType = fuelType,
                initialOdometer = initialOdometer,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            )
            try {
                store.saveVehicle(vehicle)
                refresh()
            } catch (error: Throwable) {
                _state.value = _state.value.copy(error = vehicleErrorMessage(error))
            }
        }
    }

    /**
     * Deletes [vehicleId], hiding its records as well.
     *
     * The guard is enforced here rather than only in the dialog: the screen would otherwise be able
     * to delete the last vehicle and leave every other screen with nothing to show.
     */
    fun deleteVehicle(vehicleId: String) {
        viewModelScope.launch {
            if (_state.value.vehicles.size <= 1) {
                _state.value = _state.value.copy(error = LAST_VEHICLE_MESSAGE)
                return@launch
            }
            try {
                store.deleteVehicle(vehicleId, Timestamps.nowIso())
                refresh()
            } catch (error: Throwable) {
                _state.value = _state.value.copy(error = vehicleErrorMessage(error))
            }
        }
    }

    private companion object {
        const val LAST_VEHICLE_MESSAGE = "至少需要保留一辆车"
    }
}

/**
 * `userErrorMessage` takes an [AppErrorCode] fallback, not free text, so the screen-specific context
 * is prefixed here instead of being smuggled in as a fallback code that would print the wrong
 * recovery hint.
 */
private fun vehicleErrorMessage(error: Throwable): String =
    "车辆操作失败：${userErrorMessage(error, AppErrorCode.UNKNOWN)}"
