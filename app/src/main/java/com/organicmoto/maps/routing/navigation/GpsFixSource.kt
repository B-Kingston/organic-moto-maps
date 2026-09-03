package com.organicmoto.maps.routing.navigation

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import com.organicmoto.maps.LocationPermission
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

private const val TAG = "OrganicMoto.NavGps"

/** GPS-reported full-rate interval while the rider is moving (ms). */
private const val MIN_INTERVAL_MS = 1_000L
/** Stationary pulse interval for the GPS provider (ms). */
private const val SLOW_INTERVAL_MS = 15_000L
/** Stationary this long without a moving fix → drop GPS to the slow pulse. */
private const val STATIONARY_CUTOFF_MS = 60_000L
/** Watchdog poll period (ms). */
private const val STATIONARY_CHECK_MS = 15_000L
/** Speed above this (m/s) counts as riding and re-arms the full rate. */
private const val MOVING_SPEED_MPS = 1.0f

/**
 * Emits GPS fixes from the platform [LocationManager] (no Play Services — the
 * app has zero network/Play dependencies). GPS provider is preferred; falls
 * back to network and passive providers so a fix still arrives where GPS is
 * off but another app is streaming.
 *
 * Battery policy for always-on map tracking: while the rider moves, every
 * provider streams at [MIN_INTERVAL_MS] so the position dot keeps full
 * refresh rate. After [STATIONARY_CUTOFF_MS] without a moving fix the GPS
 * provider alone drops to the [SLOW_INTERVAL_MS] pulse (network + passive
 * stay cheap and keep the dot alive while parked); one moving fix re-arms
 * the full rate immediately.
 */
object GpsFixSource {

    fun fixes(context: Context): Flow<GpsFix> = callbackFlow {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!LocationPermission.isGranted(context)) {
            Log.w(TAG, "Location permission missing; tracking stays idle")
            close()
            return@callbackFlow
        }
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            Log.w(TAG, "No enabled location provider; tracking stays idle")
            close()
            return@callbackFlow
        }
        var lowRate = false
        var lastMoveAtMs = System.currentTimeMillis()


        var armHighRate: () -> Unit = {}
        val listener = LocationListener { location ->
            val fix = toFix(location)
            if (fix.speedMps >= MOVING_SPEED_MPS) armHighRate()
            trySend(fix)
        }
        fun request(provider: String, intervalMs: Long) {
            manager.requestLocationUpdates(provider, intervalMs, 0f, listener, Looper.getMainLooper())
        }

        fun replaceRegistrations(gpsIntervalMs: Long) {
            // LocationManager does not promise that a second request for the
            // same listener replaces the first request's interval. Remove the
            // complete listener registration before changing rate, otherwise
            // the original 1 s GPS subscription can survive forever.
            manager.removeUpdates(listener)
            for (provider in providers) {
                request(
                    provider,
                    if (provider == LocationManager.GPS_PROVIDER) gpsIntervalMs else MIN_INTERVAL_MS,
                )
            }
        }

        fun armLowRate() {
            if (lowRate) return
            try {
                // GPS is the expensive provider: only it slows down. Network +
                // passive keep the dot alive cheaply while parked.
                replaceRegistrations(SLOW_INTERVAL_MS)
                lowRate = true
                Log.d(TAG, "GPS rate: stationary pulse")
            } catch (e: Exception) {
                Log.w(TAG, "Could not lower GPS update rate", e)
                close(e)
            }
        }

        armHighRate = {
            if (!lowRate) {
                lastMoveAtMs = System.currentTimeMillis()
            } else {
                try {
                    replaceRegistrations(MIN_INTERVAL_MS)
                    lowRate = false
                    lastMoveAtMs = System.currentTimeMillis()
                    Log.d(TAG, "GPS rate: full 1 s")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not restore GPS update rate", e)
                    close(e)
                }
            }
        }


        try {
            replaceRegistrations(MIN_INTERVAL_MS)
            // Seed immediately so the dot appears without waiting a second.
            providers.firstNotNullOfOrNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
                ?.let { trySend(toFix(it)) }
        } catch (e: Exception) {
            Log.w(TAG, "requestLocationUpdates failed", e)
            close(e)
            return@callbackFlow
        }

        val watchdog = launch {
            while (true) {
                delay(STATIONARY_CHECK_MS)
                if (!lowRate && System.currentTimeMillis() - lastMoveAtMs > STATIONARY_CUTOFF_MS) {
                    armLowRate()
                }
            }
        }

        awaitClose {
            watchdog.cancel()
            manager.removeUpdates(listener)
        }
    }

    private fun toFix(location: Location): GpsFix = GpsFix(
        lat = location.latitude,
        lon = location.longitude,
        bearingDeg = if (location.hasBearing()) location.bearing.toDouble() else Double.NaN,
        speedMps = if (location.hasSpeed()) location.speed.toDouble() else -1.0,
        accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else Double.NaN,
        timestampMs = location.time,
    )
}
