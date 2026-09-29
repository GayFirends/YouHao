package com.youhao.fueltrack.ui.conflicts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youhao.fueltrack.data.ConflictChoice
import com.youhao.fueltrack.data.ConflictDetail
import com.youhao.fueltrack.data.FuelTrackStore
import com.youhao.fueltrack.domain.error.AppErrorCode
import com.youhao.fueltrack.domain.error.userErrorMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The conflict list plus the in-session tally of what the user resolved.
 *
 * Like the rest of the app this reloads into a [MutableStateFlow] instead of observing Room: the
 * conflict DAO is a one-shot `suspend` API, so every mutation is followed by an explicit re-read.
 */
data class ConflictsUiState(
    val items: List<ConflictDetail> = emptyList(),
    val resolvedCount: Int = 0,
    val error: String? = null,
    val loaded: Boolean = false,
)

/**
 * Backs the conflict screen. [init] loads immediately so the first frame after navigation already
 * has the list, and every resolution re-reads the store rather than mutating the list locally,
 * because a decision can also drop a row that another conflict pointed at.
 */
class ConflictsViewModel(private val store: FuelTrackStore) : ViewModel() {

    private val _state = MutableStateFlow(ConflictsUiState())
    val state: StateFlow<ConflictsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                val items = store.conflicts()
                _state.update { it.copy(items = items, error = null, loaded = true) }
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        error = userErrorMessage(error, AppErrorCode.UNKNOWN),
                        loaded = true,
                    )
                }
            }
        }
    }

    /** Applies [choice] to one conflict and reloads; the kept entity is rewritten by the store. */
    fun resolve(id: String, choice: ConflictChoice) {
        viewModelScope.launch {
            try {
                store.resolveConflict(id, choice)
                val items = store.conflicts()
                _state.update {
                    it.copy(
                        items = items,
                        resolvedCount = it.resolvedCount + 1,
                        error = null,
                        loaded = true,
                    )
                }
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        error = userErrorMessage(error, AppErrorCode.UNKNOWN),
                        loaded = true,
                    )
                }
            }
        }
    }
}
