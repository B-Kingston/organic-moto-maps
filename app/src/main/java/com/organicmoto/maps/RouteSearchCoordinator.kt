package com.organicmoto.maps

import com.graphhopper.util.shapes.GHPoint
import com.organicmoto.maps.routing.RouteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

sealed interface RouteUiState {
    data object Idle : RouteUiState
    data object Loading : RouteUiState
    data class Success(val result: RouteResult, val selectedIndex: Int = 0) : RouteUiState
    data class Error(val message: String) : RouteUiState
}

data class RouteParams(
    val from: GHPoint,
    val to: GHPoint,
    val complexity: Double,
    val maxRoadShare: Double,
    val blockUnpaved: Boolean,
    val preferredGeometry: String? = null,
    val viaPoints: List<GHPoint> = emptyList(),
)

sealed interface RouteOutcome {
    data class Success(val result: RouteResult, val matchedIndex: Int) : RouteOutcome
    data class Failure(val message: String) : RouteOutcome
}

/** Owns route-search generations, cancellation, serialization, and UI state. */
class RouteSearchCoordinator(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val gate: Semaphore = Semaphore(1),
    private val backend: suspend (RouteParams) -> RouteOutcome,
) {
    private val _state = MutableStateFlow<RouteUiState>(RouteUiState.Idle)
    val state: StateFlow<RouteUiState> = _state.asStateFlow()
    var generation: Int = 0
        private set
    private var job: Job? = null

    fun submit(params: RouteParams) {
        generation++
        val gen = generation
        job?.cancel()
        job = scope.launch {
            _state.value = RouteUiState.Loading
            val outcome: RouteOutcome? = withContext(ioDispatcher) {
                gate.withPermit {
                    if (gen != generation || !isActive) return@withPermit null
                    try {
                        val result = backend(params)
                        if (gen != generation) null else result
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (gen != generation) null else RouteOutcome.Failure(e.message ?: "Routing failed")
                    }
                }
            }
            if (outcome != null) {
                _state.value = when (outcome) {
                    is RouteOutcome.Success -> RouteUiState.Success(outcome.result, outcome.matchedIndex)
                    is RouteOutcome.Failure -> RouteUiState.Error(outcome.message)
                }
            }
        }
    }

    fun selectRoute(index: Int) {
        val current = _state.value
        if (current is RouteUiState.Success && index in current.result.routes.indices &&
            current.selectedIndex != index
        ) {
            _state.value = current.copy(selectedIndex = index)
        }
    }

    fun invalidate() {
        generation++
        job?.cancel()
        // Loading belongs to the cancelled job. A displayed success remains
        // visible while the user edits an endpoint.
        if (_state.value is RouteUiState.Loading) _state.value = RouteUiState.Idle
    }

    /** Drops the displayed plan entirely: back to a blank Idle state. */
    fun reset() {
        generation++
        job?.cancel()
        _state.value = RouteUiState.Idle
    }

    fun publishError(gen: Int, message: String) {
        // Resolution errors do not bump generation. A matching in-flight
        // search can still complete, as in the original route screen.
        if (gen == generation) _state.value = RouteUiState.Error(message)
    }
}
