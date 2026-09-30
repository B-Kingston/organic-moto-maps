package com.organicmoto.maps.routing.navigation

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.organicmoto.maps.LocationPermission
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

private const val TAG = "OrganicMoto.NavGps"
private const val PROVIDER_RECHECK_INTERVAL_MS = 5_000L
private const val PROVIDER_FAILURE_RETRY_MS = 10_000L
private const val MAX_FIX_AGE_MS = 30_000L

/** Narrow platform seam so the adaptive subscription can be exercised on-device. */
internal interface LocationProviderClient {
    fun isProviderEnabled(provider: LocationFixProvider): Boolean
    fun lastKnownLocation(provider: LocationFixProvider): Location?
    fun requestLocationUpdates(
        provider: LocationFixProvider,
        intervalMs: Long,
        listener: LocationListener,
    )
    fun removeUpdates(listener: LocationListener)
}

/**
 * Emits monotonic location fixes from LocationManager. Healthy GPS is the
 * primary source. Network is started only after GPS stalls or is disabled;
 * passive is added only when active fallback cannot produce fixes. All active
 * subscriptions are removed when collection ends.
 */
object GpsFixSource {

    fun fixes(context: Context): Flow<GpsFix> {
        if (!LocationPermission.isGranted(context)) {
            Log.w(TAG, "Location permission missing; tracking stays idle")
            return callbackFlow { close() }
        }

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return fixes(
            client = AndroidLocationProviderClient(context.applicationContext, manager),
            fineLocationGranted = LocationPermission.isFineGranted(context),
            nowElapsedMs = SystemClock::elapsedRealtime,
        )
    }

    internal fun fixes(
        client: LocationProviderClient,
        fineLocationGranted: Boolean,
        nowElapsedMs: () -> Long,
    ): Flow<GpsFix> = callbackFlow {
        val startedAt = nowElapsedMs()
        val policy = AdaptiveLocationPolicy(startedAt)
        val freshnessGate = MonotonicFixGate(MAX_FIX_AGE_MS)
        val stateLock = Any()
        val activeRequests = mutableMapOf<LocationFixProvider, Long>()
        val failedUntil = mutableMapOf<LocationFixProvider, Long>()
        var closed = false

        fun providerAvailability(now: Long): LocationProviderAvailability {
            fun enabled(provider: LocationFixProvider): Boolean {
                if (provider == LocationFixProvider.GPS && !fineLocationGranted) return false
                if ((failedUntil[provider] ?: 0L) > now) return false
                return try {
                    client.isProviderEnabled(provider)
                } catch (security: SecurityException) {
                    failedUntil[provider] = now + PROVIDER_FAILURE_RETRY_MS
                    Log.w(TAG, "No permission for ${provider.platformName}; trying fallback providers", security)
                    false
                } catch (_: Exception) {
                    false
                }
            }
            return LocationProviderAvailability(
                gps = enabled(LocationFixProvider.GPS),
                network = enabled(LocationFixProvider.NETWORK),
                passive = enabled(LocationFixProvider.PASSIVE),
            )
        }

        lateinit var listener: LocationListener

        fun reconcileLocked(now: Long) {
            if (closed) return
            // Retry failed registrations after a cooldown, and observe provider
            // enable/disable changes even if the disabled provider was not
            // part of the previous subscription plan.
            for (provider in LocationFixProvider.entries) {
                if ((failedUntil[provider] ?: 0L) <= now) failedUntil.remove(provider)
            }
            val availability = providerAvailability(now)
            if (closed) return
            val desired = policy.requests(now, availability)
                .associate { it.provider to it.intervalMs }
            if (desired == activeRequests) return

            runCatching { client.removeUpdates(listener) }
                .onFailure { Log.d(TAG, "Could not remove old location registrations", it) }
            activeRequests.clear()

            // A failed provider is suppressed for a short monotonic cooldown;
            // recompute immediately so a broken GPS request does not prevent a
            // network/passive fallback from starting.
            var remaining = LocationFixProvider.entries.size + 1
            var plan = desired
            while (plan.isNotEmpty() && remaining-- > 0 && !closed) {
                var planChanged = false
                for ((provider, intervalMs) in plan) {
                    try {
                        client.requestLocationUpdates(provider, intervalMs, listener)
                        activeRequests[provider] = intervalMs
                    } catch (security: SecurityException) {
                        failedUntil[provider] = nowElapsedMs() + PROVIDER_FAILURE_RETRY_MS
                        Log.w(TAG, "No permission for ${provider.platformName}; trying fallback providers", security)
                        planChanged = true
                    } catch (failure: Exception) {
                        failedUntil[provider] = nowElapsedMs() + PROVIDER_FAILURE_RETRY_MS
                        Log.w(TAG, "Could not request ${provider.platformName} updates", failure)
                        planChanged = true
                    }
                }
                if (!planChanged) return

                runCatching { client.removeUpdates(listener) }
                    .onFailure { Log.d(TAG, "Could not clear a partial location plan", it) }
                activeRequests.clear()
                val retryNow = nowElapsedMs()
                plan = policy.requests(retryNow, providerAvailability(retryNow))
                    .associate { it.provider to it.intervalMs }
            }
        }

        fun acceptLocation(location: Location) {
            val provider = LocationFixProvider.fromPlatformName(location.provider) ?: return
            val fixTimestampMs = location.elapsedRealtimeNanos / NANOS_PER_MILLISECOND
            val now = nowElapsedMs()
            synchronized(stateLock) {
                val passiveIsReceiving = LocationFixProvider.PASSIVE in activeRequests
                if (closed || (provider !in activeRequests && !passiveIsReceiving)) return
                val age = now - fixTimestampMs
                if (age !in 0..MAX_FIX_AGE_MS) return
                val speed = if (location.hasSpeed()) location.speed.toDouble() else -1.0
                // Passive callbacks retain the name of the provider that made
                // the original fix. If that provider is not one of our active
                // subscriptions, account for the update as passive so it does
                // not falsely mark our GPS/network request as healthy.
                val accountingProvider = if (provider in activeRequests) {
                    provider
                } else {
                    LocationFixProvider.PASSIVE
                }
                if (!policy.recordFix(accountingProvider, fixTimestampMs, speed)) return

                // A fresh GPS fix clears fallback before emission dedupe. A GPS
                // timestamp matching a network fix should still restore the
                // low-power primary-only plan without emitting twice.
                reconcileLocked(now)
                if (freshnessGate.accept(fixTimestampMs, now)) {
                    trySend(toFix(location, fixTimestampMs))
                }
            }
        }

        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = acceptLocation(location)

            override fun onProviderEnabled(providerName: String) {
                val provider = LocationFixProvider.fromPlatformName(providerName) ?: return
                synchronized(stateLock) {
                    failedUntil.remove(provider)
                    reconcileLocked(nowElapsedMs())
                }
            }

            override fun onProviderDisabled(providerName: String) {
                synchronized(stateLock) { reconcileLocked(nowElapsedMs()) }
            }
        }

        synchronized(stateLock) {
            reconcileLocked(startedAt)

            // Last-known locations are only startup seeds. Choose the freshest
            // recent provider fix by elapsed realtime, rather than provider
            // order or wall-clock time, so stale coordinates cannot kick off
            // route matching or move the camera.
            if (!closed) {
                val candidates = mutableListOf<Pair<LocationFixProvider, Location>>()
                for (provider in LocationFixProvider.entries) {
                    if (provider == LocationFixProvider.GPS && !fineLocationGranted) continue
                    val available = providerAvailability(nowElapsedMs())[provider]
                    if (!available) continue
                    try {
                        val location = client.lastKnownLocation(provider) ?: continue
                        val sourceProvider = LocationFixProvider.fromPlatformName(location.provider)
                            ?: provider
                        candidates += sourceProvider to location
                    } catch (security: SecurityException) {
                        failedUntil[provider] = nowElapsedMs() + PROVIDER_FAILURE_RETRY_MS
                        Log.w(TAG, "No permission for ${provider.platformName} last-known fix", security)
                        break
                    } catch (failure: Exception) {
                        Log.d(TAG, "No last-known ${provider.platformName} fix", failure)
                    }
                }
                val seed = freshestRecentProviderFix(
                    candidates.map { (provider, location) ->
                        ProviderFixTimestamp(
                            provider,
                            location.elapsedRealtimeNanos / NANOS_PER_MILLISECOND,
                        )
                    },
                    nowElapsedMs(),
                    MAX_FIX_AGE_MS,
                )
                val seedLocation = seed?.let { selected ->
                    candidates.firstOrNull { (provider, location) ->
                        provider == selected.provider &&
                            location.elapsedRealtimeNanos / NANOS_PER_MILLISECOND == selected.elapsedRealtimeMs
                    }?.second
                }
                if (!closed && seed != null && seedLocation != null &&
                    policy.recordFix(
                        seed.provider,
                        seed.elapsedRealtimeMs,
                        if (seedLocation.hasSpeed()) seedLocation.speed.toDouble() else -1.0,
                    ) && freshnessGate.accept(seed.elapsedRealtimeMs, nowElapsedMs())
                ) {
                    trySend(toFix(seedLocation, seed.elapsedRealtimeMs))
                }
            }
        }

        val watchdog = launch {
            while (true) {
                delay(PROVIDER_RECHECK_INTERVAL_MS)
                synchronized(stateLock) { reconcileLocked(nowElapsedMs()) }
            }
        }

        awaitClose {
            watchdog.cancel()
            synchronized(stateLock) {
                closed = true
                activeRequests.clear()
                runCatching { client.removeUpdates(listener) }
                    .onFailure { Log.d(TAG, "Could not remove location updates at close", it) }
            }
        }
    }

    private fun toFix(location: Location, timestampElapsedMs: Long): GpsFix = GpsFix(
        lat = location.latitude,
        lon = location.longitude,
        bearingDeg = if (location.hasBearing()) location.bearing.toDouble() else Double.NaN,
        speedMps = if (location.hasSpeed()) location.speed.toDouble() else -1.0,
        accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else Double.NaN,
        timestampMs = timestampElapsedMs,
    )

    private const val NANOS_PER_MILLISECOND = 1_000_000L
}

private class AndroidLocationProviderClient(
    private val context: Context,
    private val manager: LocationManager,
) : LocationProviderClient {
    override fun isProviderEnabled(provider: LocationFixProvider): Boolean {
        if (!LocationPermission.isGranted(context)) return false
        if (provider == LocationFixProvider.GPS && !LocationPermission.isFineGranted(context)) return false
        return manager.isProviderEnabled(provider.platformName)
    }

    override fun lastKnownLocation(provider: LocationFixProvider): Location? =
        manager.getLastKnownLocation(provider.platformName)

    override fun requestLocationUpdates(
        provider: LocationFixProvider,
        intervalMs: Long,
        listener: LocationListener,
    ) {
        manager.requestLocationUpdates(
            provider.platformName,
            intervalMs,
            0f,
            listener,
            Looper.getMainLooper(),
        )
    }

    override fun removeUpdates(listener: LocationListener) {
        manager.removeUpdates(listener)
    }
}
