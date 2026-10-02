package com.organicmoto.maps.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.organicmoto.maps.RoutePlanSearchField
import com.organicmoto.maps.RoutePlanSearchResultsPopup
import com.organicmoto.maps.RouteFieldSearchState
import com.organicmoto.maps.SearchUiState
import com.organicmoto.maps.SEARCH_DEBOUNCE_MS
import com.organicmoto.maps.minimumSearchResultPopupHeightPx
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
                            RoutePlanSearchResultsPopup(
                                activeSearch,
                                maxWidth,
                                maxHeight = 140.dp,
                            )
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithContentDescription("To").performTextInput("Palm")
        advanceDebounce()
        composeRule.waitUntil(2_000) { controller.queries == listOf("Palm") }
        controller.gate("Palm").complete(
            listOf(result("Palm result")) + (1..6).map { result("Palm alternative $it") },
        )
        waitForResult("Palm result")

        val resultBounds = composeRule.onNodeWithContentDescription("Palm result")
            .fetchSemanticsNode().boundsInRoot
        val popupBounds = composeRule.onNodeWithContentDescription("Search results")
            .fetchSemanticsNode().boundsInRoot
        val fromBounds = composeRule.onNodeWithContentDescription("From")
            .fetchSemanticsNode().boundsInRoot
        val toBounds = composeRule.onNodeWithContentDescription("To")
            .fetchSemanticsNode().boundsInRoot
        assertTrue("results must be above From", resultBounds.bottom <= fromBounds.top)
        assertTrue("results must be above To", resultBounds.bottom <= toBounds.top)
        assertTrue(
            "popup should be capped at 140 dp",
            popupBounds.height <= 140f * composeRule.density.density + 1f,
        )
        assertTrue("popup must stay above From", popupBounds.bottom <= fromBounds.top)
        assertTrue(
            "first result must remain fully visible",
            resultBounds.top >= popupBounds.top && resultBounds.bottom <= popupBounds.bottom,
        )
    }

    @Test
    fun constrainedPopupWaitsForCompleteFontScaledResultRow() {
        val searchState = RouteFieldSearchState().apply {
            hasFocus = true
            uiState = SearchUiState.Results(listOf(result("Minimum row")))
        }
        var popupMaxHeight by mutableStateOf(47.dp)
        var fontScale by mutableStateOf(1f)
        var measuredTitleHeightPx by mutableStateOf(0)
        var measuredSubtitleHeightPx by mutableStateOf(0)
        val heightProbe = java.util.concurrent.atomic.AtomicReference<PopupHeightProbe?>(null)
        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, fontScale)) {
                MaterialTheme {
                    val density = LocalDensity.current
                    val minimumRowHeightPx = minimumSearchResultPopupHeightPx(
                        state = searchState.uiState,
                        width = 240.dp,
                        titleStyle = MaterialTheme.typography.titleSmall,
                        subtitleStyle = MaterialTheme.typography.bodySmall,
                    )
                    val measuredRowHeightPx = with(density) {
                        maxOf(
                            48.dp.roundToPx(),
                            measuredTitleHeightPx + measuredSubtitleHeightPx + 2 * 10.dp.roundToPx(),
                        )
                    }
                    // Measure the same two Text styles without a height cap.
                    // onTextLayout reports actual Android/Compose font scaling,
                    // including nonlinear scaling above 1.3x.
                    Column(
                        Modifier
                            .width(240.dp)
                            .minimumInteractiveComponentSize()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .graphicsLayer(alpha = 0f),
                    ) {
                        Text(
                            text = "Minimum row",
                            style = MaterialTheme.typography.titleSmall,
                            onTextLayout = { measuredTitleHeightPx = it.size.height },
                        )
                        Text(
                            text = "Queensland",
                            style = MaterialTheme.typography.bodySmall,
                            onTextLayout = { measuredSubtitleHeightPx = it.size.height },
                        )
                    }
                    SideEffect {
                        heightProbe.set(
                            PopupHeightProbe(
                                density = density.density,
                                fontScale = density.fontScale,
                                popupMaxHeightDp = popupMaxHeight.value,
                                minimumRowHeightDp = with(density) { minimumRowHeightPx.toDp().value },
                                measuredRowHeightPx = measuredRowHeightPx,
                                minimumRowHeightPx = minimumRowHeightPx,
                            ),
                        )
                    }
                    RoutePlanSearchResultsPopup(
                        state = searchState,
                        width = 240.dp,
                        maxHeight = popupMaxHeight,
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Search results").assertDoesNotExist()

        val normalProbe = checkNotNull(heightProbe.get()) { "Popup height probe did not run" }
        assertEquals("normal density row measurement", normalProbe.minimumRowHeightPx, normalProbe.measuredRowHeightPx)
        popupMaxHeight = 48.dp
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Search results").assertDoesNotExist()
        popupMaxHeight = 55.dp
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Search results").assertDoesNotExist()

        popupMaxHeight = 56.dp
        composeRule.waitForIdle()
        val popupNode = composeRule.onNodeWithContentDescription("Search results")
            .fetchSemanticsNode()
        val resultNode = composeRule.onNodeWithContentDescription(
            "Minimum row",
            useUnmergedTree = true,
        ).fetchSemanticsNode()
        val normalRowHeightPx = 56f * composeRule.density.density
        assertTrue(
            "the full two-line row's un-clipped layout height must fit",
            resultNode.size.height.toFloat() >= normalRowHeightPx - 1f,
        )
        assertTrue(
            "the complete text and target must fit inside the popup",
            resultNode.positionOnScreen.y + resultNode.size.height <=
                popupNode.positionOnScreen.y + popupNode.size.height,
        )

        fontScale = 1.5f
        popupMaxHeight = 56.dp
        composeRule.waitForIdle()
        val probe = checkNotNull(heightProbe.get()) { "Popup height probe did not run" }
        assertEquals("density used by popup placement", composeRule.density.density, probe.density, 0.01f)
        assertEquals("font scale used by popup placement", 1.5f, probe.fontScale, 0.01f)
        assertEquals("cap before measuring its threshold", 56f, probe.popupMaxHeightDp, 0.01f)
        assertEquals("helper matches independently measured font-scaled row", probe.measuredRowHeightPx, probe.minimumRowHeightPx)
        assertTrue("font-scaled row grows beyond the normal row", probe.measuredRowHeightPx > normalProbe.measuredRowHeightPx)
        val rowHeight = with(composeRule.density) { probe.measuredRowHeightPx.toDp() }
        assertEquals(
            "computed dp minimum matches rendered row typography",
            rowHeight.value,
            probe.minimumRowHeightDp,
            1f / probe.density,
        )
        val onePixelBelowRowHeight = with(composeRule.density) {
            (probe.measuredRowHeightPx - 1).toDp()
        }
        popupMaxHeight = onePixelBelowRowHeight
        composeRule.waitForIdle()
        val belowThresholdProbe = checkNotNull(heightProbe.get()) { "Popup height probe did not update" }
        assertEquals(onePixelBelowRowHeight.value, belowThresholdProbe.popupMaxHeightDp, 0.01f)
        composeRule.onNodeWithContentDescription("Search results").assertDoesNotExist()
        popupMaxHeight = rowHeight
        composeRule.waitForIdle()
        val largeTextPopupNode = composeRule.onNodeWithContentDescription("Search results")
            .fetchSemanticsNode()
        val largeTextResultNode = composeRule.onNodeWithContentDescription(
            "Minimum row",
            useUnmergedTree = true,
        ).fetchSemanticsNode()
        assertTrue(
            "font-scaled text must fit without clipping: " +
                "rendered=${largeTextResultNode.size.height}px, " +
                "measured=${probe.measuredRowHeightPx}px, " +
                "cap=${probe.popupMaxHeightDp}dp, density=${probe.density}, " +
                "fontScale=${probe.fontScale}",
            largeTextResultNode.size.height >= probe.measuredRowHeightPx,
        )
        assertTrue(
            "the font-scaled text and target must fit inside the popup: " +
                "row=${largeTextResultNode.boundsInRoot}, popup=${largeTextPopupNode.boundsInRoot}, " +
                "measured=${probe.measuredRowHeightPx}px",
            largeTextResultNode.positionOnScreen.y + largeTextResultNode.size.height <=
                largeTextPopupNode.positionOnScreen.y + largeTextPopupNode.size.height,
        )
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

    private data class PopupHeightProbe(
        val density: Float,
        val fontScale: Float,
        val popupMaxHeightDp: Float,
        val minimumRowHeightDp: Float,
        val measuredRowHeightPx: Int,
        val minimumRowHeightPx: Int,
    )
}
