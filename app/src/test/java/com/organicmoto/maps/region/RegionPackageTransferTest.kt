package com.organicmoto.maps.region

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * The save-to-user-file contract: success reports exactly the bytes that
 * reached the destination, a failing output stream propagates instead of
 * pretending the file was written, and the staged source is never modified.
 */
class RegionPackageTransferTest {

    private fun staged(bytes: ByteArray): File {
        val file = File.createTempFile("staged", ".motomap").also { it.delete() }
        file.writeBytes(bytes)
        return file
    }

    @Test
    fun copiesEveryByteAndReportsTheCount() {
        val content = ByteArray(64 * 1024 + 7) { (it % 251).toByte() }
        val source = staged(content)
        val output = ByteArrayOutputStream()
        val written = RegionPackageTransfer.copyTo(source, output)
        assertEquals(content.size.toLong(), written)
        assertArrayEquals(content, output.toByteArray())
        assertArrayEquals(content, source.readBytes())
    }

    @Test
    fun aFailedWritePropagatesAndKeepsTheSource() {
        val content = "motomap-package".repeat(128).toByteArray()
        val source = staged(content)
        val failing = object : OutputStream() {
            private var remaining = 256
            override fun write(b: Int) {
                if (remaining-- <= 0) throw IOException("disk full")
            }
        }
        try {
            RegionPackageTransfer.copyTo(source, failing)
            fail("a failing output stream was reported as success")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("disk full"))
        }
        assertArrayEquals(content, source.readBytes())
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size)
        assertTrue(expected.contentEquals(actual))
    }
}
