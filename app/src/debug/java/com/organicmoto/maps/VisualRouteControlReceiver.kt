package com.organicmoto.maps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Debug-build-only bridge from ADB to RouteScreen's real navigation session. */
class VisualRouteControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || intent.action !in setOf(
                VisualRouteSnapshot.FIX_ACTION, VisualRouteSnapshot.CAMERA_ACTION, VisualRouteSnapshot.MAP_PROBE_ACTION,
            )) return
        val requestId = intent.getStringExtra("request_id")?.toLongOrNull() ?: return
        if (intent.action == VisualRouteSnapshot.MAP_PROBE_ACTION) {
            VisualRouteSnapshot.queueMapProbe(context.cacheDir, requestId)
            return
        }
        val lat = intent.getStringExtra("lat")?.toDoubleOrNull() ?: return
        val lon = intent.getStringExtra("lon")?.toDoubleOrNull() ?: return
        if (!lat.isFinite() || lat !in -90.0..90.0 || !lon.isFinite() || lon !in -180.0..180.0) {
            return
        }
        if (intent.action == VisualRouteSnapshot.CAMERA_ACTION) {
            val zoom = intent.getStringExtra("zoom")?.toDoubleOrNull() ?: return
            val tilt = intent.getStringExtra("tilt")?.toDoubleOrNull() ?: return
            val bearing = intent.getStringExtra("bearing")?.toDoubleOrNull() ?: return
            if (!zoom.isFinite() || zoom !in 0.0..20.0 || !tilt.isFinite() || tilt !in 0.0..60.0 || !bearing.isFinite()) return
            VisualRouteSnapshot.queueCamera(context.cacheDir, requestId, lat, lon, zoom, tilt, bearing)
            return
        }
        val speedMps = intent.getFloatExtra("speed", Float.NaN)
            .takeIf { it.isFinite() && it >= 0f }
            ?.toDouble()
        VisualRouteSnapshot.queueFix(context.cacheDir, requestId, lat, lon, speedMps)
    }
}
