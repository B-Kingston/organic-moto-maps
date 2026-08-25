package com.organicmoto.maps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.graphhopper.ResponsePath
import com.graphhopper.util.shapes.GHPoint
import com.organicmoto.maps.geocoding.GeocodeResult
import com.organicmoto.maps.geocoding.GeocodeSearchController
import com.organicmoto.maps.map.clearRoutes
import com.organicmoto.maps.map.drawRoutes
import com.organicmoto.maps.map.findRouteIndexAt
import com.organicmoto.maps.map.fitBounds
import com.organicmoto.maps.map.routeColorHex
import com.organicmoto.maps.routing.GraphHopperRouter
import com.organicmoto.maps.routing.PointParser
import com.organicmoto.maps.routing.RouteResult
import com.organicmoto.maps.tiles.OfflineTileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.maps.MapView
import kotlin.coroutines.cancellation.CancellationException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.sin
import kotlin.math.roundToInt

private const val TAG = "OrganicMoto.RouteScreen"
private const val STYLE_ASSET = "style.json"
private const val TILES_PATH_PLACEHOLDER = "{tiles_path}"
private const val ROUTE_PREFS = "route_preferences"
private const val ROAD_SHARE_PREF = "max_road_share_percent"
private const val BLOCK_UNPAVED_PREF = "block_unpaved_roads"
private const val DEFAULT_ROAD_SHARE = 70f

/**
 * True for debuggable (debug) builds. Release APKs are never debuggable, so
 * user-typed queries, source/destination text and coordinates are only logged
 * when this is true (debug builds), never in release.
 */
private fun isDebugBuild(context: Context): Boolean =
    (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

private suspend fun loadOfflineStyle(context: Context): String {
    val tilesUrl = OfflineTileStore.ensureReady(context)
    val style = withContext(Dispatchers.IO) {
        context.assets.open(STYLE_ASSET).bufferedReader().use { it.readText() }
    }
    return style.replace(TILES_PATH_PLACEHOLDER, tilesUrl)
}

sealed interface RouteUiState {
    data object Idle : RouteUiState
    data object Loading : RouteUiState
    data class Success(val result: RouteResult, val selectedIndex: Int = 0) : RouteUiState
    data class Error(val message: String) : RouteUiState
}

/**
 * Human-readable travel-time estimate for a routed path, e.g. "45 min",
 * "3 h 20 min". Minutes are floored so the estimate never overstates the
 * ride; sub-minute routes read "<1 min".
 *
 * The source value is [ResponsePath.getTime] — GraphHopper's summed edge
 * travel time (`weighting.calcEdgeMillis`). At every dial position this is
 * real-world travel time, not weighting units: the complexity weighting and the
 * pure Moto path both use the motorcycle model's effective speeds, converted
 * correctly from km/h to seconds by the weighting implementation.
 */
internal fun formatRouteDuration(timeMillis: Long): String {
    val totalMinutes = timeMillis / 60_000
    val hours = totalMinutes / 60
    val minutes = (totalMinutes % 60).toInt()
    return when {
        hours > 0 -> "$hours h $minutes min"
        totalMinutes > 0 -> "$totalMinutes min"
        else -> "<1 min"
    }
}

/**
 * Human-readable route distance, e.g. "850 m", "4.7 km", "1,675 km".
 * Whole metres below 1 km, one decimal up to 10 km, grouped whole kilometres
 * above (one decimal on a 1,700 km ride is noise).
 */
internal fun formatRouteDistance(distanceMeters: Double): String = when {
    distanceMeters >= 10_000 ->
        "%,.0f km".format(Locale.US, distanceMeters / 1000.0)
    distanceMeters >= 1_000 ->
        "%.1f km".format(Locale.US, distanceMeters / 1000.0)
    else ->
        "${distanceMeters.toInt()} m"
}

/**
 * Resolves free-typed field text to a point: the top offline-geocoder hit
 * first (covers place names AND "lat,lon" — SearchEngine returns a COORDINATE
 * result for coordinates), then the lat,lon parser as a last resort.
 */
private suspend fun resolvePoint(
    text: String,
    controller: GeocodeSearchController,
    field: String,
    debugLogs: Boolean,
): GHPoint {
    val trimmed = text.trim()
    require(trimmed.isNotEmpty()) { "$field is empty — enter a place name or lat,lon" }
    if (debugLogs) Log.d(TAG, "[$field] resolving \"$trimmed\"")
    val top = try {
        controller.search(trimmed, limit = 1).firstOrNull()
    } catch (e: CancellationException) {
        throw e // never swallow coroutine cancellation (screen left / scope cancelled)
    } catch (e: Exception) {
        if (debugLogs) Log.w(TAG, "[$field] geocoder lookup failed for \"$trimmed\" — falling back to lat,lon parser", e)
        null // index unavailable: fall through to the coordinate parser
    }
    top?.let {
        if (debugLogs) Log.d(TAG, "[$field] geocoder hit: \"${it.name}\" (${it.type}) lat=${it.lat} lon=${it.lon}")
        return GHPoint(it.lat, it.lon)
    }
    return try {
        PointParser.parse(trimmed).also {
            if (debugLogs) Log.d(TAG, "[$field] parsed lat,lon fallback: lat=${it.lat} lon=${it.lon}")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw IllegalArgumentException(
            "No match for \"$trimmed\" — pick a suggestion or enter lat,lon", e
        )
    }
}

@Composable
fun RouteScreen() {
    val context = LocalContext.current
    val router = remember { GraphHopperRouter(context.applicationContext) }
    val routeState = remember { MutableStateFlow<RouteUiState>(RouteUiState.Idle) }
    val state by routeState.collectAsState()
    val scope = rememberCoroutineScope()
    var fromText by remember { mutableStateOf("") }
    var toText by remember { mutableStateOf("") }
    var fromPoint by remember { mutableStateOf<GHPoint?>(null) }
    var toPoint by remember { mutableStateOf<GHPoint?>(null) }
    // Ride-complexity dial levels: 0 = Fastest, each click adds one level
    // (+1.0 of motorcycle/curve preference). Eight clicks per revolution;
    // winding past the eighth level keeps counting (9, 10, ...). The dial
    // has a hard minimum at zero and no maximum.
    var complexity by remember { mutableStateOf(0f) }
    val routePreferences = remember {
        context.applicationContext.getSharedPreferences(ROUTE_PREFS, Context.MODE_PRIVATE)
    }
    var maxRoadShare by remember {
        mutableStateOf(
            routePreferences.getFloat(ROAD_SHARE_PREF, DEFAULT_ROAD_SHARE).coerceIn(10f, 90f)
        )
    }
    var blockUnpaved by remember {
        mutableStateOf(routePreferences.getBoolean(BLOCK_UNPAVED_PREF, false))
    }
    var routeSettingsOpen by remember { mutableStateOf(false) }
    // Resolved endpoints of the last SUCCESSFUL route — the only thing a
    // dial-triggered re-route may use (never re-geocodes/re-parses). Nulled
    // whenever either field changes so a stale release can't route to
    // edited-away points.
    var lastPoints by remember { mutableStateOf<Pair<GHPoint, GHPoint>?>(null) }
    // Handle on the in-flight route coroutine, so a dial release (or a new
    // START) cancels-and-restarts instead of stacking competing routes.
    var routeJob by remember { mutableStateOf<Job?>(null) }
    val geocodeController = remember { GeocodeSearchController(context.applicationContext) }
    val debugLogs = remember { isDebugBuild(context.applicationContext) }
    val mapRef = remember { mutableStateOf<MapLibreMap?>(null) }

    val selectRoute: (Int) -> Unit = { index ->
        val current = routeState.value
        if (current is RouteUiState.Success && index in current.result.routes.indices) {
            if (current.selectedIndex != index) {
                routeState.value = current.copy(selectedIndex = index)
            }
        }
    }
    val latestSelectRoute by rememberUpdatedState(selectRoute)
    val routeHitRadiusPx = with(LocalDensity.current) { 24.dp.toPx() }

    DisposableEffect(mapRef.value) {
        val map = mapRef.value ?: return@DisposableEffect onDispose { }
        val listener = MapLibreMap.OnMapClickListener { point ->
            val screenPoint = runCatching { map.projection.toScreenLocation(point) }.getOrNull()
            val routeIndex = screenPoint?.let { map.findRouteIndexAt(it, routeHitRadiusPx) }
            val current = routeState.value
            if (routeIndex != null && current is RouteUiState.Success &&
                routeIndex in current.result.routes.indices
            ) {
                latestSelectRoute(routeIndex)
                true
            } else {
                false
            }
        }
        map.addOnMapClickListener(listener)
        onDispose { map.removeOnMapClickListener(listener) }
    }

    var styleJson by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            styleJson = loadOfflineStyle(context.applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Offline map assets failed to load", e)
        }
    }

    val mapView = rememberMapView(context) { mapRef.value = it }

    LaunchedEffect(styleJson, mapRef.value) {
        val json = styleJson ?: return@LaunchedEffect
        val map = mapRef.value ?: return@LaunchedEffect
        map.setStyle(Style.Builder().fromJson(json))
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(-23.0, 145.5), 4.5))
    }

    val routeResult = (state as? RouteUiState.Success)?.result
    val selectedIndex = (state as? RouteUiState.Success)?.selectedIndex ?: 0

    LaunchedEffect(routeResult, styleJson, mapRef.value) {
        val map = mapRef.value ?: return@LaunchedEffect
        if (routeResult == null) {
            map.clearRoutes()
            return@LaunchedEffect
        }
        Log.d(TAG, "Drawing ${routeResult.routes.size} route(s)")
        map.drawRoutes(routeResult.routes, selectedIndex)
        map.fitBounds(routeResult.routes)
        Log.d(TAG, "Route drawn and camera fitted")
    }

    LaunchedEffect(selectedIndex) {
        val map = mapRef.value ?: return@LaunchedEffect
        routeResult?.let { map.drawRoutes(it.routes, selectedIndex) }
    }

    /**
     * Runs a route [from]→[to] with unbounded [complexityValue] >= 0, with
     * cancel-and-restart semantics: any in-flight route job is cancelled before
     * the new one starts. [onSuccess], when given, remembers the resolved
     * endpoints for dial-triggered re-routes.
     */
    fun submitRoute(
        from: GHPoint,
        to: GHPoint,
        complexityValue: Float,
        roadSharePercent: Float = maxRoadShare,
        blockUnpavedRoads: Boolean = blockUnpaved,
        onSuccess: (() -> Unit)? = null,
    ) {
        routeJob?.cancel()
        routeJob = scope.launch {
            Log.i(TAG, "Route submitted (complexity $complexityValue)")
            if (debugLogs) {
                Log.i(TAG, "Route submitted: from=\"$fromText\" to=\"$toText\"")
            } else {
                Log.i(TAG, "Route submitted")
            }
            val started = SystemClock.elapsedRealtime()
            routeState.value = RouteUiState.Loading
            routeState.value = withContext(Dispatchers.IO) {
                try {
                    val result = router.route(
                        from,
                        to,
                        complexityValue.toDouble(),
                        roadSharePercent.toDouble() / 100.0,
                        blockUnpavedRoads,
                    )
                    val primary = result.routes.first()
                    Log.i(
                        TAG,
                        "Route success in ${SystemClock.elapsedRealtime() - started} ms: " +
                            "routes=${result.routes.size}, ${primary.distance}m, ${primary.time}ms, " +
                            "${primary.points.size()} points, eta=${formatRouteDuration(primary.time)}"
                    )
                    RouteUiState.Success(result)
                } catch (e: CancellationException) {
                    throw e // preserve coroutine cancellation (scope/screen gone)
                } catch (e: Exception) {
                    // The throwable chain can embed user-typed text (e.g.
                    // resolvePoint's "No match for \"...\"" error), so log
                    // the full chain only in debug builds.
                    if (debugLogs) {
                        Log.e(TAG, "Route request failed after ${SystemClock.elapsedRealtime() - started} ms", e)
                    } else {
                        Log.e(TAG, "Route request failed after ${SystemClock.elapsedRealtime() - started} ms")
                    }
                    RouteUiState.Error(e.message ?: "Routing failed")
                }
            }
            // Record the resolved endpoints only for a route that actually
            // succeeded; cancelled/failed attempts leave lastPoints untouched.
            if (routeState.value is RouteUiState.Success) onSuccess?.invoke()
        }
    }

    val onRoute: () -> Unit = {
        scope.launch {
            // Resolve the endpoints exactly as before, but eagerly so the
            // resolved pair can be remembered for dial re-routes. A failure
            // here (no match / bad input) surfaces as the usual Error state
            // instead of crashing the coroutine.
            val resolvedFrom: GHPoint
            val resolvedTo: GHPoint
            try {
                resolvedFrom = withContext(Dispatchers.IO) {
                    fromPoint ?: resolvePoint(fromText, geocodeController, "From", debugLogs)
                }
                resolvedTo = withContext(Dispatchers.IO) {
                    toPoint ?: resolvePoint(toText, geocodeController, "To", debugLogs)
                }
            } catch (e: CancellationException) {
                throw e // never swallow coroutine cancellation (scope/screen gone)
            } catch (e: Exception) {
                // The throwable chain can embed user-typed text ("No match for
                // \"...\""), so log the full chain only in debug builds.
                if (debugLogs) {
                    Log.e(TAG, "Point resolution failed", e)
                } else {
                    Log.e(TAG, "Point resolution failed")
                }
                routeState.value = RouteUiState.Error(e.message ?: "Routing failed")
                return@launch
            }
            submitRoute(resolvedFrom, resolvedTo, complexity, maxRoadShare, blockUnpaved) {
                lastPoints = resolvedFrom to resolvedTo
            }
        }
    }

    // Map above, panel below: the map is exactly the region the route menu
    // does not cover, so the two never overlap and the menu cannot slide
    // around over the map.
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            Text(
                text = "© OpenMapTiles.org © OpenStreetMap contributors · Noto (OFL) · icons CC BY 4.0",
                color = Color.Black,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 4.dp, end = 64.dp, bottom = 4.dp)
                    .background(Color.White.copy(alpha = 0.78f), RoundedCornerShape(2.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
            ZoomPill(
                onZoomIn = { mapRef.value?.animateCamera(CameraUpdateFactory.zoomIn(), 250) },
                onZoomOut = { mapRef.value?.animateCamera(CameraUpdateFactory.zoomOut(), 250) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = 8.dp)
            )
        }
        RoutePlanPanel(
            fromText = fromText,
            onFromChange = { fromText = it; fromPoint = null; lastPoints = null },
            onFromPicked = { result ->
                fromText =
                    if (result.subtitle.isNotBlank()) "${result.name}, ${result.subtitle}" else result.name
                fromPoint = GHPoint(result.lat, result.lon)
                lastPoints = null
            },
            toText = toText,
            onToChange = { toText = it; toPoint = null; lastPoints = null },
            onToPicked = { result ->
                toText =
                    if (result.subtitle.isNotBlank()) "${result.name}, ${result.subtitle}" else result.name
                toPoint = GHPoint(result.lat, result.lon)
                lastPoints = null
            },
            geocodeController = geocodeController,
            state = state,
            onSelectRoute = latestSelectRoute,
            onRoute = onRoute,
            complexity = complexity,
            onComplexityChange = { complexity = it },
            onRouteSettings = { routeSettingsOpen = true },
            onComplexityChangeFinished = { finalComplexity ->
                // Re-route only when the knob is released, not for every
                // drag event. Use the gesture's final value directly so a
                // release cannot race the next Compose state frame.
                complexity = finalComplexity
                Log.i(TAG, "Ride complexity dial released: $finalComplexity")
                lastPoints?.let {
                    submitRoute(
                        it.first,
                        it.second,
                        finalComplexity,
                        maxRoadShare,
                        blockUnpaved,
                    )
                }
            }
        )
    }

    if (routeSettingsOpen) {
        RouteSettingsDialog(
            currentPercent = maxRoadShare,
            blockUnpaved = blockUnpaved,
            onDismiss = { routeSettingsOpen = false },
            onApply = { percent, shouldBlockUnpaved ->
                maxRoadShare = percent
                blockUnpaved = shouldBlockUnpaved
                routePreferences.edit()
                    .putFloat(ROAD_SHARE_PREF, percent)
                    .putBoolean(BLOCK_UNPAVED_PREF, shouldBlockUnpaved)
                    .apply()
                routeSettingsOpen = false
                lastPoints?.let {
                    submitRoute(
                        it.first,
                        it.second,
                        complexity,
                        percent,
                        shouldBlockUnpaved,
                    )
                }
            },
        )
    }
}

private fun complexityLabel(value: Float): String =
    if (value < 0.5f) "Fastest" else "Curvy route ${value.roundToInt()}"

/** Clicks per knob revolution; one click = one level = +1.0 complexity. */
private const val KNOB_LEVELS_PER_REVOLUTION = 8

/**
 * An endless click-stop rotary control: [KNOB_LEVELS_PER_REVOLUTION] detent
 * clicks per revolution, each clockwise click adding one complexity level
 * (+1.0). Winding past the eighth level keeps counting into the next
 * revolution (9, 10, ...); counter-clockwise rotation stops hard at zero.
 * Every crossed detent fires a short haptic tick, and release snaps to the
 * nearest level with a small spring settle.
 */
@Composable
private fun InfiniteComplexityKnob(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (Float) -> Unit,
) {
    val latestValue by rememberUpdatedState(value)
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val latestOnFinished by rememberUpdatedState(onValueChangeFinished)
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    // The painted angle chases the committed value: exact finger tracking
    // while dragging (snapTo), then a short mechanical bounce onto the
    // clicked detent after release (animateTo).
    val paintedLevels = remember { Animatable(value) }
    Canvas(
        modifier = Modifier
            .size(92.dp)
            .semantics {
                contentDescription = "Ride complexity level ${value.roundToInt()}"
            }
            .pointerInput(Unit) {
                var previousAngle = 0f
                var gestureValue = 0f
                fun hapticTick(level: Int) {
                    // CLOCK_TICK is the platform's rotary-detent click; the
                    // Fastest end stop gets the firmer VIRTUAL_KEY pulse.
                    view.performHapticFeedback(
                        if (level <= 0) HapticFeedbackConstants.VIRTUAL_KEY
                        else HapticFeedbackConstants.CLOCK_TICK,
                    )
                }
                detectDragGestures(
                    onDragStart = { position ->
                        previousAngle = atan2(
                            position.y - size.height / 2f,
                            position.x - size.width / 2f,
                        )
                        gestureValue = latestValue
                        scope.launch { paintedLevels.snapTo(gestureValue) }
                    },
                    onDragEnd = {
                        val snapped = gestureValue.roundToInt()
                        if (snapped != gestureValue.toInt()) {
                            // Release crossed one more detent than the drag
                            // ticks announced: click that detent too.
                            hapticTick(snapped)
                        }
                        gestureValue = snapped.toFloat()
                        latestOnValueChange(gestureValue)
                        latestOnFinished(gestureValue)
                        scope.launch {
                            paintedLevels.animateTo(
                                snapped.toFloat(),
                                spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium,
                                ),
                            )
                        }
                    },
                    onDragCancel = {},
                    onDrag = { change, _ ->
                        val angle = atan2(
                            change.position.y - size.height / 2f,
                            change.position.x - size.width / 2f,
                        )
                        var delta = angle - previousAngle
                        if (delta > PI) delta -= (2.0 * PI).toFloat()
                        if (delta < -PI) delta += (2.0 * PI).toFloat()
                        previousAngle = angle
                        val levelsPerRadian =
                            KNOB_LEVELS_PER_REVOLUTION / (2.0 * PI).toFloat()
                        val newValue = (gestureValue + delta * levelsPerRadian)
                            .coerceAtLeast(0f)
                        if (newValue.toInt() != gestureValue.toInt()) {
                            hapticTick(newValue.toInt())
                        }
                        gestureValue = newValue
                        latestOnValueChange(gestureValue)
                        scope.launch { paintedLevels.snapTo(newValue) }
                        change.consume()
                    },
                )
            }
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val outerRadius = size.minDimension * 0.47f
        val knobRadius = size.minDimension * 0.34f

        drawCircle(Color(0x18000000), outerRadius, center + Offset(0f, 2.dp.toPx()))
        drawCircle(Color(0xFFE7E7E7), outerRadius, center)
        repeat(KNOB_LEVELS_PER_REVOLUTION) { index ->
            val angle = Math.toRadians(-135.0 + index * 45.0)
            val dotCenter = center + Offset(
                (cos(angle) * outerRadius * 0.84).toFloat(),
                (sin(angle) * outerRadius * 0.84).toFloat(),
            )
            val level = value.roundToInt().coerceAtLeast(0)
            val activeDot = ((level % KNOB_LEVELS_PER_REVOLUTION) +
                KNOB_LEVELS_PER_REVOLUTION) % KNOB_LEVELS_PER_REVOLUTION
            drawCircle(
                color = when {
                    index == 0 && level <= 0 -> Color(0xFF249CF2)
                    index == activeDot && level > 0 -> Color(0xFF249CF2)
                    else -> Color(0xFF8A8A8A)
                },
                radius = 2.dp.toPx(),
                center = dotCenter,
            )
        }
        drawCircle(Color(0x22000000), knobRadius + 2.dp.toPx(), center + Offset(0f, 2.dp.toPx()))
        drawCircle(Color(0xFFF7F7F7), knobRadius, center)
        drawCircle(
            Color(0xFFE0E0E0),
            knobRadius,
            center,
            style = Stroke(width = 1.dp.toPx()),
        )

        val indicatorAngle = Math.toRadians(
            -135.0 + paintedLevels.value.toDouble() * (360.0 / KNOB_LEVELS_PER_REVOLUTION),
        )
        val indicatorStart = center + Offset(
            (cos(indicatorAngle) * knobRadius * 0.48).toFloat(),
            (sin(indicatorAngle) * knobRadius * 0.48).toFloat(),
        )
        val indicatorEnd = center + Offset(
            (cos(indicatorAngle) * knobRadius * 0.78).toFloat(),
            (sin(indicatorAngle) * knobRadius * 0.78).toFloat(),
        )
        drawLine(
            color = Color(0xFF249CF2),
            start = indicatorStart,
            end = indicatorEnd,
            strokeWidth = 4.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun SettingsCogIcon() {
    Canvas(Modifier.size(20.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        repeat(8) { index ->
            val angle = Math.toRadians(index * 45.0)
            drawLine(
                color = Color(0xFF616161),
                start = center + Offset(
                    (cos(angle) * size.minDimension * 0.28).toFloat(),
                    (sin(angle) * size.minDimension * 0.28).toFloat(),
                ),
                end = center + Offset(
                    (cos(angle) * size.minDimension * 0.43).toFloat(),
                    (sin(angle) * size.minDimension * 0.43).toFloat(),
                ),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        drawCircle(
            color = Color(0xFF616161),
            radius = size.minDimension * 0.29f,
            center = center,
            style = Stroke(width = 2.5.dp.toPx()),
        )
        drawCircle(Color(0xFF616161), size.minDimension * 0.08f, center)
    }
}

@Composable
private fun RouteSettingsDialog(
    currentPercent: Float,
    blockUnpaved: Boolean,
    onDismiss: () -> Unit,
    onApply: (Float, Boolean) -> Unit,
) {
    var draftPercent by remember(currentPercent) { mutableStateOf(currentPercent) }
    var draftBlockUnpaved by remember(blockUnpaved) { mutableStateOf(blockUnpaved) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Route settings") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "Target maximum shared roads",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF8A000000),
                )
                Text(
                    "${draftPercent.roundToInt()}%",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                ) {
                    RoadShareKnob(
                        value = draftPercent,
                        onValueChange = { draftPercent = it },
                    )
                }
                Text(
                    "Lower values ask routes to use more different roads. " +
                        "Higher values allow more overlap. If the road network cannot meet the target, " +
                            "the most distinct sensible route is still shown.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8A000000),
                )
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Block unpaved roads",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "Exclude roads tagged as unpaved, gravel, dirt, ground, grass, or sand.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF8A000000),
                        )
                    }
                    Switch(
                        checked = draftBlockUnpaved,
                        onCheckedChange = { draftBlockUnpaved = it },
                        modifier = Modifier.semantics {
                            contentDescription = "Block unpaved roads"
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(draftPercent, draftBlockUnpaved) }) { Text("APPLY") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL") }
        },
    )
}

/** Finite 10–90% dial with tactile-style 5% visual detents. */
@Composable
private fun RoadShareKnob(
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    Canvas(
        modifier = Modifier
            .size(150.dp)
            .semantics {
                contentDescription = "Maximum shared roads ${value.roundToInt()} percent"
            }
            .pointerInput(Unit) {
                fun updateFrom(position: Offset) {
                    var degrees = Math.toDegrees(
                        atan2(
                            position.y - size.height / 2f,
                            position.x - size.width / 2f,
                        ).toDouble()
                    )
                    if (degrees < 0.0) degrees += 360.0
                    var sweep = (degrees - 135.0 + 360.0) % 360.0
                    if (sweep > 270.0) sweep = if (sweep < 315.0) 270.0 else 0.0
                    val raw = 10f + (sweep / 270.0 * 80.0).toFloat()
                    latestOnValueChange((raw / 5f).roundToInt() * 5f)
                }
                detectDragGestures(
                    onDragStart = { updateFrom(it) },
                    onDrag = { change, _ ->
                        updateFrom(change.position)
                        change.consume()
                    },
                )
            },
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val outerRadius = size.minDimension * 0.46f
        val knobRadius = size.minDimension * 0.31f
        val fraction = ((value.coerceIn(10f, 90f) - 10f) / 80f)

        drawCircle(Color(0x16000000), outerRadius, center + Offset(0f, 2.dp.toPx()))
        drawCircle(Color(0xFFEAEAEA), outerRadius, center)
        repeat(17) { index ->
            val angle = Math.toRadians(135.0 + index * (270.0 / 16.0))
            val dot = center + Offset(
                (cos(angle) * outerRadius * 0.84).toFloat(),
                (sin(angle) * outerRadius * 0.84).toFloat(),
            )
            drawCircle(
                color = if (index <= (fraction * 16f).roundToInt()) {
                    Color(0xFF249CF2)
                } else {
                    Color(0xFF929292)
                },
                radius = if (index % 2 == 0) 2.4.dp.toPx() else 1.7.dp.toPx(),
                center = dot,
            )
        }
        drawCircle(Color(0x22000000), knobRadius + 2.dp.toPx(), center + Offset(0f, 2.dp.toPx()))
        drawCircle(Color(0xFFF9F9F9), knobRadius, center)
        drawCircle(
            Color(0xFFD9D9D9),
            knobRadius,
            center,
            style = Stroke(width = 1.dp.toPx()),
        )
        val indicatorAngle = Math.toRadians(135.0 + fraction * 270.0)
        drawLine(
            color = Color(0xFF249CF2),
            start = center + Offset(
                (cos(indicatorAngle) * knobRadius * 0.45).toFloat(),
                (sin(indicatorAngle) * knobRadius * 0.45).toFloat(),
            ),
            end = center + Offset(
                (cos(indicatorAngle) * knobRadius * 0.78).toFloat(),
                (sin(indicatorAngle) * knobRadius * 0.78).toFloat(),
            ),
            strokeWidth = 5.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/** Card width inside the swipeable route bar; edge padding centers the active card. */
private val ROUTE_CARD_WIDTH = 220.dp
private val ROUTE_CARD_HEIGHT = 46.dp

/** 50 dp From/To row inside the panel (see RoutePlanSearchField). */
private val SEARCH_FIELD_ROW_HEIGHT = 50.dp

/**
 * Fixed panel-frame slot heights. Every panel state — idle, typing, route
 * success, error — is built from exactly these slots, so the panel's total
 * height, and with it the map viewport above, never changes with state.
 */
private val ROUTE_STATUS_HEIGHT = 40.dp

/** Knob row (6 + 100 + 6 dp) plus the fixed status row. */
private val RIDE_CONTROLS_HEIGHT = 112.dp + ROUTE_STATUS_HEIGHT

/** Reserved carousel slot; equals the pager height in RouteCarouselBar. */
private val CAROUSEL_SLOT_HEIGHT = ROUTE_CARD_HEIGHT + 12.dp

/** Carousel + divider + two field rows + two dividers + ride controls + START row (2 + 40 + 10). */
private val PANEL_CONTENT_HEIGHT = CAROUSEL_SLOT_HEIGHT + RIDE_CONTROLS_HEIGHT +
    SEARCH_FIELD_ROW_HEIGHT * 2 + 52.dp + 3.dp

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

@Composable
private fun RouteCard(
    path: ResponsePath,
    routeIndex: Int,
    selected: Boolean,
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
    }
}

/**
 * Bottom route-planning panel styled after Organic Maps, with a FIXED frame:
 * the carousel slot, the 50 dp From/To rows, the ride-controls region and
 * the START row keep constant heights in every state, so the panel's top
 * edge and every control sit at one screen position. Suggestions render
 * inside the ride-controls region (replacing the knob + status rows while
 * typing) and the carousel slot stays reserved while no route exists, so
 * nothing outside the ride-controls region ever moves. The map viewport
 * ends where this panel begins.
 */
@Composable
private fun RoutePlanPanel(
    fromText: String,
    onFromChange: (String) -> Unit,
    onFromPicked: (GeocodeResult) -> Unit,
    toText: String,
    onToChange: (String) -> Unit,
    onToPicked: (GeocodeResult) -> Unit,
    geocodeController: GeocodeSearchController,
    state: RouteUiState,
    onSelectRoute: (Int) -> Unit,
    onRoute: () -> Unit,
    complexity: Float,
    onComplexityChange: (Float) -> Unit,
    onComplexityChangeFinished: (Float) -> Unit,
    onRouteSettings: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val fromSearch = rememberRouteFieldSearchState()
    val toSearch = rememberRouteFieldSearchState()
    Surface(
        color = Color(0xFFF5F5F5),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = PANEL_CONTENT_HEIGHT)
                // One combined bottom inset: nav-bar height while the
                // keyboard is closed, keyboard height while it is open (the
                // IME inset already spans the nav-bar area). The panel
                // bottom always sits exactly on whatever is below it — no
                // gap above the keyboard, no dead strip above the nav bar.
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
        ) {
            // Carousel slot, always reserved: route cards appear here on
            // success without moving the fields below.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(CAROUSEL_SLOT_HEIGHT)
            ) {
                (state as? RouteUiState.Success)?.takeIf { it.result.routes.isNotEmpty() }?.let { success ->
                    RouteCarouselBar(
                        routes = success.result.routes,
                        selectedIndex = success.selectedIndex,
                        onSelectRoute = onSelectRoute,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            HorizontalDivider(color = Color(0x1E000000), thickness = 1.dp)
            RoutePlanSearchField(
                label = "From",
                hint = "Route from",
                icon = { StartDotIcon() },
                value = fromText,
                onValueChange = onFromChange,
                onResultPicked = onFromPicked,
                controller = geocodeController,
                searchState = fromSearch,
                modifier = Modifier.fillMaxWidth()
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
                modifier = Modifier.fillMaxWidth()
            )
            HorizontalDivider(
                color = Color(0xFF1E000000),
                thickness = 1.dp,
                modifier = Modifier.padding(start = 40.dp)
            )
            // Fixed ride-controls region: while a field is searching, its
            // suggestions replace the knob and status rows inside this box;
            // nothing outside the box ever moves.
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = RIDE_CONTROLS_HEIGHT)
            ) {
                val activeSearch = fromSearch.takeIf { it.active } ?: toSearch.takeIf { it.active }
                if (activeSearch != null) {
                    RoutePlanSearchResults(
                        state = activeSearch,
                        maxHeight = RIDE_CONTROLS_HEIGHT,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
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
                            Box(Modifier.size(width = 108.dp, height = 100.dp)) {
                                InfiniteComplexityKnob(
                                    value = complexity,
                                    onValueChange = onComplexityChange,
                                    onValueChangeFinished = onComplexityChangeFinished,
                                )
                                IconButton(
                                    onClick = onRouteSettings,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(32.dp)
                                        .semantics { contentDescription = "Route settings" },
                                ) {
                                    SettingsCogIcon()
                                }
                            }
                        }
                        RouteStatusSlot(state)
                    }
                }
            }
            Button(
                onClick = {
                    // Dismiss any inline results and the keyboard before routing.
                    focusManager.clearFocus()
                    onRoute()
                },
                enabled = state !is RouteUiState.Loading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF249CF2),
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFF9ECDF5),
                    disabledContentColor = Color(0xFFE0E0E0)
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp)
                    .heightIn(min = 36.dp)
            ) {
                Text("START", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
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

/**
 * Vertical zoom-in/zoom-out capsule on the right edge of the screen, sitting
 * just above the route-planning panel: two 48 dp cells (+, −) separated by a
 * 1 dp divider, with a subtle pressed-state background dim.
 */
@Composable
private fun ZoomPill(
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .size(width = 48.dp, height = 96.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White)
    ) {
        ZoomPillCell(plus = true, onClick = onZoomIn)
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0xFF1E000000))
        )
        ZoomPillCell(plus = false, onClick = onZoomOut)
    }
}

@Composable
private fun ZoomPillCell(plus: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(if (pressed) Color(0xFFF5F5F5) else Color.White)
            .clickable(interactionSource = interaction, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(20.dp)) {
            val stroke = 2.5.dp.toPx()
            val half = 10.dp.toPx()
            val lineColor = Color(0xFF8A000000)
            if (plus) {
                drawLine(
                    color = lineColor,
                    start = Offset(center.x, center.y - half),
                    end = Offset(center.x, center.y + half),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }
            drawLine(
                color = lineColor,
                start = Offset(center.x - half, center.y),
                end = Offset(center.x + half, center.y),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}

/** Route start marker: filled #1E96F0 dot with a small white inner circle. */
@Composable
private fun StartDotIcon() {
    Canvas(Modifier.size(24.dp)) {
        drawCircle(color = Color(0xFF1E96F0), radius = size.minDimension / 2f, center = center)
        drawCircle(color = Color.White, radius = 5.5.dp.toPx(), center = center)
    }
}

/** Route finish marker: white checkered flag (~20x16dp) with a dark outline. */
@Composable
private fun FinishFlagIcon() {
    Canvas(Modifier.size(24.dp)) {
        val left = 2.dp.toPx()
        val top = 4.dp.toPx()
        val flagWidth = 20.dp.toPx()
        val flagHeight = 16.dp.toPx()
        val cols = 4
        val rows = 3
        val cellW = flagWidth / cols
        val cellH = flagHeight / rows
        // White flag body.
        drawRect(
            color = Color.White,
            topLeft = Offset(left, top),
            size = Size(flagWidth, flagHeight)
        )
        // Checkerboard: squares where (col + row) is even are black.
        for (col in 0 until cols) {
            for (row in 0 until rows) {
                if ((col + row) % 2 == 0) {
                    drawRect(
                        color = Color(0xFFDE000000),
                        topLeft = Offset(left + col * cellW, top + row * cellH),
                        size = Size(cellW, cellH)
                    )
                }
            }
        }
        // Thin dark outline on top.
        drawRect(
            color = Color(0xFF8A000000),
            topLeft = Offset(left, top),
            size = Size(flagWidth, flagHeight),
            style = Stroke(width = 1.dp.toPx())
        )
    }
}

@Composable
private fun rememberMapView(
    context: Context,
    onMapReady: (MapLibreMap) -> Unit
): MapView {

    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { MapView(context) }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        val current = lifecycleOwner.lifecycle.currentState
        if (current.isAtLeast(Lifecycle.State.CREATED)) mapView.onCreate(null)
        if (current.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (current.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        mapView.getMapAsync { map ->
            onMapReady(map)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }
    return mapView
}
