package com.organicmoto.maps.region

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class RegionPackageTest {

    private fun build(mutate: (SyntheticRegionPackage) -> Unit = {}): RegionPackageManifest {
        val builder = SyntheticRegionPackage(File.createTempFile("pkg", ".motomap").also { it.delete() })
        mutate(builder)
        return builder.build().manifest
    }

    @Test
    fun parsesAValidManifest() {
        val manifest = build()
        assertEquals(1, manifest.schemaVersion)
        assertEquals("monaco", manifest.regionId)
        assertEquals("Monaco", manifest.regionName)
        assertEquals("260928", manifest.sourceDate)
        assertEquals("motorcycle|198752012", manifest.graphProfile)
        assertEquals(3, manifest.components.size)
        assertNotNull(manifest.component(RegionPackageContract.COMPONENT_TILES))
        assertNotNull(manifest.component(RegionPackageContract.COMPONENT_GRAPH))
        assertNotNull(manifest.component(RegionPackageContract.COMPONENT_GEOCODER))
        assertEquals(5, manifest.expectedFiles().size)
    }

    @Test
    fun rejectsUnsupportedSchemaVersion() {
        assertRejected { builder ->
            builder.mutateManifest = { it.put("schemaVersion", 99) }
        }
    }

    @Test
    fun rejectsUnknownFields() {
        assertRejected { builder ->
            builder.mutateManifest = { it.put("surprise", true) }
        }
    }

    @Test
    fun rejectsUnsafeRegionIds() {
        assertRejected { builder ->
            builder.mutateManifest = {
                it.getJSONObject("region").put("id", "../escape")
            }
        }
    }

    @Test
    fun rejectsUnsupportedGraphProfile() {
        assertRejected { builder -> builder.graphProfile = "car|42" }
    }

    @Test
    fun rejectsWrongGeocoderFormat() {
        assertRejected { builder -> builder.geocoderMagic = "NOTGEO" }
    }

    @Test
    fun rejectsMissingComponent() {
        assertRejected { builder ->
            builder.mutateManifest = { manifest ->
                val components = manifest.getJSONArray("components")
                val kept = org.json.JSONArray()
                for (i in 0 until components.length() - 1) kept.put(components.getJSONObject(i))
                manifest.put("components", kept)
            }
        }
    }

    @Test
    fun rejectsFileTotalMismatch() {
        assertRejected { builder ->
            builder.mutateManifest = { manifest ->
                manifest.getJSONArray("components").getJSONObject(1).put("bytes", 123456L)
            }
        }
    }

    @Test
    fun rejectsUnsafeComponentPaths() {
        assertRejected { builder ->
            builder.mutateManifest = { manifest ->
                manifest.getJSONArray("components").getJSONObject(0).put("path", "../../tiles")
            }
        }
    }

    @Test
    fun rejectsEmptyAttribution() {
        assertRejected { builder ->
            builder.mutateManifest = { it.put("attribution", "") }
        }
    }

    @Test
    fun parsesTheRealGeneratedPackage() {
        // Cross-language contract: the committed Monaco package was produced by
        // map-server (real Planetiler + GraphHopper + geocoder-tool) and must
        // parse here.
        val packageFile = SyntheticRegionPackage.repoFile("app/src/androidTest/assets/regions/monaco.motomap")
        if (!packageFile.isFile) return // fresh clone without the fixture
        val staging = File.createTempFile("monaco", "").also { it.delete(); it.mkdirs() }
        try {
            val manifest = RegionArchiveImporter.extract(packageFile, File(staging, "monaco"))
            assertEquals("monaco", manifest.regionId)
            assertEquals("motorcycle|198752012", manifest.graphProfile)
            assertEquals(RegionPackageContract.GEOCODER_MAGIC, manifest.geocoderMagic)
            assertTrue(manifest.components.sumOf { it.bytes } > 1_000_000)
            // The manifest's own pipeline fingerprint must be a full digest.
            assertTrue(manifest.pipelineFingerprint.matches(RegionPackageContract.SHA256))
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun assertRejected(mutate: (SyntheticRegionPackage) -> Unit) {
        try {
            build(mutate)
            fail("broken manifest was accepted")
        } catch (expected: RegionPackageException) {
            // expected
        }
    }
}
