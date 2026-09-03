package com.organicmoto.maps.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.organicmoto.maps.RoutePlanSearchField
import com.organicmoto.maps.RoutePlanSearchResultsPopup
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
    fun searchResultsRenderAboveFocusedField() {
        val controller = FakeGeocodeController()
        var value by mutableStateOf("")
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                val searchState = rememberRouteFieldSearchState()
                Column {
                    Spacer(Modifier.height(400.dp))
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
        }
        composeRule.onNodeWithContentDescription("From").performTextInput("Mount")
        advanceDebounce()
        composeRule.waitUntil(2_000) { controller.queries == listOf("Mount") }
        controller.gate("Mount").complete(listOf(result("Mount result")))
        waitForResult("Mount result")

        val fieldBounds = composeRule.onNodeWithContentDescription("From")
            .fetchSemanticsNode().boundsInRoot
        val resultBounds = composeRule.onNodeWithContentDescription("Mount result")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "search result must be above its field: result=$resultBounds field=$fieldBounds",
            resultBounds.bottom <= fieldBounds.top,
        )
    }

    @Test
    fun touchingTheFocusedFieldWhileResultsAreVisibleKeepsFocus() {
        val controller = FakeGeocodeController()
        var value by mutableStateOf("")
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                val searchState = rememberRouteFieldSearchState()
                Column {
                    Spacer(Modifier.height(400.dp))
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
        }
        val field = composeRule.onNodeWithContentDescription("From")
        field.performTextInput("Mount")
        advanceDebounce()
        composeRule.waitUntil(2_000) { controller.queries == listOf("Mount") }
        controller.gate("Mount").complete(listOf(result("Mount result")))
        waitForResult("Mount result")

        field.performClick()

        field.assertIsFocused()
        field.performTextInput("ain")
        assertEquals("Mountain", value)
    }

    @Test
    fun sharedResultsPopupStaysAboveBothEndpointFields() {
        val controller = FakeGeocodeController()
        var fromValue by mutableStateOf("")
        var toValue by mutableStateOf("")
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                val fromSearch = rememberRouteFieldSearchState()
                val toSearch = rememberRouteFieldSearchState()
                Column {
                    Spacer(Modifier.height(400.dp))
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        Column {
                            RoutePlanSearchField(
                                label = "From",
                                hint = "Route from",
                                icon = {},
                                value = fromValue,
                                onValueChange = { fromValue = it },
                                onResultPicked = {},
                                controller = controller,
                                searchState = fromSearch,
                                renderResultsPopup = false,
                            )
                            RoutePlanSearchField(
                                label = "To",
                                hint = "Route to",
                                icon = {},
                                value = toValue,
                                onValueChange = { toValue = it },
                                onResultPicked = {},
                                controller = controller,
                                searchState = toSearch,
                                renderResultsPopup = false,
                            )
                        }
                        val activeSearch = fromSearch.takeIf { it.active } ?: toSearch.takeIf { it.active }
                        if (activeSearch != null) {
                            RoutePlanSearchResultsPopup(activeSearch, maxWidth)
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithContentDescription("To").performTextInput("Palm")
        advanceDebounce()
        composeRule.waitUntil(2_000) { controller.queries == listOf("Palm") }
        controller.gate("Palm").complete(listOf(result("Palm result")))
        waitForResult("Palm result")

        val resultBounds = composeRule.onNodeWithContentDescription("Palm result")
            .fetchSemanticsNode().boundsInRoot
        val fromBounds = composeRule.onNodeWithContentDescription("From")
            .fetchSemanticsNode().boundsInRoot
        val toBounds = composeRule.onNodeWithContentDescription("To")
            .fetchSemanticsNode().boundsInRoot
        assertTrue("results must be above From", resultBounds.bottom <= fromBounds.top)
        assertTrue("results must be above To", resultBounds.bottom <= toBounds.top)
    }

    @Test
    fun tappingAResultRowReportsThePick() {
        val controller = FakeGeocodeController()
        var value by mutableStateOf("")
        var resolved by mutableStateOf(false)
        val picked = java.util.concurrent.CopyOnWriteArrayList<GeocodeResult>()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                val searchState = rememberRouteFieldSearchState()
                Column {
                    Spacer(Modifier.height(400.dp))
                    RoutePlanSearchField(
                        label = "From",
                        hint = "Route from",
                        icon = {},
                        value = value,
                        onValueChange = { value = it },
                        onResultPicked = {
                            picked += it
                            value = "${it.name}, ${it.subtitle}"
                            resolved = true
                        },
                        controller = controller,
                        searchState = searchState,
                        isResolved = resolved,
                        onEditResolved = { resolved = false },
                    )
                }
            }
        }
        composeRule.onNodeWithContentDescription("From").performTextInput("Mount")
        advanceDebounce()
        composeRule.waitUntil(2_000) { controller.queries == listOf("Mount") }
        controller.gate("Mount").complete(listOf(result("Mount result")))
        waitForResult("Mount result")

        composeRule.onNodeWithContentDescription("Mount result").performClick()

        assertTrue(picked.map { it.name } == listOf("Mount result"))
        advanceDebounce()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(
            "From selected: Mount result, Queensland",
            useUnmergedTree = true,
        ).fetchSemanticsNode()
        assertEquals(listOf("Mount"), controller.queries)

        composeRule.onNodeWithContentDescription(
            "From selected: Mount result, Queensland",
            useUnmergedTree = true,
        ).performClick()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("From").fetchSemanticsNode()
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
