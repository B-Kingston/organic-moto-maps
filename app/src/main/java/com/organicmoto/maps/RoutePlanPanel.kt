package com.organicmoto.maps

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graphhopper.ResponsePath
import com.organicmoto.maps.geocoding.GeocodeController
import com.organicmoto.maps.geocoding.GeocodeResult
import com.organicmoto.maps.map.routeColorHex
import kotlin.math.absoluteValue

/** Card width inside the swipeable route bar; edge padding centers the active card. */
private val ROUTE_CARD_WIDTH = 220.dp
private val ROUTE_CARD_HEIGHT = 48.dp
/** Shared planner surface color used by fields and their result extension. */
internal val ROUTE_PANEL_COLOR = Color(0xFFF5F5F5)

/** 50 dp From/To row inside the panel (see RoutePlanSearchField). */
private val SEARCH_FIELD_ROW_HEIGHT = 50.dp

/**
 * Panel-frame slot heights. Every routeless panel state — idle, typing,
 * loading, error — is built from exactly these slots, so the panel's total
 * height never changes with state until route candidates exist.
 */
private val ROUTE_STATUS_HEIGHT = 40.dp

/** Knob row (6 + 100 + 6 dp) plus the fixed status row. */
private val RIDE_CONTROLS_HEIGHT = 112.dp + ROUTE_STATUS_HEIGHT

/** Carousel slot; equals the pager height in RouteCarouselBar. Test seam. */
internal val CAROUSEL_SLOT_HEIGHT = ROUTE_CARD_HEIGHT + 12.dp

/** Ride controls + 50 dp From/To rows + START action row (2 + 48 + 10) + divider. */
private val BASE_PANEL_HEIGHT = RIDE_CONTROLS_HEIGHT +
    SEARCH_FIELD_ROW_HEIGHT * 2 + 60.dp + 3.dp

/**
 * Compact map-side action rail for the three less-frequent planning actions.
 * It stays in the upper-right reach zone instead of crowding the complexity
 * dial, while each cell remains a full 48 dp touch target.
 */
@Composable
internal fun RouteActionsPill(
    onLoadMap: () -> Unit,
    onImportGpx: () -> Unit,
    onRouteSettings: () -> Unit,
    onVoiceSettings: () -> Unit,
    voiceGuidanceEnabled: Boolean,
    voiceSpeechStatus: OfflineSpeechStatus,
    onOpenSavedRoutes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(28.dp)
    Surface(
        color = Color.White.copy(alpha = 0.94f),
        shape = shape,
        shadowElevation = 5.dp,
        modifier = modifier
            .width(48.dp)
            .border(1.dp, Color(0x22000000), shape),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            RouteActionsPillButton(
                contentDescription = "Load map file",
                onClick = onLoadMap,
            ) { Text("MAP", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
            HorizontalDivider(
                color = Color(0x1A000000),
                thickness = 1.dp,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            RouteActionsPillButton(
                contentDescription = "Import GPX route",
                onClick = onImportGpx,
            ) { ImportIcon() }
            HorizontalDivider(
                color = Color(0x1A000000),
                thickness = 1.dp,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            RouteActionsPillButton(
                contentDescription = "Route settings",
                onClick = onRouteSettings,
            ) { SettingsCogIcon() }
            HorizontalDivider(
                color = Color(0x1A000000),
                thickness = 1.dp,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            RouteActionsPillButton(
                contentDescription = "Voice guidance settings",
                stateDescription = when {
                    !voiceGuidanceEnabled -> "Disabled"
                    voiceSpeechStatus is OfflineSpeechStatus.Ready -> "English offline voice ready"
                    voiceSpeechStatus is OfflineSpeechStatus.Checking -> "Checking for an English offline voice"
                    voiceSpeechStatus is OfflineSpeechStatus.NotStarted -> "English offline voice not checked yet"
                    else -> "English offline voice unavailable"
                },
                onClick = onVoiceSettings,
            ) {
                VoiceGuidanceIcon(
                    enabled = voiceGuidanceEnabled && voiceSpeechStatus is OfflineSpeechStatus.Ready,
                    color = Color(0xFF616161),
                )
            }
            HorizontalDivider(
                color = Color(0x1A000000),
                thickness = 1.dp,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            RouteActionsPillButton(
                contentDescription = "Saved routes",
                onClick = onOpenSavedRoutes,
            ) { BookmarkIcon(filled = false) }
        }
    }
}

@Composable
private fun RouteActionsPillButton(
    contentDescription: String,
    onClick: () -> Unit,
    stateDescription: String? = null,
    icon: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .semantics {
                this.contentDescription = contentDescription
                if (stateDescription != null) this.stateDescription = stateDescription
            },
    ) {
        icon()
    }
}

/**
 * Thin horizontal bar on top of the route-planning panel: one card per
 * proposed route in a snap-scrolling carousel. The centered card IS the
 * selection — swiping left/right re-centers another card and selects that
 * route on the map; selecting a route from the map (line tap) scrolls its
 * card into the middle instead.
 */
@Composable
private fun RouteCarouselBar(
    routes: List<ResponsePath>,
    selectedIndex: Int,
    onSelectRoute: (Int) -> Unit,
    onSaveRoute: suspend (ResponsePath) -> Boolean,
    modifier: Modifier = Modifier,
) {
    if (routes.isEmpty()) return
    val view = LocalView.current
    val pagerState = rememberPagerState(pageCount = { routes.size })

    // Settling a swipe slots that card into the middle: tick a haptic detent
    // and select the matching route on the map. Equal re-emissions are
    // ignored so external selection changes never echo back through here.
    LaunchedEffect(pagerState, routes.size) {
        var lastSettledPage = pagerState.settledPage
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val changedPage = page != lastSettledPage
            lastSettledPage = page
            if (changedPage) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onSelectRoute(page.coerceIn(0, routes.lastIndex))
            }
        }
    }
    // External selection (map tap, fresh result) animates the matching card
    // into the middle. Skipped while a gesture is driving the pager.
    LaunchedEffect(pagerState, selectedIndex, routes.size) {
        val target = selectedIndex.coerceIn(0, routes.lastIndex)
        if (!pagerState.isScrollInProgress && target != pagerState.settledPage) {
            pagerState.animateScrollToPage(target)
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val edgePadding = ((maxWidth - ROUTE_CARD_WIDTH) / 2f).coerceAtLeast(0.dp)
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = edgePadding),
            pageSpacing = 10.dp,
            // Slight overshoot before settling: the incoming card dips past
            // center and pops back — a physical slot-into-place feel.
            flingBehavior = PagerDefaults.flingBehavior(
                state = pagerState,
                snapAnimationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(ROUTE_CARD_HEIGHT + 12.dp),
        ) { page ->
            RouteCard(
                path = routes[page],
                routeIndex = page,
                selected = page == pagerState.targetPage,
                onSelectRoute = onSelectRoute,
                onSaveRoute = { onSaveRoute(routes[page]) },
                modifier = Modifier
                    .graphicsLayer {
                        // Depth cue while swiping: neighbours shrink and fade,
                        // so the landing card visibly pops into place.
                        val distanceFromCenter =
                            pagerState.getOffsetDistanceInPages(page).absoluteValue
                                .coerceIn(0f, 1f)
                        scaleX = 1f - 0.12f * distanceFromCenter
                        scaleY = 1f - 0.12f * distanceFromCenter
                        alpha = 1f - 0.4f * distanceFromCenter
                    }
                    .width(ROUTE_CARD_WIDTH)
                    .height(ROUTE_CARD_HEIGHT),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RouteCard(
    path: ResponsePath,
    routeIndex: Int,
    selected: Boolean,
    onSelectRoute: (Int) -> Unit,
    onSaveRoute: suspend () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val routeColor = Color(android.graphics.Color.parseColor(routeColorHex(routeIndex)))
    val background by animateColorAsState(
        targetValue = if (selected) routeColor else Color.White,
        animationSpec = tween(durationMillis = 150),
        label = "routeCardBackground",
    )
    val foreground by animateColorAsState(
        targetValue = if (selected) Color.White else Color(0xFF303030),
        animationSpec = tween(durationMillis = 150),
        label = "routeCardForeground",
    )
    val view = LocalView.current
    var saveBubbleShown by remember { mutableStateOf(false) }
    val metrics = "${formatRouteDuration(path.time)} · ${formatRouteDistance(path.distance)}"
    Surface(
        color = background,
        shape = RoundedCornerShape(12.dp),
        shadowElevation = if (selected) 3.dp else 1.dp,
        modifier = modifier
            .border(
                width = 1.dp,
                color = if (selected) Color.Transparent else routeColor.copy(alpha = 0.45f),
                shape = RoundedCornerShape(12.dp),
            )
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onSelectRoute(routeIndex) },
                onLongClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    saveBubbleShown = true
                },
            )
            .semantics {
                contentDescription = "Route ${routeIndex + 1}: $metrics"
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(if (selected) Color.White else routeColor, CircleShape),
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = "Route ${routeIndex + 1}",
                    color = foreground,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    text = metrics,
                    color = foreground.copy(alpha = if (selected) 0.92f else 0.72f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
        SaveRouteBubble(
            visible = saveBubbleShown,
            metrics = metrics,
            onSave = onSaveRoute,
            onDismiss = { saveBubbleShown = false },
        )
    }
}

/**
 * Bottom route-planning panel styled after Organic Maps: the 50 dp From/To
 * rows, the ride-controls region and the START button. Fields collapse in
 * ride mode. The panel is anchored to the bottom. Routeless states share one
 * fixed frame; a successful search adds the carousel bar above the fields.
 * The map viewport ends where this panel begins.
 */
@Composable
internal fun RoutePlanPanel(
    fromText: String,
    fromResolved: Boolean,
    onFromChange: (String) -> Unit,
    onEditFrom: () -> Unit,
    onFromPicked: (GeocodeResult) -> Unit,
    toText: String,
    toResolved: Boolean,
    onToChange: (String) -> Unit,
    onEditTo: () -> Unit,
    onToPicked: (GeocodeResult) -> Unit,
    geocodeController: GeocodeController,
    state: RouteUiState,
    onSelectRoute: (Int) -> Unit,
    onRoute: () -> Unit,
    complexity: Float,
    onComplexityChange: (Float) -> Unit,
    onComplexityChangeFinished: (Float) -> Unit,
    onSaveRoute: suspend (ResponsePath) -> Boolean,
    onToggleGuidance: () -> Unit,
    guidanceActive: Boolean,
    gpxImportError: String?,
    onDismissGpxError: () -> Unit,
    importedGpxName: String?,
    importedGpxMilestoneCount: Int,
    onOpenGpxPreview: () -> Unit,
    onEditImportedGpx: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val fromSearch = rememberRouteFieldSearchState()
    val toSearch = rememberRouteFieldSearchState()
    Surface(
        color = ROUTE_PANEL_COLOR,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (guidanceActive) 0.dp else BASE_PANEL_HEIGHT)
        ) {
            // In ride mode the whole planner UI hides: the top HUD carries the
            // next turn and the NavigationDataBar carries speed, ETA, distance,
            // pace delta, and the END button. The rider cannot edit the plan
            // mid-ride, and the map gets the freed screen space.
            if (guidanceActive) return@Column
            // Carousel bar renders only when a successful search produced
            // route candidates; the empty slot is not reserved, so the panel
            // stays compact until there is something to put in it.
            val success = state as? RouteUiState.Success
            if (success != null && success.result.routes.isNotEmpty()) {
                RouteCarouselBar(
                    routes = success.result.routes,
                    selectedIndex = success.selectedIndex,
                    onSelectRoute = onSelectRoute,
                    onSaveRoute = onSaveRoute,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CAROUSEL_SLOT_HEIGHT),
                )
            }
            HorizontalDivider(color = Color(0x1E000000), thickness = 1.dp)
            if (importedGpxName != null) {
                GpxEndpointRow(
                    label = "From",
                    value = fromText,
                    start = true,
                    actionLabel = "EDIT",
                    onAction = onEditImportedGpx,
                )
                HorizontalDivider(
                    color = Color(0xFF1E000000),
                    thickness = 1.dp,
                    modifier = Modifier.padding(start = 40.dp)
                )
                GpxEndpointRow(
                    label = "To",
                    value = toText,
                    start = false,
                    milestoneCount = importedGpxMilestoneCount,
                    actionLabel = "PREVIEW",
                    onAction = onOpenGpxPreview,
                )
            } else {
                // Anchor the shared result popup to the top of the complete
                // endpoint group, not to the active field. This keeps results
                // above both From and To when To owns the search.
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        RoutePlanSearchField(
                            label = "From",
                            hint = "Route from",
                            icon = { StartDotIcon() },
                            value = fromText,
                            onValueChange = onFromChange,
                            onResultPicked = onFromPicked,
                            controller = geocodeController,
                            searchState = fromSearch,
                            modifier = Modifier.fillMaxWidth(),
                            renderResultsPopup = false,
                            isResolved = fromResolved,
                            onEditResolved = onEditFrom,
                        )
                        HorizontalDivider(
                            color = Color(0xFF1E000000),
                            thickness = 1.dp,
                            modifier = Modifier.padding(start = 40.dp)
                        )
                        RoutePlanSearchField(
                            label = "To",
                            hint = "Route to",
                            icon = { FinishFlagIcon() },
                            value = toText,
                            onValueChange = onToChange,
                            onResultPicked = onToPicked,
                            controller = geocodeController,
                            searchState = toSearch,
                            modifier = Modifier.fillMaxWidth(),
                            renderResultsPopup = false,
                            isResolved = toResolved,
                            onEditResolved = onEditTo,
                        )
                    }
                    val activeSearch = fromSearch.takeIf { it.active } ?: toSearch.takeIf { it.active }
                    if (activeSearch != null && maxWidth > 0.dp) {
                        RoutePlanSearchResultsPopup(
                            state = activeSearch,
                            width = maxWidth,
                        )
                    }
                }
            }
            HorizontalDivider(
                color = Color(0xFF1E000000),
                thickness = 1.dp,
                modifier = Modifier.padding(start = 40.dp)
            )
            // Keep the ride-controls region fixed while typing. Search
            // suggestions render in a popup above the focused field.
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = RIDE_CONTROLS_HEIGHT)
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Ride complexity",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF8A000000)
                            )
                            Text(
                                text = complexityLabel(complexity),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Turn clockwise for longer, curvier roads",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF8A000000)
                            )
                        }
                        // Keep the dial centered in its compact control
                        // slot; the occasional planning actions live in
                        // the map-side vertical pill.
                        Box(Modifier.size(100.dp)) {
                            InfiniteComplexityKnob(
                                value = complexity,
                                onValueChange = onComplexityChange,
                                onValueChangeFinished = onComplexityChangeFinished,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                    }
                    RouteStatusSlot(state)
                }
            }
            Button(
                onClick = {
                    focusManager.clearFocus()
                    if (state is RouteUiState.Success) {
                        onToggleGuidance()
                    } else {
                        onRoute()
                    }
                },
                enabled = state !is RouteUiState.Loading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (guidanceActive) Color(0xFF0D2137) else Color(0xFF249CF2),
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFF9ECDF5),
                    disabledContentColor = Color(0xFFE0E0E0)
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp)
                    .heightIn(min = 48.dp)
            ) {
                Text(
                    when {
                        guidanceActive -> "STOP GUIDANCE"
                        state is RouteUiState.Success -> "RIDE"
                        else -> "START"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }

    if (gpxImportError != null) {
        AlertDialog(
            onDismissRequest = onDismissGpxError,
            title = { Text("Import failed") },
            text = { Text(gpxImportError) },
            confirmButton = {
                TextButton(onClick = onDismissGpxError) { Text("OK") }
            },
        )
    }
}

/** Read-only, geolocated endpoint row used while an imported GPX owns the plan. */
@Composable
private fun GpxEndpointRow(
    label: String,
    value: String,
    start: Boolean,
    milestoneCount: Int = 0,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .then(
                if (onAction != null) Modifier.clickable(onClick = onAction)
                else Modifier
            )
            .semantics {
                contentDescription = if (actionLabel == "PREVIEW") {
                    "$label: $value. Preview GPX route, $milestoneCount milestones"
                } else {
                    "$label: $value${if (actionLabel != null) ". $actionLabel imported route" else ""}"
                }
            }
            .padding(horizontal = 8.dp),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (start) StartDotIcon() else FinishFlagIcon()
        }
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0x99000000))
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (actionLabel != null) {
            Text(
                actionLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF249CF2),
            )
        }
    }
}

/**
 * Fixed-height status row between the ride controls and START: the selected
 * route's ETA and distance on success, the failure message on error, empty
 * otherwise. The constant size keeps the panel frame identical in every
 * state, so the START button never moves.
 */
@Composable
private fun RouteStatusSlot(state: RouteUiState) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(ROUTE_STATUS_HEIGHT)
    ) {
        (state as? RouteUiState.Success)?.let { success ->
            val selectedPath = success.result.routes.getOrNull(success.selectedIndex)
                ?: success.result.routes.first()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = formatRouteDuration(selectedPath.time),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatRouteDistance(selectedPath.distance),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFF8A000000)
                )
            }
        }
        (state as? RouteUiState.Error)?.let {
            Text(
                text = it.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.CenterStart)
                    .padding(horizontal = 16.dp)
            )
        }
    }
}
