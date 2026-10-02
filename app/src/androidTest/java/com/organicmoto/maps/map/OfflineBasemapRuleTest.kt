package com.organicmoto.maps.map

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.organicmoto.maps.tiles.OfflineTileStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class OfflineBasemapRuleTest {
    @get:Rule val temporary = TemporaryFolder()
    private val description = Description.createTestDescription(javaClass, "cameraTest")
    private fun tiles() = File(temporary.root, "tiles/basemap.pmtiles")
    private fun realArchive() = InstrumentationRegistry.getInstrumentation().context.assets
        .open("regions/monaco.motomap")
    private fun statement(block: () -> Unit) = object : Statement() {
        override fun evaluate() = block()
    }

    @Test fun installsBeforeBodyAndRemovesFixtureAfterSuccess() {
        val file = tiles()
        OfflineBasemapRule(file, ::realArchive).apply(statement {
            assertTrue("fixture must exist before Activity rule evaluates", file.isFile)
            assertTrue(OfflineTileStore.hasValidHeader(file.inputStream()))
        }, description).evaluate()
        assertFalse(file.exists())
        assertTrue(file.parentFile!!.listFiles()!!.isEmpty())
    }

    @Test fun restoresPreviousMapAfterSuccess() {
        val file = tiles()
        file.parentFile!!.mkdirs()
        val original = byteArrayOf(1, 2, 3, 4)
        file.writeBytes(original)
        OfflineBasemapRule(file, ::realArchive).apply(statement {
            assertTrue(OfflineTileStore.hasValidHeader(file.inputStream()))
        }, description).evaluate()
        assertArrayEquals(original, file.readBytes())
        assertEquals(listOf(file.name), file.parentFile!!.list()!!.toList())
    }

    @Test fun restoresPreviousMapAndPreservesBodyFailure() {
        val file = tiles()
        file.parentFile!!.mkdirs()
        val original = byteArrayOf(5, 6, 7)
        file.writeBytes(original)
        val failure = AssertionError("camera failed")
        try {
            OfflineBasemapRule(file, ::realArchive).apply(statement { throw failure }, description).evaluate()
            throw AssertionError("expected body failure")
        } catch (actual: AssertionError) {
            assertSame(failure, actual)
        }
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun rejectsMissingOrInvalidTilesBeforeBodyAndRestoresMap() {
        for (entryName in listOf("missing", "tiles/basemap.pmtiles")) {
            val file = tiles()
            file.parentFile!!.mkdirs()
            val original = byteArrayOf(8, 9)
            file.writeBytes(original)
            val bytes = ByteArrayOutputStream().also { buffer ->
                ZipOutputStream(buffer).use { zip ->
                    zip.putNextEntry(ZipEntry(entryName))
                    zip.write(byteArrayOf(0))
                    zip.closeEntry()
                }
            }.toByteArray()
            var bodyCalled = false
            try {
                OfflineBasemapRule(file, { ByteArrayInputStream(bytes) }).apply(
                    statement { bodyCalled = true }, description,
                ).evaluate()
                throw AssertionError("invalid fixture was accepted")
            } catch (failure: IllegalStateException) {
                assertTrue(failure.message!!.startsWith("Basemap prerequisite:"))
            }
            assertFalse(bodyCalled)
            assertArrayEquals(original, file.readBytes())
            assertEquals(listOf(file.name), file.parentFile!!.list()!!.toList())
        }
    }

    @Test fun disabledRuleDoesNotOpenArchiveOrChangeFiles() {
        val file = tiles()
        var bodyCalled = false
        OfflineBasemapRule(file, { error("archive must not be opened") }, { false }).apply(
            statement { bodyCalled = true }, description,
        ).evaluate()
        assertTrue(bodyCalled)
        assertFalse(file.parentFile!!.exists())
    }
}
