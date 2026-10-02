package com.organicmoto.maps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.organicmoto.maps.geocoding.GeocodeResult
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.organicmoto.maps.geocoding.GeocodeController
import com.organicmoto.maps.geocoding.GeocodeSearchController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TAG = "OrganicMoto.SearchField"
/**
 * Debounce window for the offline search-as-you-type fields. Internal (not
 * private) because SearchDebounceTest advances its virtual clock past exactly
 * this value — change it in one place and both stay honest.
 */
internal const val SEARCH_DEBOUNCE_MS = 300L
private const val MAX_DROPDOWN_ROWS = 6

/** Max dropdown height before the result list scrolls (keeps the overlay from sprawling). */
internal val MAX_DROPDOWN_HEIGHT = 320.dp

/** Result rows retain their complete minimum interactive target when the popup is shown. */
internal val MIN_SEARCH_RESULT_TARGET_HEIGHT = 48.dp
private val SEARCH_RESULT_ROW_VERTICAL_PADDING = 10.dp

/** Max result rows visible at once in the inline panel list; extra rows scroll. */
private const val MAX_INLINE_RESULTS = 5

/** True only for debuggable (debug) builds — raw user text is never logged in release. */
private fun isDebugBuild(context: Context): Boolean =
    (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
/** Search-as-you-type state rendered in the dropdown below the field. */
internal sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data class Results(val results: List<GeocodeResult>) : SearchUiState
    data object Empty : SearchUiState
}

/**
 * Search state for one route-planning field and its anchored result popup.
 */
@Stable
class RouteFieldSearchState internal constructor() {
    internal var uiState by mutableStateOf<SearchUiState>(SearchUiState.Idle)
    internal var hasFocus by mutableStateOf(false)
    internal var onPick: (GeocodeResult) -> Unit = {}

    /** True while this field owns an active result popup. */
    val active: Boolean get() = hasFocus && uiState != SearchUiState.Idle
}

@Composable
fun rememberRouteFieldSearchState(): RouteFieldSearchState = remember { RouteFieldSearchState() }
/**
 * Text field with 300 ms debounced offline search-as-you-type. While focused,
 * a results dropdown is rendered as an anchored overlay window directly below
 * the field; picking a result dismisses the dropdown and reports it via
 * [onResultPicked].
 */
@Composable
fun GeocodeSearchField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onResultPicked: (GeocodeResult) -> Unit,
    controller: GeocodeController,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
) {
    var uiState by remember { mutableStateOf<SearchUiState>(SearchUiState.Idle) }
    var hasFocus by remember { mutableStateOf(false) }
    var fieldHeightPx by remember { mutableStateOf(0) }
    var fieldWidthDp by remember { mutableStateOf(0.dp) }
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val debugLogs = isDebugBuild(LocalContext.current)

    LaunchedEffect(value) {
        if (value.isBlank()) {
            uiState = SearchUiState.Idle
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        if (debugLogs) Log.d(TAG, "[$label] debounce elapsed — searching \"$value\"")
        uiState = SearchUiState.Loading
        val results = try {
            withContext(Dispatchers.IO) { controller.search(value) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Propagate cancellation so stale results never replace a newer search.
            throw e
        } catch (e: Exception) {
            // Index unavailable (e.g. assets missing): degrade to empty results
            // so the lat,lon PointParser fallback stays usable.
            if (debugLogs) Log.w(TAG, "[$label] search for \"$value\" failed — degrading to empty results", e)
            emptyList()
        }
        if (debugLogs) {
            if (results.isEmpty()) {
                Log.d(TAG, "[$label] \"$value\" -> no results")
            } else {
                Log.d(
                    TAG,
                    "[$label] \"$value\" -> ${results.size} result(s): " +
                        results.take(MAX_DROPDOWN_ROWS).joinToString(", ") { it.name }
                )
            }
        }
        uiState = if (results.isEmpty()) SearchUiState.Empty else SearchUiState.Results(results)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned {
                val h = it.size.height
                val w = with(density) { it.size.width.toDp() }
                if (h != fieldHeightPx || w != fieldWidthDp) {
                    fieldHeightPx = h
                    fieldWidthDp = w
                }
            }
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                if (debugLogs) Log.v(TAG, "[$label] text changed: \"$it\"")
                onValueChange(it)
            },
            label = { Text(label) },
            placeholder = placeholder?.let { p -> { Text(p) } },
            singleLine = true,
            modifier = Modifier
                .semantics { contentDescription = label }
                .fillMaxWidth()
                .onFocusChanged { focused ->
                    val wasFocused = hasFocus
                    hasFocus = focused.isFocused
                    if (wasFocused && !focused.isFocused) uiState = SearchUiState.Idle
                }
        )

        // Results dropdown as an anchored overlay window. A separate window (Popup)
        // gives it correct z-order — it renders above the map panel, the sibling
        // field and the route controls instead of being clipped/covered by them.
        // It is deliberately non-focusable so the text field keeps the keyboard.
        // The popup ignores outside-window input because every soft-keyboard tap
        // is outside this window. Field focus changes still hide the results.
        if (hasFocus && uiState != SearchUiState.Idle && fieldWidthDp > 0.dp) {
            Popup(
                onDismissRequest = { uiState = SearchUiState.Idle },
                offset = IntOffset(0, fieldHeightPx),
                properties = PopupProperties(
                    focusable = false,
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                    usePlatformDefaultWidth = false,
                ),
            ) {
                SearchDropdown(width = fieldWidthDp) {
                    when (val state = uiState) {
                        SearchUiState.Idle -> Unit
                        SearchUiState.Loading -> DropdownMessage("Searching…")
                        SearchUiState.Empty -> DropdownMessage("No results", dim = true)
                        is SearchUiState.Results ->
                            state.results.take(MAX_DROPDOWN_ROWS).forEachIndexed { index, result ->
                                if (index > 0) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                                ResultRow(result) {
                                    uiState = SearchUiState.Idle
                                    focusManager.clearFocus()
                                    if (debugLogs) {
                                        Log.i(
                                            TAG,
                                            "[$label] picked \"${result.name}${if (result.subtitle.isNotBlank()) ", " + result.subtitle else ""}\" " +
                                                "(${result.type}, lat=${result.lat}, lon=${result.lon}, score=${result.score})"
                                        )
                                    }
                                    onResultPicked(result)
                                }
                            }
                    }
                }
            }
        }
    }
}


/**
 * Route-planning variant of [GeocodeSearchField]: same 300 ms debounced
 * offline search-as-you-type, but styled as a 50 dp row (icon + bold text)
 * for the bottom route-planning panel. By default, results render in an
 * anchored popup above the focused field. A host can disable that popup and
 * render [RoutePlanSearchResultsPopup] from a shared anchor.
 */
@Composable
fun RoutePlanSearchField(
    label: String,
    hint: String,
    icon: @Composable () -> Unit,
    value: String,
    onValueChange: (String) -> Unit,
    onResultPicked: (GeocodeResult) -> Unit,
    controller: GeocodeController,
    searchState: RouteFieldSearchState,
    modifier: Modifier = Modifier,
    renderResultsPopup: Boolean = true,
    isResolved: Boolean = false,
    onEditResolved: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    val debugLogs = isDebugBuild(LocalContext.current)
    // The shared result popup (rendered by the host or by this field) invokes
    // searchState.onPick; without this wiring its default is a no-op lambda,
    // so taps on result rows would do nothing.
    SideEffect { searchState.onPick = onResultPicked }
    val density = LocalDensity.current
    var fieldWidthDp by remember { mutableStateOf(0.dp) }
    val focusRequester = remember { FocusRequester() }
    var focusAfterEdit by remember { mutableStateOf(false) }

    LaunchedEffect(isResolved) {
        if (!isResolved && focusAfterEdit) {
            focusRequester.requestFocus()
            focusAfterEdit = false
        }
    }

    LaunchedEffect(value, isResolved) {
        if (isResolved || value.isBlank()) {
            searchState.uiState = SearchUiState.Idle
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        if (debugLogs) Log.d(TAG, "[$label] debounce elapsed — searching \"$value\"")
        searchState.uiState = SearchUiState.Loading
        val results = try {
            withContext(Dispatchers.IO) { controller.search(value) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Propagate cancellation so stale results never replace a newer search.
            throw e
        } catch (e: Exception) {
            // Index unavailable (e.g. assets missing): degrade to empty results
            // so the lat,lon PointParser fallback stays usable.
            if (debugLogs) Log.w(TAG, "[$label] search for \"$value\" failed — degrading to empty results", e)
            emptyList()
        }
        if (debugLogs) {
            if (results.isEmpty()) {
                Log.d(TAG, "[$label] \"$value\" -> no results")
            } else {
                Log.d(
                    TAG,
                    "[$label] \"$value\" -> ${results.size} result(s): " +
                        results.take(MAX_DROPDOWN_ROWS).joinToString(", ") { it.name }
                )
            }
        }
        searchState.uiState = if (results.isEmpty()) SearchUiState.Empty else SearchUiState.Results(results)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                val width = with(density) { coordinates.size.width.toDp() }
                if (width != fieldWidthDp) fieldWidthDp = width
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                icon()
            }
            if (isResolved) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            role = Role.Button,
                            onClick = {
                                focusAfterEdit = true
                                onEditResolved()
                            },
                        )
                        .semantics { contentDescription = "$label selected: $value" }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = value,
                            style = TextStyle(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFDE000000),
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "Selected place",
                            style = TextStyle(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF249CF2),
                            ),
                        )
                    }
                    Text(
                        text = "CHANGE",
                        style = TextStyle(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF249CF2),
                        ),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            } else {
                BasicTextField(
                    value = value,
                    onValueChange = {
                        if (debugLogs) Log.v(TAG, "[$label] text changed: \"$it\"")
                        onValueChange(it)
                    },
                    textStyle = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFDE000000)
                    ),
                    cursorBrush = SolidColor(Color(0xFF249CF2)),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        // Commit the typed text and drop focus: dismissing the IME
                        // must also leave search-suggestion mode so the ride
                        // controls (dial/status) come back without any race.
                        onDone = { focusManager.clearFocus() }
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .focusRequester(focusRequester)
                        .semantics { contentDescription = label }
                        .padding(horizontal = 8.dp)
                        .onFocusChanged { focused ->
                            val wasFocused = searchState.hasFocus
                            searchState.hasFocus = focused.isFocused
                            if (wasFocused && !focused.isFocused) searchState.uiState = SearchUiState.Idle
                        },
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (value.isEmpty()) {
                                Text(
                                    text = hint,
                                    style = TextStyle(
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF8A000000)
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            innerTextField()
                        }
                    }
                )
            }
        }

        // Suggestions use a separate, non-focusable popup. The popup ignores
        // outside-window input so a soft-keyboard tap cannot clear field focus.
        if (renderResultsPopup && searchState.active && fieldWidthDp > 0.dp) {
            RoutePlanSearchResultsPopup(
                state = searchState,
                width = fieldWidthDp,
            )
        }
    }
}

/**
 * Renders route suggestions above a shared endpoint-group anchor.
 *
 * The popup uses the anchor's top edge. RoutePlanPanel places that anchor
 * around both From and To fields, so results never cover either field.
 */
@Composable
internal fun RoutePlanSearchResultsPopup(
    state: RouteFieldSearchState,
    width: Dp,
    maxHeight: Dp = MAX_DROPDOWN_HEIGHT,
    centerHorizontally: Boolean = false,
) {
    val cappedMaxHeight = maxHeight.coerceIn(0.dp, MAX_DROPDOWN_HEIGHT)
    val density = LocalDensity.current
    val minimumRowHeightPx = minimumSearchResultPopupHeightPx(
        state = state.uiState,
        width = width,
        titleStyle = MaterialTheme.typography.titleSmall,
        subtitleStyle = MaterialTheme.typography.bodySmall,
    )
    val popupMaxHeightPx = with(density) { cappedMaxHeight.roundToPx() }
    if (!hasRoomForCompleteSearchResultRow(popupMaxHeightPx, minimumRowHeightPx)) return

    val focusManager = LocalFocusManager.current
    Popup(
        popupPositionProvider = remember(centerHorizontally) {
            AboveAnchorPopupPositionProvider(centerHorizontally)
        },
        onDismissRequest = { state.uiState = SearchUiState.Idle },
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        // Popup owns a separate Android window. Pin the measured density into
        // its content so font-scale metrics used for the fit check are exactly
        // the metrics used to render the result row.
        CompositionLocalProvider(LocalDensity provides density) {
            SearchDropdown(
                width = width,
                maxHeight = cappedMaxHeight,
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = 0.dp,
                    bottomEnd = 0.dp,
                ),
                color = ROUTE_PANEL_COLOR,
                shadowElevation = 0.dp,
                modifier = Modifier.semantics { contentDescription = "Search results" },
            ) {
                when (val current = state.uiState) {
                    SearchUiState.Idle -> Unit
                    SearchUiState.Loading -> DropdownMessage("Searching…")
                    SearchUiState.Empty -> DropdownMessage("No results", dim = true)
                    is SearchUiState.Results ->
                        current.results.take(MAX_DROPDOWN_ROWS).forEachIndexed { index, result ->
                            if (index > 0) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            ResultRow(result) {
                                state.uiState = SearchUiState.Idle
                                focusManager.clearFocus()
                                state.onPick(result)
                            }
                        }
                }
            }
        }
    }
}

/** Result surface; route-planning fields override it to extend the panel. */
@Composable
private fun SearchDropdown(
    width: Dp,
    maxHeight: Dp = MAX_DROPDOWN_HEIGHT,
    shape: Shape = MaterialTheme.shapes.small,
    color: Color = MaterialTheme.colorScheme.surface,
    shadowElevation: Dp = 4.dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .width(width)
            .heightIn(max = maxHeight),
        shape = shape,
        color = color,
        shadowElevation = shadowElevation,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            content = content,
        )
    }
}

/** Places a popup directly above its text-field anchor. */
internal class AboveAnchorPopupPositionProvider(
    private val centerHorizontally: Boolean = false,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val desiredX = if (centerHorizontally) {
            anchorBounds.left + (anchorBounds.right - anchorBounds.left - popupContentSize.width) / 2
        } else {
            when (layoutDirection) {
                LayoutDirection.Ltr -> anchorBounds.left
                LayoutDirection.Rtl -> anchorBounds.right - popupContentSize.width
            }
        }
        val x = desiredX.coerceIn(0, maxX)
        val y = (anchorBounds.top - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

/** Limits popup height to the pixels above the visible window viewport and the configured cap. */
internal fun maxSearchDropdownHeightPx(
    availableAboveAnchorPx: Int,
    preferredMaxHeightPx: Int,
    visibleViewportTopPx: Int = 0,
): Int {
    val visibleHeight = (availableAboveAnchorPx - visibleViewportTopPx).coerceAtLeast(0)
    return minOf(visibleHeight, preferredMaxHeightPx.coerceAtLeast(0))
}

/**
 * Measures the first result with the same one-line styles and width as [ResultRow].
 * TextMeasurer uses Compose's actual platform font metrics, including nonlinear
 * Android font scaling and font padding that cannot be recovered from lineHeight.
 */
@Composable
internal fun minimumSearchResultPopupHeightPx(
    state: SearchUiState,
    width: Dp,
    titleStyle: TextStyle,
    subtitleStyle: TextStyle,
): Int {
    val firstResult = (state as? SearchUiState.Results)?.results?.firstOrNull()
    val density = LocalDensity.current
    val targetHeightPx = with(density) { MIN_SEARCH_RESULT_TARGET_HEIGHT.roundToPx() }
    if (firstResult == null) return targetHeightPx

    val textMeasurer = rememberTextMeasurer()
    val textWidthPx = with(density) {
        (width - 32.dp).coerceAtLeast(0.dp).roundToPx()
    }
    val constraints = Constraints(maxWidth = textWidthPx)
    val titleHeightPx = textMeasurer.measure(
        text = firstResult.name,
        style = titleStyle,
        overflow = TextOverflow.Ellipsis,
        softWrap = true,
        maxLines = 1,
        constraints = constraints,
    ).size.height
    val subtitleHeightPx = if (firstResult.subtitle.isNotBlank()) {
        textMeasurer.measure(
            text = firstResult.subtitle,
            style = subtitleStyle,
            overflow = TextOverflow.Ellipsis,
            softWrap = true,
            maxLines = 1,
            constraints = constraints,
        ).size.height
    } else {
        0
    }
    val paddingHeightPx = with(density) {
        (SEARCH_RESULT_ROW_VERTICAL_PADDING * 2).roundToPx()
    }
    return searchResultRowMinimumHeightPx(
        titleHeightPx = titleHeightPx,
        subtitleHeightPx = subtitleHeightPx,
        paddingHeightPx = paddingHeightPx,
        targetHeightPx = targetHeightPx,
    )
}

/** Pure size rule kept separate so row gating can be checked without Android text layout. */
internal fun searchResultRowMinimumHeightPx(
    titleHeightPx: Int,
    subtitleHeightPx: Int,
    paddingHeightPx: Int,
    targetHeightPx: Int,
): Int = maxOf(
    targetHeightPx.coerceAtLeast(0),
    titleHeightPx.coerceAtLeast(0) +
        subtitleHeightPx.coerceAtLeast(0) +
        paddingHeightPx.coerceAtLeast(0),
)

/** True only when the result surface can show one complete first result row. */
internal fun hasRoomForCompleteSearchResultRow(availableHeightPx: Int, rowHeightPx: Int): Boolean =
    rowHeightPx > 0 && availableHeightPx >= rowHeightPx

/**
 * Standalone results viewport for route-planning tests and non-panel hosts.
 * The route panel uses the anchored popup from [RoutePlanSearchField].
 */
@Composable
fun RoutePlanSearchResults(
    state: RouteFieldSearchState,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
            .padding(top = 2.dp, bottom = 8.dp),
    ) {
        when (val current = state.uiState) {
            SearchUiState.Idle -> Unit
            SearchUiState.Loading -> DropdownMessage("Searching…")
            SearchUiState.Empty -> DropdownMessage("No results", dim = true)
            is SearchUiState.Results ->
                current.results.forEachIndexed { index, result ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    ResultRow(result) { state.onPick(result) }
                }
        }
    }
}

@Composable
private fun ResultRow(result: GeocodeResult, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
            .minimumInteractiveComponentSize()
            .semantics { contentDescription = result.name }
            .padding(horizontal = 16.dp, vertical = SEARCH_RESULT_ROW_VERTICAL_PADDING)
    ) {
        Text(
            text = result.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (result.subtitle.isNotBlank()) {
            Text(
                text = result.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DropdownMessage(text: String, dim: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    )
}
