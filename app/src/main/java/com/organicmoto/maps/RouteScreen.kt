package com.organicmoto.maps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.graphhopper.ResponsePath
import com.graphhopper.util.shapes.GHPoint
import com.organicmoto.maps.geocoding.GeocodeSearchController
import com.organicmoto.maps.map.drawRoute
import com.organicmoto.maps.map.fitBounds
import com.organicmoto.maps.routing.GraphHopperRouter
import com.organicmoto.maps.routing.PointParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "OrganicMoto.RouteScreen"
private const val MAP_STYLE_URL = "https://demotiles.maplibre.org/style.json"

/**
 * True for debuggable (debug) builds. Release APKs are never debuggable, so
 * user-typed queries, source/destination text and coordinates are only logged
 * when this is true (debug builds), never in release.
 */
private fun isDebugBuild(context: Context): Boolean =
    (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

sealed interface RouteUiState {
    data object Idle : RouteUiState
    data object Loading : RouteUiState
    data class Success(val path: ResponsePath) : RouteUiState
    data class Error(val message: String) : RouteUiState
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
    val geocodeController = remember { GeocodeSearchController(context.applicationContext) }
    val debugLogs = remember { isDebugBuild(context.applicationContext) }
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val mapRef = remember { mutableStateOf<MapLibreMap?>(null) }
    val mapView = rememberMapView(context, MAP_STYLE_URL) { mapRef.value = it }

    LaunchedEffect(state) {
        val success = state as? RouteUiState.Success ?: return@LaunchedEffect
        val map = mapRef.value ?: return@LaunchedEffect
        Log.d(TAG, "Drawing route: ${success.path.points.size()} points")
        map.drawRoute(success.path)
        map.fitBounds(success.path.points)
        Log.d(TAG, "Route drawn and camera fitted")
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = (screenHeightDp * 0.10f).dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                .padding(12.dp)
        ) {
            GeocodeSearchField(
                label = "From",
                value = fromText,
                onValueChange = { fromText = it; fromPoint = null },
                onResultPicked = { result ->
                    fromText =
                        if (result.subtitle.isNotBlank()) "${result.name}, ${result.subtitle}" else result.name
                    fromPoint = GHPoint(result.lat, result.lon)
                },
                controller = geocodeController,
                placeholder = "Place, address, or lat,lon",
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            GeocodeSearchField(
                label = "To",
                value = toText,
                onValueChange = { toText = it; toPoint = null },
                onResultPicked = { result ->
                    toText =
                        if (result.subtitle.isNotBlank()) "${result.name}, ${result.subtitle}" else result.name
                    toPoint = GHPoint(result.lat, result.lon)
                },
                controller = geocodeController,
                placeholder = "Place, address, or lat,lon",
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    scope.launch {
                        if (debugLogs) {
                            Log.i(TAG, "Route submitted: from=\"$fromText\" to=\"$toText\"")
                        } else {
                            Log.i(TAG, "Route submitted")
                        }
                        val started = SystemClock.elapsedRealtime()
                        routeState.value = RouteUiState.Loading
                        routeState.value = withContext(Dispatchers.IO) {
                            try {
                                val path = router.route(
                                    fromPoint ?: resolvePoint(fromText, geocodeController, "From", debugLogs),
                                    toPoint ?: resolvePoint(toText, geocodeController, "To", debugLogs)
                                )
                                Log.i(
                                    TAG,
                                    "Route success in ${SystemClock.elapsedRealtime() - started} ms: " +
                                        "${path.distance}m, ${path.time}ms, ${path.points.size()} points"
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
                    }
                },
                enabled = state !is RouteUiState.Loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state is RouteUiState.Loading) "Routing..." else "Route")
            }
            (state as? RouteUiState.Error)?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = it.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun rememberMapView(
    context: Context,
    styleUrl: String,
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
            map.setStyle(styleUrl)
            onMapReady(map)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }
    return mapView
}
