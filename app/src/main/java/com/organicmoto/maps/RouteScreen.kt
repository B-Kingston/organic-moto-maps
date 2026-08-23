package com.organicmoto.maps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
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
import com.organicmoto.maps.map.drawRoute
import com.organicmoto.maps.map.fitBounds
import com.organicmoto.maps.routing.GraphHopperRouter
import com.organicmoto.maps.routing.PointParser
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

private const val TAG = "OrganicMoto.RouteScreen"
private const val STYLE_ASSET = "style.json"
private const val TILES_PATH_PLACEHOLDER = "{tiles_path}"

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
    data class Success(val path: ResponsePath) : RouteUiState
    data class Error(val message: String) : RouteUiState
}

/**
 * Human-readable travel-time estimate for a routed path, e.g. "45 min",
 * "3 h 20 min". Minutes are floored so the estimate never overstates the
 * ride; sub-minute routes read "<1 min".
 *
 * The source value is [ResponsePath.getTime] — GraphHopper's summed edge
 * travel time (`weighting.calcEdgeMillis`). At every slider position this is
 * real-world travel time, not weighting units: the blended weighting and the
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
    // Route-blend slider position: 0.0 = "Fastest" (speed-optimised),
    // 1.0 = "Moto" (motorcycle-maximised). Defaults to 1.0 so the untouched
    // app routes exactly as before (pure motorcycle weighting via CH).
    var blend by remember { mutableStateOf(1f) }
    // Resolved endpoints of the last SUCCESSFUL route — the only thing a
    // slider-triggered re-route may use (never re-geocodes/re-parses). Nulled
    // whenever either field changes so a stale release can't route to
    // edited-away points.
    var lastPoints by remember { mutableStateOf<Pair<GHPoint, GHPoint>?>(null) }
    // Handle on the in-flight route coroutine, so a slider release (or a new
    // START) cancels-and-restarts instead of stacking competing routes.
    var routeJob by remember { mutableStateOf<Job?>(null) }
    val geocodeController = remember { GeocodeSearchController(context.applicationContext) }
    val debugLogs = remember { isDebugBuild(context.applicationContext) }
    val mapRef = remember { mutableStateOf<MapLibreMap?>(null) }
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

    LaunchedEffect(state, mapRef.value) {
        val success = state as? RouteUiState.Success ?: return@LaunchedEffect
        val map = mapRef.value ?: return@LaunchedEffect
        Log.d(TAG, "Drawing route: ${success.path.points.size()} points")
        map.drawRoute(success.path)
        map.fitBounds(success.path.points)
        Log.d(TAG, "Route drawn and camera fitted")
    }

    /**
     * Runs a route [from]→[to] with [blendValue] in [0,1], with cancel-and-
     * restart semantics: any in-flight route job is cancelled before the new
     * one starts. Keeps the Loading/Error/Success semantics of the original
     * START handler; [onSuccess], when given, runs right after a Success is
     * recorded — the START button uses it to remember the resolved endpoints
     * for slider re-routes, while slider re-routes pass none (their points are
     * already current).
     */
    fun submitRoute(
        from: GHPoint,
        to: GHPoint,
        blendValue: Float,
        onSuccess: (() -> Unit)? = null,
    ) {
        routeJob?.cancel()
        routeJob = scope.launch {
            Log.i(TAG, "Route submitted (blend $blendValue)")
            if (debugLogs) {
                Log.i(TAG, "Route submitted: from=\"$fromText\" to=\"$toText\"")
            } else {
                Log.i(TAG, "Route submitted")
            }
            val started = SystemClock.elapsedRealtime()
            routeState.value = RouteUiState.Loading
            routeState.value = withContext(Dispatchers.IO) {
                try {
                    val path = router.route(from, to, blendValue.toDouble())
                    Log.i(
                        TAG,
                        "Route success in ${SystemClock.elapsedRealtime() - started} ms: " +
                            "${path.distance}m, ${path.time}ms, ${path.points.size()} points, " +
                            "eta=${formatRouteDuration(path.time)}"
                    )
                    RouteUiState.Success(path)
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
            // resolved pair can be remembered for slider re-routes. A failure
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
            submitRoute(resolvedFrom, resolvedTo, blend) {
                lastPoints = resolvedFrom to resolvedTo
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        Text(
            text = "© OpenMapTiles.org © OpenStreetMap contributors · Noto (OFL) · icons CC BY 4.0",
            color = Color.Black,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .background(Color.White.copy(alpha = 0.78f), RoundedCornerShape(2.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .imePadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
                Spacer(Modifier.weight(1f))
                ZoomPill(
                    onZoomIn = { mapRef.value?.animateCamera(CameraUpdateFactory.zoomIn(), 250) },
                    onZoomOut = { mapRef.value?.animateCamera(CameraUpdateFactory.zoomOut(), 250) },
                    modifier = Modifier.padding(end = 8.dp, bottom = 8.dp)
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
                onRoute = onRoute,
                blend = blend,
                onBlendChange = { blend = it },
                onBlendChangeFinished = {
                    // User action: thumb released. Re-route the last resolved
                    // points with the new blend; a release mid-route cancels
                    // the in-flight job (cancel-and-restart). If no route has
                    // been drawn yet (lastPoints null), the release just
                    // leaves the value for the next START press.
                    Log.i(TAG, "Blend slider released: $blend")
                    lastPoints?.let { submitRoute(it.first, it.second, blend) }
                }
            )
        }
    }
}

/**
 * Bottom route-planning panel styled after Organic Maps: 50 dp From/To rows
 * with leading icons, dividers, a "Route style" blend slider (Fastest ↔ Moto),
 * optional error text and a full-width START button, on a #F5F5F5 surface with
 * rounded TOP corners and a subtle shadow.
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
    onRoute: () -> Unit,
    blend: Float,
    onBlendChange: (Float) -> Unit,
    onBlendChangeFinished: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    Surface(
        color = Color(0xFFF5F5F5),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            RoutePlanSearchField(
                label = "From",
                hint = "Route from",
                icon = { StartDotIcon() },
                value = fromText,
                onValueChange = onFromChange,
                onResultPicked = onFromPicked,
                controller = geocodeController,
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
                modifier = Modifier.fillMaxWidth()
            )
            HorizontalDivider(
                color = Color(0xFF1E000000),
                thickness = 1.dp,
                modifier = Modifier.padding(start = 40.dp)
            )
            // "Route style" blend slider: 0.0 = "Fastest" (speed-optimised),
            // 1.0 = "Moto" (motorcycle-maximised, the default). Releasing the
            // thumb re-routes the drawn route with the new blend. t = 1.0 is
            // the CH fast path; lower values take the flexible solver, so a
            // release cancels any in-flight route job (cancel-and-restart) —
            // the slider deliberately stays live while Loading.
            Text(
                text = "Route style",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF8A000000),
                modifier = Modifier.padding(start = 16.dp, top = 8.dp)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    text = "Fastest",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF8A000000)
                )
                Slider(
                    value = blend,
                    onValueChange = onBlendChange,
                    onValueChangeFinished = onBlendChangeFinished,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF249CF2),
                        activeTrackColor = Color(0xFF249CF2),
                        inactiveTrackColor = Color(0xFF1E000000)
                    )
                )
                Text(
                    text = "Moto",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF8A000000)
                )
            }
            // Route summary for the last successful route (set by START or a
            // slider re-route): travel-time estimate + distance. Shown in place
            // of nothing — Error takes this spot when a route fails, and both
            // clear while Loading.
            (state as? RouteUiState.Success)?.let { success ->
                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 2.dp)
                ) {
                    Text(
                        text = formatRouteDuration(success.path.time),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = formatRouteDistance(success.path.distance),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color(0xFF8A000000),
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
            }
            (state as? RouteUiState.Error)?.let {
                Text(
                    text = it.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
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
