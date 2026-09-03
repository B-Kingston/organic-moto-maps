package com.organicmoto.maps.tiles

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.InputStream
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
 * Owns the user-imported PMTiles basemap. The large archive is deliberately
 * not packaged in the APK.
 *
 * MapLibre Native reads PMTiles natively through the `pmtiles://file://` scheme
 * (maplibre-android-sdk >= 11.7). Android's picker supplies a downloaded
 * archive, which is copied into private storage so it remains available after
 * a restart or removal of the original download.
 */
object OfflineTileStore {
    private const val FILE_NAME = "basemap.pmtiles"
    private const val TEMP_FILE_NAME = "$FILE_NAME.importing"
    private const val PMTILES_HEADER_SIZE = 127
    private val PMTILES_MAGIC = "PMTiles".encodeToByteArray()
    private const val PMTILES_VERSION = 3

    private val importMutex = Mutex()

    /**
     * Returns the installed archive URL, or null until the user imports one.
     */
    suspend fun installedUrl(context: Context): String? = withContext(Dispatchers.IO) {
        installedFile(context.applicationContext)
            .takeIf { it.isFile && it.length() >= PMTILES_HEADER_SIZE && hasValidHeader(it.inputStream()) }
            ?.let(::mapUrl)
    }

    /** Validates and atomically imports [source], preserving any prior map on failure. */
    suspend fun import(context: Context, source: Uri): String = withContext(Dispatchers.IO) {
        importMutex.withLock {
            val appContext = context.applicationContext
            val destination = installedFile(appContext)
            destination.parentFile?.mkdirs()
            val input = appContext.contentResolver.openInputStream(source)
                ?: throw IllegalArgumentException("The selected map file could not be opened")
            importTo(input, destination)
            Log.i(TAG, "Imported map archive: ${destination.length()} bytes")
            mapUrl(destination)
        }
    }

    internal fun importTo(input: InputStream, destination: File) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, TEMP_FILE_NAME)
        temporary.delete()
        try {
            input.use { sourceStream ->
                temporary.outputStream().buffered().use { output -> sourceStream.copyTo(output) }
            }
            if (temporary.length() < PMTILES_HEADER_SIZE || !hasValidHeader(temporary.inputStream())) {
                throw IllegalArgumentException("Choose a valid PMTiles v3 (.pmtiles) map file")
            }
            FileChannel.open(temporary.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
            moveReplacing(temporary, destination)
        } catch (e: Exception) {
            temporary.delete()
            throw e
        }
    }

    internal fun hasValidHeader(input: InputStream): Boolean = input.use { stream ->
        val header = ByteArray(PMTILES_MAGIC.size + 1)
        var offset = 0
        while (offset < header.size) {
            val count = stream.read(header, offset, header.size - offset)
            if (count < 0) return@use false
            offset += count
        }
        header.copyOfRange(0, PMTILES_MAGIC.size).contentEquals(PMTILES_MAGIC) &&
            header.last().toInt() == PMTILES_VERSION
    }

    private fun installedFile(context: Context) = File(File(context.filesDir, "tiles"), FILE_NAME)
    private fun mapUrl(file: File) = "pmtiles://file://${file.absolutePath}"

    private fun moveReplacing(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
