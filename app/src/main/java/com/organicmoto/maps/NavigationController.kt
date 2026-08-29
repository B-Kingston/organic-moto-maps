package com.organicmoto.maps

import android.util.Log
import com.graphhopper.util.shapes.GHPoint
import com.organicmoto.maps.routing.navigation.GpsFix
import com.organicmoto.maps.routing.navigation.GpsFixSource
import com.organicmoto.maps.routing.navigation.NavigationSession
import com.organicmoto.maps.routing.navigation.RebuildRequest
import com.organicmoto.maps.routing.navigation.RouteTrack
import com.organicmoto.maps.routing.navigation.RouteTrackFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val TAG = "OrganicMoto.NavSession"

/**
 * Owns one guidance session for the route screen and glues the pure engine to
 * Android: GPS fixes in, HUD snapshots out, off-route rebuilds through the
 * same generation-safe route coordinator used for planning.
 *
 * The screen supplies a [rebuild] callback that performs the actual reroute;
 * it must be safe to call from the GPS collection coroutine.
 */
class NavigationController(
    private val scope: CoroutineScope,
    private val session: NavigationSession = NavigationSession(),
) {
    val snapshot = session.snapshot

    private var fixJob: Job? = null
    private var rebuild: (suspend (RebuildRequest) -> Unit)? = null

    /** Registers the async rebuild callback; call before [start]. */
    fun setRebuildHandler(handler: suspend (RebuildRequest) -> Unit) {
        rebuild = handler
    }

    /** Begins following [path]: loads the track, starts GPS, follows. */
    fun start(path: com.graphhopper.ResponsePath) {
        val track = RouteTrackFactory.fromPath(path)
        session.startRoute(track)
        session.beginFollowing()
        fixJob?.cancel()
        fixJob = scope.launch {
            GpsFixSource.fixes(NavigationContext.appContext).collect { fix ->
                session.onFix(fix)
            }
        }
        Log.i(TAG, "Guidance started (${track.totalDistanceM.toInt()} m, ${track.turns.size} turns)")
    }

    /** Stops guidance and releases GPS. */
    fun stop() {
        fixJob?.cancel()
        fixJob = null
        session.stop()
        Log.i(TAG, "Guidance stopped")
    }

    init {
        session.setRebuildListener { request ->
            val handler = rebuild ?: run {
                Log.w(TAG, "Off-route detected but no rebuild handler; guidance continues stale")
                return@setRebuildListener
            }
            Log.i(
                TAG,
                "Off-route: rebuilding from last good position (heading ${request.headingDeg})",
            )
            scope.launch {
                // Freeze the engine while the reroute is in flight.
                session.apply { /* state transition handled by handler via suspendRebuild */ }
                handler(request)
            }
        }
    }
}

/** Application context holder so GpsFixSource can be created without leaking the Activity. */
internal object NavigationContext {
    @Volatile
    lateinit var appContext: android.content.Context
}
