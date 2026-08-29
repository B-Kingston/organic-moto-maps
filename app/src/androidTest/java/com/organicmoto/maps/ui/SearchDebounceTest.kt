package com.organicmoto.maps.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.organicmoto.maps.RoutePlanSearchField
import com.organicmoto.maps.RoutePlanSearchResults
import com.organicmoto.maps.SEARCH_DEBOUNCE_MS
import com.organicmoto.maps.geocoding.GeocodeController
import com.organicmoto.maps.geocoding.GeocodeResult
import com.organicmoto.maps.geocoding.GeocodeResultType
import com.organicmoto.maps.rememberRouteFieldSearchState
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SearchDebounceTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun onlyTheFinalDebouncedQueryRuns() {
        val controller = FakeGeocodeController()
        var value by mutableStateOf("")
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                val searchState = rememberRouteFieldSearchState()
                Column {
                    RoutePlanSearchField(
                        label = "From",
                        hint = "Route from",
                        icon = {},
                        value = value,
                        onValueChange = { value = it },
                        onResultPicked = {},
                        controller = controller,
                        searchState = searchState,
                    )
                    RoutePlanSearchResults(searchState, maxHeight = 400.dp)
                }
            }
        }
        composeRule.onNodeWithContentDescription("From").performTextInput("Mount")
        advanceDebounce()
        composeRule.waitUntil(2_000) { controller.queries == listOf("Mount") }
        controller.gate("Mount").complete(listOf(result("Mount")))
        composeRule.waitForIdle()
        assertEquals(listOf("Mount"), controller.queries)
    }

    @Test
    fun staleSearchResultsNeverReplaceTheNewestQuery() {
        val controller = FakeGeocodeController()
        var value by mutableStateOf("")
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                val searchState = rememberRouteFieldSearchState()
                Column {
                    RoutePlanSearchField(
                        label = "From",
                        hint = "Route from",
                        icon = {},
                        value = value,
                        onValueChange = { value = it },
                        onResultPicked = {},
                        controller = controller,
                        searchState = searchState,
                    )
                    RoutePlanSearchResults(searchState, maxHeight = 400.dp)
                }
            }
        }
        composeRule.onNodeWithContentDescription("From").performTextInput("First")
        advanceDebounce()
        composeRule.waitUntil(2_000) { controller.queries == listOf("First") }
        composeRule.onNodeWithContentDescription("From").performTextInput("Second")
        advanceDebounce()
        composeRule.waitUntil(2_000) { controller.queries == listOf("First", "FirstSecond") }
        controller.gate("FirstSecond").complete(listOf(result("Second result")))
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        waitForResult("Second result")
        controller.gate("First").complete(listOf(result("First result")))
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodes(hasText("Second result")).fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodes(hasText("First result")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun blankInputDoesNotQuery() {
        val controller = FakeGeocodeController()
        var value by mutableStateOf("")
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                val searchState = rememberRouteFieldSearchState()
                RoutePlanSearchField(
                    label = "From",
                    hint = "Route from",
                    icon = {},
                    value = value,
                    onValueChange = { value = it },
                    onResultPicked = {},
                    controller = controller,
                    searchState = searchState,
                )
            }
        }
        composeRule.onNodeWithContentDescription("From").performTextInput(" ")
        advanceDebounce()
        composeRule.waitForIdle()
        assertTrue(controller.queries.isEmpty())
    }

    private fun result(name: String) = GeocodeResult(
        name = name,
        subtitle = "Queensland",
        type = GeocodeResultType.LOCALITY,
        lat = -27.0,
        lon = 153.0,
        score = 1.0,
    )

    private fun advanceDebounce() {
        composeRule.waitForIdle()
        // Flush the value recomposition before advancing the virtual debounce
        // clock. This matters when autoAdvance is disabled.
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(SEARCH_DEBOUNCE_MS + 1)
        composeRule.mainClock.advanceTimeByFrame()
    }

    private fun waitForResult(text: String) {
        val autoAdvanceBefore = composeRule.mainClock.autoAdvance
        composeRule.mainClock.autoAdvance = true
        try {
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            composeRule.mainClock.autoAdvance = autoAdvanceBefore
        }
    }
    private class FakeGeocodeController : GeocodeController {
        val queries = java.util.concurrent.CopyOnWriteArrayList<String>()
        private val gates = mutableMapOf<String, CompletableDeferred<List<GeocodeResult>>>()

        override suspend fun search(query: String, limit: Int): List<GeocodeResult> {
            queries += query
            return gate(query).await()
        }

        fun gate(query: String): CompletableDeferred<List<GeocodeResult>> =
            gates.getOrPut(query) { CompletableDeferred() }
    }
}
