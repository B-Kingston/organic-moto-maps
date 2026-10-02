package com.organicmoto.maps.region

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RegionPendingRemovalStoreTest {

    private fun store(): RegionPendingRemovalStore {
        val root = File.createTempFile("regions", "").also { it.delete() }
        root.mkdirs()
        return RegionPendingRemovalStore(File(root, "pending-removal.json"))
    }

    @Test
    fun roundTripsThePendingDeletion() {
        val store = store()
        store.save(PendingRemoval("queensland-abc", "queensland", "Queensland", 357_091_613))
        val read = store.read()!!
        assertEquals("queensland-abc", read.installId)
        assertEquals("queensland", read.regionId)
        assertEquals("Queensland", read.regionName)
        assertEquals(357_091_613L, read.bytes)
    }

    @Test
    fun clearRemovesThePointerOnly() {
        val store = store()
        store.save(PendingRemoval("queensland-abc", "queensland", "Queensland", 1))
        store.clear()
        assertNull(store.read())
    }

    @Test
    fun corruptStateReadsAsNull() {
        val root = File.createTempFile("regions", "").also { it.delete() }
        root.mkdirs()
        val file = File(root, "pending-removal.json")
        file.writeText("not json")
        assertNull(RegionPendingRemovalStore(file).read())
        file.writeText("""{"regionName":"x"}""")
        assertNull(RegionPendingRemovalStore(file).read())
    }

    @Test
    fun missingStateReadsAsNull() {
        assertNull(store().read())
    }

    @Test
    fun saveCreatesParentDirectories() {
        val root = File.createTempFile("regions", "").also { it.delete() }
        val nested = File(root, "a/b/pending.json")
        RegionPendingRemovalStore(nested).save(PendingRemoval("x", "x", "X", 2))
        assertTrue(nested.isFile)
    }
}
