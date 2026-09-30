package com.organicmoto.maps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Debug-build-only bridge from ADB to RouteScreen's real navigation session. */
class VisualRouteControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != VisualRouteSnapshot.FIX_ACTION) return
        val requestId = intent.getStringExtra("request_id")?.toLongOrNull() ?: return
        val lat = intent.getStringExtra("lat")?.toDoubleOrNull() ?: return
        val lon = intent.getStringExtra("lon")?.toDoubleOrNull() ?: return
        if (!lat.isFinite() || lat !in -90.0..90.0 || !lon.isFinite() || lon !in -180.0..180.0) {
            return
        }
        val speedMps = intent.getFloatExtra("speed", Float.NaN)
            .takeIf { it.isFinite() && it >= 0f }
            ?.toDouble()
        VisualRouteSnapshot.queueFix(context.cacheDir, requestId, lat, lon, speedMps)
    }
}
