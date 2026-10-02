package com.organicmoto.maps.region

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class RegionRegistryTest {

    private fun registry(): RegionRegistry = RegionRegistry(File(File.createTempFile("regions", "").also { it.delete() }, "regions"))

    private fun packageFor(regionId: String, fingerprint: String = "b".repeat(64)): File {
        val file = File.createTempFile("region", ".motomap").also { it.delete() }
        val builder = SyntheticRegionPackage(file)
        builder.regionId = regionId
        builder.regionName = regionId.replaceFirstChar { it.uppercase() }
        builder.packageId = "$regionId-260928-0123456789ab"
        builder.pipelineFingerprint = fingerprint
        return builder.build().file
    }

    @Test
    fun installsAndListsRegions() {
        val registry = registry()
        val install = registry.install(packageFor("monaco"))
        assertEquals("monaco", install.regionId)
        assertEquals("Monaco", install.regionName)
        assertEquals(1, registry.list().size)
        assertTrue(File(registry.installsDir, install.dirName).isDirectory)
        // Registry survives a fresh instance (persisted JSON).
        val reopened = RegionRegistry(registry.installsDir.parentFile!!)
        assertEquals(1, reopened.list().size)
        assertEquals(install.installId, reopened.list().first().installId)
    }

    @Test
    fun installingTheSamePackageTwiceIsIdempotent() {
        val registry = registry()
        val first = registry.install(packageFor("monaco"))
        val second = registry.install(packageFor("monaco"))
        assertEquals(first.installId, second.installId)
        assertEquals(1, registry.list().size)
    }

    @Test
    fun installingAFailedPackageLeavesNoStagingDirectory() {
        val registry = registry()
        val file = File.createTempFile("broken", ".motomap").also { it.delete() }
        file.writeBytes("not a zip".toByteArray())
        try {
            registry.install(file)
            fail("broken package installed")
        } catch (expected: RegionPackageException) {
        }
        val leftovers = (registry.installsDir.parentFile?.listFiles() ?: emptyArray())
            .filter { it.name.startsWith("staging-") }
        assertTrue("staging left behind: $leftovers", leftovers.isEmpty())
        assertTrue(registry.list().isEmpty())
    }

    @Test
    fun activationBumpsTheGenerationAndSwitchesTheActiveRecord() {
        val registry = registry()
        val install = registry.install(packageFor("monaco"))
        assertEquals(0, registry.active().generation)
        assertTrue(registry.active().isBundled)
        val generation = registry.activate(install.installId)
        assertEquals(1, generation)
        assertEquals(install.installId, registry.active().installId)
        val bundled = registry.activateBundled()
        assertEquals(2, bundled)
        assertNull(registry.active().installId)
        assertTrue(registry.active().generation > generation)
    }

    @Test
    fun activatingAnUnknownRegionFails() {
        val registry = registry()
        try {
            registry.activate("nope-1234567890ab")
            fail("unknown install activated")
        } catch (expected: RegionPackageException) {
        }
    }

    @Test
    fun removingAnInactiveRegionDeletesItAndKeepsOthers() {
        val registry = registry()
        val monaco = registry.install(packageFor("monaco"))
        val tasmania = registry.install(packageFor("tasmania", fingerprint = "c".repeat(64)))
        registry.remove(tasmania.installId)
        assertEquals(listOf(monaco.installId), registry.list().map { it.installId })
        assertFalse(File(registry.installsDir, tasmania.dirName).exists())
    }

    @Test
    fun removingTheActiveRegionIsRefused() {
        val registry = registry()
        val install = registry.install(packageFor("monaco"))
        registry.activate(install.installId)
        try {
            registry.remove(install.installId)
            fail("active region removed")
        } catch (expected: RegionPackageException) {
        }
        assertEquals(1, registry.list().size)
    }

    @Test
    fun corruptRegistryFallsBackToEmptyInsteadOfCrashing() {
        val registry = registry()
        registry.install(packageFor("monaco"))
        File(registry.installsDir.parentFile, "installs.json").writeText("{ not json")
        assertTrue(registry.list().isEmpty())
    }

    @Test
    fun installedBytesAreTheActualFootprintIncludingTheManifest() {
        val registry = registry()
        val install = registry.install(packageFor("monaco"))
        val dir = File(registry.installsDir, install.dirName)
        val actual = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertEquals(actual, install.bytes)
        assertTrue(File(dir, "manifest.json").isFile)
        // The manifest itself is part of the installed footprint; the
        // component sum alone would understate what deletion reclaims.
        val componentBytes = RegionArchiveImporter.readManifest(dir).components.sumOf { it.bytes }
        assertTrue("manifest must count toward installed bytes", install.bytes > componentBytes)
    }

    @Test
    fun listReportsBytesFromDiskEvenWhenTheStoredCountIsStale() {
        val registry = registry()
        val install = registry.install(packageFor("monaco"))
        val registryFile = File(registry.installsDir.parentFile, "installs.json")
        val doc = org.json.JSONObject(registryFile.readText())
        doc.getJSONArray("installs").getJSONObject(0).put("bytes", 1)
        registryFile.writeText(doc.toString())
        val listed = registry.list().single()
        val dir = File(registry.installsDir, install.dirName)
        assertEquals(dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }, listed.bytes)
    }

    @Test
    fun removingOneInstallDeletesOnlyItsOwnDirectory() {
        val registry = registry()
        val monaco = registry.install(packageFor("monaco"))
        val tasmania = registry.install(packageFor("tasmania", fingerprint = "c".repeat(64)))
        val monacoDir = File(registry.installsDir, monaco.dirName)
        val tasmaniaDir = File(registry.installsDir, tasmania.dirName)
        registry.remove(tasmania.installId)
        assertTrue(monacoDir.isDirectory)
        assertFalse(tasmaniaDir.exists())
    }
}
