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
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import com.organicmoto.maps.map.hideGpxPreviewMarker
import com.organicmoto.maps.map.routeColorHex
import com.organicmoto.maps.map.showGpxPreviewMarker
import com.organicmoto.maps.routing.GraphHopperRouter
import com.organicmoto.maps.routing.PointParser
import com.organicmoto.maps.storage.GeoPoint
import com.organicmoto.maps.storage.GpxParser
import com.organicmoto.maps.storage.GpxMilestone
import com.organicmoto.maps.storage.GpxPreview
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
import org.maplibre.android.camera.CameraPosition
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
                params.viaPoints,
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
    // Complete inputs of the most recent submission attempt (START / dial
    // release / settings apply / saved-route load). GPX imports carry bounded
    // shaping points here as well as their endpoints; keeping the complete
    // immutable request is what lets a later control edit re-route the same
    // imported ride instead of silently falling back to an endpoint-only ride.
    // Nulled whenever either field changes, so a stale reroute can never
    // target edited-away points.
    var activePlan by remember { mutableStateOf<RouteParams?>(null) }
    val geocodeController = remember { GeocodeSearchController(context.applicationContext) }
    // Guidance: pure engine + Android glue. One controller per screen.
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
        guidanceRequested = granted
        Log.i(TAG, "Location permission " + if (granted) "granted" else "denied")
    }
    val mapRef = remember { mutableStateOf<MapLibreMap?>(null) }
    // Saved-route storage: repository over device SQLite plus the menu flag.
    val savedRouteRepository = remember { SavedRouteRepository(context.applicationContext) }
    var savedRoutesOpen by remember { mutableStateOf(false) }
    var importedGpxName by remember { mutableStateOf<String?>(null) }
    var importedGpxMilestones by remember { mutableStateOf<List<GpxMilestone>>(emptyList()) }
    var gpxPreviewOpen by remember { mutableStateOf(false) }
    var gpxImportToken by remember { mutableStateOf(0) }

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
        GpsFixSource.fixes(context.applicationContext).collect { fix ->
            trackedFix = fix
            navigationController.onFix(fix)
        }
    }
    DisposableEffect(Unit) {
        onDispose { trackedFix = null }
    }

    // Location lock: when on, the camera re-centres on each fix but keeps
    // whatever zoom the user last set (follow zoom), so pinch or the zoom
    // pill works while locked. Tapping the crosshair again releases.
    var followMe by remember { mutableStateOf(false) }
    var followZoom by remember { mutableStateOf(FOLLOW_ZOOM) }

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
        viaPoints: List<GHPoint> = emptyList(),
    ) {
        val params = RouteParams(
            from = from,
            to = to,
            complexity = complexityValue.toDouble(),
            maxRoadShare = roadSharePercent.toDouble() / 100.0,
            blockUnpaved = blockUnpavedRoads,
            preferredGeometry = preferredGeometry,
            viaPoints = viaPoints.toList(),
        )
        activePlan = params
        Log.i(TAG, "Route submitted (complexity $complexityValue)")
        if (debugLogs) {
            Log.i(TAG, "Route submitted: from=\"$fromText\" to=\"$toText\"")
        } else {
            Log.i(TAG, "Route submitted")
        }
        coordinator.submit(params)
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
        val plan = activePlan
        if (plan == null) {
            Log.w(TAG_SAVED, "Save ignored: no resolved endpoints for the current route")
            return false
        }
        return try {
            val saved = savedRouteRepository.save(
                SavedRouteDraft(
                    fromName = fromText.trim()
                        .ifBlank { "${plan.from.lat}, ${plan.from.lon}" },
                    from = GeoPoint(plan.from.lat, plan.from.lon),
                    toName = toText.trim()
                        .ifBlank { "${plan.to.lat}, ${plan.to.lon}" },
                    to = GeoPoint(plan.to.lat, plan.to.lon),
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
        importedGpxName = null
        importedGpxMilestones = emptyList()
        gpxPreviewOpen = false
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
        val savedViaPoints = savedRouteViaPoints(saved)
        Log.i(TAG_SAVED, "Loading saved route ${saved.id}")
        submitRoute(
            restoredFrom,
            restoredTo,
            complexity,
            maxRoadShare,
            blockUnpaved,
            preferredGeometry = saved.geometry,
            viaPoints = savedViaPoints,
        )
    }

    /** Loads an imported GPX ride into the planner and routes it. */
    fun loadGpxRoute(
        name: String,
        points: List<GeoPoint>,
        waypoints: List<GeoPoint>,
        milestones: List<GpxMilestone>,
    ) {
        val routingPoints = GpxGeometry.routingPoints(points, waypoints)
        val start = routingPoints.first()
        val end = routingPoints.last()
        val from = GHPoint(start.lat, start.lon)
        val to = GHPoint(end.lat, end.lon)
        importedGpxName = name
        importedGpxMilestones = milestones
        fromText = coordinateLabel(start)
        toText = coordinateLabel(end)
        fromPoint = from
        toPoint = to
        submitRoute(
            from,
            to,
            complexity,
            maxRoadShare,
            blockUnpaved,
            preferredGeometry = PolylineCodec.encode(points),
            viaPoints = routingPoints.drop(1).dropLast(1).map { GHPoint(it.lat, it.lon) },
        )
    }
    // GPX import: parse off the UI thread, retain the detailed track for
    // matching, and route through bounded shaping points plus explicit stops.
    // This preserves full loops and gives ride mode instructions for every
    // imported leg instead of replacing the GPX with an endpoint-only route.
    var gpxImportError by remember { mutableStateOf<String?>(null) }
    val gpxPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val token = ++gpxImportToken
        scope.launch {
            try {
                val gpx = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { GpxParser.parse(it) }
                } ?: throw GpxParser.GpxParseException("File could not be opened")
                val thinned = withContext(Dispatchers.Default) { GpxGeometry.thin(gpx.points) }
                val milestones = withContext(Dispatchers.Default) { GpxPreview.milestones(gpx.points) }
                val name = gpx.name ?: "Imported GPX"
                Log.i(TAG, "GPX imported: \"$name\", ${gpx.points.size} points, " +
                    "${GpxGeometry.lengthMeters(gpx.points)} m, thinned to ${thinned.size}")
                if (token != gpxImportToken) return@launch
                gpxImportError = null
                loadGpxRoute(name, thinned, gpx.waypoints, milestones)
                // Resolve every preview stop in one offline index scan. Routing
                // starts immediately; labels fill in independently when ready.
                val places = try {
                    geocodeController.reverseGeocode(
                        milestones.map { it.point.lat to it.point.lon },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A missing/corrupt search asset must not undo a route that
                    // already imported successfully. Coordinate labels remain
                    // an honest offline fallback.
                    Log.w(TAG, "GPX milestone reverse geocoding unavailable", e)
                    emptyList()
                }
                if (token == gpxImportToken) {
                    val located = milestones.mapIndexed { index, milestone ->
                        val place = places.getOrNull(index)
                        milestone.copy(placeName = place?.name, placeDetail = place?.detail)
                    }
                    importedGpxMilestones = located
                    located.firstOrNull()?.let { fromText = milestoneEndpointLabel(it) }
                    located.lastOrNull()?.let { toText = milestoneEndpointLabel(it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (token == gpxImportToken) {
                    Log.e(TAG, "GPX import failed", e)
                    gpxImportError = e.message ?: "GPX import failed"
                }
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
            latestSubmitRoute(
                rebuildFrom,
                plan.to,
                complexity,
                maxRoadShare,
                blockUnpaved,
                null,
                emptyList(),
            )
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
    LaunchedEffect(state, navSnapshot.state, guidanceRequested) {
        if (guidanceRequested && state is RouteUiState.Error &&
            navSnapshot.state == NavigationState.Rebuilding
        ) {
            Log.w(TAG, "Guidance rebuild failed; returning to the planner")
            guidanceRequested = false
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
    // Location lock: re-centre on each fix at the user's zoom (followZoom),
    // never re-asserting a fixed level, so manual zoom works while locked.
    // Guidance already owns the camera; the lock only matters in planning
    // mode.
    LaunchedEffect(trackedFix, followMe, followZoom) {
        if (!followMe || guidanceRequested) return@LaunchedEffect
        val map = mapRef.value ?: return@LaunchedEffect
        val fix = trackedFix ?: return@LaunchedEffect
        if (fix.lat.isNaN() || fix.lon.isNaN()) return@LaunchedEffect
        map.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(fix.lat, fix.lon), followZoom),
            400,
        )
    }
    // Manual zoom (pinch or the +/- pill) while locked: adopt the new zoom
    // into followZoom so the re-centre effect keeps the user's level.
    DisposableEffect(mapRef.value) {
        val map = mapRef.value ?: return@DisposableEffect onDispose { }
        val listener = MapLibreMap.OnCameraMoveListener {
            if (followMe) {
                mapRef.value?.cameraPosition?.zoom?.let { followZoom = it }
            }
        }
        map.addOnCameraMoveListener(listener)
        onDispose { map.removeOnCameraMoveListener(listener) }
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
                    modifier = Modifier.align(Alignment.TopStart),
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
            CentreOnMeButton(
                visible = true,
                active = followMe,
                onClick = { followMe = !followMe },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = 8.dp)
            )
            if (guidanceRequested && navSnapshot.state != NavigationState.Idle) {
                NavigationDataBar(
                    snapshot = navSnapshot,
                    onEnd = {
                        guidanceRequested = false
                        // Return to planning: without this the button stays
                        // RIDE (state is still Success) and can never start
                        // a new route search.
                        coordinator.reset()
                        Log.i(TAG, "Guidance ended via END button")
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            ZoomPill(
                onZoomIn = { mapRef.value?.animateCamera(CameraUpdateFactory.zoomIn(), 250) },
                onZoomOut = { mapRef.value?.animateCamera(CameraUpdateFactory.zoomOut(), 250) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    // In ride mode the data bar occupies the bottom of the
                    // map box; lift the pill above it so they never overlap.
                    .padding(
                        end = 8.dp,
                        bottom = if (guidanceRequested && navSnapshot.state != NavigationState.Idle) {
                            148.dp
                        } else {
                            64.dp
                        },
                    )
            )
        }
        RoutePlanPanel(
            fromText = fromText,
            onFromChange = {
                importedGpxName = null; importedGpxMilestones = emptyList(); gpxImportToken++
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
                importedGpxName = null; importedGpxMilestones = emptyList(); gpxImportToken++
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
                if (locationPermissionGranted) {
                    guidanceRequested = true
                } else {
                    permissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                }
                Log.i(TAG, "Guidance toggled: $guidanceRequested")
            },
            guidanceActive = guidanceRequested,
            importedGpxName = importedGpxName,
            importedGpxMilestoneCount = importedGpxMilestones.size,
            onOpenGpxPreview = { gpxPreviewOpen = true },
            onEditImportedGpx = {
                importedGpxName = null
                importedGpxMilestones = emptyList()
                gpxImportToken++
                activePlan = null
                coordinator.invalidate()
            },
            onComplexityChangeFinished = { finalComplexity ->
                // Re-route only when the knob is released, not for every
                // drag event. Use the gesture's final value directly so a
                // release cannot race the next Compose state frame.
                complexity = finalComplexity
                Log.i(TAG, "Ride complexity dial released: $finalComplexity")
                activePlan?.let {
                    // Keep imported GPX shaping points on every dial edit so
                    // complexity changes the ride along the imported plan.
                    submitRoute(
                        it.from,
                        it.to,
                        finalComplexity,
                        maxRoadShare,
                        blockUnpaved,
                        viaPoints = it.viaPoints,
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
                    // Road-share and surface edits must re-use the same GPX
                    // legs; otherwise Apply would silently discard them.
                    submitRoute(
                        it.from,
                        it.to,
                        complexity,
                        percent,
                        shouldBlockUnpaved,
                        viaPoints = it.viaPoints,
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

    if (gpxPreviewOpen && importedGpxMilestones.isNotEmpty()) {
        GpxRoutePreviewSheet(
            routeName = importedGpxName ?: "Imported GPX",
            milestones = importedGpxMilestones,
            onFocusMilestone = { milestone ->
                val map = mapRef.value ?: return@GpxRoutePreviewSheet
                map.showGpxPreviewMarker(milestone.point.lat, milestone.point.lon)
                val bottomPadding = mapView.height * 0.35
                map.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(milestone.point.lat, milestone.point.lon))
                            .zoom(14.5)
                            .padding(0.0, 0.0, 0.0, bottomPadding)
                            .build(),
                    ),
                    350,
                )
            },
            onDismiss = {
                gpxPreviewOpen = false
                mapRef.value?.let { map ->
                    map.hideGpxPreviewMarker()
                    map.moveCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder(map.cameraPosition)
                                .padding(0.0, 0.0, 0.0, 0.0)
                                .build(),
                        ),
                    )
                    routeResult?.let { map.fitBounds(it.routes) }
                }
            },
        )
    }
}

private fun coordinateLabel(point: GeoPoint): String =
    "%.5f, %.5f".format(Locale.US, point.lat, point.lon)

private fun milestoneEndpointLabel(milestone: GpxMilestone): String =
    listOfNotNull(milestone.placeName, milestone.placeDetail?.takeIf(String::isNotBlank))
        .joinToString(", ")
        .ifBlank { coordinateLabel(milestone.point) }

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

/** Fixed camera zoom the crosshair lock uses; 12.5 keeps several blocks of
 * street context visible while following. */
private const val FOLLOW_ZOOM = 12.5

/** Two resolved points within ~11 m count as the same place: routing them
 * yields a zero-edge path that cannot render as a line. */
private fun samePlace(a: GHPoint, b: GHPoint): Boolean =
    abs(a.lat - b.lat) < 1e-4 && abs(a.lon - b.lon) < 1e-4

/** Closed saved rides need shaping points or GraphHopper sees start-to-start. */
internal fun savedRouteViaPoints(saved: SavedRoute): List<GHPoint> {
    val (from, to) = saved.endpoints
    if (!samePlace(GHPoint(from.lat, from.lon), GHPoint(to.lat, to.lon))) return emptyList()
    return GpxGeometry.routingPoints(saved.decodedPoints())
        .drop(1)
        .dropLast(1)
        .map { GHPoint(it.lat, it.lon) }
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

/**
 * 48 dp round button that locks the camera on the rider's position. While
 * active it tints blue and recentres on every fix; any manual map gesture
 * releases the lock. Sits at the bottom-right of the map, below the zoom pill.
 */
@Composable
private fun CentreOnMeButton(
    visible: Boolean,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val backgroundColor by animateColorAsState(
        targetValue = if (active) Color(0xFF249CF2) else Color.White,
        label = "followMeBackground",
    )
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(backgroundColor)
            .semantics {
                contentDescription =
                    if (active) "Following your location; tap to release" else "Centre on me"
            }
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(24.dp)) {
            // Crosshair: dot ring plus four ticks, like the repo's other
            // hand-drawn map icons.
            val stroke = 2.dp.toPx()
            val color = if (active) Color.White else Color(0xFF249CF2)
            drawCircle(color = color, radius = 5.dp.toPx(), style = Stroke(stroke))
            drawCircle(color = color, radius = 1.8.dp.toPx())
            val tick = 4.dp.toPx()
            val edge = 11.dp.toPx()
            drawLine(color, Offset(center.x, center.y - edge - tick), Offset(center.x, center.y - edge), stroke, StrokeCap.Round)
            drawLine(color, Offset(center.x, center.y + edge), Offset(center.x, center.y + edge + tick), stroke, StrokeCap.Round)
            drawLine(color, Offset(center.x - edge - tick, center.y), Offset(center.x - edge, center.y), stroke, StrokeCap.Round)
            drawLine(color, Offset(center.x + edge, center.y), Offset(center.x + edge + tick, center.y), stroke, StrokeCap.Round)
        }
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
internal fun StartDotIcon() {
    Canvas(Modifier.size(24.dp)) {
        drawCircle(color = Color(0xFF1E96F0), radius = size.minDimension / 2f, center = center)
        drawCircle(color = Color.White, radius = 5.5.dp.toPx(), center = center)
    }
}

/** Route finish marker: white checkered flag (~20x16dp) with a dark outline. */
@Composable
internal fun FinishFlagIcon() {
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
