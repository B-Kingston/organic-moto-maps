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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.organicmoto.maps.map.hideNavPosition
import com.organicmoto.maps.map.updateNavPosition
import com.organicmoto.maps.routing.navigation.GpsFix
import com.organicmoto.maps.routing.navigation.GpsFixSource
import com.organicmoto.maps.routing.navigation.NavigationState
import com.organicmoto.maps.routing.navigation.RebuildRequest
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.graphhopper.util.PointList
import com.graphhopper.util.shapes.GHPoint
import com.organicmoto.maps.geocoding.GeocodeResult
import com.organicmoto.maps.geocoding.GeocodeSearchController
import com.organicmoto.maps.geocoding.GeocodeController
import com.organicmoto.maps.map.clearRoutes
import com.organicmoto.maps.map.drawRoutes
import com.organicmoto.maps.map.findRouteIndexAt
import com.organicmoto.maps.map.fitBounds
import com.organicmoto.maps.map.routeColorHex
import com.organicmoto.maps.routing.GraphHopperRouter
import com.organicmoto.maps.routing.PointParser
import com.organicmoto.maps.storage.GeoPoint
import com.organicmoto.maps.storage.GpxParser
import com.organicmoto.maps.storage.PolylineCodec
import com.organicmoto.maps.storage.RouteSimilarity
import com.organicmoto.maps.storage.SavedRoute
import com.organicmoto.maps.storage.SavedRouteDraft
import com.organicmoto.maps.storage.SavedRouteRepository
import com.organicmoto.maps.storage.SavedRouteSummary
import com.organicmoto.maps.storage.GpxGeometry
import com.organicmoto.maps.tiles.OfflineTileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
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
private const val TAG_SAVED = "OrganicMoto.SavedRoutes"
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

/** Converts GraphHopper's point list into the storage package's vocabulary type. */
private fun PointList.toGeoPoints(): List<GeoPoint> =
    List(size()) { index -> GeoPoint(getLat(index), getLon(index)) }

private suspend fun loadOfflineStyle(context: Context): String {
    val tilesUrl = OfflineTileStore.ensureReady(context)
    val style = withContext(Dispatchers.IO) {
        context.assets.open(STYLE_ASSET).bufferedReader().use { it.readText() }
    }
    return style.replace(TILES_PATH_PLACEHOLDER, tilesUrl)
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
    val scope = rememberCoroutineScope()
    val debugLogs = remember { isDebugBuild(context.applicationContext) }
    val routeBackend: suspend (RouteParams) -> RouteOutcome = { params ->
        val started = SystemClock.elapsedRealtime()
        try {
            val result = router.route(
                params.from,
                params.to,
                params.complexity,
                params.maxRoadShare,
                params.blockUnpaved,
            )
            val primary = result.routes.first()
            Log.i(
                TAG,
                "Route success in ${SystemClock.elapsedRealtime() - started} ms: " +
                    "routes=${result.routes.size}, ${primary.distance}m, ${primary.time}ms, " +
                    "${primary.points.size()} points, eta=${formatRouteDuration(primary.time)}"
            )
            val matchedIndex = params.preferredGeometry?.let { encoded ->
                RouteSimilarity.bestMatchIndex(
                    PolylineCodec.decode(encoded),
                    result.routes.map { path -> path.points.toGeoPoints() },
                )
            }
            if (matchedIndex != null && matchedIndex > 0) {
                Log.i(TAG_SAVED, "Restored route matched alternative ${matchedIndex + 1}")
            }
            RouteOutcome.Success(result, matchedIndex ?: 0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (debugLogs) {
                Log.e(TAG, "Route request failed after ${SystemClock.elapsedRealtime() - started} ms", e)
            } else {
                Log.e(TAG, "Route request failed after ${SystemClock.elapsedRealtime() - started} ms")
            }
            RouteOutcome.Failure(e.message ?: "Routing failed")
        }
    }
    val coordinator = remember { RouteSearchCoordinator(scope, backend = routeBackend) }
    val state by coordinator.state.collectAsState()
    // Editable plan inputs survive rotation; the computed route itself does
    // not and is simply re-runnable via START.
    var fromText by rememberSaveable { mutableStateOf("") }
    var toText by rememberSaveable { mutableStateOf("") }
    var fromPoint by remember { mutableStateOf<GHPoint?>(null) }
    var toPoint by remember { mutableStateOf<GHPoint?>(null) }
    // Ride-complexity dial levels: 0 = Fastest, each click adds one level
    // (+1.0 of motorcycle/curve preference). Eight clicks per revolution;
    // winding past the eighth level keeps counting (9, 10, ...). The dial
    // has a hard minimum at zero and no maximum.
    var complexity by rememberSaveable { mutableStateOf(0f) }
    val routePreferences = remember {
        context.applicationContext.getSharedPreferences(ROUTE_PREFS, Context.MODE_PRIVATE)
    }
    var maxRoadShare by rememberSaveable {
        mutableStateOf(
            routePreferences.getFloat(ROAD_SHARE_PREF, DEFAULT_ROAD_SHARE).coerceIn(10f, 90f)
        )
    }
    var blockUnpaved by rememberSaveable {
        mutableStateOf(routePreferences.getBoolean(BLOCK_UNPAVED_PREF, false))
    }
    var routeSettingsOpen by remember { mutableStateOf(false) }
    // Endpoints of the most recent submission attempt (START / dial release /
    // settings apply / saved-route load). Set at SUBMIT time so controls stay
    // consistent while a route is in flight; nulled whenever either field
    // changes, so a stale reroute can never target edited-away points.
    var activePlan by remember { mutableStateOf<Pair<GHPoint, GHPoint>?>(null) }
    val geocodeController = remember { GeocodeSearchController(context.applicationContext) }
    // Guidance: pure engine + Android glue. One controller per screen.
    NavigationContext.appContext = context.applicationContext
    val navigationController = remember { NavigationController(scope) }
    val navSnapshot by navigationController.snapshot.collectAsState()
    var guidanceRequested by remember { mutableStateOf(false) }
    var locationPermissionGranted by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_FINE_LOCATION,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        locationPermissionGranted = granted
        Log.i(TAG, "Location permission " + if (granted) "granted" else "denied")
    }
    val mapRef = remember { mutableStateOf<MapLibreMap?>(null) }
    // Saved-route storage: repository over device SQLite plus the menu flag.
    val savedRouteRepository = remember { SavedRouteRepository(context.applicationContext) }
    var savedRoutesOpen by remember { mutableStateOf(false) }

    val selectRoute: (Int) -> Unit = coordinator::selectRoute
    val latestSelectRoute by rememberUpdatedState(selectRoute)
    val routeHitRadiusPx = with(LocalDensity.current) { 24.dp.toPx() }

    DisposableEffect(mapRef.value) {
        val map = mapRef.value ?: return@DisposableEffect onDispose { }
        val listener = MapLibreMap.OnMapClickListener { point ->
            val screenPoint = runCatching { map.projection.toScreenLocation(point) }.getOrNull()
            val routeIndex = screenPoint?.let { map.findRouteIndexAt(it, routeHitRadiusPx) }
            val current = coordinator.state.value
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
    // Always-on map tracking: a bare fix stream (no guidance engine) that
    // keeps the position dot visible whenever permission is granted. The
    var trackedFix by remember { mutableStateOf<GpsFix?>(null) }
    LaunchedEffect(locationPermissionGranted) {
        if (!locationPermissionGranted) {
            trackedFix = null
            return@LaunchedEffect
        }
        GpsFixSource.fixes(context.applicationContext).collect { trackedFix = it }
    }
    DisposableEffect(Unit) {
        onDispose { trackedFix = null }
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
    val routeCount = routeResult?.routes?.size ?: 0
    val routeStateName = when (state) {
        RouteUiState.Idle -> "idle"
        RouteUiState.Loading -> "loading"
        is RouteUiState.Success -> "success"
        is RouteUiState.Error -> "error"
    }

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

    /** Submits one route request through the generation-safe coordinator. */
    fun submitRoute(
        from: GHPoint,
        to: GHPoint,
        complexityValue: Float,
        roadSharePercent: Float = maxRoadShare,
        blockUnpavedRoads: Boolean = blockUnpaved,
        preferredGeometry: String? = null,
    ) {
        activePlan = from to to
        Log.i(TAG, "Route submitted (complexity $complexityValue)")
        if (debugLogs) {
            Log.i(TAG, "Route submitted: from=\"$fromText\" to=\"$toText\"")
        } else {
            Log.i(TAG, "Route submitted")
        }
        coordinator.submit(
            RouteParams(
                from = from,
                to = to,
                complexity = complexityValue.toDouble(),
                maxRoadShare = roadSharePercent.toDouble() / 100.0,
                blockUnpaved = blockUnpavedRoads,
                preferredGeometry = preferredGeometry,
            ),
        )
    }

    val onRoute: () -> Unit = {
        val gen = coordinator.generation
        // Snapshot the typed fields; if they change while resolution runs
        // offline, this attempt is stale and must not route or overwrite.
        val fromSnapshot = fromText
        val toSnapshot = toText
        scope.launch {
            // Resolve the endpoints eagerly so a failure (no match / bad
            // input) surfaces as the usual Error state instead of crashing.
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
                coordinator.publishError(gen, e.message ?: "Routing failed")
                return@launch
            }
            if (fromText != fromSnapshot || toText != toSnapshot || !isActive) return@launch
            // Identical endpoints make GraphHopper return a zero-edge path
            // with a single geometry point, which cannot draw or fit as a
            // line; reject it up front.
            if (samePlace(resolvedFrom, resolvedTo)) {
                coordinator.publishError(gen, "From and To are the same place")
                return@launch
            }
            submitRoute(resolvedFrom, resolvedTo, complexity, maxRoadShare, blockUnpaved)
        }
    }

    /**
     * Persists one proposed route card together with the live planning state.
     * Suspending and honest: returns whether the row actually landed in the
     * database, so the save bubble can never confirm a save that stored
     * nothing. The endpoints come from [activePlan] (the current submission),
     * never from a stale earlier route.
     */
    suspend fun saveProposedRoute(path: ResponsePath): Boolean {
        val endpoints = activePlan
        if (endpoints == null) {
            Log.w(TAG_SAVED, "Save ignored: no resolved endpoints for the current route")
            return false
        }
        return try {
            val saved = savedRouteRepository.save(
                SavedRouteDraft(
                    fromName = fromText.trim()
                        .ifBlank { "${endpoints.first.lat}, ${endpoints.first.lon}" },
                    from = GeoPoint(endpoints.first.lat, endpoints.first.lon),
                    toName = toText.trim()
                        .ifBlank { "${endpoints.second.lat}, ${endpoints.second.lon}" },
                    to = GeoPoint(endpoints.second.lat, endpoints.second.lon),
                    distanceMeters = path.distance,
                    durationMillis = path.time,
                    complexity = complexity,
                    maxRoadSharePercent = maxRoadShare,
                    blockUnpaved = blockUnpaved,
                    points = path.points.toGeoPoints(),
                )
            )
            Log.i(TAG_SAVED, "Saved route ${saved.id} (${saved.distanceMeters} m)")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG_SAVED, "Saving route failed", e)
            false
        }
    }

    /**
     * Restores a saved route into the editable planning state and routes it.
     * The loaded route behaves like a fresh plan: every control reflects the
     * stored value and can be changed to steer the route differently.
     */
    fun loadSavedRoute(saved: SavedRoute) {
        savedRoutesOpen = false
        val (from, to) = saved.endpoints
        val restoredFrom = GHPoint(from.lat, from.lon)
        val restoredTo = GHPoint(to.lat, to.lon)
        fromText = saved.fromName
        toText = saved.toName
        fromPoint = restoredFrom
        toPoint = restoredTo
        complexity = saved.complexity.coerceAtLeast(0f)
        maxRoadShare = saved.maxRoadSharePercent.coerceIn(10f, 90f)
        blockUnpaved = saved.blockUnpaved
        Log.i(TAG_SAVED, "Loading saved route ${saved.id}")
        submitRoute(
            restoredFrom,
            restoredTo,
            complexity,
            maxRoadShare,
            blockUnpaved,
            preferredGeometry = saved.geometry,
        )
    }

    /** Loads an imported GPX ride into the planner and routes it. */
    fun loadGpxRoute(name: String, points: List<GeoPoint>) {
        // A closed-loop GPX (start == end) routes to the loop antipode; the
        // full track still steers candidate selection via preferredGeometry.
        val (start, end) = GpxGeometry.routingEndpoints(points)
        val from = GHPoint(start.lat, start.lon)
        val to = GHPoint(end.lat, end.lon)
        fromText = name
        toText = ""
        fromPoint = from
        toPoint = to
        submitRoute(
            from,
            to,
            complexity,
            maxRoadShare,
            blockUnpaved,
            preferredGeometry = PolylineCodec.encode(points),
        )
    }
    // GPX import: pick a .gpx file from device storage, parse it off the UI
    // thread, then load it exactly like a saved route — endpoints from the
    // track ends, the thinned track as the preferred geometry so the router
    // re-selects the candidate closest to the imported shape, and ride mode
    // works through the normal RIDE button.
    var gpxImportError by remember { mutableStateOf<String?>(null) }
    val gpxPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val gpx = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { GpxParser.parse(it) }
                } ?: throw GpxParser.GpxParseException("File could not be opened")
                val thinned = withContext(Dispatchers.Default) { GpxGeometry.thin(gpx.points) }
                val name = gpx.name ?: "Imported GPX"
                Log.i(TAG, "GPX imported: \"$name\", ${gpx.points.size} points, " +
                    "${GpxGeometry.lengthMeters(gpx.points)} m, thinned to ${thinned.size}")
                gpxImportError = null
                loadGpxRoute(name, thinned)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "GPX import failed", e)
                gpxImportError = e.message ?: "GPX import failed"
            }
        }
    }


    // Guidance rebuild: reroute from the engine's last good position using
    // the SAME coordinator as planning, so generation/cancellation rules hold.
    val latestSubmitRoute by rememberUpdatedState(::submitRoute)
    remember(navigationController) {
        navigationController.setRebuildHandler { request: RebuildRequest ->
            val plan = activePlan
            if (plan == null) {
                Log.w(TAG, "Rebuild skipped: no active plan")
                return@setRebuildHandler
            }
            val rebuildFrom = GHPoint(request.lat, request.lon)
            // Route from the last good position to the ORIGINAL destination.
            latestSubmitRoute(rebuildFrom, plan.second, complexity, maxRoadShare, blockUnpaved, null)
        }
    }

    // Start guidance once a route exists and permission is granted; stop when
    // the plan is invalidated or the route disappears.
    LaunchedEffect(routeResult, selectedIndex, guidanceRequested, locationPermissionGranted) {
        if (!guidanceRequested || !locationPermissionGranted) {
            navigationController.stop()
            mapRef.value?.hideNavPosition()
            return@LaunchedEffect
        }
        val path = routeResult?.routes?.getOrNull(selectedIndex)
        if (path != null) {
            navigationController.start(path)
        }
    }
    DisposableEffect(Unit) {
        onDispose { navigationController.stop() }
    }

    // Follow the rider while guidance runs: the camera re-centres on the
    // snapped position and eases out as speed rises so the view ahead stays
    // readable at pace. Zoom only changes on band crossings, so the map does
    // not pulse on every fix.
    LaunchedEffect(navSnapshot) {
        val map = mapRef.value ?: return@LaunchedEffect
        val snap = navSnapshot
        if (snap.state == NavigationState.Idle || snap.lat.isNaN()) {
            map.hideNavPosition()
            return@LaunchedEffect
        }

        map.updateNavPosition(snap.lat, snap.lon, snap.bearingDeg)
        if (guidanceRequested) {
            val zoom = guidanceZoomFor(if (snap.speedMps.isNaN()) 0.0 else snap.speedMps)
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(snap.lat, snap.lon), zoom),
                600,
            )
        }
    }
    // Always-on tracking render: when guidance is idle the position dot
    // still follows the raw tracked fix (GPS bearing when available). During
    // guidance the snapshot effect below owns the dot.
    LaunchedEffect(trackedFix, guidanceRequested, navSnapshot) {
        if (guidanceRequested && navSnapshot.state != NavigationState.Idle) return@LaunchedEffect
        val map = mapRef.value ?: return@LaunchedEffect
        val fix = trackedFix
        if (fix == null || fix.lat.isNaN() || fix.lon.isNaN()) {
            map.hideNavPosition()
        } else {
            map.updateNavPosition(fix.lat, fix.lon, fix.bearingDeg)
        }
    }

    // Map above, panel below: the map is exactly the region the route menu
    // does not cover, so the two never overlap and the menu cannot slide
    // around over the map.
    Column(
        Modifier
            .fillMaxSize()
            .semantics {
                this[RouteUiStateKey] = routeStateName
                this[RouteGenerationKey] = coordinator.generation
                this[SelectedRouteKey] = selectedIndex
                this[RouteCountKey] = routeCount
            }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .semantics {
                    this[FocusedRouteIndexKey] = selectedIndex
                    this[MapRouteCountKey] = routeCount
                    this[MapReadyKey] = mapRef.value != null && styleJson != null
                }
        ) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            if (guidanceRequested && navSnapshot.state != NavigationState.Idle) {
                NavigationHud(
                    snapshot = navSnapshot,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
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
            onFromChange = {
                fromText = it; fromPoint = null
                activePlan = null; coordinator.invalidate()
            },
            onFromPicked = { result ->
                fromText =
                    if (result.subtitle.isNotBlank()) "${result.name}, ${result.subtitle}" else result.name
                fromPoint = GHPoint(result.lat, result.lon)
                activePlan = null; coordinator.invalidate()
            },
            toText = toText,
            onToChange = {
                toText = it; toPoint = null
                activePlan = null; coordinator.invalidate()
            },
            onToPicked = { result ->
                toText =
                    if (result.subtitle.isNotBlank()) "${result.name}, ${result.subtitle}" else result.name
                toPoint = GHPoint(result.lat, result.lon)
                activePlan = null; coordinator.invalidate()
            },
            geocodeController = geocodeController,
            state = state,
            onSelectRoute = latestSelectRoute,
            onRoute = onRoute,
            complexity = complexity,
            onComplexityChange = { complexity = it },
            onRouteSettings = { routeSettingsOpen = true },
            onOpenSavedRoutes = { savedRoutesOpen = true },
            onImportGpx = {
                gpxPicker.launch(
                    arrayOf("application/gpx+xml", "application/gpx", "text/xml", "application/xml", "*/*")
                )
            },
            gpxImportError = gpxImportError,
            onDismissGpxError = { gpxImportError = null },
            onSaveRoute = ::saveProposedRoute,
            onToggleGuidance = {
                if (guidanceRequested) {
                    guidanceRequested = false
                } else {
                    if (!locationPermissionGranted) {
                        permissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                    guidanceRequested = true
                }
                Log.i(TAG, "Guidance toggled: $guidanceRequested")
            },
            guidanceActive = guidanceRequested,
            guidanceAvailable = locationPermissionGranted,
            onComplexityChangeFinished = { finalComplexity ->
                // Re-route only when the knob is released, not for every
                // drag event. Use the gesture's final value directly so a
                // release cannot race the next Compose state frame.
                complexity = finalComplexity
                Log.i(TAG, "Ride complexity dial released: $finalComplexity")
                activePlan?.let {
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
                activePlan?.let {
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

    if (savedRoutesOpen) {
        SavedRoutesSheet(
            summariesProvider = { savedRouteRepository.summaries() },
            commentsProvider = { routeId -> savedRouteRepository.comments(routeId) },
            onAddComment = { routeId, text -> savedRouteRepository.addComment(routeId, text) },
            onLoadRoute = ::loadSavedRoute,
            onDeleteRoute = { saved ->
                savedRouteRepository.delete(saved.id)
                Log.i(TAG_SAVED, "Deleted saved route ${saved.id}")
            },
            onDismiss = { savedRoutesOpen = false },
        )
    }
}

/**
 * Camera zoom for the current riding speed, in discrete bands so the map
 * eases out as the rider speeds up and back in when they slow down. The
 * thresholds are deliberate: below 7 m/s (~25 km/h) riders read street
 * detail; above 31 m/s (~110 km/h) a highway corridor fits the screen.
 */
internal fun guidanceZoomFor(speedMps: Double): Double = when {
    speedMps < 7.0 -> 16.5
    speedMps < 14.0 -> 15.5
    speedMps < 22.0 -> 14.5
    speedMps < 31.0 -> 13.5
    else -> 12.5
}

/** Two resolved points within ~11 m count as the same place: routing them
 * yields a zero-edge path that cannot render as a line. */
private fun samePlace(a: GHPoint, b: GHPoint): Boolean =
    abs(a.lat - b.lat) < 1e-4 && abs(a.lon - b.lon) < 1e-4

internal fun complexityLabel(value: Float): String =
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
    modifier: Modifier = Modifier,
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
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { latestValue }.collect { committed ->
            // External value changes (saved-route restore) must move the
            // painted indicator even though no drag produced them.
            if (!dragging && paintedLevels.value != committed) {
                paintedLevels.animateTo(
                    committed,
                    spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                )
            }
        }
    }
    Canvas(
        modifier = modifier
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
                        dragging = true
                        previousAngle = atan2(
                            position.y - size.height / 2f,
                            position.x - size.width / 2f,
                        )
                        gestureValue = latestValue
                        scope.launch { paintedLevels.snapTo(gestureValue) }
                    },
                    onDragEnd = {
                        dragging = false
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
                    onDragCancel = {
                        dragging = false
                        // Interrupted gesture: settle back onto a whole detent
                        // instead of leaving a fractional level in state (no
                        // re-route fires for cancelled gestures).
                        gestureValue = gestureValue.roundToInt().toFloat()
                        latestOnValueChange(gestureValue)
                    },
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
private val ROUTE_CARD_HEIGHT = 48.dp

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

/** Carousel + divider + ride controls + START row (2 + 40 + 10), planning mode. */
private val PANEL_CONTENT_HEIGHT = CAROUSEL_SLOT_HEIGHT + RIDE_CONTROLS_HEIGHT +
    SEARCH_FIELD_ROW_HEIGHT * 2 + 52.dp + 3.dp

/** Ride mode hides the two planner field rows, so the panel shrinks by them. */
private val RIDE_PANEL_CONTENT_HEIGHT = PANEL_CONTENT_HEIGHT -
    SEARCH_FIELD_ROW_HEIGHT * 2 - 3.dp

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
 * Bottom route-planning panel styled after Organic Maps, with a FIXED frame:
 * the carousel slot, the 50 dp From/To rows, the ride-controls region and
 * the START button. Fields collapse in ride mode. The panel is anchored to
 * the bottom (no typing) and the carousel slot stays reserved while no route
 * exists, so nothing outside the ride-controls region ever moves. The map
 * viewport ends where this panel begins.
 */
@Composable
private fun RoutePlanPanel(
    fromText: String,
    onFromChange: (String) -> Unit,
    onFromPicked: (GeocodeResult) -> Unit,
    toText: String,
    onToChange: (String) -> Unit,
    onToPicked: (GeocodeResult) -> Unit,
    geocodeController: GeocodeController,
    state: RouteUiState,
    onSelectRoute: (Int) -> Unit,
    onRoute: () -> Unit,
    complexity: Float,
    onComplexityChange: (Float) -> Unit,
    onComplexityChangeFinished: (Float) -> Unit,
    onRouteSettings: () -> Unit,
    onOpenSavedRoutes: () -> Unit,
    onSaveRoute: suspend (ResponsePath) -> Boolean,
    onToggleGuidance: () -> Unit,
    guidanceActive: Boolean,
    onImportGpx: () -> Unit,
    gpxImportError: String?,
    onDismissGpxError: () -> Unit,
    guidanceAvailable: Boolean,
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
                .heightIn(min = if (guidanceActive) RIDE_PANEL_CONTENT_HEIGHT else PANEL_CONTENT_HEIGHT)
        ) {
            // In ride mode the planner fields hide: the rider does not edit
            // the plan mid-ride, and the map gets the freed screen space.
            if (!guidanceActive) {
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
            }
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
                            // 164 dp wide so the 48 dp corner buttons sit
                            // clear of the 92 dp knob circle: the buttons'
                            // inner corners stay outside the knob ring.
                            Box(Modifier.size(width = 164.dp, height = 100.dp)) {
                                IconButton(
                                    onClick = onImportGpx,
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .size(48.dp)
                                        .semantics { contentDescription = "Import GPX route" },
                                ) {
                                    ImportIcon()
                                }
                                InfiniteComplexityKnob(
                                    value = complexity,
                                    onValueChange = onComplexityChange,
                                    onValueChangeFinished = onComplexityChangeFinished,
                                    modifier = Modifier.align(Alignment.Center),
                                )
                                IconButton(
                                    onClick = onRouteSettings,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(48.dp)
                                        .semantics { contentDescription = "Route settings" },
                                ) {
                                    SettingsCogIcon()
                                }
                                IconButton(
                                    onClick = onOpenSavedRoutes,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .size(48.dp)
                                        .semantics { contentDescription = "Saved routes" },
                                ) {
                                    BookmarkIcon(filled = false)
                                }
                            }
                        }
                        RouteStatusSlot(state)
                    }
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
            .semantics { contentDescription = if (plus) "Zoom in" else "Zoom out" }
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
    MapLibre.getInstance(context)

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
