package com.organicmoto.maps.tiles

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
private const val TAG = "OrganicMoto.Tiles"

/**
 * Serves the prebuilt vector basemap (PMTiles, OpenMapTiles schema) that ships
 * in APK assets.
 *
 * MapLibre Native reads PMTiles natively through the `pmtiles://file://` scheme
 * (maplibre-android-sdk >= 11.7). That reader performs byte-range reads, which
 * AssetManager cannot do, so the archive is copied to internal storage on first
 * use — the same first-run pattern as the GraphHopper graph cache and the
 * geocoder index.
 */
object OfflineTileStore {
    private const val ASSET_NAME = "tiles/queensland.pmtiles"
    private const val ASSET_HASH_NAME = "tiles/queensland.pmtiles.sha256"
    private const val FILE_NAME = "queensland.pmtiles"
    private const val HASH_FILE_NAME = "$FILE_NAME.sha256"

    private val copyMutex = Mutex()

    /**
     * Ensures the tile archive exists in internal storage and returns the
     * `pmtiles://file://` URL to hand to the style's tile source. Safe to call
     * concurrently; the copy runs once.
     */
    suspend fun ensureReady(context: Context): String = withContext(Dispatchers.IO) {
        copyMutex.withLock {
            val appContext = context.applicationContext
            val dir = File(appContext.filesDir, "tiles").apply { mkdirs() }
            val file = File(dir, FILE_NAME)
            val hashFile = File(dir, HASH_FILE_NAME)
            val expectedHash = appContext.assets.open(ASSET_HASH_NAME).bufferedReader().use { it.readText().trim() }
            check(expectedHash.length == 64 && expectedHash.all { it in "0123456789abcdefABCDEF" }) {
                "invalid PMTiles SHA-256 sidecar"
            }
            val cachedHash = hashFile.takeIf { it.isFile }?.runCatching { readText().trim() }?.getOrNull()
            val cacheMatches = file.isFile && file.length() > 0L && cachedHash == expectedHash
            if (!cacheMatches) {
                val tmp = File(dir, "$FILE_NAME.tmp")
                val hashTmp = File(dir, "$HASH_FILE_NAME.tmp")
                tmp.delete()
                hashTmp.delete()
                try {
                    Log.i(TAG, "Copying $ASSET_NAME -> ${file.absolutePath}")
                    appContext.assets.open(ASSET_NAME).use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    }
                    FileChannel.open(tmp.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
                    try {
                        Files.move(
                            tmp.toPath(),
                            file.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                    hashTmp.writeText("$expectedHash\n")
                    FileChannel.open(hashTmp.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
                    try {
                        Files.move(
                            hashTmp.toPath(),
                            hashFile.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(hashTmp.toPath(), hashFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                } catch (e: Exception) {
                    tmp.delete()
                    hashTmp.delete()
                    throw IllegalStateException("failed to copy tile archive from assets", e)
                }
                Log.i(TAG, "Tile archive ready: ${file.length()} bytes")
            } else {
                Log.d(TAG, "Tile archive already present: ${file.length()} bytes")
            }
            "pmtiles://file://${file.absolutePath}"
        }
    }
}
