package com.organicmoto.maps

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.organicmoto.maps.map.MapPerformanceProbe
import com.organicmoto.maps.map.MapPerfProbeGate
import com.organicmoto.maps.map.MapPerfSweepQueue
import com.organicmoto.maps.map.MapPerfSweepRunner
import com.organicmoto.maps.map.DARK_RIDE_BASEMAP_PROBE_LAYERS
import com.organicmoto.maps.map.hideNavPosition
import com.organicmoto.maps.map.updateGuidancePosition
import com.organicmoto.maps.map.updateNavPosition
import com.organicmoto.maps.routing.navigation.GpsFix
import com.organicmoto.maps.routing.navigation.GpsFixSource
import com.organicmoto.maps.routing.navigation.NavigationState
import com.organicmoto.maps.routing.navigation.RebuildRequest
import com.organicmoto.maps.routing.navigation.VoiceDistanceUnit
import com.organicmoto.maps.routing.navigation.VoiceGuidanceSettings
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.graphhopper.ResponsePath
import com.graphhopper.util.PointList
import com.graphhopper.util.shapes.GHPoint
import com.organicmoto.maps.geocoding.GeocodeResult
import com.organicmoto.maps.geocoding.GeocodeSearchController
import com.organicmoto.maps.geocoding.GeocodeController
import com.organicmoto.maps.media.AndroidMediaKeyController
import com.organicmoto.maps.media.AndroidMediaSessionGateway
import com.organicmoto.maps.media.AndroidVolumeController
import com.organicmoto.maps.media.MediaCenterController
import com.organicmoto.maps.media.MediaControlCenter
import com.organicmoto.maps.media.MediaListenerBridge
import com.organicmoto.maps.media.MediaPanelJoin
import com.organicmoto.maps.media.VolumeTarget
import com.organicmoto.maps.map.clearRoutes
import com.organicmoto.maps.map.drawRoutes
import com.organicmoto.maps.map.findRouteIndexAt
import com.organicmoto.maps.map.fitBounds
import com.organicmoto.maps.map.hideGpxPreviewMarker
import com.organicmoto.maps.map.routeColorHex
import com.organicmoto.maps.map.RouteGeometryCache
import com.organicmoto.maps.map.showGpxPreviewMarker
import com.organicmoto.maps.routing.GraphHopperRouter
import com.organicmoto.maps.routing.PointParser
import com.organicmoto.maps.region.RegionManager
import com.organicmoto.maps.storage.GeoPoint
import com.organicmoto.maps.storage.GpxParser
import com.organicmoto.maps.storage.GpxMilestone
import com.organicmoto.maps.storage.GpxPreview
import com.organicmoto.maps.storage.PolylineCodec
import com.organicmoto.maps.storage.RouteSimilarity
import com.organicmoto.maps.storage.SavedRoute
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import com.organicmoto.maps.storage.SavedRouteDraft
import com.organicmoto.maps.storage.SavedRouteRepository
import com.organicmoto.maps.storage.SavedRouteSummary
import com.organicmoto.maps.storage.GpxGeometry
import com.organicmoto.maps.tiles.OfflineTileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull
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
private const val TAG_MEDIA = "OrganicMoto.Media"
private const val TAG_MAP_PERF = "OrganicMoto.MapPerf"
private const val STYLE_ASSET = "style.json"
private const val DARK_RIDE_STYLE_ASSET = "ride-dark-style.json"
private const val TILES_PATH_PLACEHOLDER = "{tiles_path}"
private const val ROUTE_PREFS = "route_preferences"
private const val ROAD_SHARE_PREF = "max_road_share_percent"
private const val BLOCK_UNPAVED_PREF = "block_unpaved_roads"
private const val VOICE_ENABLED_PREF = "voice_guidance_enabled"
private const val VOICE_INTERVAL_METERS_PREF = "voice_guidance_interval_meters"
private const val VOICE_PREVIEW_COUNT_PREF = "voice_guidance_preview_count"
private const val DARK_RIDE_MAP_PREF = "dark_ride_map_enabled"
private const val DEFAULT_ROAD_SHARE = 70f

private data class NativeStyleLoadRequest(
    val json: String?,
    val map: MapLibreMap?,
    val screenStarted: Boolean,
    val revision: Int,
    val darkRideStyle: Boolean,
)

/**
 * What the system "Save to" dialog is being opened for. The region download,
 * an interrupted-download resume, and saving a recovered app-storage package
 * all use the same create-document picker.
 */
private sealed interface MapsDownloadAction {
    data class New(val regionId: String) : MapsDownloadAction
    data object Resume : MapsDownloadAction
    data object SaveRecovered : MapsDownloadAction
}

// While the media panel shows the local music stream, the app polls the real
// AudioManager readback at this interval so hardware volume presses (which are
// invisible to the app) still update the displayed level and limits.
private const val LOCAL_VOLUME_POLL_MILLIS = 1_000L

/**
 * True for debuggable (debug) builds. Release APKs are never debuggable, so
 * user-typed queries, source/destination text and coordinates are only logged
 * when this is true (debug builds), never in release.
 */
private fun isDebugBuild(context: Context): Boolean =
    (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

/** Unwraps the host activity so the debug probe can read its window metrics. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// The rendering backend is chosen at build time by the MapLibre artifact
// (debug: OpenGL, release: Vulkan; see app/build.gradle.kts). A runtime switch
// is not possible with these single-backend artifacts: MapLibre.getInstance
// fixes RenderingEngine's type on first call, and setCurrentType rejects the
// other backend with UnsupportedOperationException.

/** Converts GraphHopper's point list into the storage package's vocabulary type. */
private fun PointList.toGeoPoints(): List<GeoPoint> =
    List(size()) { index -> GeoPoint(getLat(index), getLon(index)) }

private suspend fun loadOfflineStyle(
    context: Context,
    tilesUrl: String,
    darkRideMode: Boolean,
): String {
    val style = withContext(Dispatchers.IO) {
        val asset = if (darkRideMode) DARK_RIDE_STYLE_ASSET else STYLE_ASSET
        context.assets.open(asset).bufferedReader().use { it.readText() }
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
    val lifecycleOwner = LocalLifecycleOwner.current
    var screenStarted by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    // Regional datasets: the active package decides the graph, geocoder, and
    // basemap. The manager is app-scoped (it survives recomposition and keeps
    // polling/downloading while the settings screen is closed).
    val regionManager = remember { RegionManager(context.applicationContext) }
    val mapsState by regionManager.state.collectAsState()
    // Debug visual tooling (tools/test/visual.py) can preview intermediate
    // Maps states; release builds never set this flow.
    val visualMapsPreview by VisualMapsPreview.state.collectAsState()
    val activeDataset by regionManager.dataset.collectAsState()
    DisposableEffect(regionManager) {
        onDispose { regionManager.close() }
    }
    val router = remember(activeDataset.installId, activeDataset.generation) {
        GraphHopperRouter(
            context.applicationContext,
            activeDataset.graphDir,
            activeDataset.copyGraphFromAssets,
        )
    }
    // The coordinator captures its backend once; rememberUpdatedState keeps a
    // dataset switch from leaving it pointed at the closed router.
    val latestRouter = rememberUpdatedState(router)
    DisposableEffect(router) {
        // The dataset this router was built from: a pending active-map deletion
        // waits for exactly this reader to be released before unlinking files.
        val releasedInstallId = activeDataset.installId
        onDispose {
            val drain = router.closeAsync()
            if (drain == null) {
                regionManager.onDatasetReadersReleased(releasedInstallId)
            } else {
                // Join the drain off the UI thread, then let the manager know
                // the graph directory is no longer in use.
                Thread(
                    {
                        runCatching { drain.join() }
                        regionManager.onDatasetReadersReleased(releasedInstallId)
                    },
                    "gh-drain",
                ).apply { isDaemon = true }.start()
            }
        }
    }
    val scope = rememberCoroutineScope()
    val debugLogs = remember { isDebugBuild(context.applicationContext) }
    val routeBackend: suspend (RouteParams) -> RouteOutcome = { params ->
        val started = SystemClock.elapsedRealtime()
        try {
            val result = latestRouter.value.route(
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
            RouteOutcome.Failure(describeRoutingFailure(e.message ?: "Routing failed"))
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
    var fromLocationState by remember { mutableStateOf(CurrentLocationSelectionState()) }
    fun cancelFromLocationRequest() {
        fromLocationState = fromLocationState.cancel()
    }
    // Ride-complexity dial levels: 0 = Fastest, each click adds one level
    // (+1.0 of motorcycle/curve preference). Eight clicks per revolution;
    // winding past the eighth level keeps counting (9, 10, ...). The dial
    // has a hard minimum at zero and no maximum.
    var complexity by rememberSaveable { mutableStateOf(0f) }
    val voiceUnit = remember { VoiceDistanceUnit.forLocale(Locale.getDefault()) }
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
    var voiceGuidanceEnabled by rememberSaveable {
        mutableStateOf(routePreferences.getBoolean(VOICE_ENABLED_PREF, true))
    }
    var darkRideMapEnabled by rememberSaveable {
        mutableStateOf(routePreferences.getBoolean(DARK_RIDE_MAP_PREF, false))
    }
    var voiceIntervalMeters by rememberSaveable {
        val defaultInterval = VoiceGuidanceSettings.defaults(voiceUnit).intervalMeters
        val minimumInterval = voiceUnit.toMetres(voiceUnit.minimumInterval)
        val maximumInterval = voiceUnit.toMetres(voiceUnit.maximumInterval)
        val persistedInterval = routePreferences.getFloat(
            VOICE_INTERVAL_METERS_PREF,
            defaultInterval.toFloat(),
        ).toDouble()
        mutableStateOf(
            (persistedInterval.takeIf { it.isFinite() } ?: defaultInterval).coerceIn(
                minimumInterval,
                maximumInterval,
            ),
        )
    }
    var voicePreviewCount by rememberSaveable {
        mutableStateOf(routePreferences.getInt(VOICE_PREVIEW_COUNT_PREF, 2).coerceIn(1, 5))
    }
    var voiceSettingsOpen by remember { mutableStateOf(false) }
    var routeSettingsOpen by remember { mutableStateOf(false) }
    // The settings cog expands into a card over the whole screen; the tap
    // scrim and the BackHandler live here rather than inside the rail so the
    // dismissal covers the planner panel too. Not rememberSaveable: menus are
    // deliberately closed across recreation, like the other overlays.
    var settingsMenuOpen by remember { mutableStateOf(false) }
    // Complete inputs of the most recent submission attempt (START / dial
    // release / settings apply / saved-route load). GPX imports carry bounded
    // shaping points here as well as their endpoints; keeping the complete
    // immutable request is what lets a later control edit re-route the same
    // imported ride instead of silently falling back to an endpoint-only ride.
    // Nulled whenever either field changes, so a stale reroute can never
    // target edited-away points.
    var activePlan by remember { mutableStateOf<RouteParams?>(null) }
    // Bumped when a legacy basemap replacement is imported at the same private
    // path; the style reload effect keys on it.
    var mapRevision by remember { mutableIntStateOf(0) }
    val geocodeController = remember(activeDataset.installId, activeDataset.generation) {
        GeocodeSearchController(context.applicationContext, activeDataset.geocoderFile)
    }
    // A dataset switch invalidates everything computed from the previous
    // region: displayed routes, resolved points, and the map style all rebuild
    // from the new package. Saved rides are untouched (they are just data).
    // The first composition initializes the key and does nothing: bumping the
    // map revision there would start a second style load on every launch.
    var appliedDatasetKey by remember {
        mutableStateOf(activeDataset.installId to activeDataset.generation)
    }
    LaunchedEffect(activeDataset.installId, activeDataset.generation) {
        val key = activeDataset.installId to activeDataset.generation
        if (key == appliedDatasetKey) return@LaunchedEffect
        appliedDatasetKey = key
        coordinator.reset()
        activePlan = null
        fromPoint = null
        cancelFromLocationRequest()
        toPoint = null
        mapRevision++
        Log.i(
            TAG,
            "Active region: ${activeDataset.displayName} (${activeDataset.regionId}, " +
                "generation ${activeDataset.generation})",
        )
    }
    // Guidance: pure engine + Android glue. One controller per screen.
    val navigationController = remember { NavigationController(scope) }
    val navSnapshot by navigationController.snapshot.collectAsStateWithLifecycle()
    val voiceGuidanceOutput = remember {
        OfflineVoiceGuidance(context.applicationContext, voiceUnit)
    }
    val voiceSpeechStatus by voiceGuidanceOutput.status.collectAsState()
    val voiceGuidanceSettings = VoiceGuidanceSettings(
        enabled = voiceGuidanceEnabled,
        intervalMeters = voiceIntervalMeters,
        previewCount = voicePreviewCount,
    )
    val latestVoiceGuidanceSettings = rememberUpdatedState(voiceGuidanceSettings)
    DisposableEffect(voiceGuidanceOutput) {
        onDispose { voiceGuidanceOutput.close() }
    }
    var guidanceRequested by remember { mutableStateOf(false) }
    var locationPermissionGranted by remember {
        mutableStateOf(LocationPermission.isGranted(context))
    }
    var pendingGuidanceStart by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // Runtime permissions belong to Android, not app storage: a normal
        // grant is retained across launches until the user changes it in
        // Settings. Android may expire one-time grants by policy.
        val granted = LocationPermission.isGranted(context)
        val waitingForPermission =
            fromLocationState.status as? CurrentLocationStatus.WaitingForPermission
        if (waitingForPermission != null) {
            fromLocationState = fromLocationState.permissionResult(waitingForPermission.request.id, granted)
            if (!granted) {
                fromText = ""
                fromPoint = null
            }
        }
        locationPermissionGranted = granted
        if (pendingGuidanceStart) {
            guidanceRequested = granted
            pendingGuidanceStart = false
        }
        Log.i(TAG, "Location permission " + if (granted) "granted" else "denied")
    }
    // Start location acquisition as soon as the screen is composed. This runs
    // once per screen lifetime and is a no-op on later launches when Android
    // has already retained the user's grant.
    LaunchedEffect(Unit) {
        if (!locationPermissionGranted) {
            Log.i(TAG, "Requesting location permission at app startup")
            permissionLauncher.launch(LocationPermission.requestedPermissions)
        }
    }
    // Refresh the cached UI state after returning from Settings, where a user
    // may grant or revoke location access without going through our launcher.
    val mapRef = remember { mutableStateOf<MapLibreMap?>(null) }
    val guidanceBearingTracker = remember { GuidanceBearingTracker() }
    var planningCameraBeforeGuidance by remember { mutableStateOf<CameraPosition?>(null) }
    var guidanceWasActive by remember { mutableStateOf(false) }
    // Saved-route storage: repository over device SQLite plus the menu flag.
    val savedRouteRepository = remember { SavedRouteRepository(context.applicationContext) }
    var savedRoutesOpen by remember { mutableStateOf(false) }
    var mapsSettingsOpen by remember { mutableStateOf(false) }
    var importedGpxName by remember { mutableStateOf<String?>(null) }
    var importedGpxMilestones by remember { mutableStateOf<List<GpxMilestone>>(emptyList()) }
    var gpxPreviewOpen by remember { mutableStateOf(false) }
    var gpxImportToken by remember { mutableStateOf(0) }
    val requestCurrentLocation: () -> Unit = {
        fromLocationState = fromLocationState.beginRequest()
        val waiting = fromLocationState.status as CurrentLocationStatus.WaitingForPermission
        fromText = ""
        fromPoint = null
        importedGpxName = null
        importedGpxMilestones = emptyList()
        gpxImportToken++
        activePlan = null
        coordinator.invalidate()
        if (LocationPermission.isGranted(context)) {
            locationPermissionGranted = true
            fromLocationState = fromLocationState.permissionResult(waiting.request.id, granted = true)
        } else {
            permissionLauncher.launch(LocationPermission.requestedPermissions)
        }
    }

    // Ride media control center: native MediaSessionManager discovery plus a
    // local AudioManager volume path that needs no notification access. The
    // panel opens from the data bar; the controller reads the platform only
    // while the panel is visible.
    val mediaController = remember {
        MediaCenterController(
            gateway = AndroidMediaSessionGateway(context.applicationContext),
            localVolume = AndroidVolumeController(context.applicationContext),
            mediaKeys = AndroidMediaKeyController(context.applicationContext),
            onLog = { message ->
                // Media state transitions are debug-build diagnostics only.
                if (debugLogs) Log.d(TAG_MEDIA, message)
            },
        )
    }
    val mediaState by mediaController.state.collectAsState()
    var mediaPanelOpen by remember { mutableStateOf(false) }
    DisposableEffect(mediaController) {
        mediaController.attach()
        onDispose {
            mediaController.setPanelOpen(false)
            mediaController.detach()
        }
    }
    // The listener service connecting/disconnecting changes session
    // availability and can drop the platform callback: re-assert the listener
    // and refresh an open panel when that happens.
    DisposableEffect(mediaController) {
        val bridge = { mediaController.onListenerServiceChanged() }
        MediaListenerBridge.addListener(bridge)
        onDispose { MediaListenerBridge.removeListener(bridge) }
    }
    LaunchedEffect(mediaPanelOpen) {
        mediaController.setPanelOpen(mediaPanelOpen)
    }
    // Measured from the real bar so map controls and the media panel sit
    // exactly above it instead of at hard-coded ride offsets.
    var dataBarHeightPx by remember { mutableIntStateOf(0) }
    val dataBarHeightDp = with(LocalDensity.current) { dataBarHeightPx.toDp() }
    // Measured directions HUD so the media panel can never grow over it.
    var hudHeightPx by remember { mutableIntStateOf(0) }
    val hudHeightDp = with(LocalDensity.current) { hudHeightPx.toDp() }
    val rideActive = guidanceRequested && navSnapshot.state != NavigationState.Idle
    // Deleting the active map is refused mid-ride; the manager enforces the
    // same rule as the disabled trash control.
    LaunchedEffect(rideActive) {
        regionManager.setRideActive(rideActive)
    }
    val mapControlsHidden = rideActive && mediaPanelOpen
    // Hardware volume buttons are invisible to the app; while the panel shows
    // the local music stream, poll the real AudioManager readback so the level
    // and its disabled limits follow the hardware. Remote sessions report
    // through their own onAudioInfoChanged callback instead.
    val localVolumeTargeted = mediaState.volume.target == VolumeTarget.PHONE
    LaunchedEffect(rideActive, mediaPanelOpen, localVolumeTargeted) {
        if (!rideActive || !mediaPanelOpen || !localVolumeTargeted) return@LaunchedEffect
        while (true) {
            delay(LOCAL_VOLUME_POLL_MILLIS)
            mediaController.pollLocalVolume()
        }
    }

    val selectRoute: (Int) -> Unit = coordinator::selectRoute
    val latestSelectRoute by rememberUpdatedState(selectRoute)
    val routeHitRadiusPx = with(LocalDensity.current) { 24.dp.toPx() }

    DisposableEffect(mapRef.value) {
        val map = mapRef.value ?: return@DisposableEffect onDispose { }
        val listener = MapLibreMap.OnMapClickListener { point ->
            val screenPoint = runCatching { map.projection.toScreenLocation(point) }.getOrNull()
            val current = coordinator.state.value
            val focusedIndex = (current as? RouteUiState.Success)?.selectedIndex
            val routeIndex = screenPoint?.let {
                map.findRouteIndexAt(it, routeHitRadiusPx, focusedIndex)
            }
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
    // Planning only needs live location while the map is on screen. During
    // active guidance keep the existing stream alive when the activity stops
    // so background speech can continue within Android's location limits.
    // Keep background fixes out of Compose state; navigation and speech still
    // receive each fix, while the map and screen remain quiescent.
    var trackedFix by remember { mutableStateOf<GpsFix?>(null) }
    var trackingActive by remember { mutableStateOf(false) }
    val latestFix = remember { AtomicReference<GpsFix?>(null) }
    // Debug runner authority: while queued visual fixes are flowing, the live
    // GPS stream must not pull guidance back to the emulator's parked provider
    // position between injections.
    val lastDebugFixAt = remember { AtomicLong(0L) }
    val latestGuidanceRequested = rememberUpdatedState(guidanceRequested)
    var initialLocationCentered by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    screenStarted = true
                    trackedFix = latestFix.get()
                }
                Lifecycle.Event.ON_STOP -> screenStarted = false
                Lifecycle.Event.ON_RESUME -> {
                    val granted = LocationPermission.isGranted(context)
                    locationPermissionGranted = granted
                    if (!granted) {
                        latestFix.set(null)
                        trackedFix = null
                        pendingGuidanceStart = false
                        guidanceRequested = false
                    }
                    // Notification access may have been granted or revoked
                    // while we were in Settings; re-read it immediately.
                    mediaController.onResume()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(locationPermissionGranted, guidanceRequested) {
        if (!guidanceRequested) lastDebugFixAt.set(0L)
        if (!locationPermissionGranted) {
            latestFix.set(null)
            trackedFix = null
            trackingActive = false
            initialLocationCentered = false
            return@LaunchedEffect
        }

        suspend fun collectFixes() {
            trackingActive = true
            var streamEnded = false
            try {
                GpsFixSource.fixes(context.applicationContext).collect { fix ->
                    latestFix.set(fix)
                    val debugAgeMs = lastDebugFixAt.get().takeIf { it > 0L }?.let {
                        SystemClock.elapsedRealtime() - it
                    }
                    if (!debugFixOwnsGuidance(debugLogs, debugAgeMs)) {
                        navigationController.onFix(fix)
                    }
                    val snapshot = navigationController.snapshot.value
                    if (latestGuidanceRequested.value && snapshot.state != NavigationState.Idle) {
                        voiceGuidanceOutput.onSnapshot(
                            snapshot,
                            latestVoiceGuidanceSettings.value,
                        )
                    }
                    if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        trackedFix = fix
                    }
                }
                streamEnded = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                streamEnded = true
                Log.w(TAG, "Location fix stream ended", failure)
            } finally {
                // A guidance-mode effect replacement can briefly cancel this
                // collector; retain its last fix until the replacement starts.
                if (streamEnded || !latestGuidanceRequested.value) {
                    latestFix.set(null)
                    trackedFix = null
                    trackingActive = false
                }
            }
        }

        if (guidanceRequested) {
            collectFixes()
        } else {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                collectFixes()
            }
        }
    }
    LaunchedEffect(fromLocationState, locationPermissionGranted) {
        val waiting =
            fromLocationState.status as? CurrentLocationStatus.WaitingForFix ?: return@LaunchedEffect
        if (!locationPermissionGranted) {
            fromLocationState = fromLocationState.permissionLost(waiting.request.id)
            fromText = ""
            fromPoint = null
            return@LaunchedEffect
        }

        val fix = withTimeoutOrNull(CURRENT_LOCATION_FIX_TIMEOUT_MS) {
            snapshotFlow { trackedFix }.first { candidate ->
                fromLocationState.currentPoint(
                    waiting.request,
                    candidate,
                    SystemClock.elapsedRealtime(),
                ) != null
            }
        }
        if (fromLocationState.status != waiting) return@LaunchedEffect
        val endpoint = fromLocationState.currentEndpoint(
            waiting.request,
            fix,
            SystemClock.elapsedRealtime(),
        )
        if (endpoint == null) {
            fromLocationState = fromLocationState.noRecentFix(waiting.request.id)
            fromText = ""
            fromPoint = null
        } else {
            fromText = endpoint.label
            fromPoint = GHPoint(endpoint.point.lat, endpoint.point.lon)
            fromLocationState = fromLocationState.complete(waiting.request.id)
        }
    }
    // The debug visual runner uses an app-private queued fix to move the real
    // map rider deterministically in planning and NavigationSession in ride
    // mode. This seam is absent from release builds and avoids emulator GPS
    // provider throttling during camera checks and route jumps.
    LaunchedEffect(debugLogs) {
        if (!debugLogs) return@LaunchedEffect
        while (true) {
            val request = withContext(Dispatchers.IO) {
                VisualRouteSnapshot.takeQueuedFix(context.cacheDir)
            }
            if (request == null) {
                delay(40)
                continue
            }
            val fix = GpsFix(
                lat = request.lat,
                lon = request.lon,
                // A percentage jump is a test teleport, not a speed sample:
                // without a runner-requested speed it stays stationary so
                // pace-delta metrics stay truthful. The camera runner can
                // request a speed to capture a zoom band.
                speedMps = request.speedMps ?: 0.0,
                accuracyM = 1.0,
                timestampMs = SystemClock.elapsedRealtime(),
            )
            latestFix.set(fix)
            val guidanceActive = latestGuidanceRequested.value
            if (guidanceActive) {
                lastDebugFixAt.set(SystemClock.elapsedRealtime())
                navigationController.onFix(fix)
            }
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                trackedFix = fix
            }
            val snapshot = navigationController.snapshot.value
            if (guidanceActive && snapshot.state != NavigationState.Idle) {
                voiceGuidanceOutput.onSnapshot(snapshot, latestVoiceGuidanceSettings.value)
            }
            withContext(Dispatchers.IO) {
                VisualRouteSnapshot.acknowledgeFix(context.cacheDir, request.requestId)
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            latestFix.set(null)
            trackedFix = null
            trackingActive = false
        }
    }

    // The planner lock keeps the zoom the user selected. A new ride starts
    // with its own guidance camera lock, which keeps guidance framing.
    var followMe by remember { mutableStateOf(false) }
    var followZoom by remember { mutableStateOf(FOLLOW_ZOOM) }
    var guidanceCameraLocked by remember(guidanceRequested) { mutableStateOf(guidanceRequested) }
    var cameraFollowSuspension by remember { mutableStateOf(CameraFollowSuspension()) }

    var mapUrl by remember { mutableStateOf<String?>(null) }
    var mapFileChecked by remember { mutableStateOf(false) }
    var mapImporting by remember { mutableStateOf(false) }
    var mapImportError by remember { mutableStateOf<String?>(null) }
    var styleJson by remember { mutableStateOf<String?>(null) }
    val darkGuidanceStyle = guidanceRequested && darkRideMapEnabled
    // The active dataset owns the basemap: an installed region uses its own
    // PMTiles archive, the bundled fallback keeps the legacy "Load map file"
    // import. Re-resolving on dataset generation and map revision keeps the
    // URL honest after a switch or a replacement import.
    LaunchedEffect(activeDataset.installId, activeDataset.generation, mapRevision) {
        try {
            val resolvedUrl = activeDataset.tilesUrl
                ?: OfflineTileStore.installedUrl(context.applicationContext)
            withContext(Dispatchers.Main.immediate) { mapUrl = resolvedUrl }
        } catch (e: CancellationException) {
            // Leaving the composition cancels this effect; it is not a load
            // failure, and recording one would leave a stale modal error.
            throw e
        } catch (e: Exception) {
            withContext(Dispatchers.Main.immediate) {
                mapImportError = "The installed map file could not be opened"
            }
            Log.e(TAG, "Installed map failed to load", e)
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) { mapFileChecked = true }
        }
    }
    LaunchedEffect(mapUrl, mapRevision, darkGuidanceStyle) {
        // Force a style reload when a replacement is imported at the same
        // private path; the URL string itself intentionally stays stable.
        withContext(Dispatchers.Main.immediate) { styleJson = null }
        val url = mapUrl ?: run {
            return@LaunchedEffect
        }
        try {
            val loadedJson = loadOfflineStyle(
                context.applicationContext,
                url,
                darkRideMode = darkGuidanceStyle,
            )
            // loadOfflineStyle reads the asset on Dispatchers.IO. Publish UI
            // state on Main so recomposition and the keyed native-style effect
            // are scheduled on the UI dispatcher after that context switch.
            withContext(Dispatchers.Main.immediate) { styleJson = loadedJson }
        } catch (e: CancellationException) {
            // A recomposition/recreation cancellation is not a style failure;
            // rethrowing keeps the map on its next successful load instead of
            // popping a false "Map file not loaded" dialog over it.
            throw e
        } catch (e: Exception) {
            withContext(Dispatchers.Main.immediate) {
                mapImportError = "The offline map style could not be loaded"
            }
            Log.e(TAG, "Offline map style failed to load", e)
        }
    }

    val mapPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            mapImporting = true
            mapImportError = null
            try {
                mapUrl = OfflineTileStore.import(context.applicationContext, uri)
                mapRevision++
                // The legacy basemap belongs to the bundled dataset; switch
                // back to it so the import is actually visible.
                regionManager.activateBundled()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mapImportError = e.message ?: "Map file import failed"
                Log.e(TAG, "Map file import failed", e)
            } finally {
                mapImporting = false
                mapFileChecked = true
            }
        }
    }
    val chooseMapFile = {
        mapPicker.launch(arrayOf("application/octet-stream", "*/*"))
    }

    // Offline regional package import: the system picker supplies a `.motomap`
    // file; the manager copies, verifies, and installs it transactionally. The
    // user's file is left where it is.
    val mapsPackagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) regionManager.importPackage(uri)
    }

    // Downloads are saved as a user-visible file first: the system "Save to"
    // dialog picks the location (Downloads by default), then the resumable
    // transfer stages privately and copies into that file once it is complete.
    // The same picker also serves resuming an interrupted download and saving a
    // package recovered from the old private-only flow.
    var pendingMapsDownload by remember { mutableStateOf<MapsDownloadAction?>(null) }
    val mapsDownloadPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val action = pendingMapsDownload
        pendingMapsDownload = null
        if (uri == null || action == null) return@rememberLauncherForActivityResult
        when (action) {
            is MapsDownloadAction.New -> regionManager.startDownload(action.regionId, uri)
            MapsDownloadAction.Resume -> regionManager.resumeDownload(uri)
            MapsDownloadAction.SaveRecovered -> regionManager.saveRecoveredPackage(uri)
        }
    }

    val mapView = rememberMapView(context) { map ->
        mapRef.value = map
    }

    var styledMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var appliedStyleJson by remember { mutableStateOf<String?>(null) }
    var appliedMapRevision by remember { mutableStateOf<Int?>(null) }
    var loadedStyleJson by remember { mutableStateOf<String?>(null) }
    var styleLoadStartedJson by remember { mutableStateOf<String?>(null) }
    var styleLoadError by remember { mutableStateOf("") }
    var styleLoadGeneration by remember { mutableIntStateOf(0) }
    // Debug-only, request-driven inspection. No production camera change and
    // no building queries per GPS update. The runner waits for actual rendered
    // basemap/rider buckets and installed route layers before capturing a
    // style transition. Pitched line queries alone are not reliable ink proof.
    LaunchedEffect(debugLogs, mapRef.value, loadedStyleJson, darkGuidanceStyle) {
        if (!debugLogs) return@LaunchedEffect
        val map = mapRef.value ?: return@LaunchedEffect
        while (true) {
            val (camera, probeId) = withContext(Dispatchers.IO) {
                VisualRouteSnapshot.takeQueuedCamera(context.cacheDir) to
                    VisualRouteSnapshot.takeQueuedMapProbe(context.cacheDir)
            }
            camera?.let { map.moveCamera(CameraUpdateFactory.newCameraPosition(it.camera)) }
            if (probeId != null && loadedStyleJson != null) {
                val bounds = android.graphics.RectF(0f, 0f, mapView.width.toFloat(), mapView.height.toFloat())
                val basemap = if (darkGuidanceStyle) {
                    DARK_RIDE_BASEMAP_PROBE_LAYERS.sumOf { layerId ->
                        map.queryRenderedFeatures(bounds, layerId).size
                    }
                } else {
                    map.queryRenderedFeatures(bounds, "building").size
                }
                val routeReady = map.style?.let { style ->
                    style.getSource("route") != null && style.getLayer("route-focus-line") != null
                } == true
                val rider = map.queryRenderedFeatures(bounds, "nav-position-dot").size
                withContext(Dispatchers.IO) {
                    VisualRouteSnapshot.writeMapProbe(context.cacheDir, probeId, darkGuidanceStyle, basemap, routeReady, rider)
                }
            }
            delay(40)
        }
    }
    // Debug-only native frame benchmark. The probe attaches to the live map
    // only in debug builds, and only while this screen owns it; the runner
    // animates host-queued camera sweeps with MapLibre's own easeCamera and
    // exports per-sweep frame JSON. Release builds never attach the listeners.
    // `screenStarted` and `loadedStyleJson` are keys so backgrounding the app
    // or reloading the style cancels an in-flight sweep and restores camera
    // and paint instead of leaving a half-driven camera behind.
    LaunchedEffect(debugLogs, mapView, mapRef.value, screenStarted, loadedStyleJson) {
        if (!debugLogs || !screenStarted) return@LaunchedEffect
        val map = mapRef.value ?: return@LaunchedEffect
        val probe = MapPerformanceProbe(
            mapView = mapView,
            map = map,
            window = context.findActivity()?.window,
            log = { Log.d(TAG_MAP_PERF, it) },
        )
        probe.attach()
        val runner = MapPerfSweepRunner(
            mapView = mapView,
            map = map,
            probe = probe,
            cacheDir = context.cacheDir,
            log = { Log.d(TAG_MAP_PERF, it) },
        )
        try {
            while (true) {
                // A queued sweep must wait for a live style: animating the
                // camera before the style exists would measure nothing and
                // could be cancelled by the style load itself.
                if (map.style == null) {
                    delay(100)
                    continue
                }
                val request = withContext(Dispatchers.IO) {
                    MapPerfSweepQueue.take(context.cacheDir)
                }
                if (request != null) {
                    try {
                        runner.run(request)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.e(TAG_MAP_PERF, "sweep '${request.label}' failed", error)
                    }
                }
                delay(40)
            }
        } finally {
            // Leaving the screen cancels the sweep: the runner restores the
            // camera and building paint, and the gate always comes back down.
            probe.detach()
            MapPerfProbeGate.exit()
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow {
            NativeStyleLoadRequest(
                json = styleJson,
                map = mapRef.value,
                screenStarted = screenStarted,
                revision = mapRevision,
                darkRideStyle = guidanceRequested && darkRideMapEnabled,
            )
        }.collectLatest { request ->
            withContext(Dispatchers.Main.immediate) {
                if (debugLogs) {
                    Log.d(
                        TAG,
                        "Map style request observed json=${request.json != null} " +
                            "map=${request.map != null} screenStarted=${request.screenStarted} " +
                            "revision=${request.revision}",
                    )
                }
                if (!request.screenStarted) {
                    if (debugLogs) {
                        Log.d(TAG, "Map style load deferred: screenStarted=false revision=${request.revision}")
                    }
                    return@withContext
                }
                val json = request.json ?: run {
                    if (debugLogs) Log.d(TAG, "Map style load deferred: style JSON is unavailable")
                    return@withContext
                }
                val map = request.map ?: run {
                    if (debugLogs) Log.d(TAG, "Map style load deferred: native map is unavailable")
                    return@withContext
                }
                if (styledMap === map && appliedStyleJson == json && appliedMapRevision == request.revision) {
                    if (debugLogs) {
                        Log.d(
                            TAG,
                            "Map style load skipped: request already applied revision=${request.revision} " +
                                "generation=$styleLoadGeneration loaded=${loadedStyleJson == json}",
                        )
                    }
                    return@withContext
                }
                loadedStyleJson = null
                styleLoadStartedJson = null
                styleLoadError = ""
                // Check the live style state as well as the generation: a callback
                // can arrive after the desired style is cleared, before a new request.
                val generation = ++styleLoadGeneration
                if (debugLogs) {
                    Log.i(
                        TAG,
                        "Map style load start generation=$generation revision=${request.revision} " +
                            "screenStarted=${request.screenStarted} mapCurrent=${mapRef.value === map} " +
                            "sameMap=${styledMap === map} " +
                            "style=${if (request.darkRideStyle) "ride-dark" else "color"}",
                    )
                }
                try {
                    if (styledMap !== map) {
                        // Set an initial Queensland view once for each MapLibreMap. Style
                        // changes on the same map retain the live planning/guidance camera.
                        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(-23.0, 145.5), 4.5))
                    }
                    map.setStyle(Style.Builder().fromJson(json)) {
                        val stillCurrent = mapRef.value === map &&
                            styleLoadGeneration == generation && styleJson == json
                        if (stillCurrent) {
                            loadedStyleJson = json
                            styleLoadError = ""
                            if (debugLogs) {
                                Log.i(
                                    TAG,
                                    "Map style load success generation=$generation revision=${request.revision} " +
                                        "screenStarted=${request.screenStarted} mapCurrent=${mapRef.value === map}",
                                )
                            }
                        } else if (debugLogs) {
                            Log.w(
                                TAG,
                                "Map style callback ignored generation=$generation currentGeneration=$styleLoadGeneration " +
                                    "screenStarted=${request.screenStarted} mapCurrent=${mapRef.value === map} " +
                                    "requestMatches=${styleJson == json}",
                            )
                        }
                    }
                    styleLoadStartedJson = json
                    styledMap = map
                    appliedStyleJson = json
                    appliedMapRevision = request.revision
                } catch (failure: Exception) {
                    styleLoadError = "${failure.javaClass.simpleName}: ${failure.message.orEmpty()}"
                    if (debugLogs) {
                        Log.e(TAG, "Map style load request failed generation=$generation revision=${request.revision}", failure)
                    }
                    throw failure
                }
            }
        }
    }

    val routeResult = (state as? RouteUiState.Success)?.result
    val selectedIndex = (state as? RouteUiState.Success)?.selectedIndex ?: 0
    val routeCount = routeResult?.routes?.size ?: 0
    LaunchedEffect(routeResult, selectedIndex, debugLogs) {
        if (!debugLogs) return@LaunchedEffect
        val paths = routeResult?.routes
        if (paths == null) {
            withContext(Dispatchers.IO) {
                VisualRouteSnapshot.clear(context.cacheDir)
            }
        } else {
            val geometry = paths.map { path -> path.points.toGeoPoints() }
            withContext(Dispatchers.IO) {
                VisualRouteSnapshot.write(context.cacheDir, geometry, selectedIndex)
            }
        }
    }
    LaunchedEffect(guidanceRequested, mapRef.value) {
        val map = mapRef.value
        if (guidanceRequested && !guidanceWasActive && map != null) {
            planningCameraBeforeGuidance = map.cameraPosition
            guidanceBearingTracker.reset()
            guidanceWasActive = true
        } else if (!guidanceRequested && guidanceWasActive) {
            guidanceWasActive = false
            guidanceBearingTracker.reset()
            planningCameraBeforeGuidance?.let { camera ->
                map?.animateCamera(CameraUpdateFactory.newCameraPosition(camera), 500)
            }
            planningCameraBeforeGuidance = null
        }
    }
    LaunchedEffect(guidanceRequested, routeResult, selectedIndex, voiceGuidanceEnabled) {
        if (guidanceRequested && voiceGuidanceEnabled &&
            routeResult?.routes?.getOrNull(selectedIndex) != null
        ) {
            voiceGuidanceOutput.ensureAvailability()
        }
    }
    val routeStateName = when (state) {
        RouteUiState.Idle -> "idle"
        RouteUiState.Loading -> "loading"
        is RouteUiState.Success -> "success"
        is RouteUiState.Error -> "error"
    }

    val routeGeometryCache = remember { RouteGeometryCache() }
    var fittedRouteSet by remember { mutableStateOf<List<ResponsePath>?>(null) }
    var fittedMap by remember { mutableStateOf<MapLibreMap?>(null) }
    DisposableEffect(routeGeometryCache) {
        onDispose { routeGeometryCache.invalidatePendingDraws() }
    }
    LaunchedEffect(
        routeResult,
        selectedIndex,
        styleJson,
        loadedStyleJson,
        darkGuidanceStyle,
        mapRef.value,
        screenStarted,
    ) {
        val generation = routeGeometryCache.beginDraw()
        if (!screenStarted) return@LaunchedEffect
        val map = mapRef.value ?: return@LaunchedEffect
        val requestIsCurrent = { routeGeometryCache.isCurrent(generation) }
        if (routeResult == null) {
            routeGeometryCache.clearRouteData()
            map.clearRoutes(requestIsCurrent)
            fittedRouteSet = null
            fittedMap = null
            return@LaunchedEffect
        }
        if (loadedStyleJson == null || loadedStyleJson != styleJson) return@LaunchedEffect
        Log.d(TAG, "Drawing ${routeResult.routes.size} route(s)")
        map.drawRoutes(
            routeResult.routes,
            selectedIndex,
            routeGeometryCache,
            requestIsCurrent,
            darkGuidanceMode = darkGuidanceStyle,
        )
        val needsInitialRouteFit = fittedRouteSet !== routeResult.routes || fittedMap !== map
        if (needsInitialRouteFit && !guidanceRequested) {
            map.fitBounds(routeResult.routes)
            Log.d(TAG, "Route drawn and camera fitted")
        }
        fittedRouteSet = routeResult.routes
        fittedMap = map
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
        cancelFromLocationRequest()
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
        cancelFromLocationRequest()
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
            voiceGuidanceOutput.reset()
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

    // Follow the rider in planning mode only when the planner lock is on.
    // Guidance has its own lock and keeps its speed-based zoom and framing.
    LaunchedEffect(
        trackedFix,
        followMe,
        followZoom,
        guidanceRequested,
        screenStarted,
        cameraFollowSuspension,
    ) {
        withContext(Dispatchers.Main.immediate) {
            if (!screenStarted) return@withContext
            if (!followMe || guidanceRequested) return@withContext
            if (cameraFollowSuspension.isSuspended(SystemClock.elapsedRealtime())) {
                return@withContext
            }
            val map = mapRef.value ?: return@withContext
            val fix = trackedFix ?: return@withContext
            if (fix.lat.isNaN() || fix.lon.isNaN()) return@withContext
            val camera = map.cameraPosition
            val cameraTarget = camera.target ?: return@withContext
            val needsPositionOrZoom = shouldFollowCamera(
                cameraTarget.latitude,
                cameraTarget.longitude,
                camera.zoom,
                fix.lat,
                fix.lon,
                followZoom,
                followMovementThresholdMeters(fix.speedMps),
            )
            val needsOrientation = planningOrientationNeedsUpdate(camera.bearing, camera.tilt)
            if (needsPositionOrZoom || needsOrientation) {
                map.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder(camera)
                            .target(LatLng(fix.lat, fix.lon))
                            .zoom(followZoom)
                            .bearing(0.0)
                            .tilt(0.0)
                            .build(),
                    ),
                    400,
                )
            }
        }
    }
    // Finish the grace period even when the rider is stationary and no new
    // location fix arrives. Resetting the state re-enables follow and gives
    // tests/visual tooling a settled `followSuspended=false` snapshot.
    LaunchedEffect(cameraFollowSuspension) {
        val pending = cameraFollowSuspension
        if (pending.gestureActive) return@LaunchedEffect
        val remainingMs = pending.remainingDelayMs(SystemClock.elapsedRealtime())
        if (remainingMs > 0L) delay(remainingMs)
        if (cameraFollowSuspension != pending) return@LaunchedEffect
        cameraFollowSuspension = pending.cooldownElapsed()
        if (debugLogs && !MapPerfProbeGate.sweepActive) {
            val camera = withContext(Dispatchers.Main.immediate) {
                mapRef.value?.cameraPosition
            }
            camera?.let {
                val locked = if (guidanceRequested) guidanceCameraLocked else followMe
                VisualRouteSnapshot.writeCamera(
                    context.cacheDir,
                    it,
                    guidanceRequested,
                    followSuspended = false,
                    cameraLocked = locked,
                )
            }
        }
    }
    // The debug camera probe also exposes lock transitions that do not move
    // the native camera (for example, turning follow on while already centred).
    LaunchedEffect(debugLogs, mapRef.value, followMe, guidanceRequested, guidanceCameraLocked) {
        if (!debugLogs || MapPerfProbeGate.sweepActive) return@LaunchedEffect
        val camera = withContext(Dispatchers.Main.immediate) {
            mapRef.value?.cameraPosition
        } ?: return@LaunchedEffect
        val locked = if (guidanceRequested) guidanceCameraLocked else followMe
        VisualRouteSnapshot.writeCamera(
            context.cacheDir,
            camera,
            guidanceRequested,
            followSuspended = locked &&
                cameraFollowSuspension.isSuspended(SystemClock.elapsedRealtime()),
            cameraLocked = locked,
        )
    }
    // On the first usable fix, move the initial map viewport to the rider.
    // This is intentionally one-shot: later fixes update the position marker,
    // while the rider can pan freely until they explicitly enable the lock.
    LaunchedEffect(
        trackedFix,
        styleJson,
        loadedStyleJson,
        mapRef.value,
        routeResult,
        followMe,
        guidanceRequested,
        screenStarted,
    ) {
        if (!screenStarted) return@LaunchedEffect
        if (initialLocationCentered || routeResult != null || followMe || guidanceRequested) {
            return@LaunchedEffect
        }
        val map = mapRef.value ?: return@LaunchedEffect
        val fix = trackedFix ?: return@LaunchedEffect
        if (fix.lat.isNaN() || fix.lon.isNaN()) return@LaunchedEffect
        map.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(fix.lat, fix.lon), FOLLOW_ZOOM),
            500,
        )
        initialLocationCentered = true
        Log.i(TAG, "Initial map viewport centered on the first location fix")
    }
    // In planning mode, manual zoom changes followZoom so the location lock
    // keeps the user's selected level.
    val latestFollowMe = rememberUpdatedState(followMe)
    val latestGuidanceCameraLocked = rememberUpdatedState(guidanceCameraLocked)
    DisposableEffect(mapRef.value) {
        val map = mapRef.value ?: return@DisposableEffect onDispose { }
        var gestureStartZoom: Double? = null
        var mapTouchActive = false
        var mapTouchWasLocked = false
        fun cameraLockActive(): Boolean = if (latestGuidanceRequested.value) {
            latestGuidanceCameraLocked.value
        } else {
            latestFollowMe.value
        }
        fun finishMapGesture() {
            if (!mapTouchActive && gestureStartZoom == null) return
            val lockedGesture = mapTouchWasLocked || gestureStartZoom != null
            mapTouchActive = false
            mapTouchWasLocked = false
            if (!lockedGesture) return
            if (latestFollowMe.value && !latestGuidanceRequested.value) {
                followZoom = map.cameraPosition.zoom
            }
            gestureStartZoom = null
            val endedAt = SystemClock.elapsedRealtime()
            cameraFollowSuspension = cameraFollowSuspension.gestureEnded(endedAt)
            if (debugLogs && !MapPerfProbeGate.sweepActive) {
                // Publish the camera at pointer-up as well as native idle. The
                // map can still be finishing its gesture animation, and the
                // debug runner needs an immediate snapshot of the real angle
                // plus the active follow pause.
                val guidanceActive = latestGuidanceRequested.value
                val locked = cameraLockActive()
                VisualRouteSnapshot.writeCamera(
                    context.cacheDir,
                    map.cameraPosition,
                    guidanceActive,
                    followSuspended = locked &&
                        cameraFollowSuspension.isSuspended(endedAt),
                    cameraLocked = locked,
                )
            }
        }
        val touchListener = View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    mapTouchActive = true
                    mapTouchWasLocked = cameraLockActive()
                    if (mapTouchWasLocked) {
                        gestureStartZoom = map.cameraPosition.zoom
                        cameraFollowSuspension = cameraFollowSuspension.gestureStarted()
                    }
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> finishMapGesture()
            }
            // Observe the pointers while leaving every event for MapLibre's
            // normal pan, pinch, rotate, and two-finger tilt recognizers.
            false
        }
        mapView.setOnTouchListener(touchListener)
        val moveStarted = MapLibreMap.OnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE &&
                cameraLockActive()
            ) {
                if (gestureStartZoom == null) gestureStartZoom = map.cameraPosition.zoom
                cameraFollowSuspension = cameraFollowSuspension.gestureStarted()
            }
        }
        val idle = MapLibreMap.OnCameraIdleListener {
            // Native camera idle may fire while a rider is holding a finger
            // still on the map. The raw touch observer owns the true end of
            // the gesture; this fallback covers native gestures without a
            // delivered touch sequence and never ends an active touch early.
            if (!mapTouchActive && gestureStartZoom != null) finishMapGesture()
            if (debugLogs && !MapPerfProbeGate.sweepActive) {
                // Debug seam for tools/test/visual.py: publish the settled
                // camera so the runner can check guidance framing and the
                // planning-camera restore after END. Suppressed while a perf
                // sweep drives the camera, so the benchmark measures the map
                // and not its own debug bookkeeping.
                val settled = map.cameraPosition
                val guidanceActive = latestGuidanceRequested.value
                val suspension = cameraFollowSuspension
                val locked = if (guidanceActive) {
                    latestGuidanceCameraLocked.value
                } else {
                    latestFollowMe.value
                }
                val suspended = locked && suspension.isSuspended(SystemClock.elapsedRealtime())
                scope.launch(Dispatchers.IO) {
                    VisualRouteSnapshot.writeCamera(
                        context.cacheDir,
                        settled,
                        guidanceActive,
                        followSuspended = suspended,
                        cameraLocked = locked,
                    )
                }
            }
        }
        map.addOnCameraMoveStartedListener(moveStarted)
        // Zoom-gated tile LOD: the coarse distant-tile shift only applies to
        // wide views (<= z15); close views keep pre-LOD rendering. The band
        // changes rarely, so this makes no native call while it is stable.
        map.addOnCameraIdleListener(idle)
        onDispose {
            map.removeOnCameraMoveStartedListener(moveStarted)
            map.removeOnCameraIdleListener(idle)
            mapView.setOnTouchListener(null)
        }
    }

    fun zoomFromPill(delta: Double) {
        val map = mapRef.value ?: return
        val camera = map.cameraPosition
        val target = camera.target ?: return
        val targetZoom = (camera.zoom + delta)
            .coerceIn(map.minZoomLevel, map.maxZoomLevel)
        if (followMe && !guidanceRequested) {
            followZoom = targetZoom
            // Let the follow effect combine the new zoom with the latest
            // rider position. Without a fix, apply the zoom at the current
            // target directly.
            if (trackedFix == null) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(target, targetZoom), 250)
            }
        } else {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(target, targetZoom), 250)
        }
    }
    // This is the only position-marker owner. It collects navigation
    // snapshots directly while started, so returning from background renders
    // the current position once without waking RouteScreen per fix.
    val markerDensity = LocalDensity.current.density
    LaunchedEffect(
        trackingActive,
        guidanceRequested,
        guidanceCameraLocked,
        loadedStyleJson,
        mapRef.value,
        screenStarted,
        markerDensity,
        darkGuidanceStyle,
        cameraFollowSuspension,
    ) {
        if (!screenStarted) return@LaunchedEffect
        val map = mapRef.value ?: return@LaunchedEffect
        if (loadedStyleJson == null || loadedStyleJson != styleJson) return@LaunchedEffect
        if (!trackingActive) {
            map.hideNavPosition()
            return@LaunchedEffect
        }
        if (guidanceRequested) {
            navigationController.snapshot.collect { snap ->
                // MapLibre requires native updates on the Android UI thread,
                // including emissions resumed by snapshot/test dispatchers.
                withContext(Dispatchers.Main.immediate) {
                    if (snap.state == NavigationState.Idle || !snap.lat.isFinite() || !snap.lon.isFinite()) {
                        map.hideNavPosition()
                        return@withContext
                    }
                    val camera = map.cameraPosition
                    val bearing = guidanceBearingTracker.resolve(snap.bearingDeg, camera.bearing)
                    map.updateGuidancePosition(
                        snap.lat,
                        snap.lon,
                        bearing,
                        markerDensity,
                        monochrome = darkGuidanceStyle,
                    )
                    // Navigation and the position marker remain live while the
                    // rider inspects the map with the camera lock released.
                    if (!guidanceCameraLocked) return@withContext
                    if (cameraFollowSuspension.isSuspended(SystemClock.elapsedRealtime())) {
                        return@withContext
                    }
                    val zoom = guidanceZoomFor(snap.speedMps)
                    val cameraTarget = camera.target ?: return@withContext
                    val needsPositionOrZoom = shouldFollowCamera(
                        cameraTarget.latitude,
                        cameraTarget.longitude,
                        camera.zoom,
                        snap.lat,
                        snap.lon,
                        zoom,
                        followMovementThresholdMeters(snap.speedMps),
                    )
                    val needsOrientation = guidanceOrientationNeedsUpdate(
                        cameraBearing = camera.bearing,
                        targetBearing = bearing,
                        cameraTilt = camera.tilt,
                    )
                    if (needsPositionOrZoom || needsOrientation) {
                        val topPadding = (mapView.height.coerceAtLeast(0) * GUIDANCE_TOP_PADDING_FRACTION)
                        map.animateCamera(
                            CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder()
                                    .target(LatLng(snap.lat, snap.lon))
                                    .zoom(zoom)
                                    .bearing(bearing)
                                    .tilt(GUIDANCE_TILT_DEGREES)
                                    .padding(0.0, topPadding, 0.0, 0.0)
                                    .build(),
                            ),
                            600,
                        )
                    }
                }
            }
        } else {
            snapshotFlow { trackedFix }.collect { fix ->
                withContext(Dispatchers.Main.immediate) {
                    if (fix == null || !fix.lat.isFinite() || !fix.lon.isFinite()) {
                        map.hideNavPosition()
                    } else {
                        map.updateNavPosition(fix.lat, fix.lon, fix.bearingDeg)
                    }
                }
            }
        }
    }

    // Map above, panel below: the map is exactly the region the route menu
    // does not cover, so the two never overlap and the menu cannot slide
    // around over the map. The expanded settings card and its scrim sit above
    // both regions, so a tap anywhere outside the card closes it.
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .semantics {
                    this[RouteUiStateKey] = routeStateName
                    this[RouteGenerationKey] = coordinator.generation
                    this[SelectedRouteKey] = selectedIndex
                    this[RouteCountKey] = routeCount
                    this[LocationPermissionKey] = locationPermissionGranted
                    this[MapInstalledKey] = mapUrl != null
                    this[MapStyleJsonReadyKey] = styleJson != null
                    this[MapNativeStyleReadyKey] = mapRef.value?.style != null
                    this[MapStyleLoadedKey] = loadedStyleJson != null
                    this[MapStyleMatchesRequestKey] = styleJson != null && loadedStyleJson == styleJson
                    this[MapScreenStartedKey] = screenStarted
                    this[MapStyleLoadStartedKey] = styleJson != null && styleLoadStartedJson == styleJson
                    this[MapStyleLoadGenerationKey] = styleLoadGeneration
                    this[MapAppliedStyleReadyKey] = appliedStyleJson != null
                    this[MapAppliedStyleMatchesRequestKey] =
                        styleJson != null && appliedStyleJson == styleJson && appliedMapRevision == mapRevision
                    this[MapStyleLoadErrorKey] = styleLoadError
                    this[MapLoadFailureKey] = mapImportError.orEmpty()
                    val cameraLocked = if (guidanceRequested) guidanceCameraLocked else followMe
                    this[FollowCameraLockedKey] = cameraLocked
                    this[FollowCameraSuspendedKey] = cameraLocked &&
                        cameraFollowSuspension.isSuspended(SystemClock.elapsedRealtime())
                    this[GuidanceActiveKey] = guidanceRequested && navSnapshot.state != NavigationState.Idle
                    this[DarkRideMapEnabledKey] = darkRideMapEnabled
                    this[DarkGuidanceStyleReadyKey] = darkGuidanceStyle &&
                        styleJson != null && loadedStyleJson == styleJson
                    this[MediaPanelOpenKey] = mediaPanelOpen
                    this[MediaCenterStatusKey] = mediaState.status.name.lowercase()
                    this[MediaAccessGrantedKey] = mediaState.access.name == "GRANTED"
                    this[ActiveRegionKey] = activeDataset.regionId
                    this[ActiveRegionGenerationKey] = activeDataset.generation
                    this[InstalledRegionCountKey] = mapsState.installed.size
                }
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .semantics {
                        this[FocusedRouteIndexKey] = selectedIndex
                        this[MapRouteCountKey] = routeCount
                        this[MapReadyKey] = mapRef.value != null &&
                            styleJson != null && loadedStyleJson == styleJson
                    }
            ) {
                AndroidView(
                    factory = { mapView },
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = "Map" },
                )
                if (guidanceRequested && navSnapshot.state != NavigationState.Idle) {
                    NavigationHud(
                        snapshot = navSnapshot,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .onGloballyPositioned { coordinates ->
                                hudHeightPx = coordinates.size.height
                            },
                    )
                }
                if (mapFileChecked && mapUrl == null) {
                    MissingMapCard(
                        importing = mapImporting,
                        error = mapImportError,
                        onChooseFile = chooseMapFile,
                        modifier = Modifier.align(Alignment.Center),
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
                if (!mapControlsHidden) {
                    CentreOnMeButton(
                        visible = true,
                        active = if (guidanceRequested) guidanceCameraLocked else followMe,
                        description = when {
                            guidanceRequested && guidanceCameraLocked -> "Rider lock on; tap to release"
                            guidanceRequested -> "Rider lock off; tap to follow"
                            followMe -> "Following your location; tap to release"
                            else -> "Centre on me"
                        },
                        onClick = {
                            if (guidanceRequested) {
                                val enableLock = !guidanceCameraLocked
                                if (!enableLock) {
                                    mapRef.value?.cancelTransitions()
                                }
                                cameraFollowSuspension = CameraFollowSuspension()
                                guidanceCameraLocked = enableLock
                            } else {
                                val enableFollow = !followMe
                                if (enableFollow) {
                                    mapRef.value?.cameraPosition?.zoom?.let { followZoom = it }
                                }
                                cameraFollowSuspension = CameraFollowSuspension()
                                followMe = enableFollow
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(
                                end = 8.dp,
                                bottom = if (rideActive) dataBarHeightDp + 8.dp else 8.dp,
                            )
                    )
                }
                // The media panel is drawn BEFORE the data bar on purpose. It
                // reaches MediaPanelJoin.fuseDepth down into the bar's empty top
                // padding, and letting the bar paint after it hides that overlap
                // under the bar's own opaque surface: the pair then reads as one
                // continuous silhouette instead of two cards with a seam.
                if (rideActive && mediaPanelOpen) {
                    // Bound the panel to the space between the directions HUD
                    // and the measured data bar; the card scrolls inside that
                    // bound, so landscape cannot cover the HUD or END.
                    BoxWithConstraints(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxSize()
                            .padding(bottom = dataBarHeightDp - MediaPanelJoin.fuseDepth),
                    ) {
                        val panelMaxHeight = (maxHeight - hudHeightDp - 12.dp)
                            .coerceAtLeast(96.dp)
                        MediaControlCenter(
                            state = mediaState,
                            onCommand = { mediaController.dispatch(it) },
                            onVolume = { mediaController.adjustVolume(it) },
                            onSelectPlayer = { mediaController.selectSession(it) },
                            onClose = { mediaPanelOpen = false },
                            maxHeight = panelMaxHeight,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
                if (rideActive) {
                    NavigationDataBar(
                        snapshot = navSnapshot,
                        onEnd = {
                            mediaPanelOpen = false
                            guidanceRequested = false
                            // Return to planning: without this the button stays
                            // RIDE (state is still Success) and can never start
                            // a new route search.
                            coordinator.reset()
                            Log.i(TAG, "Guidance ended via END button")
                        },
                        mediaPanelOpen = mediaPanelOpen,
                        onMediaToggle = { mediaPanelOpen = !mediaPanelOpen },
                        monochrome = darkRideMapEnabled,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            // Measure the real bar so the media panel and map
                            // controls anchor exactly above it.
                            .onGloballyPositioned { coordinates ->
                                dataBarHeightPx = coordinates.size.height
                            },
                    )
                }
                // The keyboard shrinks the map box; in that state the pill rides
                // up under the RouteActionsPill stack and steals its taps, so it
                // stays hidden while the IME is visible.
                if (WindowInsets.ime.getBottom(LocalDensity.current) == 0 && !mapControlsHidden) {
                    ZoomPill(
                        onZoomIn = { zoomFromPill(1.0) },
                        onZoomOut = { zoomFromPill(-1.0) },
                        rideMode = rideActive,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            // In ride mode the measured data bar occupies the
                            // bottom of the map box; lift the pill above it (and
                            // above the centre button) so they never overlap.
                            .padding(
                                end = 8.dp,
                                bottom = if (rideActive) dataBarHeightDp + 64.dp else 64.dp,
                            )
                    )
                }
            }
            RoutePlanPanel(
                fromText = fromText,
                fromResolved = fromPoint != null,
                fromHint = when (val locationStatus = fromLocationState.status) {
                    CurrentLocationStatus.Idle -> "Route from"
                    is CurrentLocationStatus.WaitingForPermission -> "Waiting for location permission…"
                    is CurrentLocationStatus.WaitingForFix -> "Finding current location…"
                    is CurrentLocationStatus.Unavailable -> locationStatus.reason.hint
                },
                onFromChange = {
                    cancelFromLocationRequest()
                    importedGpxName = null; importedGpxMilestones = emptyList(); gpxImportToken++
                    fromText = it; fromPoint = null
                    activePlan = null; coordinator.invalidate()
                },
                onEditFrom = {
                    cancelFromLocationRequest()
                    fromPoint = null
                    activePlan = null
                    coordinator.invalidate()
                },
                onFromPicked = { result ->
                    cancelFromLocationRequest()
                    fromText =
                        if (result.subtitle.isNotBlank()) "${result.name}, ${result.subtitle}" else result.name
                    fromPoint = GHPoint(result.lat, result.lon)
                    activePlan = null; coordinator.invalidate()
                },
                onUseCurrentLocation = requestCurrentLocation,
                toText = toText,
                toResolved = toPoint != null,
                onToChange = {
                    importedGpxName = null; importedGpxMilestones = emptyList(); gpxImportToken++
                    toText = it; toPoint = null
                    activePlan = null; coordinator.invalidate()
                },
                onEditTo = {
                    toPoint = null
                    activePlan = null
                    coordinator.invalidate()
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
                gpxImportError = gpxImportError,
                onDismissGpxError = { gpxImportError = null },
                onSaveRoute = ::saveProposedRoute,
                onToggleGuidance = {
                    if (locationPermissionGranted) {
                        guidanceRequested = true
                    } else {
                        pendingGuidanceStart = true
                        permissionLauncher.launch(LocationPermission.requestedPermissions)
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
        // Tapping anywhere outside the expanded card dismisses the settings
        // menu. The rail is drawn after this scrim, so its own 48 dp targets
        // stay reachable while the menu is open.
        if (mapsSettingsOpen) {
            MapsSettingsScreen(
                state = visualMapsPreview ?: mapsState,
                rideActive = rideActive,
                onDismiss = {
                    regionManager.clearMessages()
                    mapsSettingsOpen = false
                },
                onCheckServer = { address ->
                    regionManager.saveServerAddress(address)
                    regionManager.checkServer()
                },
                onToggleInsecure = regionManager::setAllowInsecureLocal,
                onRequestBuild = regionManager::requestBuild,
                onCancelBuild = regionManager::cancelActiveJob,
                onDownloadPackage = { regionId ->
                    pendingMapsDownload = MapsDownloadAction.New(regionId)
                    mapsDownloadPicker.launch(regionManager.suggestedFileName(regionId))
                },
                onCancelDownload = regionManager::cancelDownload,
                onResumeDownload = {
                    pendingMapsDownload = MapsDownloadAction.Resume
                    mapsDownloadPicker.launch(regionManager.suggestedFileNameForResume())
                },
                onSaveRecoveredPackage = {
                    pendingMapsDownload = MapsDownloadAction.SaveRecovered
                    mapsDownloadPicker.launch(regionManager.suggestedFileNameForRecovered())
                },
                onImportRecoveredPackage = regionManager::importRecoveredPackage,
                onImportSavedPackage = regionManager::importSavedPackage,
                onImportPackage = {
                    mapsPackagePicker.launch(arrayOf("application/octet-stream", "application/zip", "*/*"))
                },
                onActivate = regionManager::activate,
                onActivateBundled = regionManager::activateBundled,
                onRemove = regionManager::requestRemove,
                onDismissMessage = regionManager::clearMessages,
            )
        }
        if (settingsMenuOpen) {
            Box(
                Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = null,
                        indication = null,
                        onClick = { settingsMenuOpen = false },
                    )
            )
        }
        if (!guidanceRequested || rideActive) {
            RouteActionsPill(
                onLoadMap = chooseMapFile,
                onImportGpx = {
                    gpxPicker.launch(
                        arrayOf(
                            "application/gpx+xml",
                            "application/gpx",
                            "text/xml",
                            "application/xml",
                            "*/*",
                        )
                    )
                },
                onRouteSettings = {
                    // The settings dialogs share the APPLY label; only one may
                    // be open at a time so the confirm action is unambiguous.
                    voiceSettingsOpen = false
                    routeSettingsOpen = true
                },
                onMapsSettings = {
                    regionManager.clearMessages()
                    routeSettingsOpen = false
                    voiceSettingsOpen = false
                    mapsSettingsOpen = true
                },
                onVoiceSettings = {
                    routeSettingsOpen = false
                    voiceGuidanceOutput.refreshAvailability()
                    voiceSettingsOpen = true
                },
                voiceGuidanceEnabled = voiceGuidanceEnabled,
                voiceSpeechStatus = voiceSpeechStatus,
                onOpenSavedRoutes = { savedRoutesOpen = true },
                settingsMenuExpanded = settingsMenuOpen,
                onSettingsMenuExpandedChange = { settingsMenuOpen = it },
                rideMode = rideActive,
                darkRideMapEnabled = darkRideMapEnabled,
                monochrome = darkRideMapEnabled,
                onDarkRideMapToggle = {
                    darkRideMapEnabled = !darkRideMapEnabled
                    routePreferences.edit()
                        .putBoolean(DARK_RIDE_MAP_PREF, darkRideMapEnabled)
                        .apply()
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 32.dp, end = if (rideActive) 0.dp else 8.dp),
            )
        }
    }
    BackHandler(enabled = settingsMenuOpen) { settingsMenuOpen = false }
    // The Maps screen is a full-screen surface; system Back closes it like the
    // other screens opened from the settings card. The card keeps priority
    // when it is also open (it is drawn above the Maps screen).
    BackHandler(enabled = mapsSettingsOpen && !settingsMenuOpen) {
        regionManager.clearMessages()
        mapsSettingsOpen = false
    }
    // System Back closes the open media panel instead of leaving the app. The
    // settings card keeps priority: this handler is disabled while it is open,
    // so one Back always peels exactly the top-most surface.
    BackHandler(enabled = rideActive && mediaPanelOpen && !settingsMenuOpen) {
        mediaPanelOpen = false
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

    if (voiceSettingsOpen) {
        VoiceGuidanceSettingsDialog(
            current = voiceGuidanceSettings,
            unit = voiceUnit,
            speechStatus = voiceSpeechStatus,
            onDismiss = { voiceSettingsOpen = false },
            onApply = { settings ->
                voiceGuidanceEnabled = settings.enabled
                voiceIntervalMeters = settings.intervalMeters
                voicePreviewCount = settings.previewCount
                routePreferences.edit()
                    .putBoolean(VOICE_ENABLED_PREF, settings.enabled)
                    .putFloat(VOICE_INTERVAL_METERS_PREF, settings.intervalMeters.toFloat())
                    .putInt(VOICE_PREVIEW_COUNT_PREF, settings.previewCount)
                    .apply()
                // Applying while riding takes effect immediately; enabling
                // speech also previews the current next turns right away.
                if (guidanceRequested &&
                    (navSnapshot.state != NavigationState.Idle || !settings.enabled)
                ) {
                    voiceGuidanceOutput.onSnapshot(navSnapshot, settings)
                }
                voiceSettingsOpen = false
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

    if (mapUrl != null && mapImportError != null) {
        AlertDialog(
            onDismissRequest = { mapImportError = null },
            title = { Text("Map file not loaded") },
            text = { Text(mapImportError.orEmpty()) },
            confirmButton = {
                TextButton(onClick = { mapImportError = null }) { Text("OK") }
            },
        )
    }
}

private fun coordinateLabel(point: GeoPoint): String =
    "%.5f, %.5f".format(Locale.US, point.lat, point.lon)

/**
 * Turns GraphHopper's routing errors into honest user-facing messages. Points
 * outside the active dataset are the expected failure when a saved ride or a
 * search result belongs to a region that is not installed; the message says so
 * instead of leaking the engine's internal wording.
 */
internal fun describeRoutingFailure(message: String): String {
    val lower = message.lowercase(Locale.US)
    return when {
        lower.contains("out of bounds") || lower.contains("outside of the") ->
            "That place is outside the active region. Install or activate a region that covers it."
        lower.contains("no route was found") ->
            "No route was found in the active region."
        lower.contains("no road") ->
            "No routable road was found near one of the points in the active region."
        else -> message
    }
}

private fun milestoneEndpointLabel(milestone: GpxMilestone): String =
    listOfNotNull(milestone.placeName, milestone.placeDetail?.takeIf(String::isNotBlank))
        .joinToString(", ")
        .ifBlank { coordinateLabel(milestone.point) }

/**
 * Camera zoom for the current riding speed, in discrete bands so the map
 * eases out as the rider speeds up and back in when they slow down. The
 * closer low-speed levels keep turns readable; the heading-up tilt shows a
 * useful distance ahead as the rider speeds up. Invalid or missing speed
 * (NaN, infinity, negative) reads as stationary, which is the closest band,
 * so a dropped speed sample can never zoom the rider out mid-corner.
 */
internal fun guidanceZoomFor(speedMps: Double): Double = when {
    !speedMps.isFinite() || speedMps < 7.0 -> 18.0
    speedMps < 14.0 -> 17.0
    speedMps < 22.0 -> 16.0
    speedMps < 31.0 -> 15.0
    else -> 14.0
}

/** Forward pitch the guidance view holds; the low rider stays at [GUIDANCE_RIDER_VERTICAL_FRACTION] of the map viewport. */
internal const val GUIDANCE_TILT_DEGREES = 58.0
internal const val GUIDANCE_RIDER_VERTICAL_FRACTION = 0.70
internal const val GUIDANCE_TOP_PADDING_FRACTION =
    2.0 * GUIDANCE_RIDER_VERTICAL_FRACTION - 1.0

/**
 * How long a queued debug fix owns guidance. The emulator's live provider
 * keeps replaying its parked position, so between runner injections it would
 * yank the camera off the injected path. Release builds never enable debug
 * logging and always follow live GPS.
 */
internal const val DEBUG_FIX_AUTHORITY_MS = 20_000L

internal fun debugFixOwnsGuidance(debugLogs: Boolean, sinceDebugFixMs: Long?): Boolean =
    debugLogs && sinceDebugFixMs != null && sinceDebugFixMs in 0..DEBUG_FIX_AUTHORITY_MS

/** Keeps the last usable course when GPS temporarily stops reporting heading. */
internal class GuidanceBearingTracker {
    private var lastReliableBearing: Double? = null

    fun resolve(incomingBearing: Double, cameraBearing: Double): Double {
        if (incomingBearing.isFinite()) {
            lastReliableBearing = normalizeBearing(incomingBearing)
        }
        return lastReliableBearing
            ?: normalizeBearing(cameraBearing.takeIf(Double::isFinite) ?: 0.0)
    }

    fun reset() {
        lastReliableBearing = null
    }
}

internal fun bearingDistanceDegrees(first: Double, second: Double): Double {
    if (!first.isFinite() || !second.isFinite()) return Double.POSITIVE_INFINITY
    val difference = (normalizeBearing(first) - normalizeBearing(second) + 540.0) % 360.0
    return kotlin.math.abs(difference - 180.0)
}

internal fun guidanceOrientationNeedsUpdate(
    cameraBearing: Double,
    targetBearing: Double,
    cameraTilt: Double,
): Boolean = bearingDistanceDegrees(cameraBearing, targetBearing) >= 6.0 ||
    !cameraTilt.isFinite() || kotlin.math.abs(cameraTilt - GUIDANCE_TILT_DEGREES) >= 1.0

/** Planner follow always returns a manually tilted/rotated map to north-up. */
internal fun planningOrientationNeedsUpdate(
    cameraBearing: Double,
    cameraTilt: Double,
): Boolean = bearingDistanceDegrees(cameraBearing, 0.0) >= 1.0 ||
    !cameraTilt.isFinite() || kotlin.math.abs(cameraTilt) >= 1.0

private fun normalizeBearing(bearing: Double): Double = ((bearing % 360.0) + 360.0) % 360.0

/**
 * Suppresses camera churn from stationary GPS jitter while retaining a
 * movement-dependent follow cadence. The distance is measured from the
 * camera's current target, so a moving camera does not restart an animation
 * for every small location update; [desiredZoom] still honors the caller's
 * current follow zoom or guidance speed band.
 */
internal fun shouldFollowCamera(
    cameraLat: Double,
    cameraLon: Double,
    cameraZoom: Double,
    targetLat: Double,
    targetLon: Double,
    desiredZoom: Double,
    minimumMovementMeters: Double,
): Boolean {
    if (!cameraLat.isFinite() || !cameraLon.isFinite() || !cameraZoom.isFinite() ||
        !targetLat.isFinite() || !targetLon.isFinite() || !desiredZoom.isFinite()
    ) {
        return true
    }
    val movement = RouteSimilarity.haversineMeters(
        GeoPoint(cameraLat, cameraLon),
        GeoPoint(targetLat, targetLon),
    )
    return movement >= minimumMovementMeters.coerceAtLeast(0.0) ||
        abs(cameraZoom - desiredZoom) >= CAMERA_ZOOM_FOLLOW_THRESHOLD
}

/** Detects an intentional pan gesture while the zoom stayed effectively fixed. */
internal fun isManualPan(
    startLat: Double,
    startLon: Double,
    startZoom: Double,
    currentLat: Double,
    currentLon: Double,
    currentZoom: Double,
): Boolean {
    if (!startLat.isFinite() || !startLon.isFinite() || !startZoom.isFinite() ||
        !currentLat.isFinite() || !currentLon.isFinite() || !currentZoom.isFinite()
    ) {
        return false
    }
    val movedMeters = RouteSimilarity.haversineMeters(
        GeoPoint(startLat, startLon),
        GeoPoint(currentLat, currentLon),
    )
    return movedMeters >= MANUAL_PAN_RELEASE_METERS &&
        abs(startZoom - currentZoom) < CAMERA_ZOOM_FOLLOW_THRESHOLD
}

/** Stationary jitter needs to accumulate; actual motion gets a faster cadence. */
internal fun followMovementThresholdMeters(speedMps: Double): Double =
    if (speedMps.isFinite() && speedMps >= 1.0) {
        (speedMps * 0.5).coerceIn(MOVING_FOLLOW_MIN_METERS, MOVING_FOLLOW_MAX_METERS)
    } else {
        STATIONARY_FOLLOW_MIN_METERS
    }

private const val CAMERA_ZOOM_FOLLOW_THRESHOLD = 0.15
private const val STATIONARY_FOLLOW_MIN_METERS = 15.0
private const val MOVING_FOLLOW_MIN_METERS = 5.0
private const val MOVING_FOLLOW_MAX_METERS = 30.0
private const val MANUAL_PAN_RELEASE_METERS = 12.0

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
    rideMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val iconColor = if (rideMode) RideSurfaceColor else Color(0x8A000000)
    Column(
        modifier = modifier
            .size(width = 48.dp, height = 96.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White)
    ) {
        ZoomPillCell(plus = true, iconColor = iconColor, onClick = onZoomIn)
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0xFF1E000000))
        )
        ZoomPillCell(plus = false, iconColor = iconColor, onClick = onZoomOut)
    }
}

/**
 * A 48 dp map control that toggles camera lock to the rider. Planning keeps its
 * selected zoom; guidance restores its speed-based framing when the lock returns.
 *
 * It shares the map pills' palette — white fill with [RideSurfaceColor] ink —
 * and inverts it while the lock is on, so no third colour appears on the map.
 */
@Composable
private fun CentreOnMeButton(
    visible: Boolean,
    active: Boolean,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val backgroundColor by animateColorAsState(
        targetValue = if (active) RideSurfaceColor else Color.White,
        label = "followMeBackground",
    )
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(backgroundColor)
            .semantics {
                contentDescription = description
            }
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        CentreOnMeGlyph(color = if (active) Color.White else RideSurfaceColor)
    }
}

/**
 * The shared map-target glyph for the planner and ride camera-lock controls.
 */
@Composable
internal fun CentreOnMeGlyph(color: Color = RideSurfaceColor) {
    Canvas(Modifier.size(24.dp)) {
        val stroke = 2.dp.toPx()
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

@Composable
private fun ZoomPillCell(plus: Boolean, iconColor: Color, onClick: () -> Unit) {
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
            val lineColor = iconColor
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
private fun MissingMapCard(
    importing: Boolean,
    error: String?,
    onChooseFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color.White.copy(alpha = 0.96f),
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 6.dp,
        modifier = modifier.padding(24.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(20.dp),
        ) {
            Text("Offline map file needed", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                error ?: "Download the .pmtiles map to this phone, then select it here.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onChooseFile, enabled = !importing) {
                Text(if (importing) "Importing…" else "Choose map file")
            }
        }
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
        var destroyedByLifecycle = false
        val observer = LifecycleEventObserver { _, event ->
            if (isDebugBuild(context.applicationContext)) {
                Log.d(TAG, "MapView lifecycle event=$event state=${lifecycleOwner.lifecycle.currentState}")
            }
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> {
                    destroyedByLifecycle = true
                    mapView.onDestroy()
                }
                else -> Unit
            }
        }
        if (isDebugBuild(context.applicationContext)) {
            Log.d(TAG, "Registering MapView lifecycle observer at ${lifecycleOwner.lifecycle.currentState}")
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        mapView.getMapAsync { map ->
            map.uiSettings.isTiltGesturesEnabled = true
            onMapReady(map)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (!destroyedByLifecycle) mapView.onDestroy()
        }
    }
    return mapView
}

private fun OfflineSpeechStatus.hudDescription(): String = when (this) {
    OfflineSpeechStatus.NotStarted -> "offline voice not checked yet"
    OfflineSpeechStatus.Checking -> "checking for English offline voice"
    is OfflineSpeechStatus.Ready -> "English offline voice ready"
    is OfflineSpeechStatus.Unavailable -> "English offline voice unavailable"
}
