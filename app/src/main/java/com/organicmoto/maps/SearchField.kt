package com.organicmoto.maps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.organicmoto.maps.geocoding.GeocodeResult
import com.organicmoto.maps.geocoding.GeocodeSearchController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TAG = "OrganicMoto.SearchField"
private const val SEARCH_DEBOUNCE_MS = 300L
private const val MAX_DROPDOWN_ROWS = 6

/** Max dropdown height before the result list scrolls (keeps the overlay from sprawling). */
private val MAX_DROPDOWN_HEIGHT = 320.dp

/** True only for debuggable (debug) builds — raw user text is never logged in release. */
private fun isDebugBuild(context: Context): Boolean =
    (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

/** Search-as-you-type state rendered in the dropdown below the field. */
private sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data class Results(val results: List<GeocodeResult>) : SearchUiState
    data object Empty : SearchUiState
}

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
    controller: GeocodeSearchController,
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
        // It is deliberately non-focusable so the text field keeps the keyboard,
        // taps outside dismiss it, and taps on non-result areas propagate to the
        // controls below (the second field and the route button stay reachable).
        if (hasFocus && uiState != SearchUiState.Idle && fieldWidthDp > 0.dp) {
            Popup(
                onDismissRequest = {
                    uiState = SearchUiState.Idle
                    focusManager.clearFocus()
                },
                offset = IntOffset(0, fieldHeightPx),
                properties = PopupProperties(focusable = false, usePlatformDefaultWidth = false),
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

/** Elevated surface anchored directly below the text field (overlays the map). */
@Composable
private fun SearchDropdown(
    width: Dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .width(width)
            .heightIn(max = MAX_DROPDOWN_HEIGHT),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            content = content,
        )
    }
}

@Composable
private fun ResultRow(result: GeocodeResult, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
            .padding(horizontal = 16.dp, vertical = 10.dp)
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
