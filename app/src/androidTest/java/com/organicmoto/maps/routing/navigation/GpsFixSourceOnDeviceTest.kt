package com.organicmoto.maps.routing.navigation

import android.location.Location
import android.location.LocationListener
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsFixSourceOnDeviceTest {

    @Test
    fun fallbackRecoversToGpsDeduplicatesAndUnregisters() {
        runBlocking {
            val now = MutableElapsedClock(60_000L)
            val client = FakeLocationProviderClient()
            val received = Channel<GpsFix>(Channel.UNLIMITED)
            val collection = launch(start = CoroutineStart.UNDISPATCHED) {
                GpsFixSource.fixes(client, fineLocationGranted = true, nowElapsedMs = now::get)
                    .collect(received::send)
            }
            yield()

            assertEquals(mapOf(LocationFixProvider.GPS to 1_000L), client.activeRequests)

            client.setEnabled(LocationFixProvider.GPS, false)
            client.signalProviderState(LocationFixProvider.GPS)
            assertEquals(mapOf(LocationFixProvider.NETWORK to 1_000L), client.activeRequests)

            now.value = 61_000L
            client.emit(location(LocationFixProvider.NETWORK, 61_000L, speedMps = 4f))
            assertEquals(61_000L, withTimeout(2_000L) { received.receive() }.timestampMs)

            now.value = 91_000L
            client.setEnabled(LocationFixProvider.GPS, true)
            client.signalProviderState(LocationFixProvider.GPS)
            assertEquals(
                mapOf(
                    LocationFixProvider.GPS to 15_000L,
                    LocationFixProvider.NETWORK to 1_000L,
                    LocationFixProvider.PASSIVE to 5_000L,
                ),
                client.activeRequests,
            )

            now.value = 92_000L
            client.emit(location(LocationFixProvider.NETWORK, 91_000L, speedMps = 0f))
            assertEquals(91_000L, withTimeout(2_000L) { received.receive() }.timestampMs)
            client.emit(location(LocationFixProvider.GPS, 91_000L, speedMps = 0f))
            assertEquals(mapOf(LocationFixProvider.GPS to 1_000L), client.activeRequests)
            assertTrue(withTimeoutOrNull(150L) { received.receive() } == null)

            now.value = 93_000L
            client.emit(location(LocationFixProvider.NETWORK, 93_000L, speedMps = 0f))
            assertTrue(withTimeoutOrNull(150L) { received.receive() } == null)

            collection.cancelAndJoin()
            assertTrue(client.activeRequests.isEmpty())
            assertTrue(client.removedUpdateCount > 0)
            received.close()
        }
    }

    @Test
    fun approximateLocationDoesNotAttemptGpsProvider() {
        runBlocking {
            val client = FakeLocationProviderClient()
            val received = Channel<GpsFix>(Channel.UNLIMITED)
            val collection = launch(start = CoroutineStart.UNDISPATCHED) {
                GpsFixSource.fixes(client, fineLocationGranted = false, nowElapsedMs = { 1_000L })
                    .collect(received::send)
            }
            yield()

            assertEquals(mapOf(LocationFixProvider.NETWORK to 1_000L), client.activeRequests)
            collection.cancelAndJoin()
            assertTrue(client.activeRequests.isEmpty())
            received.close()
        }
    }

    @Test
    fun passiveSubscriptionAcceptsFixNamedForItsOriginalProvider() {
        runBlocking {
            val client = FakeLocationProviderClient().apply {
                setEnabled(LocationFixProvider.GPS, false)
                setEnabled(LocationFixProvider.NETWORK, false)
            }
            val received = Channel<GpsFix>(Channel.UNLIMITED)
            val collection = launch(start = CoroutineStart.UNDISPATCHED) {
                GpsFixSource.fixes(client, fineLocationGranted = false, nowElapsedMs = { 1_000L })
                    .collect(received::send)
            }
            yield()

            assertEquals(mapOf(LocationFixProvider.PASSIVE to 5_000L), client.activeRequests)
            // PASSIVE relays a GPS fix but Location.provider remains "gps".
            client.emit(location(LocationFixProvider.GPS, 1_000L, speedMps = 0f))
            assertEquals(1_000L, withTimeout(2_000L) { received.receive() }.timestampMs)

            collection.cancelAndJoin()
            assertTrue(client.activeRequests.isEmpty())
            received.close()
        }
    }

    @Test
    fun gpsPermissionFailureFallsBackToNetwork() {
        runBlocking {
            val now = MutableElapsedClock(1_000L)
            val client = FakeLocationProviderClient().apply {
                deniedRequests += LocationFixProvider.GPS
            }
            val received = Channel<GpsFix>(Channel.UNLIMITED)
            val collection = launch(start = CoroutineStart.UNDISPATCHED) {
                GpsFixSource.fixes(client, fineLocationGranted = true, nowElapsedMs = now::get)
                    .collect(received::send)
            }
            yield()

            assertEquals(mapOf(LocationFixProvider.NETWORK to 1_000L), client.activeRequests)
            now.value = 2_000L
            client.emit(location(LocationFixProvider.NETWORK, 2_000L, speedMps = 3f))
            assertEquals(2_000L, withTimeout(2_000L) { received.receive() }.timestampMs)

            collection.cancelAndJoin()
            assertTrue(client.activeRequests.isEmpty())
            received.close()
        }
    }

    private fun location(
        provider: LocationFixProvider,
        elapsedRealtimeMs: Long,
        speedMps: Float,
    ): Location = Location(provider.platformName).apply {
        latitude = -27.47
        longitude = 153.02
        accuracy = 5f
        speed = speedMps
        elapsedRealtimeNanos = elapsedRealtimeMs * 1_000_000L
    }

    private class MutableElapsedClock(var value: Long) {
        fun get(): Long = value
    }

    private class FakeLocationProviderClient : LocationProviderClient {
        private val enabled = LocationFixProvider.entries.associateWith { true }.toMutableMap()
        private val requests = mutableMapOf<LocationFixProvider, Long>()
        val deniedRequests = mutableSetOf<LocationFixProvider>()
        private var listener: LocationListener? = null
        var removedUpdateCount: Int = 0
            private set

        val activeRequests: Map<LocationFixProvider, Long>
            get() = requests.toMap()

        override fun isProviderEnabled(provider: LocationFixProvider): Boolean = enabled[provider] == true

        override fun lastKnownLocation(provider: LocationFixProvider): Location? = null

        override fun requestLocationUpdates(
            provider: LocationFixProvider,
            intervalMs: Long,
            listener: LocationListener,
        ) {
            if (provider in deniedRequests) throw SecurityException("Permission denied")
            this.listener = listener
            requests[provider] = intervalMs
        }

        override fun removeUpdates(listener: LocationListener) {
            if (this.listener === listener) {
                requests.clear()
                removedUpdateCount++
            }
        }

        fun setEnabled(provider: LocationFixProvider, isEnabled: Boolean) {
            enabled[provider] = isEnabled
        }

        fun signalProviderState(provider: LocationFixProvider) {
            val target = listener ?: error("No location listener registered")
            if (enabled[provider] == true) {
                target.onProviderEnabled(provider.platformName)
            } else {
                target.onProviderDisabled(provider.platformName)
            }
        }

        fun emit(location: Location) {
            (listener ?: error("No location listener registered")).onLocationChanged(location)
        }
    }
}
