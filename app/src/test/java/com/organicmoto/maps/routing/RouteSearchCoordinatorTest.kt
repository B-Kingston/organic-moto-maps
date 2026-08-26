package com.organicmoto.maps.routing

import com.graphhopper.ResponsePath
import com.graphhopper.util.PointList
import com.graphhopper.util.shapes.GHPoint
import com.organicmoto.maps.RouteOutcome
import com.organicmoto.maps.RouteParams
import com.organicmoto.maps.RouteSearchCoordinator
import com.organicmoto.maps.RouteUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSearchCoordinatorTest {

    @Test
    fun latestGenerationWinsWhenEarlyCompletes() = runTest {
        val early = CompletableDeferred<Unit>()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        try {
            val coordinator = RouteSearchCoordinator(
                scope = scope,
                ioDispatcher = StandardTestDispatcher(testScheduler),
                backend = { params ->
                    if (params.complexity == 1.0) {
                        early.await()
                        RouteOutcome.Success(result(1), 0)
                    } else {
                        RouteOutcome.Success(result(2), 0)
                    }
                },
            )
            coordinator.submit(params(1.0))
            runCurrent()
            coordinator.submit(params(2.0))
            advanceUntilIdle()
            assertSuccessDistance(coordinator, 2.0)
            early.complete(Unit)
            advanceUntilIdle()
            assertSuccessDistance(coordinator, 2.0)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun staleCompletionIsDiscardedWithoutCancellation() = runTest {
        val early = CompletableDeferred<Unit>()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        var active = 0
        var maxActive = 0
        try {
            val coordinator = RouteSearchCoordinator(
                scope = scope,
                ioDispatcher = StandardTestDispatcher(testScheduler),
                gate = Semaphore(1),
                backend = { params ->
                    active++
                    maxActive = maxOf(maxActive, active)
                    try {
                        if (params.complexity == 1.0) {
                            withContext(NonCancellable) { early.await() }
                        }
                        RouteOutcome.Success(result(params.complexity.toInt()), 0)
                    } finally {
                        active--
                    }
                },
            )
            coordinator.submit(params(1.0))
            runCurrent()
            coordinator.submit(params(2.0))
            runCurrent()
            assertTrue("the first backend call must be non-cancellable", active == 1)
            early.complete(Unit)
            advanceUntilIdle()
            assertSuccessDistance(coordinator, 2.0)
            assertEquals(1, maxActive)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun staleFailureIsDiscarded() = runTest {
        val early = CompletableDeferred<Unit>()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        try {
            val coordinator = RouteSearchCoordinator(
                scope = scope,
                ioDispatcher = StandardTestDispatcher(testScheduler),
                gate = Semaphore(1),
                backend = { params ->
                    if (params.complexity == 1.0) {
                        withContext(NonCancellable) { early.await() }
                        RouteOutcome.Failure("old failure")
                    } else {
                        RouteOutcome.Success(result(2), 0)
                    }
                },
            )
            coordinator.submit(params(1.0))
            runCurrent()
            coordinator.submit(params(2.0))
            early.complete(Unit)
            advanceUntilIdle()
            assertSuccessDistance(coordinator, 2.0)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun invalidateResetsLoadingToIdle() = runTest {
        val latch = CompletableDeferred<Unit>()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        try {
            val coordinator = RouteSearchCoordinator(
                scope = scope,
                ioDispatcher = StandardTestDispatcher(testScheduler),
                backend = {
                    latch.await()
                    RouteOutcome.Success(result(1), 0)
                },
            )
            coordinator.submit(params(1.0))
            runCurrent()
            assertEquals(RouteUiState.Loading, coordinator.state.value)
            coordinator.invalidate()
            assertEquals(RouteUiState.Idle, coordinator.state.value)
            latch.complete(Unit)
            advanceUntilIdle()
            assertEquals(RouteUiState.Idle, coordinator.state.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun publishErrorIgnoresStaleGeneration() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        try {
            val coordinator = RouteSearchCoordinator(scope, ioDispatcher = StandardTestDispatcher(testScheduler)) {
                RouteOutcome.Success(result(1), 0)
            }
            val staleGeneration = coordinator.generation
            coordinator.invalidate()
            coordinator.publishError(staleGeneration, "stale")
            assertEquals(RouteUiState.Idle, coordinator.state.value)
            coordinator.publishError(coordinator.generation, "current")
            assertEquals(RouteUiState.Error("current"), coordinator.state.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun gateSerializesRapidSubmissionsAndOnlyLatestRuns() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val calls = mutableListOf<RouteParams>()
        var active = 0
        var maxActive = 0
        try {
            val coordinator = RouteSearchCoordinator(
                scope = scope,
                ioDispatcher = StandardTestDispatcher(testScheduler),
                gate = Semaphore(1),
                backend = { params ->
                    calls += params
                    active++
                    maxActive = maxOf(maxActive, active)
                    active--
                    RouteOutcome.Success(result(params.complexity.toInt()), 0)
                },
            )
            repeat(5) { coordinator.submit(params((it + 1).toDouble())) }
            advanceUntilIdle()
            assertEquals(1, calls.size)
            assertEquals(5.0, calls.single().complexity, 0.0)
            assertEquals(1, maxActive)
            assertSuccessDistance(coordinator, 5.0)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun invalidateKeepsDisplayedSuccess() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        try {
            val coordinator = RouteSearchCoordinator(scope, ioDispatcher = StandardTestDispatcher(testScheduler)) {
                RouteOutcome.Success(result(7), 0)
            }
            coordinator.submit(params(7.0))
            advanceUntilIdle()
            val before = coordinator.generation
            assertSuccessDistance(coordinator, 7.0)
            coordinator.invalidate()
            assertEquals(before + 1, coordinator.generation)
            assertSuccessDistance(coordinator, 7.0)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun preferredGeometryIsForwardedToBackend() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        var received: String? = null
        try {
            val coordinator = RouteSearchCoordinator(scope, ioDispatcher = StandardTestDispatcher(testScheduler)) { params ->
                received = params.preferredGeometry
                RouteOutcome.Success(result(1), 0)
            }
            coordinator.submit(params(1.0, preferredGeometry = "encoded-shape"))
            advanceUntilIdle()
            assertEquals("encoded-shape", received)
        } finally {
            scope.cancel()
        }
    }

    private fun params(complexity: Double, preferredGeometry: String? = null) = RouteParams(
        from = GHPoint(-27.0, 153.0),
        to = GHPoint(-28.0, 152.0),
        complexity = complexity,
        maxRoadShare = 0.70,
        blockUnpaved = false,
        preferredGeometry = preferredGeometry,
    )

    private fun result(marker: Int): RouteResult = RouteResult(
        listOf(
            ResponsePath()
                .setPoints(PointList().apply { add(0.0, 0.0); add(0.01, 0.01) })
                .setDistance(marker.toDouble())
                .setTime(marker.toLong()),
        ),
    )

    private fun assertSuccessDistance(coordinator: RouteSearchCoordinator, expected: Double) {
        val success = coordinator.state.value as? RouteUiState.Success
        assertTrue(success != null)
        assertEquals(expected, success!!.result.routes.single().distance, 0.0)
    }
}
