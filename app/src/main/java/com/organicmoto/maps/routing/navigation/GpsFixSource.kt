package com.organicmoto.maps.routing.navigation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "OrganicMoto.NavGps"

/**
 * Emits GPS fixes from the platform [LocationManager] (no Play Services — the
 * app has zero network/Play dependencies). GPS provider is preferred; falls
 * back to network and passive providers so guidance still works where GPS is
 * off but another app is streaming fixes.
 */
object GpsFixSource {

    fun fixes(context: Context): Flow<GpsFix> = callbackFlow {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Location permission missing; guidance stays idle")
            close()
            return@callbackFlow
        }
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            Log.w(TAG, "No enabled location provider; guidance stays idle")
            close()
            return@callbackFlow
        }
        val listener = LocationListener { location: Location ->
            trySend(toFix(location))
        }
        val minDistanceM = 0f
        try {
            for (provider in providers) {
                manager.requestLocationUpdates(provider, minIntervalMs, minDistanceM, listener, Looper.getMainLooper())
            }
            // Seed immediately with the last known fix so guidance starts
            // oriented instead of waiting up to a second for the first update.
            providers.firstNotNullOfOrNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
                ?.let { trySend(toFix(it)) }
        } catch (e: Exception) {
            Log.w(TAG, "requestLocationUpdates failed", e)
            close(e)
            return@callbackFlow
        }
        awaitClose {
            manager.removeUpdates(listener)
        }
    }

    private fun toFix(location: Location): GpsFix = GpsFix(
        lat = location.latitude,
        lon = location.longitude,
        speedMps = if (location.hasSpeed()) location.speed.toDouble() else -1.0,
        accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else Double.NaN,
        timestampMs = location.time,
    )

    private const val minIntervalMs = 1_000L
}
