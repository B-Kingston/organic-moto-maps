package com.organicmoto.maps.region

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Recovery contract for the staged download: records survive round trips,
 * legacy records without the new fields still resolve to their old file names,
 * and clearing metadata never deletes staged bytes the user could still resume
 * or save.
 */
class RegionDownloadStoreTest {

    private fun store(): Pair<RegionDownloadStore, File> {
        val root = File.createTempFile("regions", "").also { it.delete() }.resolve("downloads")
        root.mkdirs()
        return RegionDownloadStore(File(root.parentFile, "download.json"), root) to root
    }

    private fun record(fileName: String = "queensland-abc.motomap") = RegionDownloadRecord(
        jobId = "job-1",
        regionId = "queensland",
        regionName = "Queensland",
        fingerprint = "b".repeat(64),
        downloadUrl = "/api/v1/artifacts/queensland/${"b".repeat(64)}/queensland.motomap",
        etag = "\"v1\"",
        bytes = 10,
        total = 100,
        fileName = fileName,
        destinationUri = "content://downloads/queensland.motomap",
        sha256 = "c".repeat(64),
    )

    @Test
    fun roundTripsEveryField() {
        val (store, _) = store()
        store.save(record())
        val read = store.read()!!
        assertEquals("job-1", read.jobId)
        assertEquals("queensland", read.regionId)
        assertEquals("\"v1\"", read.etag)
        assertEquals(10L, read.bytes)
        assertEquals(100L, read.total)
        assertEquals("queensland-abc.motomap", read.fileName)
        assertEquals("content://downloads/queensland.motomap", read.destinationUri)
        assertEquals("c".repeat(64), read.sha256)
    }

    @Test
    fun legacyRecordsWithoutAFileNameResolveToTheOldStagingName() {
        val (store, downloads) = store()
        downloads.mkdirs()
        store.save(record())
        // Simulate the pre-SAF document: no fileName and no destinationUri.
        File(downloads.parentFile, "download.json").writeText(
            """{"jobId":"job-1","regionId":"queensland","regionName":"Queensland",""" +
                """"fingerprint":"${"b".repeat(64)}",""" +
                """"downloadUrl":"/api/v1/artifacts/queensland/${"b".repeat(64)}/queensland.motomap",""" +
                """"bytes":10,"total":100}""",
        )
        val read = store.read()!!
        assertEquals("queensland-${"b".repeat(64)}.motomap", read.fileName)
        assertNull(read.destinationUri)
    }

    @Test
    fun clearingTheRecordNeverDeletesStagedFiles() {
        val (store, downloads) = store()
        store.save(record())
        val staged = store.destinationFile(store.read()!!)
        val part = store.partFile(store.read()!!)
        staged.writeBytes("complete".toByteArray())
        part.writeBytes("partial".toByteArray())
        store.clear()
        assertNull(store.read())
        assertTrue("complete staged file must survive", staged.isFile)
        assertTrue("partial staged file must survive", part.isFile)
        assertEquals("partial", part.readText())
    }

    @Test
    fun corruptOrBlankRecordsReadAsNull() {
        val (store, downloads) = store()
        File(downloads.parentFile, "download.json").writeText("{ not json")
        assertNull(store.read())
        File(downloads.parentFile, "download.json").writeText("""{"downloadUrl":""}""")
        assertNull(store.read())
    }

    @Test
    fun aRecordWithOnlyAUrlIsNotRecoverableMetadata() {
        val (store, downloads) = store()
        File(downloads.parentFile, "download.json").writeText("""{"downloadUrl":"https://x/y"}""")
        val read = store.read()
        assertFalse(read == null)
        assertEquals("region-package.motomap", read!!.fileName)
    }
}
