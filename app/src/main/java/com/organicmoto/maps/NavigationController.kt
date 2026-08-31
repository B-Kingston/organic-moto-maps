package com.organicmoto.maps

import android.util.Log
import com.organicmoto.maps.routing.navigation.GpsFix
import com.organicmoto.maps.routing.navigation.NavigationSession
import com.organicmoto.maps.routing.navigation.RebuildRequest
import com.organicmoto.maps.routing.navigation.RouteTrackFactory
import kotlinx.coroutines.CoroutineScope
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

    private var rebuild: (suspend (RebuildRequest) -> Unit)? = null

    /** Registers the async rebuild callback; call before [start]. */
    fun setRebuildHandler(handler: suspend (RebuildRequest) -> Unit) {
        rebuild = handler
    }

    /** Begins following [path], or atomically installs it as an off-route rebuild. */
    fun start(path: com.graphhopper.ResponsePath) {
        val track = RouteTrackFactory.fromPath(path)
        if (session.snapshot.value.state in setOf(
                com.organicmoto.maps.routing.navigation.NavigationState.NeedRebuild,
                com.organicmoto.maps.routing.navigation.NavigationState.Rebuilding,
            )
        ) {
            session.applyRebuiltRoute(track, session.currentRouteCoveredM())
        } else {
            session.startRoute(track)
            session.beginFollowing()
        }
        Log.i(TAG, "Guidance started (${track.totalDistanceM.toInt()} m, ${track.turns.size} turns)")
    }

    /** Feeds the shared screen-level location stream into guidance. */
    fun onFix(fix: GpsFix) {
        session.onFix(fix)
    }

    /** Stops guidance. Location ownership remains with the screen. */
    fun stop() {
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
            session.markRebuilding()
            scope.launch {
                handler(request)
            }
        }
    }
}
