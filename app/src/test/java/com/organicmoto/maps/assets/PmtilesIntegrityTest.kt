package com.organicmoto.maps.assets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class PmtilesIntegrityTest {

    @Test
    fun packagedPmtilesHasHeaderSizeAndMatchingSha256() {
        val root = repoRoot()
        val archive = root.resolve("app/src/main/assets/tiles/queensland.pmtiles")
        val sidecar = root.resolve("app/src/main/assets/tiles/queensland.pmtiles.sha256")
        assertTrue("Missing PMTiles archive; run tools/tiles/build-tiles.sh", archive.isFile)
        assertTrue("Missing PMTiles SHA-256 sidecar; run tools/tiles/build-tiles.sh", sidecar.isFile)
        assertTrue(
            "PMTiles archive is unexpectedly small; run tools/tiles/build-tiles.sh",
            archive.length() > 100_000_000L,
        )
        archive.inputStream().use { input ->
            val header = ByteArray(7)
            assertEquals(
                "Invalid PMTiles header; run tools/tiles/build-tiles.sh",
                7,
                input.read(header),
            )
            assertEquals("Invalid PMTiles header; run tools/tiles/build-tiles.sh", "PMTiles", header.decodeToString())
        }

        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        val expected = sidecar.readText().trim().substringBefore(" ")
        assertEquals("PMTiles SHA-256 mismatch; run tools/tiles/build-tiles.sh", expected, actual)
    }

    private fun repoRoot(): File {
        var directory: File? = File(".").absoluteFile
        while (directory != null) {
            if (directory.resolve("settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("Could not locate repository root")
    }
}
