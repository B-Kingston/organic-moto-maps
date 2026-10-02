package com.organicmoto.maps.region

import com.organicmoto.maps.region.net.RegionHttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RegionDownloadReliabilityTest {

    private val transient = RegionHttpException("dropped", transient = true)
    private val permanent = RegionHttpException("not found", statusCode = 404)

    @Test
    fun retriesOnlyTransientFailuresAndOnlyUpToTheCap() {
        assertTrue(DownloadRetryPolicy.shouldRetry(transient, 1))
        assertTrue(DownloadRetryPolicy.shouldRetry(transient, DownloadRetryPolicy.MAX_ATTEMPTS - 1))
        assertFalse(DownloadRetryPolicy.shouldRetry(transient, DownloadRetryPolicy.MAX_ATTEMPTS))
        assertFalse(DownloadRetryPolicy.shouldRetry(permanent, 1))
        assertFalse(DownloadRetryPolicy.shouldRetry(IllegalStateException("bug"), 1))
    }

    @Test
    fun backoffGrowsMonotonicallyWithinBounds() {
        val delays = (1..8).map { DownloadRetryPolicy.delayMs(it, retryAfterMs = null) }
        assertEquals(2_000L, delays.first())
        delays.zipWithNext().forEach { (a, b) -> assertTrue("$a then $b", b >= a) }
        assertTrue(delays.all { it in 2_000L..30_000L })
    }

    @Test
    fun retryAfterIsHonouredButClamped() {
        assertEquals(7_000L, DownloadRetryPolicy.delayMs(1, 7_000L))
        assertEquals(30_000L, DownloadRetryPolicy.delayMs(1, 3_600_000L))
        assertEquals(2_000L, DownloadRetryPolicy.delayMs(1, 0L))
    }

    @Test
    fun checksumMatchesAKnownDigestAndNormalisesTheExpectedForm() {
        val file = File.createTempFile("checksum", ".bin").apply { writeText("abc") }
        try {
            val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            assertEquals(abc, PackageChecksum.sha256Hex(file))
            assertEquals(abc, PackageChecksum.expected("\"${abc.uppercase()}\""))
        } finally {
            file.delete()
        }
        assertNull(PackageChecksum.expected(null))
        assertNull(PackageChecksum.expected(""))
        assertNull(PackageChecksum.expected("not-a-digest"))
    }
}
