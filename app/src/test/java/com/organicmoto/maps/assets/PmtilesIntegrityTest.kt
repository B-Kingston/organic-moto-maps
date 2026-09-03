package com.organicmoto.maps.assets

import com.organicmoto.maps.tiles.OfflineTileStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

class PmtilesIntegrityTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun separatelyDistributedPmtilesHasHeaderSizeAndMatchingSha256() {
        val root = repoRoot()
        val archive = root.resolve("data/tiles/queensland.pmtiles")
        val sidecar = root.resolve("data/tiles/queensland.pmtiles.sha256")
        assertTrue("Missing distributable PMTiles archive; run tools/tiles/build-tiles.sh", archive.isFile)
        assertTrue("Missing distributable PMTiles sidecar; run tools/tiles/build-tiles.sh", sidecar.isFile)
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

    @Test
    fun importerAcceptsOnlyPmtilesVersion3Header() {
        assertTrue(OfflineTileStore.hasValidHeader(ByteArrayInputStream("PMTiles\u0003".toByteArray())))
        assertFalse(OfflineTileStore.hasValidHeader(ByteArrayInputStream("PMTiles\u0002".toByteArray())))
        assertFalse(OfflineTileStore.hasValidHeader(ByteArrayInputStream("not a map".toByteArray())))
        assertFalse(OfflineTileStore.hasValidHeader(ByteArrayInputStream(byteArrayOf())))
    }

    @Test
    fun validImportReplacesTheInstalledFile() {
        val destination = temporaryFolder.newFile("old.pmtiles").apply { writeText("old") }
        val archive = validMinimalArchive()

        OfflineTileStore.importTo(ByteArrayInputStream(archive), destination)

        assertTrue(destination.readBytes().contentEquals(archive))
        assertFalse(File(destination.parentFile, "basemap.pmtiles.importing").exists())
    }

    @Test
    fun invalidImportPreservesTheInstalledFile() {
        val destination = temporaryFolder.newFile("old.pmtiles").apply { writeText("old map") }

        try {
            OfflineTileStore.importTo(ByteArrayInputStream("not a map".toByteArray()), destination)
            fail("Invalid archive should be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("PMTiles v3"))
        }

        assertEquals("old map", destination.readText())
        assertFalse(File(destination.parentFile, "basemap.pmtiles.importing").exists())
    }

    private fun validMinimalArchive(): ByteArray = ByteArray(127).also { bytes ->
        "PMTiles".toByteArray().copyInto(bytes)
        bytes[7] = 3
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
