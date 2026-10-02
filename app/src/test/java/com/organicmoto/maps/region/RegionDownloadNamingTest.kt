package com.organicmoto.maps.region

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionDownloadNamingTest {

    @Test
    fun suggestedNameUsesTheSourceDate() {
        assertEquals(
            "queensland-260815.motomap",
            RegionDownloadNaming.suggestedDisplayName("queensland", "260815", "b".repeat(64)),
        )
    }

    @Test
    fun suggestedNameFallsBackToTheFingerprint() {
        assertEquals(
            "queensland-1ea03fcf.motomap",
            RegionDownloadNaming.suggestedDisplayName("queensland", null, "1ea03fcf" + "0".repeat(56)),
        )
    }

    @Test
    fun suggestedNameSurvivesMissingMetadata() {
        assertEquals("region.motomap", RegionDownloadNaming.suggestedDisplayName("", null, ""))
    }

    @Test
    fun namesAreSanitizedAndKeepTheExtension() {
        val name = RegionDownloadNaming.suggestedDisplayName("New South/Wales!", "2026-08-15", "")
        assertTrue(name, name.endsWith(".motomap"))
        assertFalse(name, name.contains('/'))
        assertFalse(name, name.contains('!'))
        assertFalse(name, name.contains(' '))
    }

    @Test
    fun stagedNameMatchesTheLegacyConventionForResume() {
        val fingerprint = "b".repeat(64)
        assertEquals(
            "queensland-$fingerprint.motomap",
            RegionDownloadNaming.stagedFileName("queensland", fingerprint),
        )
        assertEquals("queensland-package.motomap", RegionDownloadNaming.stagedFileName("queensland", ""))
    }
}
