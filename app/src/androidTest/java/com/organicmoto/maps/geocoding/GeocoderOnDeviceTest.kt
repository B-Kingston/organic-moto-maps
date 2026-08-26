package com.organicmoto.maps.geocoding

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.organicmoto.maps.BuildConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class GeocoderOnDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test(timeout = 120_000)
    fun geocoderIndexLoadsFromTheAssetCopy() {
        val index = GeocoderIndex.load(context)
        assertTrue(index.docCount > 0)
        assertTrue(index.termCount > 0)
        assertTrue(index.localityCount > 0)
    }

    @Test(timeout = 120_000)
    fun corruptCacheRecoversFromAssets() {
        val cache = File(File(context.filesDir, "geocoder"), "geocoder.dat")
        cache.parentFile!!.mkdirs()
        cache.writeText("corrupt cache")
        val index = GeocoderIndex.load(context)
        assertTrue(index.docCount > 0)
        assertEquals(BuildConfig.GEOCODER_SHA256, sha256(cache))
    }

    @Test(timeout = 120_000)
    fun controllerSearchReturnsLocality() = runBlocking {
        val results = GeocodeSearchController(context).search("Brisbane")
        assertTrue(results.isNotEmpty())
        assertTrue(
            results.first().type == GeocodeResultType.LOCALITY ||
                results.first().name.contains("Brisbane", ignoreCase = true),
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
