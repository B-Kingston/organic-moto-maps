package com.organicmoto.maps

import android.content.Context
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.graphhopper.ResponsePath
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

private const val MAP_STYLE_URL = "https://demotiles.maplibre.org/style.json"

sealed interface RouteUiState {
    data object Idle : RouteUiState
    data object Loading : RouteUiState
    data class Success(val path: ResponsePath) : RouteUiState
    data class Error(val message: String) : RouteUiState
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
    val mapRef = remember { mutableStateOf<MapLibreMap?>(null) }
    val mapView = rememberMapView(context, MAP_STYLE_URL) { mapRef.value = it }

    LaunchedEffect(state) {
        val success = state as? RouteUiState.Success ?: return@LaunchedEffect
        val map = mapRef.value ?: return@LaunchedEffect
        map.drawRoute(success.path)
        map.fitBounds(success.path.points)
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                .padding(12.dp)
        ) {
            OutlinedTextField(
                value = fromText,
                onValueChange = { fromText = it },
                label = { Text("From (lat,lon)") },
                placeholder = { Text("48.137,11.575") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = toText,
                onValueChange = { toText = it },
                label = { Text("To (lat,lon)") },
                placeholder = { Text("52.520,13.405") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    scope.launch {
                        routeState.value = RouteUiState.Loading
                        routeState.value = withContext(Dispatchers.IO) {
                            try {
                                RouteUiState.Success(
                                    router.route(
                                        PointParser.parse(fromText),
                                        PointParser.parse(toText)
                                    )
                                )
                            } catch (e: Exception) {
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
